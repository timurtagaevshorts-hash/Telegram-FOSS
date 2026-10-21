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

    private const val BASE = "https://api.deezer.com"

    /** Qo'shiq nomi/ijrochi bo'yicha qidirish — avval to'liq audio manbasi. */
    fun search(query: String): List<YuzTrack> {
        // YuzGram #67: avval worker /search (tez), keyin to'g'ridan-to'g'ri YT, Deezer.
        val gw = runCatching { YuzMusicGateway.search(query) }.getOrDefault(emptyList())
        if (gw.isNotEmpty()) return gw
        val yt = runCatching { YuzYouTubeApi.search(query) }.getOrDefault(emptyList())
        if (yt.isNotEmpty()) return yt
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return fetchTracks("$BASE/search?q=$q&limit=25")
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
        for (t in list) {
            if (t.id in exclude || t.title.isBlank()) continue
            if (!map.containsKey(t.id)) map[t.id] = t
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
                val artist = t.optJSONObject("artist")?.optString("name").orEmpty()
                val albumObj = t.optJSONObject("album")
                out.add(
                    YuzTrack(
                        id = t.optLong("id"),
                        title = title,
                        artist = artist,
                        album = albumObj?.optString("title").orEmpty(),
                        durationSec = t.optInt("duration"),
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

    private fun httpGet(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
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
