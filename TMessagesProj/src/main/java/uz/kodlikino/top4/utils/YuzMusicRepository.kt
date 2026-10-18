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
     */
    fun chart(): List<YuzTrack> {
        val q = "o'zbek qo'shiqlari 2026"
        val gw = runCatching { YuzMusicGateway.search(q) }.getOrDefault(emptyList())
        if (gw.isNotEmpty()) return gw
        val yt = runCatching {
            YuzYouTubeApi.search(q, limit = 25)
        }.getOrDefault(emptyList())
        if (yt.isNotEmpty()) return yt
        return fetchTracks("$BASE/chart/0/tracks?limit=25")
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
