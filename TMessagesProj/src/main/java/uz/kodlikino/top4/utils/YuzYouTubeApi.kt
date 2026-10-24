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
    // #164: ILDIZ SABAB topildi. googlevideo PO-token'siz ANDROID/IOS URL'lariga
    // (video, IP) juftligi uchun aynan 1 048 576 bayt "cold start" budjeti
    // beradi (StreamProtectionStatus=2), keyin HAR QANDAY yangi URL ham 403.
    // 1MB ≈ 128kbps'da 65 soniya — "1 daqiqadan keyin to'xtaydi" shu.
    // VISIONOS klienti bu cheklovga tushmaydi: bir xil IP'da 4.8MB faylni
    // to'liq, parallel Range'lar bilan ham berdi (2026-10-24 o'lchov).
    // yt-dlp ham standart zanjirda shu klientni ishlatadi (JSLess default).
    private const val UA_VIS = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    // #161: PO-token'ga ega bo'lmagan oqim URL'lari googlevideo tomonidan
    // (video, IP) juftligi uchun ~1MB (PC'da ~384KB)dan keyin 403 qilinadi —
    // "faqat 1 daqiqa ijro etiladi" alomatining ildizi. yt-dlp Po-Token
    // Guide'ga ko'ra ANDROID_VR va TVHTML5 klientlari PO talab qilmaydi;
    // faqat IP-reputatsiyasi past bo'lgan (datacenter) manzillardan
    // LOGIN_REQUIRED beradi. Telefonda ishlashi kutiladi — sinab ko'riladi.
    private const val UA_VR =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; " +
            "eureka-user Build/SQ3A.220705.004.A1) gzip"
    private const val UA_TV = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
    private const val SEARCH_URL = "https://www.youtube.com/youtubei/v1/search"
    private const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"

    private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .dns(uz.kodlikino.top4.utils.YuzNet.DNS_V4_FIRST)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * #164b: [id] — InnerTube'ning "X-YouTube-Client-Name" raqami. yt-dlp
     * shu sarlavhalarni yuboradi; ularsiz VISIONOS so'rovi bot-tekshiruviga
     * tushadi ("Sign in to confirm you're not a bot") — telefon va PC'da
     * o'lchandi. Raqamlar: WEB=1, ANDROID=3, IOS=5, TVHTML5=7, ANDROID_VR=28,
     * VISIONOS=101.
     */
    private data class Client(
        val name: String,
        val version: String,
        val ua: String,
        val id: Int,
        val extras: Map<String, Any>
    )

    // #164: zanjir VISIONOS (PO-cap'siz) -> NPE/ANDROID/VR/IOS/TV (zaxira).
    // VISIONOS muvaffaqiyatli bo'lsa qolgan (1MB'da 403 bo'ladigan)
    // klientlar umuman so'ralmaydi — resolve ham tezroq.
    private val VISIONOS_CLIENT = Client(
        "VISIONOS", "1.02", UA_VIS, 101,
        mapOf(
            "deviceMake" to "Apple",
            "deviceModel" to "RealityDevice17,1",
            "osName" to "visionOS",
            "osVersion" to "26.5.23O471"
        )
    )

    private val ANDROID_CLIENT =
        Client("ANDROID", "20.10.40", UA, 3, mapOf("androidSdkVersion" to 35))

    private val PLAYERS = listOf(
        VISIONOS_CLIENT,
        ANDROID_CLIENT,
        Client("ANDROID_VR", "1.60.19", UA_VR, 28, emptyMap()),
        Client("IOS", "20.08.3", UA_IOS, 5, mapOf("deviceModel" to "iPhone16,2")),
        Client("TVHTML5", "7.20250312.16.00", UA_TV, 7, emptyMap()),
    )

    /** Birinchi sinovdan o'tgan so'rovlar 3 ta rendererni qaytardi. */
    private val RENDERERS = arrayOf("videoRenderer", "compactVideoRenderer", "gridVideoRenderer")

    /** Qidiruv natijalari (to'liq qo'shiq videoId'lari). */
    fun search(query: String, limit: Int = 25): List<YuzTrack> {
        // #164: qidiruvda VISIONOS ishlatilmaydi — u faqat player uchun
        // sinovdan o'tgan; eski ANDROID qidiruvi o'zgarmaydi.
        val c = ANDROID_CLIENT
        val body = baseBody(c).put("query", query)
        val resp = post(SEARCH_URL, body, c) ?: return emptyList()
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
        // #161: rotatsiya endi QAYTMAYDI. Eski `attempt[videoId] = i - 1`
        // (muqilliqda counter'ni pasaytirish) nomzodlar ro'yxatida faqat
        // 0-chi va 1-chi orasida tebranishga olib kelardi: ANDROID URL'lari
        // PO-cap tufayli o'lgach, pleyer ANDROID_VR/IOS/TV nomzodlariga
        // umuman chiqa olmasdi — "1 daqiqa ijro" shundan. Endi counter
        // faqat noteBadStream() bilan o'sadi; tozalanishi: navbat oxiri
        // (resetRotation), kesh TTL tugashi yoki trek muvaffaqiyatli
        // tugashi (noteStreamOk -> sticky saqlanadi).
        val i = maxOf(attempt[videoId] ?: 0, stickyIndex)
        if (i > 0) {
            return l[i % l.size].also {
                pinItag(videoId, it)
                rememberClient(videoId, it)
            }
        }
        return fitThroughput(l).also {
            pinItag(videoId, it)
            rememberClient(videoId, it)
        }
    }

    /**
     * #161: oxirgi ishlagan nomzod indeksi — keyingi treklar birinchi
     * urinishdan o'sha klientdan boshlaydi (har trekda 403-raqsga
     * tushmaslik uchun). Trek oxirigacha ijro etilganda chaqiriladi.
     */
    @Volatile
    private var stickyIndex = 0

    fun noteStreamOk(videoId: String) {
        val i = attempt[videoId] ?: 0
        if (i > 0) stickyIndex = i
    }

    /**
     * #161: diagnostika — shu videoning oxirgi tanlangan nomzodi qaysi
     * mijozdan keldi (UA bo'yicha). Telefondagi haqiqiy test natijasi
     * pult chatiga shu teg bilan tushadi: ANDROID/VR/IOS/TV'dan qaysi
     * biri PO-cap'dan o'tayotgani shu yerda aniq ko'rinadi.
     */
    private val lastClient = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun clientNow(videoId: String): String = lastClient[videoId] ?: "?"

    private fun rememberClient(videoId: String, s: YuzAudioStream) {
        lastClient[videoId] = when (s.ua) {
            UA_VIS -> "VIS"
            UA_VR -> "VR"
            UA_TV -> "TV"
            UA_IOS -> "IOS"
            else -> "AND"
        }
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
        // #161: attempt ham tozalanadi (URL'lar yangilandi — eski o'liklar
        // ro'yxatga endi aloqador emas).
        streamCache.remove(videoId)
        used.remove(videoId)
        attempt.remove(videoId)
        return emptyList()
    }

    /** Yagona resolve zanjiri: NewPipeExtractor -> InnerTube (4 klient); natija cache. */
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
            // #164: avval VISIONOS — uning URL'lari googlevideo'ning
            // (video, IP) uchun 1MB "cold start" chekloviga tushmaydi:
            // qo'shiq oxirigacha ijro etiladi, yuklash 100% tugaydi.
            collectFromVideo(videoId, VISIONOS_CLIENT, out, seen, 2)
            cancellation?.check()
            if (out.isEmpty()) {
                // Zaxira: "made for kids" videolari yoki VISIONOS bloklanganda
                // eski zanjir (NPE -> ANDROID/VR/IOS/TV). Bu URL'lar 1MB'dan
                // keyin 403 bo'ladi, lekin ijro baribir boshlanadi.
                for (s in YuzNpeExtractor.audioStreams(videoId, 2)) if (seen.add(s.url)) out.add(s)
                cancellation?.check()
                // #161: har klientdan eng yaxshi 2 ta format — KLIENTLAR
                // BO'YICHA ARALASH. Eski `collectFromVideo(..., 6)` bitta
                // global chegarani birinchi klientning 6 ta formati to'ldirib
                // qo'yardi: ANDROID_VR/IOS/TVHTML5 umuman ro'yxatga kirmasdi
                // va rotatsiya ularga hech qachon chiqa olmasdi.
                for (c in PLAYERS) {
                    if (c.name == VISIONOS_CLIENT.name) continue
                    cancellation?.check()
                    val perClient = ArrayList<YuzAudioStream>()
                    collectFromVideo(videoId, c, perClient, seen, 2)
                    out.addAll(perClient)
                }
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
        lastClient.clear()
        stickyIndex = 0
    }

    private fun collectFromVideo(
        videoId: String,
        c: Client,
        out: MutableList<YuzAudioStream>,
        seen: MutableSet<String>,
        max: Int
    ) {
        if (out.size >= max) return
        // #164b: VISIONOS faqat to'g'ri so'rov shakli bilan bot-tekshiruvidan
        // o'tadi — visitorData sarlavhasi + yt-dlp konteksti (pastda).
        // visitorData qidiruv javobidan olinadi; hali bo'lmasa bootstrap.
        val visitor = visitorData ?: ensureVisitor()
        val body = baseBody(c)
            .put("videoId", videoId)
            .put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
                )
            )
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val p = post(PLAYER_URL, body, c, visitor) ?: return
        val ps = p.optJSONObject("playabilityStatus")
        val st = ps?.optString("status")
        if (st != "OK") {
            android.util.Log.i(
                "YuzMusic",
                "player $videoId ${c.name} -> $st (${ps?.optString("reason")}) vis=${visitor != null}"
            )
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
            .put("userAgent", c.ua)
            .put("hl", "en")
            .put("gl", "UZ")
            .put("timeZone", "UTC")
            .put("utcOffsetMinutes", 0)
        for ((k, v) in c.extras) client.put(k, v)
        return JSONObject().put("context", JSONObject().put("client", client))
    }

    /**
     * #164b: visitorData — YouTube'ning "mehmon" identifikatori. yt-dlp uni
     * har qanday javobning responseContext.visitorData'sidan olib, keyingi
     * so'rovlarga X-Goog-Visitor-Id sarlavhasi sifatida qo'shadi. Usiz
     * VISIONOS player bot-tekshiruviga tushadi: 2026-10-24 o'lchovi —
     * sarlavhasiz LOGIN_REQUIRED, sarlavha bilan status=OK va 1MB'dan
     * keyingi Range'lar ham 206 (cap yo'q).
     */
    @Volatile
    private var visitorData: String? = null

    private fun ensureVisitor(): String? {
        visitorData?.let { return it }
        val c = ANDROID_CLIENT
        post(SEARCH_URL, baseBody(c).put("query", "music"), c)
        return visitorData
    }

    private fun post(
        url: String,
        body: JSONObject,
        c: Client,
        visitor: String? = visitorData
    ): JSONObject? {
        val cancellation = YuzDownloadCancellation.current()
        cancellation?.check()
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MT))
            .header("User-Agent", c.ua)
            .header("Accept-Encoding", "identity")
            .header("X-YouTube-Client-Name", c.id.toString())
            .header("X-YouTube-Client-Version", c.version)
            .header("Origin", "https://www.youtube.com")
        if (!visitor.isNullOrBlank()) builder.header("X-Goog-Visitor-Id", visitor)
        val call = http.newCall(builder.build())
        try {
            cancellation?.attach(call)
            return call.execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
                cancellation?.check()
                val json = JSONObject(text)
                json.optJSONObject("responseContext")
                    ?.optString("visitorData")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { visitorData = it }
                json
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
