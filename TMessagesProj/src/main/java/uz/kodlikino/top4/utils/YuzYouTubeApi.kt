package uz.kodlikino.top4.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * YuzGram #63: qo'shiqlarni TO'LIQ eshitish manbasi — YouTube InnerTube API.
 *
 * Muammo: bepul Deezer API faqat 30 soniyalik preview berardi.
 * Yechim: YouTube'ning o'z ANDROID mijozini taqlid qilib
 * youtubei/v1/search va youtubei/v1/player so'rovlarini yuboramiz —
 * kalit shart emas, IP cheklovi qurilmadan qurilmaga (telefon uy/IP dan
 * ishlaydi; server orqali resolv qilinsa URL boshqa IP'da o'laydi).
 * player javobidagi "audio/mp4" adaptiveFormats — to'liq qo'shiq,
 * ExoPlayer'da bevosita ijro etiladi va keshga yuklab olinadi.
 *
 * @see https://github.com/TeamNewPipe/NewPipeExtractor (xuddi shu yo'l)
 */
object YuzYouTubeApi {

    const val UA = "com.google.android.youtube/20.10.40 (Linux; U; Android 15) gzip"
    private const val SEARCH_URL = "https://www.youtube.com/youtubei/v1/search"
    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"

    /** Birinchi sinovdan o'tgan so'rovlar 3 ta rendererni qaytardi. */
    private val RENDERERS = arrayOf("videoRenderer", "compactVideoRenderer", "gridVideoRenderer")

    /** Qidiruv natijalari (to'liq qo'shiq videoId'lari). */
    fun search(query: String, limit: Int = 25): List<YuzTrack> {
        val body = baseBody().put("query", query)
        val resp = post(SEARCH_URL, body) ?: return emptyList()
        val out = ArrayList<YuzTrack>()
        collect(resp, out, HashSet(), limit)
        return out
    }

    /** videoId bo'yicha eng sifatli sof audio oqimini topadi. */
    fun resolveStream(videoId: String): YuzAudioStream? {
        val p = post(PLAYER_URL, baseBody().put("videoId", videoId)) ?: return null
        val status = p.optJSONObject("playabilityStatus")?.optString("status")
        if (status != "OK") return null
        val sd = p.optJSONObject("streamingData") ?: return null
        val arrs = listOfNotNull(sd.optJSONArray("adaptiveFormats"), sd.optJSONArray("formats"))
        var best: YuzAudioStream? = null
        var bestScore = -1
        for (arr in arrs) {
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val mime = f.optString("mimeType")
                if (!mime.startsWith("audio")) continue
                val url = f.optString("url")
                if (url.isBlank()) continue
                val mp4 = mime.contains("mp4")
                val score = f.optInt("bitrate") + if (mp4) 1_000_000 else 0
                if (score > bestScore) {
                    bestScore = score
                    best = YuzAudioStream(url, if (mp4) "m4a" else "webm", f.optLong("contentLength"))
                }
            }
        }
        return best
    }

    private fun baseBody(): JSONObject {
        val client = JSONObject()
            .put("clientName", "ANDROID")
            .put("clientVersion", "20.10.40")
            .put("androidSdkVersion", 35)
            .put("hl", "en")
            .put("gl", "UZ")
        return JSONObject().put("context", JSONObject().put("client", client))
    }

    private fun post(url: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", UA)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val ins = conn.inputStream
            val raw = (if (conn.contentEncoding == "gzip") GZIPInputStream(ins) else ins)
                .use { it.readBytes() }
            JSONObject(String(raw, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** JSON daraxtini kezib, video renderer'larni yig'adi. */
    private fun collect(node: Any?, out: MutableList<YuzTrack>, seen: MutableSet<String>, limit: Int) {
        if (out.size >= limit) return
        when (node) {
            is JSONObject -> {
                for (key in RENDERERS) {
                    node.optJSONObject(key)?.let { v ->
                        parse(v)?.let { if (seen.add(it.videoId)) out.add(it) }
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) collect(node.opt(keys.next()), out, seen, limit)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collect(node.opt(i), out, seen, limit)
            }
        }
    }

    private fun parse(v: JSONObject): YuzTrack? {
        val vid = v.optString("videoId")
        if (vid.isBlank()) return null
        val title = textRuns(v.optJSONObject("title"))
        if (title.isBlank()) return null
        val artist = textRuns(v.optJSONObject("ownerText"))
            .ifBlank { textRuns(v.optJSONObject("longBylineText")) }
        var cover = ""
        v.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { th ->
            var bestArea = -1
            for (i in 0 until th.length()) {
                val t = th.optJSONObject(i) ?: continue
                val area = t.optInt("width") * t.optInt("height")
                if (area > bestArea) {
                    bestArea = area
                    cover = t.optString("url")
                }
            }
        }
        return YuzTrack(
            id = vid.hashCode().toLong() and 0xFFFFFFFFL,
            title = title,
            artist = artist,
            album = "",
            durationSec = durationSec(v),
            coverUrl = cover,
            previewUrl = "",
            link = "https://www.youtube.com/watch?v=" + vid,
            videoId = vid
        )
    }

    private fun durationSec(v: JSONObject): Int {
        val s = v.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
        if (s.matches(Regex("\\d+(:\\d+)+"))) return parseHms(s)
        val ov = v.optJSONArray("thumbnailOverlays") ?: return 0
        for (i in 0 until ov.length()) {
            val t = ov.optJSONObject(i)
                ?.optJSONObject("thumbnailOverlayTimeStatusRenderer")
                ?.optJSONObject("text") ?: continue
            val txt = t.optString("simpleText").ifBlank { textRuns(t) }
            if (txt.matches(Regex("\\d+(:\\d+)+"))) return parseHms(txt)
        }
        return 0
    }

    private fun parseHms(s: String): Int {
        val p = s.split(":").mapNotNull { it.toIntOrNull() }
        return when (p.size) {
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            2 -> p[0] * 60 + p[1]
            1 -> p[0]
            else -> 0
        }
    }

    private fun textRuns(o: JSONObject?): String {
        o ?: return ""
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it.trim() }
        val runs = o.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        }
        return sb.toString().trim()
    }
}

/** Resolve qilingan audio oqim: to'liq URL + fayl kengaytmasi + taxminiy hajm. */
data class YuzAudioStream(val url: String, val ext: String, val approxBytes: Long)
