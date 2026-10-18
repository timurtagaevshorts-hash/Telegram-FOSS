package uz.kodlikino.top4.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * YuzGram #67: musiqa shlyuzi — Cloudflare Worker orqali qidiruv.
 *
 * Nima uchun: telefondan youtubei/v1/search ~10-20s (sekin); worker'ning o'zi
 * qidiruvni 1.8s'da qaytaradi va kichik, toza JSON beradi.
 * DIQQAT: /play (oqim proksi) HOZIR ISHLATMAIMIZ — YouTube CF data-center
 * IP'larini player so'rovlarida "LOGIN_REQUIRED / bot" deb bloklaydi. Audio
 * URL'lar telefonning o'z IP'sida resolve bo'lib, telefon'dan ochiladi
 * (googlevideo URL'lari IP-ga bog'langan).
 */
object YuzMusicGateway {

    private const val BASE = "https://yuzgram-ai-proxy.timurtagaevshorts.workers.dev"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Worker /search — tez ro'yxat. Xatoda bo'sh ro'yxat (chaqiruvchi fallback qiladi). */
    fun search(query: String, limit: Int = 25): List<YuzTrack> {
        return try {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("$BASE/search?q=$q&n=$limit")
                .header("User-Agent", "okhttp/4.12.0")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val arr = JSONObject(body).optJSONArray("tracks") ?: return emptyList()
                val out = ArrayList<YuzTrack>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val vid = o.optString("id")
                    if (vid.length != 11) continue
                    out.add(
                        YuzTrack(
                            id = vid.hashCode().toLong() and 0xFFFFFFFFL,
                            title = o.optString("t"),
                            artist = o.optString("a"),
                            album = "",
                            durationSec = o.optInt("d"),
                            coverUrl = o.optString("c"),
                            previewUrl = "",
                            link = "https://www.youtube.com/watch?v=$vid",
                            videoId = vid
                        )
                    )
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
