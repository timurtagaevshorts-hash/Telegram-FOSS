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
        .dns(uz.kodlikino.top4.utils.YuzNet.DNS_V4_FIRST)
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

    /** videoId bo'yicha ijroga MOS eng sifatli sof audio oqimini topadi. */
    fun resolveStream(videoId: String): YuzAudioStream? {
        val all = streamsFor(videoId)
        if (all.isEmpty()) return null
        // #157: media3 buffer'lanmagan har qanday seek'da DataSource'ni QAYTA
        // ochadi va resolveStream yana ishlaydi. O'sha paytda taroq tezligi
        // pasaygan bo'lsa fitThroughput boshqa KONTEYNERga o'tib ketardi
        // (m4a -> webm/opus), ProgressiveMediaPeriod esa prepare paytida
        // tanlangan Mp4Extractor'da qolgan — oqim buzilar, ovoz g'oyib bo'lardi.
        val l = sameItag(videoId, all)
        val i = attempt[videoId] ?: 0
        // Rotatsiya rejimida (oldingisi 403 berdi) tartib bo'yicha keyingisi.
        // #120: bir martalik 403 video uchun rotatsiyani MAG'ULI QILIB
        // qo'ymasligi uchun har bir muvaffaqiyatli resolve counter'ni
        // pasaytiradi — uzluks ijro etilganda #86 throughput-tanlovga
        // qaytamiz; yangi 403 noteBadStream() counter'ni yana ko'taradi.
        if (i > 0) {
            attempt[videoId] = i - 1
            return l[i % l.size].also { pinItag(videoId, it) }
        }
        return fitThroughput(l).also { pinItag(videoId, it) }
    }

    /** Tanlangan itag'ni shu video uchun qo'zg'atmaymiz (rotatsiya shu itag ichida). */
    private val pinnedItag = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun pinItag(videoId: String, s: YuzAudioStream) {
        itagOf(s.url)?.let { pinnedItag[videoId] = it }
    }

    private fun sameItag(videoId: String, list: List<YuzAudioStream>): List<YuzAudioStream> {
        val want = pinnedItag[videoId] ?: return list
        val same = list.filter { itagOf(it.url) == want }
        return if (same.isEmpty()) list else same
    }

    /**
     * YuzGram #86: ro'yxatdan taroq tezligiga SIG'ADIGAN eng yuqori
     * sifatli nomzodni tanlaymiz.
     *
     * Nima uchun: list doim "eng yaxshisi tepada" — past internetda shu
     * nomzod sekin yuklanib buffering'ni yutqazardi (sifat yaxshi, lekin
     * qo'shiq umuman boshlanmasdi). Endi o'lchangan tezlikdan ~15% past
     * bitreytli format tanlanadi: uzluks ijro + iloji boricha yuqori ovoz.
     * Bitreyt noma'lum (0) yoki tezlik hali o'lchanmagan bo'lsa — eski
     * xatti-harakat: eng yaxshisi.
     */
    private fun fitThroughput(list: List<YuzAudioStream>): YuzAudioStream {
        val r = YuzNetPulse.rate()
        if (r <= 0.0) return list.first()
        val budget = r * 8.0 * 0.85
        return list.firstOrNull { it.bitrate <= 0 || it.bitrate <= budget }
            ?: list.minByOrNull { if (it.bitrate > 0) it.bitrate else Int.MAX_VALUE }
            ?: list.last()
    }

    /**
     * YuzGram #65/#69: bir nechta oqim nomzodi — avval cache'dan (tez),
     * bo'sh bo'lsa to'liq zanjir. Video'da umuman audio bo'lmasa — qo'shiq
     * nomidan qayta qidirib 3 ta muqobil videoni sinaydi.
     */
    fun resolveStreamCandidates(track: YuzTrack, max: Int = 6): List<YuzAudioStream> {
        val out = ArrayList(streamsFor(track.videoId))
        if (out.size < 2) {
            val query = (track.artist + " " + track.title).trim()
            if (query.isNotEmpty()) {
                val cands = runCatching { search(query, limit = 5) }.getOrDefault(emptyList())
                var tried = 0
                for (c in cands) {
                    if (c.videoId == track.videoId) continue
                    if (++tried > 3) break
                    for (s in streamsFor(c.videoId)) {
                        if (out.size >= max) break
                        if (out.none { it.url == s.url }) out.add(s)
                    }
                    if (out.size >= max) break
                }
            }
        }
        return out
    }

    // ------------------------------------------------------ stream cache #69
    // #69 tezlik: resolve qilingan oqimlar 5 daqiqa saqlanadi — dvigatel
    // qo'shiq ijrosi boshlanishida KEYINGisini prefetch qiladi, shunda
    // YuzStreamDataSource.open() URL'ni cache'dan olib LOADER ipida bir
    // lahza ichida ochadi (ijro keskin tez boshlanadi).
    // Rotatsiya: bir URL 403 berisa noteBadStream() keyingi nomzodga o'tadi.

    private const val CACHE_TTL = 5 * 60 * 1000L
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<YuzAudioStream>>>()
    private val attempt = java.util.concurrent.ConcurrentHashMap<String, Int>()
    // #149: bitta video uchun bir vaqtda bitta resolve. Ilgari loader
    // (ijro), prefetch va yuklash iplari kesh tekshiruvidan birgalikda
    // o'tib, bitta videoId uchun 2-3 marta NPE + InnerTube zanjirini
    // boshlardi — sekin taroqda bu ijro boshlanishini ham kechiktirardi.
    private val resolveLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    // #84: bitta URL ko'pincha bir range so'rovini o'tkazadi — refresh
    // takroran bir xil URL'ni qaytarmasligi uchun ishlatilganlarni belgilaymiz.
    private val used = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    private fun cached(videoId: String): List<YuzAudioStream> {
        val e = streamCache[videoId] ?: return emptyList()
        if (System.currentTimeMillis() - e.first < CACHE_TTL) return e.second
        // URL'lar muddati tugadi — "ishlatilgan" belgilari ham keraksiz.
        streamCache.remove(videoId)
        used.remove(videoId)
        return emptyList()
    }

    /** Yagona resolve zanjiri: NewPipeExtractor -> ANDROID -> IOS; natija cache. */
    fun streamsFor(videoId: String): List<YuzAudioStream> {
        val cancellation = YuzDownloadCancellation.current()
        cancellation?.check()
        if (videoId.isBlank()) return emptyList()
        cached(videoId).let { if (it.isNotEmpty()) return it }
        // #149: kesh tekshiruvidan so'ng bitta ip zanjirni bajaradi, qolganlari
        // tayyor natijani olish uchun kutadi — takroriy so'rovlar yo'q.
        val lock = resolveLocks.computeIfAbsent(videoId) { Any() }
        synchronized(lock) {
            cached(videoId).let { if (it.isNotEmpty()) return it }
            val out = ArrayList<YuzAudioStream>()
            val seen = HashSet<String>()
            for (s in YuzNpeExtractor.audioStreams(videoId, 2)) if (seen.add(s.url)) out.add(s)
            cancellation?.check()
            for (c in PLAYERS) {
                cancellation?.check()
                collectFromVideo(videoId, c, out, seen, 6)
            }
            cancellation?.check()
            val streams = out.map { it.copy(sourceVideoId = videoId) }
            streamCache[videoId] = System.currentTimeMillis() to streams
            return streams
        }
    }

    /** Dvigatel prefetch'i: navbatdagi qo'shiq URL'ini oldinden ochib qo'yamiz. */
    fun prefetch(videoId: String) {
        if (videoId.isBlank() || cached(videoId).isNotEmpty()) return
        Thread { runCatching { streamsFor(videoId) } }.start()
    }

    /** Oqim xatosi (403/throttle) — navbatdagi nomzodga o'tamiz va yangilaymiz. */
    fun noteBadStream(videoId: String) {
        attempt[videoId] = (attempt[videoId] ?: 0) + 1
        streamCache.remove(videoId)
        used.remove(videoId)
    }

    /**
     * YuzGram #84: YuzRangeReader bir oynada rad etildi — [at] baytdan
     * davom ettiradigan YANGI URL kerak.
     *
     * MUHIM: davom etish uchun beriladigan URL shu [currentUrl]'ning
     * itag'ida bo'lishi shart — 140 (aac) va 251 (opus) boshqa-boshqa
     * fayllar, ularning baytlari aralashsa oqim buziladi
     * (yarim/noqulayotgan qo'shiq).
     * Ketma-ketlik: keshdagi ishlatilmagan shu-itag URL -> qayta resolve ->
     * (faqat boshida, at==0) har qanday nomzod.
     */
    fun refreshStream(videoId: String, at: Long, currentUrl: String): YuzAudioStream? {
        val itag = itagOf(currentUrl)
        usedUrls(videoId).add(currentUrl)
        var s = pickStream(streamsFor(videoId), itag, videoId)
        if (s == null) {
            streamCache.remove(videoId)
            attempt[videoId] = (attempt[videoId] ?: 0) + 1
            s = pickStream(streamsFor(videoId), itag, videoId)
            // Boshidan (at==0) format farqi zararsiz — istalgan nomzod.
            if (s == null && at == 0L) s = pickStream(streamsFor(videoId), null, videoId)
        }
        if (s == null) return null
        usedUrls(videoId).add(s.url)
        android.util.Log.i("YuzMusic", "refreshStream $videoId @$at itag=$itag -> ${s.ext} bits=${s.approxBytes}")
        return s
    }

    private fun pickStream(
        list: List<YuzAudioStream>,
        itag: String?,
        videoId: String
    ): YuzAudioStream? {
        val busy = usedUrls(videoId)
        return list.firstOrNull {
            (itag == null || itagOf(it.url) == itag) && !busy.contains(it.url)
        }
    }

    private fun usedUrls(videoId: String): MutableSet<String> =
        used.computeIfAbsent(videoId) {
            java.util.Collections.newSetFromMap(
                java.util.concurrent.ConcurrentHashMap<String, Boolean>()
            )
        }

    /** URL'dagi itag= parametri; NPE va InnerTube URL'larida mavjud. */
    fun itagOf(url: String): String? = ITAG_RE.find(url)?.groupValues?.get(1)

    private val ITAG_RE = Regex("[?&]itag=(\\d+)")

    /** Yangi navbat boshlanganda rotatsiyani tozalash. */
    fun resetRotation() {
        attempt.clear()
        used.clear()
        pinnedItag.clear()
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
        val cleans = ArrayList<YuzAudioStream>()
        for (s in audioFormats(p, c.ua)) {
            if (out.size >= max) break
            if (seen.add(s.url)) out.add(s)
            val clean = stripN(s.url)
            if (clean != s.url && !seen.contains(clean)) {
                cleans.add(YuzAudioStream(clean, s.ext, s.approxBytes, c.ua, s.bitrate))
            }
        }
        // #157: "n" throttling'siz egizaklarni ORTGA suramiz. Ilgari ular har
        // formatning yoniga juft bo'lib kirardi: rotatsiyaning 1-qadami
        // throttled URL'ga tushar, max=6 sig'imi esa faqat 3 ta haqiqiy
        // formatni sig'dirardi.
        for (cs in cleans) {
            if (out.size >= max) break
            if (seen.add(cs.url)) out.add(cs)
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
                // #86: bitreytni SAQLAYMIZ — oldin faqat saralash uchun
                // ishlatilib, keyin tashlab yuborilardi; tezlikka mos format
                // tanlash shu songa bog'liq.
                val br = f.optInt("bitrate").takeIf { it > 0 }
                    ?: f.optInt("averageBitrate")
                pairs.add(
                    YuzAudioStream(url, if (mp4) "m4a" else "webm", f.optLong("contentLength"), ua, br) to
                        (br + if (mp4) 1_000_000 else 0)
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

    private fun post(url: String, body: JSONObject, ua: String): JSONObject? {
        val cancellation = YuzDownloadCancellation.current()
        cancellation?.check()
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MT))
            .header("User-Agent", ua)
            .header("Accept-Encoding", "identity")
            .build()
        val call = http.newCall(req)
        try {
            cancellation?.attach(call)
            return call.execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
                cancellation?.check()
                JSONObject(text)
            }
        } catch (_: Exception) {
            cancellation?.check()
            return null
        } finally {
            cancellation?.detach(call)
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
    val ua: String = YuzYouTubeApi.UA,
    /**
     * #86: YouTube'ning e'lon qilgan bitreyti (bit/sekund; 0 = noma'lum).
     * Shu son orqali tezlikka MOS format tanlanadi — past internetda
     * 128 kbit/s'lik oqim 256 kbit/s'likdan tezroq va uzluksiz yetib boradi.
     */
    val bitrate: Int = 0,
    val sourceVideoId: String = ""
)
