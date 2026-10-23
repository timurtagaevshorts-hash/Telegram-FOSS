package uz.kodlikino.top4.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * /voice natijasi: bitta Gemini chaquvda transkripsiya + niyat + javob (+ inson ovozi WAV).
 */
data class YuzVoiceResult(
    val transcript: String,
    val intent: String,
    val peerName: String,
    val message: String,
    val goto: String,
    val answer: String,
    val audio: ByteArray?
)

/**
 * Cloudflare Worker'ning ovozli endpoint'lari. Kalit APK'da YO'Q — faqat worker'da.
 */
object YuzAiCloud {

    private const val BASE = "https://yuzgram-ai-proxy.timurtagaevshorts.workers.dev"

    suspend fun voice(wav: ByteArray, peers: List<String>): YuzVoiceResult? =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject()
                    .put("audio_b64", Base64.encodeToString(wav, Base64.NO_WRAP))
                    // Worker FAQAT birinchi 120 nomni ishlatadi — ko'p yuborish
                    // so'rovni uldirab, Gemini prompt'ini uzaytiradi.
                    .put("peers", JSONArray(peers.take(120)))
                    // Tezlik: /voice ichida TTS kutmaymiz — javob ovozi keyin /speak'dan olinadi.
                    .put("speak", false)
                // #157: oldingi 3 savol/javob — davom ettiruvchi buyruqlar uchun.
                uz.kodlikino.top4.utils.YuzgramAiBrain.historyJson()?.let { body.put("h", it) }
                // #157: 30s o'qish vaqti orbni osib qo'yardi — 15s'da zaxira
                // (tizim mikrofoni) ishga tushadi.
                val resp = post("$BASE/voice", body.toString(), 8000, 15000) ?: return@withContext null
                val j = JSONObject(resp)
                if (j.has("error") && !j.has("i")) return@withContext null
                val audio = j.optString("ab64").takeIf { it.isNotBlank() }?.let {
                    try { Base64.decode(it, Base64.NO_WRAP) } catch (_: Exception) { null }
                }
                YuzVoiceResult(
                    transcript = j.optString("t"),
                    intent = j.optString("i", "ask"),
                    peerName = j.optString("p"),
                    message = j.optString("x"),
                    goto = j.optString("g"),
                    answer = j.optString("a"),
                    audio = audio
                ).also { r ->
                    // #157: ovozli suhbat ham xotiraga yoziladi (matnli yo'l bilan birga).
                    if (r.transcript.isNotBlank() && r.answer.isNotBlank()) {
                        uz.kodlikino.top4.utils.YuzgramAiBrain.remember(r.transcript, r.answer)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

    /** Matn uchun inson ovozi (WAV baytlari) — bo'lmasa null (tizim TTS fallback). */
    suspend fun speakText(text: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("text", text)
            val resp = post("$BASE/speak", body.toString(), 6000, 20000) ?: return@withContext null
            val b64 = JSONObject(resp).optString("audio")
            if (b64.isBlank()) null else Base64.decode(b64, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * DNS+TLS+edge "isitish": birinchi haqiqiy so'rov handshake to'lovini to'lamaydi.
     * OPTIONS — worker darhol javob beradi (Gemini chaqilmaydi, rate-limit sanalmaydi).
     */
    suspend fun warmup() = withContext(Dispatchers.IO) {
        try {
            val c = (URL("$BASE/chat").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "OPTIONS"
            }
            // Body TO'LIQ o'qilsa Android socket'ni pool'da saqlaydi (keep-alive).
            c.inputStream.use { it.readBytes() }
            Unit
        } catch (_: Exception) {
        }
        // disconnect() YO'Q — pool socket'ni keyingi /voice o'rib oladi.
    }

    /** 429 (limit) xabari — UI foydalanuvchiga SABABNI aytishi uchun. */
    @Volatile
    var lastRateMessage: String? = null
        private set

    private fun rateText(c: HttpURLConnection): String? = try {
        c.errorStream?.bufferedReader()?.use { it.readText() }
            ?.let { JSONObject(it).optString("a").trim() }
            ?.takeIf { it.length > 3 }
    } catch (_: Exception) {
        null
    }

    private fun post(url: String, jsonBody: String, connectMs: Int, readMs: Int): String? {
        lastRateMessage = null
        var connection: HttpURLConnection? = null
        var healthy = false
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectMs
                readTimeout = readMs
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "YuzGram-AI/2.0")
            }
            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                // #157: worker 429 da tayyor o'zbekcha xabar qaytaradi — uni
                // chiqarib yubormaymiz, aks holda "aloqa uzildi" deyiladi.
                if (code == 429) lastRateMessage = rateText(connection)
                return null
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            healthy = true
            text
        } catch (_: Exception) {
            null
        } finally {
            // Muvaffaqiyatli so'rovda disconnect QILMAYMIZ — Android HTTPS
            // keep-alive pooli keyingi socket'ni qayta ishlatadi (~300-500 ms
            // TLS handshake tejaldi: /voice -> /speak ketma-ketligida).
            if (!healthy) {
                try { connection?.disconnect() } catch (_: Exception) { }
            }
        }
    }
}
