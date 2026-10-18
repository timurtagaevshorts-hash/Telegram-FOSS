package uz.kodlikino.top4.utils

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YuzGram #63/#64: qo'shiqlarni TO'LIQ eshitish manbasi — YouTube InnerTube API.
 *
 * Muammo: bepul Deezer API faqat 30 soniyalik preview berardi.
 * Yechim: YouTube'ning o'z ANDROID/IOS mijozlarini taqlid qilib
 * youtubei/v1/search va youtubei/v1/player so'rovlarini yuboramiz —
 * kalit shart emas. player javobidagi "audio/mp4" adaptiveFormats —
 * to'liq qo'shiq, ExoPlayer'da bevosita ijro etiladi va keshga yuklab olinadi.
 *
 * #64 darslari:
 *  - Android HttpURLConnection gzip javobni SHUVOYI ochib beradi, lekin
 *    getContentEncoding() baribir "gzip" deb qoladi -> qo'lda GZIPInputStream
 *    ikki marta ochib JSON buzardi (telefonning xatosi shu). Endi OkHttp +
 *    aniq "Accept-Encoding: identity" — hamma joyda bir xil.
 *  - ANDROID klienti bloklandi deb faqat IOS player'ni qaytaradi (IOS search=0),
 *    shuning uchun resolve zanjir: ANDROID -> IOS.
 *  - videoId o'lsa (boshqa holat) — qo'shiq nomidan qayta qidirib 3 ta
 *    muqobil videoni sinaymiz (resolveStreamCandidates, #65).
 *
 * @see https://github.com/TeamNewPipe/NewPipeExtractor (xuddi shu yo'l)
 */
object YuzYouTubeApi {

    const val UA = "com.google.android.youtube/20.10.40 (Linux; U; Android 15) gzip"
    private const val UA_IOS = "com.google.ios.youtube/20.08.3 (iPhone16,2; U; CPU iOS 18_1 like Mac OS X)"
    private const val SEARCH_URL = "https://www.youtube.com/youtubei/v1/search"
    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"

    private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private data class Client(val name: String, val version: String, val ua: String, val extras: Map<String, Any>)

    private val PLAYERS = listOf(
        Client("ANDROID", "20.10.40", UA, mapOf("androidSdkVersion" to 35)),
        Client("IOS", "20.08.3", UA_IOS, mapOf("deviceModel" to "iPhone16,2")),
    )

    /** Birinchi sinovdan o'tgan so'rovlar 3 ta rendererni qaytardi. */
    private val RENDERERS = arrayOf("videoRenderer", "compactVideoRenderer", "gridVideoRenderer")

    /** Qidiruv natijalari (to'liq qo'shiq videoId'lari). */
    fun search(query: String, limit: Int = 25): List<YuzTrack> {
        val c = PLAYERS[0]
        val body = baseBody(c).put("query", query)
        val resp = post(SEARCH_URL, body, c.ua) ?: return emptyList()
        val out = ArrayList<YuzTrack>()
        collect(resp, out, HashSet(), limit)
        return out
    }

    /** videoId bo'yicha eng sifatli sof audio oqimini topadi (mijozlar zanjiri). */
    fun resolveStream(videoId: String): YuzAudioStream? {
        val out = ArrayList<YuzAudioStream>()
        val seen = HashSet<String>()
        for (c in PLAYERS) collectFromVideo(videoId, c, out, seen, 1)
        return out.firstOrNull()
    }

    /**
     * YuzGram #65: bitta emas — BIR NECHTA oqim nomzodi.
     * ANDROID/IOS mijozlari, sifat bo'yicha saralangan; har URL'ga "n"
     * throttling parametrsiz variant ham qo'shiladi (Google sekinlashtirsa
     * yoki 403 qilsa — keyingisini sinash uchun). video'da umuman audio
     * bo'lmasa — qo'shiq nomidan qayta qidirib 3 ta muqobil videoni sinaydi.
     */
    fun resolveStreamCandidates(track: YuzTrack, max: Int = 6): List<YuzAudioStream> {
        val out = ArrayList<YuzAudioStream>()
        val seen = HashSet<String>()
        if (track.videoId.isNotBlank()) {
            collectFromVideo(track.videoId, PLAYERS[0], out, seen, 2)
            collectFromVideo(track.videoId, PLAYERS[1], out, seen, 4)
            collectFromVideo(track.videoId, PLAYERS[0], out, seen, max)
            collectFromVideo(track.videoId, PLAYERS[1], out, seen, max)
        }
        if (out.size < 2) {
            val query = (track.artist + " " + track.title).trim()
            if (query.isNotEmpty()) {
                val cands = runCatching { search(query, limit = 5) }.getOrDefault(emptyList())
                var tried = 0
                for (c in cands) {
                    if (c.videoId == track.videoId) continue
                    if (++tried > 3) break
                    collectFromVideo(c.videoId, PLAYERS[0], out, seen, max)
                    if (out.size >= max) break
                }
            }
        }
        return out
    }

    private fun collectFromVideo(
        videoId: String,
        c: Client,
        out: MutableList<YuzAudioStream>,
        seen: MutableSet<String>,
        max: Int
    ) {
        if (out.size >= max) return
        val p = post(PLAYER_URL, baseBody(c).put("videoId", videoId), c.ua) ?: return
        val st = p.optJSONObject("playabilityStatus")?.optString("status")
        if (st != "OK") {
            android.util.Log.i("YuzMusic", "player $videoId ${c.name} -> $st")
            return
        }
        for (s in audioFormats(p, c.ua)) {
            if (out.size >= max) return
            if (seen.add(s.url)) out.add(s)
            val clean = stripN(s.url)
            if (clean != s.url && out.size < max && seen.add(clean)) {
                out.add(YuzAudioStream(clean, s.ext, s.approxBytes, c.ua))
            }
        }
    }

    /** streamingData'dagi barcha sof audio formatlar, sifat bo'yicha saralangan. */
    private fun audioFormats(p: JSONObject, ua: String): List<YuzAudioStream> {
        val sd = p.optJSONObject("streamingData") ?: return emptyList()
        val pairs = ArrayList<Pair<YuzAudioStream, Int>>()
        val local = HashSet<String>()
        for (arr in listOfNotNull(sd.optJSONArray("adaptiveFormats"), sd.optJSONArray("formats"))) {
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val mime = f.optString("mimeType")
                if (!mime.startsWith("audio")) continue
                val url = f.optString("url")
                if (url.isBlank() || !local.add(url)) continue
                val mp4 = mime.contains("mp4")
                pairs.add(
                    YuzAudioStream(url, if (mp4) "m4a" else "webm", f.optLong("contentLength"), ua) to
                        (f.optInt("bitrate") + if (mp4) 1_000_000 else 0)
                )
            }
        }
        pairs.sortByDescending { it.second }
        return pairs.map { it.first }
    }

    /** "n" throttling parametrini olib tashlangan URL varianti. */
    private fun stripN(url: String): String {
        val r = url.replace(Regex("[&?]n=[^&]*"), "").replace("&&", "&")
            .trimEnd('&', '?')
        if (r == url || r.isBlank()) return url
        return if (r.startsWith("&")) "?" + r.substring(1) else r
    }

    private fun baseBody(c: Client): JSONObject {
        val client = JSONObject()
            .put("clientName", c.name)
            .put("clientVersion", c.version)
            .put("hl", "en")
            .put("gl", "UZ")
        for ((k, v) in c.extras) client.put(k, v)
        return JSONObject().put("context", JSONObject().put("client", client))
    }

    private fun post(url: String, body: JSONObject, ua: String): JSONObject? = try {
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MT))
            .header("User-Agent", ua)
            .header("Accept-Encoding", "identity")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body?.string() ?: return null
            JSONObject(text)
        }
    } catch (_: Exception) {
        null
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
        if (vid.isBlank() || vid.length != 11) return null
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

/** Resolve qilingan audio oqim: to'liq URL + fayl kengaytmasi + hajm + kerakli UA. */
data class YuzAudioStream(
    val url: String,
    val ext: String,
    val approxBytes: Long,
    val ua: String = YuzYouTubeApi.UA
)
