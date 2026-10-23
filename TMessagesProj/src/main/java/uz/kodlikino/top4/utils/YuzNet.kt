package uz.kodlikino.top4.utils

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * #160 Ilovaning HTTP qatlami. Ikki sabab bilan mavjud:
 *
 * 1) IPv4 OLDIN. Cloudflare'ning bitta IPv6 manzili ba'zi tarmoqlarda "qora
 *    tuynuk": SYN javobsiz qoladi va ulanish 20+ soniya kutadi. O'lchov
 *    (2026-10-23): worker javobni 1.5s'da qaytardi, mijozda esa 23-44s kutildi.
 *    OkHttp DNS ro'yxati bo'ylab yuradi — IPv4'ni oldinga qo'yish kutishni
 *    butunlay oladi. IPv6-only tarmoqda IPv6 ro'yxatda qoladi, hech narsa
 *    buzilmaydi.
 *
 * 2) [java.net.HttpURLConnection] faqat BIRINCHI manzilni sinaydi: qora
 *    tuynukda so'rov kutib o'rniga UMUMAN muvaffaqiyatsiz tugardi. Bundan
 *    tashqari keep-alive uchun `disconnect()` qilmaslik hiylasi kerak edi;
 *    OkHttp'da bitta umumiy pool buni o'zi bajaradi (/voice -> /speak
 *    ketma-ketligida TLS handshake qayta to'lanmaydi).
 */
object YuzNet {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    val DNS_V4_FIRST: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            if (all.size < 2) return all
            val v4 = all.filterIsInstance<Inet4Address>()
            if (v4.isEmpty() || v4.size == all.size) return all
            return v4 + all.filter { it !is Inet4Address }
        }
    }

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DNS_V4_FIRST)
            .retryOnConnectionFailure(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** HTTP kodi + body (xato kodlarida ham). `null` — so'rov o'tmadi. */
    class Reply(val code: Int, val body: String)

    fun postJson(url: String, json: String, connectMs: Int, readMs: Int, ua: String): Reply? =
        exchange(
            Request.Builder().url(url).header("User-Agent", ua)
                .post(json.toRequestBody(JSON)).build(),
            connectMs, readMs
        )

    fun get(url: String, connectMs: Int, readMs: Int, ua: String): Reply? =
        exchange(
            Request.Builder().url(url).header("User-Agent", ua).get().build(),
            connectMs, readMs
        )

    /** DNS+TLS+edge isitish: worker bu yo'lda Gemini'ni chaqirmaydi. */
    fun warm(url: String, timeoutMs: Int) {
        try {
            client(timeoutMs, timeoutMs).newCall(
                Request.Builder().url(url).header("User-Agent", "YuzGram-AI/2.0")
                    .method("OPTIONS", null).build()
            ).execute().use { it.body?.string() }
        } catch (_: Exception) {
        }
    }

    /** newBuilder() pool va dispatcher'ni bo'lishadi — keep-alive saqlanadi. */
    private fun client(connectMs: Int, readMs: Int): OkHttpClient = base.newBuilder()
        .connectTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(readMs.toLong(), TimeUnit.MILLISECONDS)
        .build()

    private fun exchange(req: Request, connectMs: Int, readMs: Int): Reply? = try {
        client(connectMs, readMs).newCall(req).execute().use { r ->
            Reply(r.code, r.body?.string() ?: "")
        }
    } catch (_: Exception) {
        null
    }
}
