package uz.kodlikino.top4.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

    /** Tarmoqsiz, bir zumda javob beriladigan savollar (soat, sana, salomlashuv). */
    fun quickAnswer(question: String): String? = localAnswer(question.trim())

    // ------------------------------------------------------------- #157 xotira
    /**
     * YuzGram #157: suhbat xotirasi. Ilgari har bir so'rov BIR TARMOQLI edi —
     * "unga yana yoz", "batafsilroq ayt" kabi davom ettiruvchi gaplar umuman
     * ishlamasdi. Endi oxirgi [MEMORY_TURNS] savol/javob jufti worker'ga "h"
     * ro'yxatida yuboriladi; matnli va ovozli yo'l bitta xotirani bo'lishadi.
     */
    private const val MEMORY_TURNS = 3
    private const val MEMORY_CHARS = 200
    private val memory = ArrayDeque<Pair<String, String>>()

    fun remember(question: String, answer: String) {
        val q = question.trim().take(MEMORY_CHARS)
        val a = answer.trim().take(MEMORY_CHARS)
        if (q.isEmpty() || a.isEmpty()) return
        synchronized(memory) {
            memory.addLast(q to a)
            while (memory.size > MEMORY_TURNS) memory.removeFirst()
        }
    }

    fun clearMemory() {
        synchronized(memory) { memory.clear() }
    }

    /** Worker'ga ketadigan oldingi suhbat; bo'sh bo'lsa null (ortiqcha bayt yo'q). */
    fun historyJson(): JSONArray? {
        val out = JSONArray()
        synchronized(memory) {
            for (pair in memory) {
                out.put(JSONObject().put("r", "user").put("t", pair.first))
                out.put(JSONObject().put("r", "model").put("t", pair.second))
            }
        }
        return if (out.length() == 0) null else out
    }

    suspend fun answer(question: String): String = withContext(Dispatchers.IO) {
        val q = question.trim()
        if (q.isBlank()) return@withContext "Ha, eshitaman. Savolingizni ayting."
        localAnswer(q)?.let { remember(q, it); return@withContext it }
        if (isWeather(q)) {
            weatherAnswer()?.let { remember(q, it); return@withContext it }
        }
        if (AI_PROXY_URL.isNotBlank()) {
            llmAnswer(q)?.let { remember(q, it); return@withContext it }
        }
        // #157: worker limit (429) bergan bo'lsa SABABNI aytamiz — Wikipedia'ga
        // tushib "manba topa olmadim" deyish foydalanuvchini chalkashtiradi.
        rateLimitedMessage?.let { return@withContext it }
        (wikipediaAnswer(q) ?: fallback(q)).also { remember(q, it) }
    }

    /**
     * Worker'ga POST {"q": "..."} → javob {"a": "matn"}. Xato/bo'sh bo'lsa null
     * (zanjir davom etadi, Wikipedia fallback'ga tushadi).
     */
    private fun llmAnswer(question: String): String? {
        return try {
            val body = JSONObject().put("q", question)
            historyJson()?.let { body.put("h", it) }
            val response = httpPostJson(AI_PROXY_URL, body.toString()) ?: return null
            val text = JSONObject(response).optString("a").trim()
            if (text.length < 2) null else text
        } catch (_: Exception) {
            null
        }
    }

    private fun httpPostJson(url: String, jsonBody: String): String? {
        // #160: OkHttp + IPv4-first DNS. HttpURLConnection bitta manzilni
        // sinardi va qora tuynuk IPv6'da so'rov butunlay so'nardi.
        val r = YuzNet.postJson(url, jsonBody, 6000, 12000, "YuzgramAI/1.0") ?: return null
        if (r.code !in 200..299) {
            rateLimitedMessage = rateText(r)
            return null
        }
        return r.body
    }

    /** Oxirgi 429 (limit) xabari — worker o'zbekcha tayyor matn qaytaradi. */
    @Volatile
    var rateLimitedMessage: String? = null
        private set

    private fun rateText(r: YuzNet.Reply): String? = try {
        if (r.code != 429) null
        else JSONObject(r.body).optString("a").trim().takeIf { it.length > 3 }
    } catch (_: Exception) {
        null
    }

    private fun localAnswer(raw: String): String? {
        val n = raw.lowercase().replace('’', '\'').replace('‘', '\'').trim()
        val flat = n.replace("'", "")
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
            // #134: odam kabi qisqa suhbat — tarmoq kutmasdan, bir zumda.
            n == "salom" || n.startsWith("salom ") || n.contains("assalom") || n.contains("salomlar") ->
                pick(
                    "Salom! Sizni eshityapman. Gaplashamizmi?",
                    "Assalomu alaykum! Nima bilan yordam bera olaman?",
                    "Salom! Men shu yerdaman — marhamat, gapiring."
                )
            n.contains("qalesan") || n.contains("qalaysan") || n.contains("qandaysan") ||
                n.contains("yaxshimisan") || n.contains("tuzukmisan") || n.contains("tuzuqmisan") ->
                pick(
                    "Yaxshi, rahmat! Sizga yordam berishga tayyorman. Siz qalaysiz?",
                    "Zo'r, rahmat! Sizni eshityapman. Kayfiyatingiz qanday?",
                    "Yaxshi! Doim shu yerdaman. Siz nima qilyapsiz?"
                )
            n.contains("rahmat") || n.contains("raxmat") || n.contains("tashakkur") ->
                pick(
                    "Arzimaydi, doim yordamga tayyorman.",
                    "Hechqisi yo'q! Yana savolingiz bo'lsa, ayting."
                )
            n == "xayr" || n.startsWith("xayr ") || flat.contains("korishguncha") || flat.contains("sog bo") ->
                pick(
                    "Xayr! Yana kerak bo'lsam, shu yerdaman.",
                    "Ko'rishguncha! Yaxshi kun tilayman."
                )
            flat.contains("nimagap") || n.contains("nima gap") || n.contains("nima yangilik") ->
                pick(
                    "Hammasi joyida — ishlayapman. Sizda nima yangilik?",
                    "Tinch, tinch. Sizni eshityapman, gapiring."
                )
            n.contains("ob-havo") || n.contains("ob havo") || n.contains("havo qanday") -> null
            else -> null
        }
    }

    private fun pick(vararg options: String): String = options.random()

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
        val r = YuzNet.get(url, 5000, 5000, "YuzgramAI/1.0") ?: return null
        return if (r.code in 200..299) r.body else null
    }
}
