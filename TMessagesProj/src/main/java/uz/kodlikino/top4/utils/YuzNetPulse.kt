package uz.kodlikino.top4.utils

import android.os.SystemClock

/**
 * YuzGram #86: taroq o'tkazuvchanlik o'lchagichi — "internet qanchalik
 * tez?" savoliga javob beradigan yagona manba.
 *
 * Nima uchun: oldin barcha chegaralar QATIY SONLAR edi (oyna 12s, buffer
 * 120s, hajm 1MB). Ular tez taroqda yaxshi ishlagan, lekin past internetda
 * yomon: 1 MB'lik oyna 2 daqiqada yuklanadi -> deadline uni o'ldirgan,
 * 120s'lik limit ishlayotgan yuklashni kesgan. Endi har bir chegarasi
 * o'lchangan tezlikdan hisoblanadi.
 *
 * Qanday ishlaydi: har o'qilgan bayt guruhini vaqti bilan halqaga yozamiz,
 * `rate()` so'nggi 3 sekunddagi ochiq-o'rtichni (bayt/sekund) qaytaradi.
 * 2.5s'dan yangi bo'lmasa 0 (ma'lumotsiz) qaytaramiz — eskirgan son
 * bo'yicha qaror chiqarish xato.
 *
 * Chaqiruv nuqtalari: YuzStreamDataSource (ijro) va YuzMusicCache (fonda
 * yuklash). Ikkalasi ham IO/loader iplarida — shuning uchun metodlar
 * `synchronized`, lekin juda arzon (chunk'lar soniyada bir necha marta).
 */
object YuzNetPulse {

    private const val WINDOW_MS = 3000L
    private const val STALE_MS = 2500L
    private const val SAMPLE_MS = 200L
    private const val N = 24

    private val stamps = LongArray(N)
    private val totals = LongArray(N)
    private var head = 0
    private var filled = 0
    private var bytes = 0L
    private var lastStamp = 0L
    private var smoothed = 0.0

    /** [n] bayt o'tdi. Faqat HAQIQIY tarmoq o'qishlari (lokal fayl emas). */
    @Synchronized
    fun add(n: Int) {
        if (n <= 0) return
        bytes += n
        val now = SystemClock.elapsedRealtime()
        if (lastStamp == 0L || now - lastStamp >= SAMPLE_MS) {
            head = (head + 1) % N
            if (filled < N) filled++
            stamps[head] = now
            totals[head] = bytes
            lastStamp = now
        }
    }

    /** So'nggi 3s o'rtachasi, bayt/sekund. 0 = hali o'lchanmagan. */
    @Synchronized
    fun rate(): Double {
        if (filled < 2) return 0.0
        val now = SystemClock.elapsedRealtime()
        if (now - stamps[head] > STALE_MS) return 0.0
        var oldest = head
        var back = 1
        while (back < filled) {
            val p = (head - back + N) % N
            if (stamps[p] < now - WINDOW_MS) break
            oldest = p
            back++
        }
        val dt = stamps[head] - stamps[oldest]
        if (dt < 300L) return smoothed
        val raw = (totals[head] - totals[oldest]).toDouble() * 1000.0 / dt
        // Silliq: bitta katta chunk' hisobni sakratmasin.
        smoothed = if (smoothed <= 0.0) raw else smoothed * 0.55 + raw * 0.45
        return smoothed
    }

    /**
     * Bitta Range oynasining yuqori chegarasi: [max]'dan oshmaydi va
     * taxminan 3 sekundda yuklanadigan hajmga tushadi. Past internetda
     * kichik oyna = tezroq javob, kamroq 403, uzilmagan progress.
     */
    fun windowCeiling(max: Long): Long {
        val r = rate()
        if (r <= 0.0) return max
        return (r * 3.0).toLong().coerceIn(32L * 1024L, max)
    }

    /** [window] baytli oynani ochishga beriladigan maksimal vaqt. */
    fun windowDeadlineMs(window: Long): Long {
        val r = rate()
        if (r <= 0.0) return 12_000L
        val est = window * 1000.0 / r
        return (est * 3.0 + 4000.0).toLong().coerceIn(7_000L, 30_000L)
    }

    /** Buffer o'sishining to'xtab turishiga bo'ladigan vaqt (xato sanama). */
    fun stallLimitMs(): Long {
        val r = rate()
        return when {
            r <= 0.0 -> 20_000L
            r < 25_000.0 -> 45_000L
            r < 60_000.0 -> 30_000L
            else -> 20_000L
        }
    }

    /** Bir qo'shiqni to'liq tayyorlashga beriladigan umumiy vaqt. */
    fun bufferingCapMs(): Long {
        val r = rate()
        return when {
            r <= 0.0 -> 120_000L
            r < 20_000.0 -> 420_000L
            r < 60_000.0 -> 240_000L
            else -> 120_000L
        }
    }
}
