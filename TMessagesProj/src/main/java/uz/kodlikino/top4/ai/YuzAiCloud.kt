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
                    .put("peers", JSONArray(peers.take(120)))
                val resp = post("$BASE/voice", body.toString(), 8000, 30000) ?: return@withContext null
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
                )
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

    private fun post(url: String, jsonBody: String, connectMs: Int, readMs: Int): String? {
        var connection: HttpURLConnection? = null
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
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
