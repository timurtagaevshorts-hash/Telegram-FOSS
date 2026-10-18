package uz.kodlikino.top4.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI yordamchining "miyasi" — Gemini kalitsiz ham ishlaydigan mahalliy javoblar:
 * soat/sana, ilova haqida, ob-havo (open-meteo) va Wikipedia qidiruvi (uz/ru/en).
 * KinoTOP4 donor loyihasidagi [uz.kodlikino.top4.utils.YuzgramAiBrain] porti.
 */
object YuzgramAiBrain {

    /**
     * Cloudflare Worker proksi manzili (Gemini kaliti FAKAT worker'da; APK'da yoq).
     * Bo'sh bo'lsa — LLM qadami o'tkazib yuboriladi, eski Wikipedia zanjiri ishlaydi.
     */
    private const val AI_PROXY_URL = "https://yuzgram-ai-proxy.timurtagaevshorts.workers.dev/chat"

    suspend fun answer(question: String): String = withContext(Dispatchers.IO) {
        val q = question.trim()
        if (q.isBlank()) return@withContext "Ha, eshitaman. Savolingizni ayting."
        localAnswer(q)?.let { return@withContext it }
        if (isWeather(q)) {
            weatherAnswer()?.let { return@withContext it }
        }
        if (AI_PROXY_URL.isNotBlank()) {
            llmAnswer(q)?.let { return@withContext it }
        }
        wikipediaAnswer(q) ?: fallback(q)
    }

    /**
     * Worker'ga POST {"q": "..."} → javob {"a": "matn"}. Xato/bo'sh bo'lsa null
     * (zanjir davom etadi, Wikipedia fallback'ga tushadi).
     */
    private fun llmAnswer(question: String): String? {
        return try {
            val body = JSONObject().put("q", question).toString()
            val response = httpPostJson(AI_PROXY_URL, body) ?: return null
            val text = JSONObject(response).optString("a").trim()
            if (text.length < 2) null else text
        } catch (_: Exception) {
            null
        }
    }

    private fun httpPostJson(url: String, jsonBody: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 12000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "YuzgramAI/1.0")
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

    private fun localAnswer(raw: String): String? {
        val n = raw.lowercase().trim()
        val now = Date()
        return when {
            n.contains("soat") || n.contains("vagt") || n.contains("vaqt nechchi") -> {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
                "Hozir soat $time."
            }
            n.contains("bugun") && (n.contains("sana") || n.contains("qaysi kun") || n.contains("nechinchi")) -> {
                val date = SimpleDateFormat("d MMMM yyyy, EEEE", Locale("uz")).format(now)
                "Bugun $date."
            }
            n.contains("yuzgram") && (n.contains("nima") || n.contains("kim") || n.contains("qanday")) ->
                "YuzGram — kinolar, reels, chat va AI yordamchi bor ilova. Men sizga xabar yuborish, soatni aytish va savollarga javob berishda yordam beraman."
            n.contains("xabar") && (n.contains("qanday") || n.contains("qanday yubor")) ->
                "Ismni va xabarni ayting. Masalan: «Sardor qayerdasan». Men kontakt yoki chatdan topib yuboraman."
            n.contains("sen kimsan") || n.contains("kim san") || n == "ai" || n.contains("isming") ->
                "Men YuzGram AI yordamchisiman. Siz bilan ovozda suhbatlashaman, savollarga javob beraman, xabar yuboraman."
            n.contains("ob-havo") || n.contains("ob havo") || n.contains("havo qanday") -> null
            else -> null
        }
    }

    private fun isWeather(raw: String): Boolean {
        val n = raw.lowercase()
        return n.contains("ob-havo") || n.contains("ob havo") || n.contains("havo qanday") ||
            n.contains("obhavo") || n.contains("yog'ingarchilik") || n.contains("issiqmi")
    }

    private fun weatherAnswer(): String? {
        return try {
            val url =
                "https://api.open-meteo.com/v1/forecast?latitude=40.103&longitude=65.369&current_weather=true&timezone=Asia%2FTashkent"
            val json = JSONObject(httpGet(url) ?: return null)
            val current = json.optJSONObject("current_weather") ?: return null
            val temp = current.optDouble("temperature")
            val wind = current.optDouble("windspeed")
            val code = current.optInt("weathercode")
            "Hozir Toshkentda taxminan ${temp.toInt()} daraja, shamol ${wind.toInt()} kilometr soat. ${weatherHint(code)}"
        } catch (_: Exception) {
            null
        }
    }

    private fun weatherHint(code: Int): String = when (code) {
        0 -> "Osmon ochiq."
        in 1..3 -> "Biroz bulutli."
        in 45..48 -> "Tumanli."
        in 51..67 -> "Yengil yomg'ir bo'lishi mumkin."
        in 71..77 -> "Qor yog'ishi mumkin."
        in 80..82 -> "Yomg'irli."
        in 95..99 -> "Momaqaldiroq bo'lishi mumkin."
        else -> "Havo o'zgaruvchan."
    }

    private fun wikipediaAnswer(query: String): String? {
        val cleaned = query
            .replace(Regex("\\?+$"), "")
            .replace(Regex("^(nima|kim|qayerda|qachon|qanday|neg|nega|why|what|who)\\s+"), "")
            .replace(Regex("\\s+(nima|haqida|haqida ayt|tushuntir|aytib ber)$"), "")
            .trim()
        if (cleaned.length < 3) return null
        listOf("uz", "ru", "en").forEach { lang ->
            searchWiki(lang, cleaned)?.let { return it }
        }
        return null
    }

    private fun searchWiki(lang: String, query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl =
                "https://$lang.wikipedia.org/w/api.php?action=opensearch&search=$encoded&limit=1&namespace=0&format=json"
            val array = JSONArray(httpGet(searchUrl) ?: return null)
            val titles = array.optJSONArray(1) ?: return null
            val title = titles.optString(0)
            if (title.isBlank()) return null
            val summaryUrl =
                "https://$lang.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title, "UTF-8").replace("+", "%20")
            val summary = JSONObject(httpGet(summaryUrl) ?: null)
            val extract = summary.optString("extract").trim()
            if (extract.length < 20) return null
            shortenForVoice(extract)
        } catch (_: Exception) {
            null
        }
    }

    private fun shortenForVoice(text: String): String {
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        val cut = sentences.take(2).joinToString(" ").trim()
        return if (cut.length > 320) cut.take(317).trim() + "…" else cut
    }

    private fun fallback(query: String): String =
        "Bu haqida aniq manba topa olmadim. Savolni boshqacha ayting, yoki xabar yuborish kabi buyruq bering."

    private fun httpGet(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "YuzgramAI/1.0")
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
