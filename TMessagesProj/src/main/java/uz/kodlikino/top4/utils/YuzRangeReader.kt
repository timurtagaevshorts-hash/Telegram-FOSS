package uz.kodlikino.top4.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * YuzGram #84: googlevideo audio oqimini kichik, cheklangan oynalar
 * ketma-ketligi bilan uzluksiz o'qiydigan yagona manba — ijro
 * (YuzStreamDataSource) va yuklab olish (YuzMusicCache) shu sinf ustida
 * quriladi.
 *
 * Nima uchun (PC probe'lari, 2026-10-19): bitta URL'dan Range'SIZ yoki
 * butun faylni so'rasak googlevideo HTTP 403 qaytaradi; kichik Range -> 206.
 * 1 MB ≈ 128 kbit/s'da 65 soniya — aynan foydalanuvchi aytagan "faqat
 * 1 daqiqalik qo'shiqlar eshitiladi" chegarasi. Eski kod bitta katta GET
 * qilgani uchun uzun treklar shu yerda sinib qolardi.
 *
 * Ishlash modeli (probe'langan):
 *  - yangi URL BORTA kichik Range so'rovini o'tkazadi (1MB -> 206,
 *    2/4/8MB -> 403);
 *  - shu URL'ni ishlatgach ikkinchi Range'li so'rov ko'pincha 403 oladi.
 *    Shuning uchun oynalar 3 tillarda yuriladi:
 *      1) URL sarf bo'lmagan -> "Range: bytes=.." header (ishonchli, 206);
 *      2) URL sarf bo'lgan  -> "?rn=N&range=START-END" QUERY ko'rinishi
 *         (yt-dlp usuli: bitta resolve bilan davom etadi, ~1 qo'shiq =
 *         1 resolve, buffering tushib ketmaydi);
 *      3) query rad etildi -> [refresh] orqali YANGI URL va 1-tillar.
 *    Qaysi til ishlaydini qurilmada birinchi javob hal qiladi — PC'dan
 *    buni tekshirib bo'lmadi (datacenter IP probe'lar oxirida to'liq
 *    rate-limit'ga kirdi, hatto bytes=0- ham 403). Shuning uchun har bir
 *    urinish javobdan o'rganadi, taxmindan emas.
 *  - javob Range'ni buzsa (0'dan kelgan baytlar o'rtaga aralashmasin)
 *    o'qish davom etmaydi: 206'da Content-Range boshi tekshiriladi,
 *    200'da Content-Length oyna hajmiga teng bo'lishi shart.
 *
 * EOF faqat haqiqiy oxirda qaytadi — media3 "source ended unexpectedly"ga
 * chiqmasligi uchun. Tuzilmaydigan xato -> IOException (chaqiruvchi
 * keyingi nomzodga o'tadi).
 *
 * @param start o'qish boshlanadigan absolyut bayt
 * @param limit nechta bayt kerak (-1 = fayl oxirigacha)
 * @param expectedTotal URL'dagi clen= (0 = noma'lum; 206 javob aniqlaydi)
 * @param refresh (bayt joyi, eski URL) -> shu formatda davom ettiruvchi
 *        yangi URL; null = yo'q
 * @param windows false = bitta so'rov, oynalar yo'q (Range'ni
 *        qo'llamaydigan serverlar, masalan Deezer preview)
 */
class YuzRangeReader(
    private val http: OkHttpClient,
    private var url: String,
    private var ua: String,
    private val start: Long,
    private val limit: Long,
    expectedTotal: Long,
    private val refresh: ((Long, String) -> YuzAudioStream?)? = null,
    private val windows: Boolean = true
) {

    companion object {
        private const val TAG = "YuzMusic"

        // Probe: 1MB -> 206, 2MB/4MB/8MB -> 403. Rad etilsa kamayamiz.
        private val LADDER = longArrayOf(1L shl 20, 512L shl 10, 128L shl 10)
        private const val MAX_TRIES = 6
    }

    private var pos = start
    private var total = if (expectedTotal > 0L) expectedTotal else -1L
    private var windowEnd = -1L
    private var window = LADDER[0]
    private var windowStep = 0
    private var rn = 0
    private var singleShot = !windows
    private var finished = false
    private var urlSpent = false
    private var queryFailed = false
    private var cleanWindows = 0
    private var response: Response? = null
    private var stream: InputStream? = null

    /** start'dan qolgan jami bayt; -1 = hali aniq emas. */
    val length: Long
        get() {
            if (total < 0L) return -1L
            val t = (total - start).coerceAtLeast(0L)
            return if (limit >= 0L) minOf(t, limit) else t
        }

    /** Birinchi oynani ochadi — shundan keyin [length] aniq bo'ladi. */
    @Throws(IOException::class)
    fun open() {
        openWindow()
    }

    /** [buffer]'ga offset'dan boshlab yozadi; -1 = haqiqiy oxir. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        if (count <= 0) return 0
        var guard = 0
        while (guard++ < 64) {
            if (isEof()) return -1
            val s = stream ?: run {
                openWindow()
                stream ?: return -1
            }
            if (pos >= windowEnd) {
                if (singleShot) return -1
                openWindow()
                continue
            }
            val room = (windowEnd - pos).coerceAtMost(count.toLong()).toInt()
            if (room <= 0) return -1
            val n = try {
                s.read(buffer, offset, room)
            } catch (e: IOException) {
                Log.i(TAG, "range read broke pos=$pos ${e.javaClass.simpleName}")
                if (singleShot) return -1
                openWindow()
                continue
            }
            if (n > 0) {
                pos += n
                return n
            }
            // Jism tugadi, lekin oyna to'lmagan — uzilgan ulanish.
            if (singleShot || isEof()) return -1
            Log.i(TAG, "window short at pos=$pos end=$windowEnd — reopen")
            openWindow()
        }
        throw IOException("range read stuck at $pos")
    }

    fun isEof(): Boolean {
        if (finished) return true
        if (limit >= 0L && pos >= start + limit) return true
        return total >= 0L && pos >= total
    }

    /** Qancha bayt o'qilgan (download progress uchun). */
    fun position(): Long = pos

    fun close() {
        runCatching { stream?.close() }
        runCatching { response?.close() }
        stream = null
        response = null
    }

    // ------------------------------------------------------------- ichki

    @Throws(IOException::class)
    private fun openWindow() {
        close()
        if (isEof()) {
            finished = true
            return
        }
        var last: Exception? = null
        var attempt = 0
        while (attempt < MAX_TRIES) {
            if (attempt > 0) {
                last?.let { Log.i(TAG, "  retry: ${it.message}") }
                try {
                    Thread.sleep((200L * (1L shl (attempt - 1))).coerceAtMost(3000L))
                } catch (_: InterruptedException) {
                }
            }
            val end = windowEndFor()
            if (windows && end < pos) {
                finished = true
                return
            }
            val useQuery = windows && urlSpent && !queryFailed
            try {
                val resp = fetch(end, useQuery)
                val code = resp.code
                if (code == 416) {
                    parseTotal(resp.header("Content-Range"))
                    runCatching { resp.close() }
                    finished = true
                    Log.i(TAG, "range 416 at pos=$pos total=$total — eof")
                    return
                }
                if (code == 206 || code == 200) {
                    val body = resp.body?.byteStream()
                        ?: throw IOException("empty body")
                    val want = end - pos + 1
                    val cl = resp.body?.contentLength() ?: -1L
                    val crStart = rangeStart(resp.header("Content-Range"))
                    // Jism javob biz kutgan baytlardan boshlanishi shart,
                    // aks holda 0'dan kelgan baytlar o'rtaga aralashadi.
                    // 200 javobni faqat hajmi to'g'ri kelganda qabul qilamiz
                    // (chunked = tekshirib bo'lmaydi → rad).
                    val honored = when {
                        code == 206 -> crStart < 0L || crStart == pos
                        pos == start -> true
                        !windows -> false
                        else -> cl == want
                    }
                    if (!honored) {
                        runCatching { resp.close() }
                        throw IOException("range ignored code=$code cl=$cl want=$want")
                    }
                    response = resp
                    stream = body
                    if (code == 206) parseTotal(resp.header("Content-Range"))
                    else if (cl > 0L && total < 0L && !useQuery) total = pos + cl
                    // Oynasiz rejimda jism = qolgan butun fayl, shuning uchun
                    // chekni hajm bo'yicha qo'yamiz (1MB'lik oyna bilan kesib
                    // qo'ymaslik uchun).
                    windowEnd = when {
                        !windows -> bounded(if (cl > 0L) pos + cl else Long.MAX_VALUE / 4)
                        code == 206 || cl <= 0L -> bounded(end + 1L)
                        else -> bounded(pos + cl)
                    }
                    urlSpent = true
                    // Kichik oyna = ko'proq resolve. Bir nechta oyna muvaffaqiyatli
                    // o'qilsa hajmini qayta ko'taramiz (1 qo'shiq = 1 resolve maqsad).
                    cleanWindows++
                    if (cleanWindows >= 3 && windowStep > 0) {
                        windowStep--
                        window = LADDER[windowStep]
                        cleanWindows = 0
                        Log.i(TAG, "range window back to ${window / 1024}KB")
                    }
                    Log.i(TAG, "range open $pos..$windowEnd code=$code q=$useQuery total=$total")
                    return
                }
                runCatching { resp.close() }
                last = IOException("HTTP $code")
                Log.i(TAG, "range HTTP $code pos=$pos win=${window / 1024}KB q=$useQuery ${hostOf()}")
            } catch (e: Exception) {
                last = e
                Log.i(TAG, "range ERR ${e.javaClass.simpleName} ${e.message?.take(48)} pos=$pos")
            }
            // Rad etildi: query tili ishlamasa URL'ni yangilaymiz (bitta
            // URL = bitta ishlaydigan range so'rovi degan taxmin), aksincha
            // shu URL'da qolib keyingi oynaga o'tamiz. Oyna hajmini ham
            // bosamiz — server katta range'larni umuman yubormasligi mumkin.
            attempt++
            if (useQuery) queryFailed = true
            if ((urlSpent && queryFailed) || (!urlSpent && attempt >= 2)) refreshUrl()
            if (attempt >= 2 && windowStep < LADDER.size - 1) {
                windowStep++
                window = LADDER[windowStep]
            }
        }
        throw IOException("range open failed at $pos: ${last?.message}")
    }

    @Throws(IOException::class)
    private fun fetch(end: Long, useQuery: Boolean): Response {
        val builder = Request.Builder()
            .url(if (useQuery) queryUrl(end) else url)
            .header("User-Agent", ua)
            .header("Accept-Encoding", "identity")
        if (windows) {
            if (!useQuery) builder.header("Range", "bytes=$pos-$end")
        } else if (start > 0L) {
            builder.header("Range", "bytes=$start-")
        }
        return http.newCall(builder.build()).execute()
    }

    /** yt-dlp usuli: bitta URL'da range'ni query param bilan davom ettirish. */
    private fun queryUrl(end: Long): String {
        rn++
        val sep = if (url.contains('?')) "&" else "?"
        return "$url$sep=rn=$rn&range=$pos-$end"
    }

    private fun refreshUrl() {
        val next = runCatching { refresh?.invoke(pos, url) }.getOrNull()
        if (next == null || next.url.isBlank() || next.url == url) return
        url = next.url
        ua = next.ua
        urlSpent = false
        queryFailed = false
        Log.i(TAG, "range new url @pos=$pos ext=${next.ext}")
    }

    private fun hostOf(): String =
        url.run { substringAfter("://").substringBefore("/") }.take(28)

    private fun bounded(e: Long): Long {
        var v = e
        if (total > 0L) v = minOf(v, total)
        if (limit >= 0L) v = minOf(v, start + limit)
        return v
    }

    private fun windowEndFor(): Long {
        var end = pos + window - 1
        if (total > 0L) end = minOf(end, total - 1)
        if (limit >= 0L) end = minOf(end, start + limit - 1)
        return end
    }

    /** "bytes 1048576-2097151/3853271" -> total = 3853271 */
    private fun parseTotal(contentRange: String?) {
        val cr = contentRange ?: return
        val slash = cr.lastIndexOf('/')
        if (slash < 0 || slash == cr.length - 1) return
        val t = cr.substring(slash + 1).trim().toLongOrNull() ?: return
        if (t > 0L) total = t
    }

    /** Content-Range'dagi boshlang'ich bayt; -1 = yo'q/aniksiz. */
    private fun rangeStart(contentRange: String?): Long {
        val cr = contentRange ?: return -1L
        val sp = cr.indexOf(' ')
        if (sp < 0 || cr.substring(0, sp).trim() != "bytes") return -1L
        return cr.substring(sp + 1).substringBefore('-').trim().toLongOrNull() ?: -1L
    }
}
