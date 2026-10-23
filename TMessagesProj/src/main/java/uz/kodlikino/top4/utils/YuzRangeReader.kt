package uz.kodlikino.top4.utils

import android.util.Log
import okhttp3.Call
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
 * Nima uchun: eski yo'l bitta Range'siz GET bilan butun faylni so'ragan,
 * googlevideo uni ~1 MB'da (128 kbit/s ≈ 65 soniya) kesib 403 beragan.
 * Shu alomat "faqat 1 daqiqalik qo'shiqlar eshitiladi, kattalari ijro
 * boshlamaydi" edi.
 *
 * Kuzatilgan model (2026-10-19 JVM probe'lar, itag 140, clen=1452832):
 *  - KATTA range rad etiladi: 2/4/8MB -> 403; throttled IP'da 1MB ham 403,
 *    512KB ham 403, 128KB -> 206. Demak hajmni moslash tanlaymiz.
 *  - "Range: bytes=.." HEADER istalgan offset'da ishlaydi (131072 va
 *    262144'da 206 olindi) — shu asosiy til.
 *  - "?rn=N&range=START-END" QUERY (yt-dlp usuli) header rad etilganda
 *    yordam beradi: 200 + aniq shu baytlar.
 *  - Host almashtirish (mn= / rrN---) FAYDASIZ: 8 ta urinishning barchasi
 *    403 (imzo ip=id param'lariga bog'liq). Shuning uchun bu versiyada yo'q.
 *  - Xuddi shu URL'dagi ketma-ket kichik oynalar eng samarali — yangi
 *    resolve (refresh) oxirgi chora, chunki u qo'shimcha ~200ms va YouTube
 *    so'rovi.
 *
 * Escalatiq: til (header -> query) -> [refresh] bilan yangi URL (shu itag)
 * -> uzunroq "cooldown" bilan qayta urinish. Rad etilgan oyna hajmi kamayadi
 * (3 zina), 3 ta toza oynadan keyin ko'tariladi, lekin bir bor rad etilgan
 * hajmga qaytmaydi (badMask). O'rganilgan hajm butun pleyer uchun umumiy
 * (learnedStep) — har qo'shiq boshida 2 ta behuda 403 sarflanmaydi.
 *
 * YuzGram #86: oyna HAJMI va DEADLINE'i endi o'lchangan tezlikdan
 * hisoblanadi (YuzNetPulse). Oldin 1MB + 12s qat'iy edi: 8 kB/s'lik
 * taroqda 1 MB 2 daqiqada yuklanadi, deadline esa 12s'da o'ldirardi —
 * past internetda qo'shiq shu sababga ham umuman boshlanmasdi. Endi
 * oyna ~3 sekundda sig'adigan hajmga tushadi, deadline shunga mos
 * uzayadi, va [gate] orqali fon yuklashi ijroga yo'l beradi.
 *
 * Jism Range'ni buzsa (0'dan kelgan baytlar o'rtaga aralashib ketmasin)
 * javob rad etiladi: 206'da Content-Range boshi, 200'da Content-Length
 * oyna hajmi bilan tekshiriladi.
 *
 * EOF faqat haqiqiy oxirda qaytadi — media3 "source ended unexpectedly"ga
 * chiqmasligi uchun. Tuzilmaydigan xato -> IOException (chaqiruvchi
 * keyingi nomzodga o'tadi).
 *
 * @param start o'qish boshlanadigan absolyut bayt
 * @param limit nechta bayt kerak (-1 = fayl oxirigacha)
 * @param expectedTotal URL'dagi clen= (0 = noma'lum; 206 javob aniqlaydi)
 * @param refresh (bayt joyi, eski URL) -> shu itag'da davom ettiruvchi
 *        yangi URL; null = yo'q
 * @param windows false = bitta so'rov, oynalar yo'q (Range'ni
 *        qo'llamaydigan serverlar, masalan Deezer preview)
 * @param gate fon yuklashi uchun "yo'l berish" eshigi: har oyna ochishdan
 *        oldin chaqiriladi. Qaytargan son = shuncha ms kutish; 0 = darhol;
 *        manfiy = yuklashni BEKOR qil (ijro tarmoqni kerak qildi).
 *        Ijro mana shu o'qiyotgan bo'lsa null bo'ladi.
 */
class YuzRangeReader(
    private val http: OkHttpClient,
    private var url: String,
    private var ua: String,
    private val start: Long,
    private val limit: Long,
    expectedTotal: Long,
    private val refresh: ((Long, String) -> YuzAudioStream?)? = null,
    private val windows: Boolean = true,
    private val gate: (() -> Long)? = null,
    private val cancellation: YuzDownloadCancellation? = null
) {

    companion object {
        private const val TAG = "YuzMusic"

        // #86: oynaning YUQORI chegarasi adaptive (tezlikdan hisoblanadi),
        // lekin PROVEN zinasidan oshmaydi — 1MB/512KB/128KB telefonda
        // sinab ko'rilgan: katta range'larni googlevideo 403 bilan rad
        // etadi, 128KB esa throttling bo'lgan IP'da ham ishlaydi.
        // Sekin taroqda chegara kichrayadi va butun zina kichrayadi.
        private val PROVEN = longArrayOf(1L shl 20, 512L shl 10, 128L shl 10)
        private const val CEIL_MAX = 1L shl 20
        private const val STEP_MIN = 16L shl 10
        private const val STEPS = 3
        private const val MAX_TRIES = 9
        // Yangi URL ko'pincha ishlaydi — 2 tadan ko'p resolve trekni
        // sekinlashtiradi, lekin bittasi yetmaydi.
        // #161: endi bu KETMA-KET cheklov: muvaffaqiyatli ochilgan oyna
        // counter'ni nollaydi. Eskidan bu READER UMRI bo'yicha 2 edi —
        // uzun trekda 3-url'dan keyin (har biri PO-cap bilan o'lsa)
        // refresh umuman bo'lmay qolardi va ijro yarimda to'xtar edi.
        private const val MAX_REFRESHES = 2
        // Hammasi rad etilsa: cooldown bilan qayta urinishlar soni.
        private const val MAX_STALL_RETRIES = 2

        /**
         * Butun pleyer uchun umumiy o'rganilgan oyna hajmi. Birinchi trek
         * sinab biladi, keyingi treklar shu hajmdan boshlaydi.
         */
        @Volatile
        private var learnedStep = 0
    }

    /** Jism kutgan baytlardan boshlandi — offset buziladi, javob rad. */
    private class RangeMismatch(message: String) : Exception(message)

    private var pos = start
    private var total = if (expectedTotal > 0L) expectedTotal else -1L
    private var windowEnd = -1L
    private var windowStep = learnedStep.coerceIn(0, STEPS - 1)
    // #86: hajm har oyna ochishda tezlikdan qayta hisoblanadi.
    private var ceiling = CEIL_MAX
    private var window = stepBytes(windowStep)
    private var sizeFails = 0
    private var badMask = 0
    private var cleanWindows = 0
    private var rn = 0
    private val singleShot = !windows
    private val queryCapable = windows && url.contains("googlevideo")
    private var finished = false
    private var queryFailed = false
    private var headerFailed = false
    private var refreshes = 0
    private var response: Response? = null
    private var stream: InputStream? = null
    @Volatile private var call: Call? = null
    @Volatile private var aborted = false

    /** [gate] yuklashni bekor qildi — chaqiruvchi qayta urinishmasligi kerak. */
    @Volatile
    var canceled = false
        private set

    /** Zina qiymati: 0 = joriy chegara, keyinigi yarmi/choragi. */
    private fun stepBytes(step: Int): Long {
        val s = step.coerceIn(0, STEPS - 1)
        return minOf(ceiling shr s, PROVEN[s]).coerceAtLeast(STEP_MIN)
    }

    /**
     * Joriy oyna uchun ruxsat etilgan vaqt (tezlikdan kelib chiqadi).
     * #161: har sarflangan refresh qo'shimcha 6s beradi — refresh o'zi
     * tarmoqdan yangi URL resolve qiladi (sekin taroqda 2-5s); eskidan
     * bu vaqt umumiy byudjetdan yeyilardi va oyna ochilmay qolib,
     * "range open failed" bilan ijro to'xtar edi.
     */
    private fun budgetMs(): Long = YuzNetPulse.windowDeadlineMs(window) + 6_000L * refreshes

    private fun checkCanceled() {
        if (aborted) throw IOException("range aborted @$pos")
        if (cancellation?.canceled == true || gate?.invoke()?.let { it < 0L } == true) {
            canceled = true
            throw IOException("download canceled @$pos")
        }
    }

    private fun holdForGate() {
        while (true) {
            checkCanceled()
            val wait = gate?.invoke() ?: 0L
            if (wait <= 0L) return
            Thread.sleep(wait.coerceAtMost(100L))
        }
    }

    private fun pause(ms: Long) {
        var remaining = ms
        while (remaining > 0L) {
            checkCanceled()
            val slice = remaining.coerceAtMost(100L)
            Thread.sleep(slice)
            remaining -= slice
        }
        checkCanceled()
    }

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
            checkCanceled()
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
                checkCanceled()
                if (singleShot) throw e
                openWindow()
                continue
            }
            checkCanceled()
            if (n > 0) {
                pos += n
                return n
            }
            // Jism tugadi, lekin oyna to'lmagan — uzilgan ulanish.
            if (isEof()) return -1
            if (singleShot) {
                if (total >= 0L && pos < total) throw IOException("short body @$pos")
                return -1
            }
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

    /** #152: server tasdiqlagan jami hajm; -1 = hali aniq emas. */
    val serverTotal: Long
        get() = total

    fun abort() {
        aborted = true
        call?.cancel()
    }

    fun close() {
        call?.let {
            it.cancel()
            cancellation?.detach(it)
        }
        call = null
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
        holdForGate()
        // #86: chegara va deadline har oyni ochishda tezlikdan qayta
        // hisoblanadi — sekin taroqda kichik oyna, lekin yetarli vaqt.
        ceiling = YuzNetPulse.windowCeiling(CEIL_MAX)
        window = stepBytes(windowStep)
        var last: Exception? = null
        var attempt = 0
        var stalls = 0
        val t0 = System.nanoTime() / 1_000_000L
        val tried = HashSet<String>()
        while (attempt < MAX_TRIES && elapsedMs(t0) < budgetMs()) {
            holdForGate()
            var move: Move? = nextMove(tried)
            if (move == null) {
                // Barcha til/URL variantlari rad etildi. Google throttle'i
                // ko'pincha bir necha soniyada bo'shashadi — qisqa damlar
                // bilan qayta uritamiz, lekin deadline'dan oshmaydi.
                if (stalls >= MAX_STALL_RETRIES ||
                    elapsedMs(t0) + stallMs(stalls + 1) > budgetMs()
                ) break
                stalls++
                tried.clear()
                queryFailed = false
                headerFailed = false
                last = IOException("stalled")
                Log.i(TAG, "range stall @$pos win=${window / 1024}KB — ${stallMs(stalls)}ms dam")
                pause(stallMs(stalls))
                move = nextMove(tried) ?: break
            }
            if (attempt > 0 && last != null) {
                Log.i(TAG, "  retry: ${last.message}")
                pause(backoff(attempt))
            }
            attempt++
            tried.add(key(move))
            val end = windowEndFor()
            if (windows && end < pos) {
                finished = true
                return
            }
            var netErr = false
            try {
                val resp = fetch(move, end)
                val code = resp.code
                if (code == 416) {
                    parseTotal(resp.header("Content-Range"))
                    runCatching { resp.close() }
                    // #152: 416 = server "bu offset'da bayt yo'q" dedi —
                    // total aniq bo'lmasa ham, start'dan oldinga o'tib
                    // bo'lgan bo'lsak, bu haqiqiy EOF.
                    if ((total >= 0L && pos >= total) || (total < 0L && pos > start)) {
                        finished = true
                        Log.i(TAG, "range 416 at pos=$pos total=$total — eof")
                        return
                    }
                    throw IOException("premature range end @$pos")
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
                        code == 206 -> crStart == pos
                        pos == 0L -> true
                        !windows -> false
                        else -> cl == want
                    }
                    if (!honored) {
                        runCatching { resp.close() }
                        throw RangeMismatch("range ignored code=$code cl=$cl want=$want")
                    }
                    url = move.url
                    ua = move.ua
                    response = resp
                    stream = body
                    if (code == 206) parseTotal(resp.header("Content-Range"))
                    else if (cl > 0L && total < 0L && !move.query) total = pos + cl
                    // Oynasiz rejimda jism = qolgan butun fayl, chekni hajm
                    // bo'yicha qo'yamiz (1MB'lik oyna bilan kesib qo'lmasin).
                    windowEnd = when {
                        !windows -> bounded(if (cl > 0L) pos + cl else Long.MAX_VALUE / 4)
                        code == 206 || cl <= 0L -> bounded(end + 1L)
                        else -> bounded(pos + cl)
                    }
                    if (move.query) queryFailed = false else headerFailed = false
                    sizeFails = 0
                    growAfterClean()
                    // #161: oyna muvaffaqiyatli ochildi — refresh byudjeti
                    // tiklanadi. Uzun trekda har bir o'lik URL avlodidan
                    // keyin yangi URL olish imkoni cheksiz qoladi.
                    refreshes = 0
                    Log.i(TAG, "range open $pos..$windowEnd code=$code q=${move.query} total=$total")
                    return
                }
                runCatching { resp.close() }
                last = IOException("HTTP $code")
                Log.i(TAG, "range HTTP $code pos=$pos win=${window / 1024}KB q=${move.query} ${hostOf(move.url)}")
            } catch (e: Exception) {
                close()
                checkCanceled()
                last = e
                netErr = e !is RangeMismatch
                Log.i(TAG, "range ERR ${e.javaClass.simpleName} ${e.message?.take(48)} pos=$pos")
            }
            close()
            if (move.query) queryFailed = true else headerFailed = true
            if (!netErr) {
                sizeFails++
                if (sizeFails >= 2 && windowStep < STEPS - 1) {
                    badMask = badMask or (1 shl windowStep)
                    windowStep++
                    window = stepBytes(windowStep)
                    sizeFails = 0
                    cleanWindows = 0
                    learnedStep = windowStep
                    Log.i(TAG, "range window -> ${window / 1024}KB")
                }
            }
        }
        throw IOException("range open failed at $pos: ${last?.message}")
    }

    /**
     * Keyingi urinish: avval joriy URL'dagi ikki til, ular tugasa [refresh]
     * bilan yangi URL (shu itag). Imkon qolmasa null.
     */
    private fun nextMove(tried: Set<String>): Move? {
        do {
            for (m in cheapMoves()) if (key(m) !in tried) return m
        } while (refreshes < MAX_REFRESHES && refreshUrl())
        return null
    }

    private fun cheapMoves(): List<Move> {
        if (!windows) return listOf(Move(url, ua, false))
        val q = queryCapable
        val first = preferredDialect()
        return if (q) listOf(Move(url, ua, first), Move(url, ua, !first)) else listOf(Move(url, ua, false))
    }

    /**
     * Asosiy til: yangi (sarflanmagan) URL'da header 206 beradi, shuning
     * uchun header; header rad etilsa yoki URL allaqachon query rejimida
     * bo'lsa — query.
     */
    private fun preferredDialect(): Boolean =
        queryCapable && headerFailed && !queryFailed

    /** Server kichik oynalarga moslashsa hajmni qayta ko'taramiz. */
    private fun growAfterClean() {
        cleanWindows++
        if (windowStep > 0 && cleanWindows >= 3) {
            val up = windowStep - 1
            if (badMask and (1 shl up) == 0) {
                windowStep = up
                window = stepBytes(windowStep)
                learnedStep = windowStep
                Log.i(TAG, "range window back to ${window / 1024}KB")
            }
            cleanWindows = 0
        }
    }

    @Throws(IOException::class)
    private fun fetch(move: Move, end: Long): Response {
        val builder = Request.Builder()
            .url(if (move.query) queryUrl(end, move.url) else move.url)
            .header("User-Agent", move.ua)
            .header("Accept-Encoding", "identity")
        if (windows) {
            if (!move.query) builder.header("Range", "bytes=$pos-$end")
        } else if (start > 0L) {
            builder.header("Range", "bytes=$start-")
        }
        checkCanceled()
        val requestCall = http.newCall(builder.build())
        call = requestCall
        cancellation?.attach(requestCall)
        checkCanceled()
        return requestCall.execute()
    }

    /** yt-dlp usuli: bitta URL'da range'ni query param bilan davom ettirish. */
    private fun queryUrl(end: Long, u: String): String {
        rn++
        val sep = if (u.contains('?')) "&" else "?"
        return "${u}${sep}rn=$rn&range=$pos-$end"
    }

    private fun key(m: Move): String = "${window}_${pos}_${m.query}_${m.url.hashCode()}"

    private fun backoff(attempt: Int): Long =
        (300L * (1L shl (attempt - 1).coerceAtMost(3))).coerceAtMost(2000L)

    private fun stallMs(retry: Int): Long = 1200L * retry

    private fun elapsedMs(t0: Long): Long = System.nanoTime() / 1_000_000L - t0

    private fun refreshUrl(): Boolean {
        checkCanceled()
        val next = runCatching { refresh?.invoke(pos, url) }.getOrNull()
            ?: return false
        checkCanceled()
        if (next.url.isBlank() || next.url == url) return false
        url = next.url
        ua = next.ua
        refreshes++
        queryFailed = false
        headerFailed = false
        Log.i(TAG, "range refreshed #${refreshes} @pos=$pos ext=${next.ext}")
        return true
    }

    private fun hostOf(u: String): String =
        u.run { substringAfter("://").substringBefore("/") }.take(34)

    /** Bir urinish: qaysi URL, qaysi til. */
    private data class Move(val url: String, val ua: String, val query: Boolean)

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
        if (t <= 0L) return
        // #152: SERVER totali haqiqat — InnerTube clen= tez-tez yolg'on
        // gapiradi (katta/kichik). Avval bu yerda RangeMismatch otilardi:
        // har bir oyna "aybdor" bo'lib, qo'shiq o'rtasida sakrardi va
        // yuklash hech qachon tugamasdi. Endi tuzatamiz va loglaymiz.
        if (total > 0L && t != total) {
            Log.i(TAG, "range total corrected $total -> $t @$pos")
        }
        total = t
    }

    /** Content-Range'dagi boshlang'ich bayt; -1 = yo'q/aniksiz. */
    private fun rangeStart(contentRange: String?): Long {
        val cr = contentRange ?: return -1L
        val sp = cr.indexOf(' ')
        if (sp < 0 || cr.substring(0, sp).trim() != "bytes") return -1L
        return cr.substring(sp + 1).substringBefore('-').trim().toLongOrNull() ?: -1L
    }
}
