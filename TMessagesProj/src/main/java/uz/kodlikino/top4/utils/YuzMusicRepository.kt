package uz.kodlikino.top4.utils

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Musiqa qidiruvi — Deezer och API (kalit TALAB QILMAYDI).
 * Natijada: nom, ijrochi, muqova va 30 soniyalik preview mp3.
 * YuzGram #62: preview ilova ichidagi pleyerda ijro etiladi (YouTube emas),
 * xohlasa keshga yuklab olinadi (YuzMusicCache).
 */
data class YuzTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val coverUrl: String,
    val previewUrl: String,
    val link: String
)

object YuzMusicRepository {

    private const val BASE = "https://api.deezer.com"

    /** Qo'shiq nomi/ijrochi bo'yicha qidirish. */
    fun search(query: String): List<YuzTrack> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return fetchTracks("$BASE/search?q=$q&limit=25")
    }

    /** Boshlang'ich ekran: ommabop xitlar. */
    fun chart(): List<YuzTrack> = fetchTracks("$BASE/chart/0/tracks?limit=25")

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
