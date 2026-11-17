package uz.kodlikino.top4.utils

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Musiqa qidiruvi — ikki manba:
 *  1) YuzGram #63: YouTube InnerTube (YuzYouTubeApi) — TO'LIQ qo'shiqlar,
 *     videoId to'lgan qatorlar pleyerda resolve bo'lib eshitiladi/yuklanadi.
 *  2) Deezer och API — zaxira (kalit talab qilmaydi, lekin faqat 30s preview).
 * YuzGram #62: previewlar ham YouTube'ga o'tkazmaydi — ilova ichida ijro etiladi.
 */
data class YuzTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val coverUrl: String,
    val previewUrl: String,
    val link: String,
    // YuzGram #63: YouTube videoId — bo'sh bo'lsa preview'li Deezer qatori.
    val videoId: String = ""
)

object YuzMusicRepository {

    /**
     * #223: 1 daqiqadan qisqa treklar — shorts/reels va 30s preview'lar —
     * qidiruvda ham, radio navbatida ham ko'rinmaydi. Davomiylik noma'lum
     * (0) bo'lsa qator SAQLANADI: aks holda ba'zi manbalarda barcha
     * natijalar yo'qolib qolardi.
     */
    const val MIN_TRACK_SECONDS = 60

    fun longEnough(t: YuzTrack): Boolean =
        t.durationSec <= 0 || t.durationSec >= MIN_TRACK_SECONDS

    private const val BASE = "https://api.deezer.com"

    // ------------------------------------------------------------ #287 keys

    private val TITLE_BRACKETS = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private val APOSTROPHES = Regex("[’‘`´ʼ]")
    private val NOISE_WORDS = setOf(
        "official", "video", "audio", "lyric", "lyrics", "music", "clip",
        "premyera", "premiere", "hd", "hq", "4k", "8k", "remix", "cover",
        "live", "slowed", "reverb", "bass", "boosted", "topic", "records",
        "qoshiq", "qoshig", "song", "songs", "ashula", "muzika", "musiqa"
    )

    /** Matnni so'zlarga ajratadi; apostrof turlari olib tashlanadi. */
    private fun words(raw: String): List<String> =
        WORD_SPLIT.split(APOSTROPHES.replace(raw.lowercase(), ""))
            .filter { it.isNotBlank() }

    /** Ijrochi kaliti — "Artist - Topic" kanal nomi ham bir xil kalitga tushsin. */
    fun artistKey(artist: String): String =
        words(artist.replace("- topic", " ")).filter { it !in NOISE_WORDS }.joinToString(" ")

    /**
     * #287: qo'shiq imzosi — nom tartibidan, "(Official Video)" kabi
     * qo'shimchalardan va ijrochi nomidan mustaqil. Shu tufayli "Ummon -
     * Sokin tun" bilan boshqa kanaldagi "Sokin tun (Official)" bir xil
     * imzo beradi va ketma-ket takrorlanmaydi.
     */
    fun titleSignature(title: String, artist: String = ""): String {
        val artistTokens = words(artist.replace("- topic", " ")).toSet()
        val core = words(TITLE_BRACKETS.replace(title, " "))
            .filter { it !in NOISE_WORDS && it !in artistTokens }
        val chosen = core.ifEmpty { words(title).filter { it !in NOISE_WORDS } }
        return chosen.sorted().joinToString(" ")
    }

    fun titleSignature(t: YuzTrack): String = titleSignature(t.title, t.artist)

    private fun artistTokens(artist: String): Set<String> =
        words(artist.replace("- topic", " ")).toSet()

    private val EMPTY_TOKENS = emptySet<String>()

    private fun signatureTokens(t: YuzTrack): Set<String> =
        titleSignature(t.title, t.artist).split(' ').filter { it.isNotEmpty() }.toSet()

    private fun signaturesConflict(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.size < 2 || b.size < 2) return false
        val common = a.count { it in b }
        if (common == minOf(a.size, b.size)) return true
        return common >= 3 && common.toDouble() / a.union(b).size >= 0.8
    }

    /** Ikki imzo SATRI bir xil qo'shiqnikimi ("sokin tun jonli" ~ "sokin tun"). */
    fun titleSignaturesConflict(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return signaturesConflict(
            a.split(' ').filter { it.isNotEmpty() }.toSet(),
            b.split(' ').filter { it.isNotEmpty() }.toSet()
        )
    }

    /** Bir xil qo'shiqning boshqa kanal/nomlanishdagi nusxasimi. */
    fun sameSong(a: YuzTrack, b: YuzTrack): Boolean {
        if (a.videoId.isNotBlank() && a.videoId == b.videoId) return true
        val sa = signatureTokens(a)
        val sb = signatureTokens(b)
        if (sa.isEmpty() || sb.isEmpty()) return a.id == b.id
        if (sa == sb || signaturesConflict(sa, sb)) return true
        /*
         * Ijrochi maydoni bo'sh qatorlarda ("Massa - Soqqa" kanal nomisiz)
         * imzoga ijrochi so'zlari tushib qoladi; shu so'zlarni ikkinchi
         * qatorning ijrochisi bo'yicha olib tashlab qayta solishtiramiz.
         */
        val extraA = if (a.artist.isBlank()) artistTokens(b.artist) else EMPTY_TOKENS
        val extraB = if (b.artist.isBlank()) artistTokens(a.artist) else EMPTY_TOKENS
        if (extraA.isEmpty() && extraB.isEmpty()) return false
        val ca = (sa - extraA).ifEmpty { sa }
        val cb = (sb - extraB).ifEmpty { sb }
        return ca == cb || signaturesConflict(ca, cb)
    }

    /** Ro'yxatdan bir xil qo'shiqlarni olib tashlaydi (birinchi qator qoladi). */
    fun dedupe(list: List<YuzTrack>): List<YuzTrack> {
        val out = ArrayList<YuzTrack>(list.size)
        for (t in list) {
            if (out.any { sameSong(it, t) }) continue
            out.add(t)
        }
        return out
    }

    /** Qo'shiq nomi/ijrochi bo'yicha qidirish — avval to'liq audio manbasi. */
    fun search(query: String): List<YuzTrack> {
        // YuzGram #67: avval worker /search (tez), keyin to'g'ridan-to'g'ri YT, Deezer.
        val gw = runCatching { YuzMusicGateway.search(query) }.getOrDefault(emptyList())
        if (gw.isNotEmpty()) return dedupe(gw)
        val yt = runCatching { YuzYouTubeApi.search(query) }.getOrDefault(emptyList())
        if (yt.isNotEmpty()) return dedupe(yt)
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return dedupe(fetchTracks("$BASE/search?q=$q&limit=25"))
    }

    /**
     * Boshlang'ich ekran: ommabop xitlar.
     * YuzGram #63: bu ham YouTube'dan — chart qatorlari ham to'liq eshitiladi.
     * YuzGram #104: endi SO'ROV QAT'II EMAS — seed havuzidan har safar
     * aralashtib olinadi, natijalar shufllanadi va ko'proq qator qaytadi.
     * Shunda "Ommabop" har doim yangi ko'rinishda ochiladi.
     */
    private val chartSeeds = arrayOf(
        "eng yaxshi ozbek qoshiklari 2026",
        "ozbek xit qoshiklar 2026",
        "trend musiqi 2026",
        "yangi ozbekcha qoshiklar",
        "ozbek pop musiqasi 2026",
        "mashhur qoshiklar kliplar",
        "ozbekiya hitlari 2026",
        "eng zo'r qoshiklar"
    )

    fun chart(): List<YuzTrack> {
        val order = chartSeeds.toList().shuffled()
        val out = LinkedHashMap<Long, YuzTrack>()
        // Birinchi seed odatda yetadi; 15 tadan kam bo'lsa ikkinchisi qo'shiladi.
        mergeInto(out, pull(order[0], 30), emptySet())
        if (out.size < 15) mergeInto(out, pull(order[1], 30), emptySet())
        if (out.isNotEmpty()) return out.values.shuffled().toList()
        return fetchTracks("$BASE/chart/0/tracks?limit=50")
    }

    /** #104: scroll oxiri — ko'rilganlardan tashqari yangi ommabop qatorlar. */
    fun chartMore(exclude: Set<Long>): List<YuzTrack> {
        val order = chartSeeds.toList().shuffled()
        val out = LinkedHashMap<Long, YuzTrack>()
        var i = 0
        while (out.size < 20 && i < order.size) {
            mergeInto(out, pull(order[i], 30), exclude)
            i++
        }
        return out.values.toList()
    }

    // YuzGram #116: tezkor qidiruv tugmalari seed hovuzlari.
    val seedsMostPlayed = arrayOf(
        "eng kop eshitilgan qoshiklar",
        "most listened songs 2026",
        "top hits most played",
        "eng mashhur qoshiklar 2026"
    )
    val seedsRussianHits = arrayOf(
        "russkie hity 2026",
        "top russian hits",
        "russkaya pop muzyka novinki 2026",
        "russkie pop qoshiklar"
    )

    /** #116: seed ro'yxatini birmarom ko'rib, exclude'dan tashqari qatorlar. */
    fun preset(seeds: Array<String>, exclude: Set<Long>, target: Int): List<YuzTrack> {
        val out = LinkedHashMap<Long, YuzTrack>()
        var i = 0
        while (out.size < target && i < seeds.size) {
            mergeInto(out, pull(seeds[i], 30), exclude)
            i++
        }
        return out.values.toList()
    }

    /**
     * #104: qidiruv natijalarini davom ettirish — so'rovga navbatdagi
     * variant qo'shib qayta qidiriladi (InnerTube'da offset yo'q), ko'rilgan
     * qatorlar chiqarib tashlanadi.
     */
    fun searchMore(query: String, exclude: Set<Long>, round: Int): List<YuzTrack> {
        val variants = arrayOf(
            " 2026", " lyrics", " official", " remix", " live", " bass boosted", " cover"
        )
        val q = query.trim() + variants[round % variants.size]
        val out = LinkedHashMap<Long, YuzTrack>()
        mergeInto(out, pull(q, 30), exclude)
        return out.values.toList()
    }

    /**
     * #294 + xilma-xillik: ANIQ qo'shiqqa o'xshash qatorlar — lekin endi
     * "faqat shu ijrochi / shu nomdagi qo'shiqlar" halqasi buziladi:
     *  1) yaqin qatorlar (YouTube avto-radio + o'xshash ijrochilar) —
     *     anchor ijrochisidan MAKSIMUM 2 qo'shiq, anchor nomining
     *     boshqa nusxalari (remix/cover/live/"official") umuman chiqmaydi;
     *  2) o'zbek sara qo'shiqlari havzasi — aylanadigan seedlar, 1 ijrochidan
     *     1 qo'shiq, aralashtirilgan tartibda.
     * Natija 1:2 nisbatda aralashtiriladi — asosan o'zbek sara qo'shiqlari.
     */
    private val uzDiversitySeeds = arrayOf(
        "eng sara ozbek qoshiklari",
        "ozbek musiqa top 2026",
        "yangi ozbek xitlari 2026",
        "ozbek estrada eng yaxshi qoshiklar",
        "ozbek xit paradi 2026",
        "ozbekcha eng zo'r qoshiklar",
        "mashhur ozbek qoshiklari 2026",
        "ozbek gold qoshiklar"
    )
    @Volatile private var diversityRotate = 0

    fun similar(anchor: YuzTrack, exclude: Set<Long>, limit: Int = 20): List<YuzTrack> {
        val anchorKey = artistKey(anchor.artist)

        // 1) Yaqin qatorlar: radio + o'xshash ijrochilar + (zaxira) shu
        //    ijrochining boshqa qo'shiqlari — lekin cheklangan.
        val base = ArrayList<YuzTrack>()
        if (anchor.videoId.isNotBlank()) {
            base += runCatching { YuzYouTubeApi.radio(anchor.videoId, limit * 2) }
                .getOrDefault(emptyList())
        }
        val artist = anchor.artist.trim()
        if (artist.isNotEmpty()) {
            // Radio boy bo'lsa o'xshash ijrochilarni qo'shimcha tortmaymiz —
            // sust tarmoqda har bir pull alohida kutishdir.
            if (base.size < 10) {
                for (name in relatedArtistNames(artist, 4)) {
                    base += pull(name + " qoshiklari", 10)
                }
            }
            base += pull(artist + " qoshiklari", 12)
        }
        val near = ArrayList<YuzTrack>()
        val nearArtists = HashMap<String, Int>()
        val seenSigs = HashSet<String>()
        for (t in base) {
            if (t.id in exclude || t.title.isBlank() || !longEnough(t)) continue
            if (sameSong(anchor, t)) continue
            val sig = titleSignature(t)
            if (sig.isEmpty() || !seenSigs.add(sig)) continue
            val k = artistKey(t.artist)
            val n = if (k.isEmpty()) 0 else nearArtists[k] ?: 0
            if (k == anchorKey && n >= 2) continue
            if (k.isNotEmpty()) nearArtists[k] = n + 1
            near.add(t)
        }

        // 2) O'zbek sara qo'shiqlari havzasi — har chaqiruvda boshqa seedlar.
        val uz = ArrayList<YuzTrack>()
        val uzArtists = HashMap<String, Int>()
        val s1 = uzDiversitySeeds[diversityRotate % uzDiversitySeeds.size]
        val s2 = uzDiversitySeeds[(diversityRotate + 1) % uzDiversitySeeds.size]
        val s3 = uzDiversitySeeds[(diversityRotate + 3) % uzDiversitySeeds.size]
        diversityRotate++
        for (s in arrayOf(s1, s2, s3)) {
            for (t in pull(s, 20)) {
                if (t.id in exclude || t.title.isBlank() || !longEnough(t)) continue
                if (sameSong(anchor, t)) continue
                val sig = titleSignature(t)
                if (sig.isEmpty() || sig in seenSigs || !seenSigs.add(sig)) continue
                val k = artistKey(t.artist)
                val n = if (k.isEmpty()) 0 else uzArtists[k] ?: 0
                // O'zbek havzasida 1 ijrochidan faqat 1 qo'shiq — maksimal xilma-xillik.
                if (k.isNotEmpty() && n >= 1) continue
                if (k.isNotEmpty()) uzArtists[k] = n + 1
                uz.add(t)
            }
        }
        uz.shuffle()

        // 3) 1 yaqin : 2 o'zbek nisbatida aralashtirish.
        val out = ArrayList<YuzTrack>(limit)
        var i = 0
        var j = 0
        while (out.size < limit) {
            var added = false
            if (j < uz.size && out.size < limit) { out.add(uz[j++]); added = true }
            if (j < uz.size && out.size < limit) { out.add(uz[j++]); added = true }
            if (i < near.size && out.size < limit) { out.add(near[i++]); added = true }
            if (!added) break
        }
        return out
    }

    /** Bitta seedni avval worker (tez), keyin to'g'ridan-to'g'ri YouTube'dan oladi. */
    private fun pull(seed: String, limit: Int): List<YuzTrack> {
        val gw = runCatching { YuzMusicGateway.search(seed, limit) }.getOrDefault(emptyList())
        if (gw.isNotEmpty()) return gw
        return runCatching { YuzYouTubeApi.search(seed, limit) }.getOrDefault(emptyList())
    }

    private fun mergeInto(
        map: LinkedHashMap<Long, YuzTrack>,
        list: List<YuzTrack>,
        exclude: Set<Long>
    ) {
        val kept = ArrayList<YuzTrack>(map.values)
        for (t in list) {
            if (t.id in exclude || t.title.isBlank()) continue
            if (map.containsKey(t.id)) continue
            if (kept.any { sameSong(it, t) }) continue
            map[t.id] = t
            kept.add(t)
        }
    }

    /**
     * #287: Deezer "o'xshash ijrochilar" grafi — faqat NOMLAR qaytadi.
     * To'liq treklar baribir YouTube shlyuzidan olinadi; Deezer preview
     * qatorlari ishlatilmaydi.
     */
    fun relatedArtistNames(artist: String, limit: Int = 6): List<String> {
        val name = artist.trim()
        if (name.length < 2) return emptyList()
        return try {
            val q = URLEncoder.encode(name, "UTF-8")
            val body = httpGet("$BASE/search/artist?q=$q&limit=5", 3000, 4000)
                ?: return emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val target = artistKey(name)
            var id = 0L
            for (i in 0 until data.length()) {
                val a = data.optJSONObject(i) ?: continue
                val aid = a.optLong("id")
                if (aid == 0L) continue
                if (id == 0L) id = aid
                if (artistKey(a.optString("name")) == target) {
                    id = aid
                    break
                }
            }
            if (id == 0L) return emptyList()
            val relBody = httpGet("$BASE/artist/$id/related?limit=$limit", 3000, 4000)
                ?: return emptyList()
            val rel = JSONObject(relBody).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<String>(limit)
            for (i in 0 until rel.length()) {
                val n = rel.optJSONObject(i)?.optString("name").orEmpty().trim()
                if (n.isEmpty()) continue
                if (artistKey(n) == target) continue
                if (out.any { artistKey(it) == artistKey(n) }) continue
                out.add(n)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchTracks(url: String): List<YuzTrack> {
        val body = httpGet(url) ?: return emptyList()
        return try {
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<YuzTrack>(data.length())
            for (i in 0 until data.length()) {
                val t = data.optJSONObject(i) ?: continue
                val title = t.optString("title").ifBlank { t.optString("title_short") }
                if (title.isBlank()) continue
                val duration = t.optInt("duration")
                if (duration in 1 until MIN_TRACK_SECONDS) continue
                val artist = t.optJSONObject("artist")?.optString("name").orEmpty()
                val albumObj = t.optJSONObject("album")
                out.add(
                    YuzTrack(
                        id = t.optLong("id"),
                        title = title,
                        artist = artist,
                        album = albumObj?.optString("title").orEmpty(),
                        durationSec = duration,
                        coverUrl = albumObj?.optString("cover_medium").orEmpty(),
                        previewUrl = t.optString("preview"),
                        link = t.optString("link")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun httpGet(url: String, connectMs: Int = 6000, readMs: Int = 8000): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectMs
                readTimeout = readMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", "YuzGram/1.0")
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
