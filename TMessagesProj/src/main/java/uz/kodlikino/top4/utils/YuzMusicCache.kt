package uz.kodlikino.top4.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * YuzGram #62: qo'shiqlarni keshga yuklab olish (oflayd tinglash).
 * YuzGram #63: YouTube qatorlari endi TO'LIQ audio sifatida saqlanadi
 * (M4A/WebM), Deezer zaxirasi eski mp3 preview formatida qoladi.
 * YuzGram #84: YouTube fayllari YuzRangeReader orqali — kichik Range
 * oynalari ketma-ketligi + to'liqlik tekshiruvi (yarim fayl qabul
 * qilinmaydi). Fayllar ichki xotirada, indeks SharedPreferences'da JSON.
 */
object YuzMusicCache {

    private const val PREFS = "yuz_music_dl"
    private const val DIR = "yuzmusic"
    private const val TAG = "YuzMusic"
    private val main = Handler(Looper.getMainLooper())
    private val EXT = arrayOf("m4a", "mp3", "webm")

    // fetchRanges natijalari (foydalanuvchi xatosi / bekor qilish ajraladi).
    private const val FAIL = 0
    private const val OK = 1
    private const val CANCELED = 2

    /**
     * #86: keshning hajm chegarasi. Yuqorisida ENG KCHNA ishlatilgan
     * qo'shiqlar o'chiriladi — aks holda "barchasi yuklandi" degan
     * foydalanuvchi telefon xotirasini tugatadi va yuklashlar chiqib
     * ketishga (write fail) yo'l qo'yadi.
     */
    private const val MAX_BYTES = 220L * 1024 * 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private fun dir(context: Context): File {
        val d = File(context.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun index(context: Context): JSONArray {
        return try {
            JSONArray(prefs(context).getString("list", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun saveIndex(context: Context, arr: JSONArray) {
        prefs(context).edit().putString("list", arr.toString()).apply()
    }

    /** Saqlangan fayl (barcha mumkin bo'lgan kengaytmalarni tekshiradi). */
    fun localFile(context: Context, id: Long): File? {
        val d = dir(context)
        for (e in EXT) {
            val f = File(d, "$id.$e")
            if (f.exists()) return f
        }
        return null
    }

    fun isDownloaded(context: Context, id: Long): Boolean = localFile(context, id) != null

    // YuzGram #104: single-flight — bir id uchun ikkinchi so'rov kelib tutsa
    // yangi oqim ochilmaydi (foydalanuvchi tugmasi + fon prebuffer bir vaqtda
    // tushib, faylni buzmasligi kerak).
    private val active = java.util.Collections.synchronizedSet(HashSet<Long>())

    // #120: index (SharedPreferences JSON) read-modify-write — yuklash ipidagi
    // addToIndex va main ipdagi touch bir vaqtda o'qisa, biri yo'qolardi
    // ("Yuklanganlar"da qo'shiq tushib qolishi). Barcha RMW'lar shu lock ostida.
    private val indexLock = Any()

    // YuzGram #109: shu hajmdan katta googlevideo fayllari IKKI ulanishda
    // parallel tortiladi (quyi va yuqori yarmi) — throttle har bir
    // alohida ulanishga qo'yiladi, shuning uchun taxminan 2 barobar tez.
    private const val PAR_MIN = 512L * 1024

    // YuzGram #109: bekor qilish bayroqlari. download() id bo'yicha bayroq
    // ro'yxatdan o'tkazadi, UI cancel(id) qo'yadi, gate orqali reader'ga
    // yetib boradi (manfiy = "taslim bo'l", tmp o'chiriladi). Fon prebuffer
    // o'z gate'ini o'zi beradi — cancel unda ishtirok etmaydi.
    private val cancelFlags =
        java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicBoolean>()

    // YuzGram #115: davom etayotgan yuklashlar reyestri. "Yuklanganlar"
    // ro'yxati endi tugallanganlarni EMAS, balki yarim ketayotgan qo'shiqlarni
    // ham shu yerdan ko'radi; qatorlar foiz va tezlikni (KB/s) shu holatdan
    // o'qiydi. Tezlik — EMA: birinchi namunadan so'ng 3:1 siljiyma o'rtacha.
    private class Inflight(val track: YuzTrack) {
        // #120: pct/rate yuklash ipida yoziladi, main ipda o'qiladi —
        // volatile bo'lmasa UI eski qiymatni uzoq vaqt ko'rsatib qolishi mumkin.
        @Volatile var pct = 0
        @Volatile var rate = 0
        private var lastGot = 0L
        private var lastAt = 0L
        fun update(got: Long, expect: Long) {
            if (expect > 0L) pct = ((got * 100) / expect).toInt().coerceIn(0, 99)
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastAt > 0L && got >= lastGot) {
                val dt = now - lastAt
                if (dt > 0L) {
                    val inst = ((got - lastGot) * 1000L / dt).toInt()
                    rate = if (rate == 0) inst else (rate * 3 + inst) / 4
                }
            }
            lastGot = got
            lastAt = now
        }
    }
    private val inflight = java.util.concurrent.ConcurrentHashMap<Long, Inflight>()

    fun isInflight(id: Long): Boolean = inflight.containsKey(id)
    fun inflightIds(): Set<Long> = inflight.keys.toSet()
    fun inflightPct(id: Long): Int = inflight[id]?.pct ?: 0

    /** "128K/s" / "1.4M/s"; tezlik hali o'lchanmagan bo'lsa bo'sh satr. */
    fun rateLabel(id: Long): String {
        val b = inflight[id]?.rate ?: 0
        if (b <= 0) return ""
        val kb = b / 1024
        return if (kb >= 1000) String.format("%.1fM/s", kb / 1000.0) else "${kb}K/s"
    }

    /** #109: davom etayotgan foydalanuvchi yuklashini to'xtatish. */
    fun cancel(id: Long) {
        cancelFlags[id]?.set(true)
    }

    /** Yo'ldan boshlab [onDone] UI ipida chaqiriladi (true = muvaffaqiyat).
     * [onProgress] 0..99 foizni asosiy ipda beradi. */
    fun download(context: Context, track: YuzTrack, onProgress: ((Int) -> Unit)? = null, onDone: (Boolean) -> Unit) {
        if (!active.add(track.id)) {
            // #120: id band (takror bosish yoki muhandislik fon yuklashi) —
            // yangi oqim ochilmaydi, lekin qator "yuklanmoqda"da qotib
            // qolmasin: fonga ketkazib, band bo'shayotganda natijani
            // shu so'rovga yetkazamiz.
            val app = context.applicationContext
            val tid = track.id
            Thread {
                while (active.contains(tid)) {
                    try {
                        Thread.sleep(400L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                val ok = isDownloaded(app, tid)
                main.post { runCatching { onDone(ok) } }
            }.start()
            return
        }
        val app = context.applicationContext
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        cancelFlags[track.id] = cancel
        val st = Inflight(track)
        inflight[track.id] = st
        var lastPct = -1
        val raw: (Long, Long) -> Unit = { got, expect ->
            st.update(got, expect)
            if (expect > 0L) {
                val pct = ((got * 100) / expect).toInt().coerceIn(0, 99)
                if (pct != lastPct) {
                    lastPct = pct
                    // #115: UI qayta bog'lash istisnosiy bekor qilingan
                    // qatorda process'ni ushlab qolmasin — post tanasi
                    // har doim qalqan ostida.
                    main.post { runCatching { onProgress?.invoke(pct) } }
                }
            }
        }
        Thread {
            // #109: gate endi bekor qilish bayrog'ini olib boradi —
            // reader har oyna ochishda tekshiradi va -1'da to'xtaydi.
            val gate = { if (cancel.get()) -1L else 0L }
            val ok = runCatching { downloadBlocking(app, track, gate, raw) }.getOrDefault(false)
            // #115: bayroq/faqat O'ZIMIZniki bilan birga o'chiriladi — foydalanuvchi
            // yuklashni qayta boshlasa yangi ro'yxatdan o'tgan bayroq buzilmasin
            // (eski ketma-ketlik bekor qilishni o'ldirar edi).
            cancelFlags.remove(track.id, cancel)
            inflight.remove(track.id, st)
            active.remove(track.id)
            main.post { runCatching { onDone(ok) } }
        }.start()
    }

    /**
     * #120: muhandislik (fon) yuklashlari ham single-flight'dan o'tadi.
     * Avval prebuffer/silentDownload `downloadBlocking`ni to'g'ridan-to'g'ri
     * chaqib, [active] ni aylanib o'tardi — foydalanuvchi tugmasi bilan bir
     * vaqtda tushsa bitta .tmp'ga ikki oqim yozib faylni buzishi mumkin edi.
     * Band bo'lsa false qaytaradi (chaqiruvchi keyinroq qayta urinadi).
     */
    fun downloadBlockingExclusive(
        context: Context,
        track: YuzTrack,
        gate: (() -> Long)? = null
    ): Boolean {
        if (!active.add(track.id)) return false
        // #120: UI "cancel" fon yuklashiga ham yetib boradi — qatorni bosib
        // bekor qilsa fayl yarim holida keraksiz tortilmasligi kerak.
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        cancelFlags[track.id] = cancel
        val g: () -> Long = {
            if (cancel.get()) -1L else gate?.invoke() ?: 0L
        }
        try {
            return downloadBlocking(context, track, g)
        } finally {
            cancelFlags.remove(track.id, cancel)
            active.remove(track.id)
        }
    }

    /**
     * IO ipida chaqiladigan blokiy yuklab olish.
     * YuzGram #63: YouTube qatori avval resolve qilinadi — keshga TO'LIQ
     * qo'shiq (m4a/webm) tushadi.
     * YuzGram #65: bitta URL emas — resolveStreamCandidates nomzodlari
     * ketma-ket sinaladi (ANDROID/IOS, "n"siz variantlar); oxirgi chora
     * Deezer mp3 preview.
     * YuzGram #84: googlevideo nomzodlari endi YuzRangeReader bilan — kichik
     * Range oynalari ketma-ketligi + to'liqlik tekshiruvi (yarim fayl
     * qabul qilinmaydi).
     * YuzGram #86: [gate] — FON yuklashini ijro uchun to'xtatib turish
     * imkoni (masalan keyingi trek jim yuklanayotganda ijro buffering'ga
     * kirsa). null = to'siqsiz (foydalanuvchi bosgan "yuklab olish").
     * YuzGram #104: [progress] har ~1% da (got, expect) asosiy IP'dan EMAS —
     * chaqiruvchi o'zi post qiladi; 24 soatdan eski .tmp qoldiqlari tozalanadi.
     */
    fun downloadBlocking(
        context: Context,
        track: YuzTrack,
        gate: (() -> Long)? = null,
        progress: ((Long, Long) -> Unit)? = null
    ): Boolean {
        if (isDownloaded(context, track.id)) return true
        cleanupStaleTmp(context)
        val streams = try {
            YuzYouTubeApi.resolveStreamCandidates(track, 8)
        } catch (_: Exception) {
            emptyList()
        }
        Log.i(TAG, "downloadBlocking ${track.videoId} candidates=${streams.size}")
        val attempts = ArrayList<YuzAudioStream>()
        attempts.addAll(streams)
        if (track.previewUrl.isNotBlank()) {
            attempts.add(YuzAudioStream(track.previewUrl, "mp3", 0L, ""))
        }
        for (s in attempts) {
            if (s.url.contains("googlevideo")) {
                val r = fetchRanges(context, track.id, s, track.videoId, gate, progress)
                if (r == CANCELED) {
                    Log.i(TAG, "download canceled ${track.videoId} (ijro yo'l so'radi)")
                    return false
                }
                if (r == OK) {
                    addToIndex(context, track)
                    return true
                }
            } else if (fetchPlain(context, track.id, s, gate, progress)) {
                addToIndex(context, track)
                return true
            }
        }
        return false
    }

    /** #104: tugallanmagan .tmp qoldiqlari (eski nom va yangi "id-<nano>.tmp"). */
    private fun cleanupStaleTmp(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - 86_400_000L
            dir(context).listFiles()?.forEach { f ->
                if (f.name.endsWith(".tmp") && f.lastModified() < cutoff) f.delete()
            }
        }
    }

    /** Bayt joyi bo'yicha shu videoId'ning (shu itag'da) yangi URL'ini qaytaradi. */
    private fun sameVideo(videoId: String): (Long, String) -> YuzAudioStream? =
        { at: Long, cur: String -> YuzYouTubeApi.refreshStream(videoId, at, cur) }

    /** googlevideo: kichik Range oynalari + rad etilsa shu baytga yangi URL. */
    private fun fetchRanges(
        context: Context,
        id: Long,
        stream: YuzAudioStream,
        videoId: String,
        gate: (() -> Long)?,
        progress: ((Long, Long) -> Unit)?
    ): Int {
        // #104: har urinishga O'Z tmp nomi — ikki ip bir faylga yozib,
        // uni buzishi endi imkonsiz.
        val tmp = File(dir(context), "$id-${System.nanoTime()}.tmp")
        runCatching { if (tmp.exists()) tmp.delete() }
        // refresh FAQAT shu videoId uchun: muqobil videoning baytlari
        // yarim faylga aralashib ketmasligi kerak.
        val refresh: ((Long, String) -> YuzAudioStream?)? =
            if (videoId.isNotBlank()) sameVideo(videoId) else null
        // #109: hajmi aniq katta faylni avval ikki parallel ulanishda
        // tortib ko'ramiz. Muvaffaqiyatsizlik bo'lib birorta bayt kelmasa
        // — oddiy ketma-ket yo'lga qaytamiz (arzon); o'rtada uzilsa —
        // keyingi nomzodga o'tamiz (ketma-ket ham shu joyni bosar edi).
        if (stream.approxBytes > PAR_MIN) {
            val r = fetchRangesSplit(context, tmp, id, stream, refresh, gate, progress)
            if (r != FAIL) return r
            if (tmp.length() > 0L) {
                runCatching { tmp.delete() }
                return FAIL
            }
            runCatching { tmp.delete() }
        }
        val reader = YuzRangeReader(
            http, stream.url, stream.ua, 0L, -1L, stream.approxBytes, refresh,
            windows = true, gate = gate
        )
        var complete = false
        try {
            reader.open()
            val expect = reader.length
            var got = 0L
            var lastEmit = 0L
            tmp.outputStream().use { out ->
                // #109: 256KB buffer — chaqiruvlar sonini 4 barobar qisqartiradi.
                val buf = ByteArray(256 * 1024)
                var guard = 0
                while (guard++ < 65536) {
                    val n = reader.read(buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    got += n
                    // #104: ~1% qadamlar bilan foizni xabar qilamiz.
                    if (progress != null && expect > 0 &&
                        got - lastEmit >= expect / 100 + 8192
                    ) {
                        lastEmit = got
                        progress(got, expect)
                    }
                    // #86: fon yuklashi ham taroq tezligini o'lchaydi —
                    // oyna/deadline/format shundan hisoblanadi.
                    YuzNetPulse.add(n)
                }
            }
            complete = got > 0L && (expect < 0L || got >= expect)
            Log.i(TAG, "download ranges got=$got expect=$expect ext=${stream.ext} ok=$complete")
        } catch (e: Exception) {
            Log.i(TAG, "download range ERR ${e.javaClass.simpleName} ${stream.url.take(60)}")
        } finally {
            reader.close()
        }
        if (reader.canceled) {
            runCatching { tmp.delete() }
            return CANCELED
        }
        return if (complete) {
            if (promote(context, tmp, id, stream.ext)) OK else FAIL
        } else {
            runCatching { tmp.delete() }
            FAIL
        }
    }

    /**
     * #109: faylni ikki yarimga bo'lib har yarmini ALOHIDA YuzRangeReader
     * (alohida HTTP ulanish) bilan tortadi. Yozishlar bitta
     * RandomAccessFile'ga seek+synchronized bilan tushadi — o'rtada
     * baytlar aralashmaydi. To'liqlik: jami bayt >= kutilgan hajm.
     * Bekor qilish: biror reader gate'dan -1 ollsa CANCELED.
     */
    private fun fetchRangesSplit(
        context: Context,
        tmp: File,
        id: Long,
        stream: YuzAudioStream,
        refresh: ((Long, String) -> YuzAudioStream?)?,
        gate: (() -> Long)?,
        progress: ((Long, Long) -> Unit)?
    ): Int {
        val total = stream.approxBytes
        val mid = total / 2
        val ra = YuzRangeReader(
            http, stream.url, stream.ua, 0L, mid, total, refresh, true, gate
        )
        val rb = YuzRangeReader(
            http, stream.url, stream.ua, mid, total - mid, total, refresh, true, gate
        )
        val got = java.util.concurrent.atomic.AtomicLong(0L)
        val lastEmit = java.util.concurrent.atomic.AtomicLong(0L)
        val upperFailed = java.util.concurrent.atomic.AtomicBoolean(false)
        // setLength QO'YMAYMIZ: fayl hajmi yozilgan baytlardan o'sadi —
        // chaqiruvchi "birorta bayt keldi-yo'qmi"ni tmp.length() bo'yicha
        // adashmasdan biladi (seek EOF ortiga yozish ham kengaytiradi).
        val raf = try {
            java.io.RandomAccessFile(tmp, "rw")
        } catch (_: Exception) {
            return FAIL
        }
        val upper = Thread {
            upperFailed.set(
                runCatching { readHalf(rb, raf, got, lastEmit, total, progress) }
                    .getOrDefault(false).not()
            )
        }
        upper.start()
        val lowerOk = runCatching { readHalf(ra, raf, got, lastEmit, total, progress) }
            .getOrDefault(false)
        // #120: join uzilsa (InterruptedException) raf yopilmay FD sizib
        // chiqardi — close'ni finally'ga o'tkazamiz.
        try {
            upper.join()
        } finally {
            runCatching { raf.close() }
        }
        val canceled = ra.canceled || rb.canceled
        val complete = lowerOk && !upperFailed.get() && got.get() >= total
        Log.i(TAG, "download split got=${got.get()} expect=$total cancel=$canceled ok=$complete")
        if (canceled) {
            runCatching { tmp.delete() }
            return CANCELED
        }
        if (complete && promote(context, tmp, id, stream.ext)) return OK
        // #120: FAIL yo'lida tmp'ni O'CHIRMAYMIZ — chaqiruvchi (271-277)
        // tmp.length() orqali "bayt keldi-yo'qmi"ni ko'radi va o'zi
        // tozalaydi. Eski ertalabki o'chirish check'ni doim-yolg'on
        // qilib, 90% tortilgan faylni noldan ketma-ket qayta tortdirardi.
        return FAIL
    }

    /** #109: bitta yarmni oxirigacha o'qib, to'g'ri offsetga yozadi. */
    private fun readHalf(
        reader: YuzRangeReader,
        raf: java.io.RandomAccessFile,
        got: java.util.concurrent.atomic.AtomicLong,
        lastEmit: java.util.concurrent.atomic.AtomicLong,
        total: Long,
        progress: ((Long, Long) -> Unit)?
    ): Boolean {
        val buf = ByteArray(256 * 1024)
        try {
            reader.open()
            var guard = 0
            while (guard++ < 65536) {
                val n = reader.read(buf, 0, buf.size)
                if (n <= 0) break
                val at = reader.position() - n
                synchronized(raf) {
                    raf.seek(at)
                    raf.write(buf, 0, n)
                }
                val g = got.addAndGet(n.toLong())
                YuzNetPulse.add(n)
                if (progress != null && g - lastEmit.get() >= total / 100 + 8192) {
                    lastEmit.set(g)
                    progress(g, total)
                }
            }
        } finally {
            reader.close()
        }
        return reader.isEof()
    }

    /** Oddiy HTTPS (Deezer preview) — bitta GET, hajm kichik. */
    private fun fetchPlain(
        context: Context,
        id: Long,
        stream: YuzAudioStream,
        gate: (() -> Long)?,
        progress: ((Long, Long) -> Unit)?
    ): Boolean {
        val tmp = File(dir(context), "$id-${System.nanoTime()}.tmp")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(stream.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 30000
                requestMethod = "GET"
                if (stream.ua.isNotBlank()) setRequestProperty("User-Agent", stream.ua)
                // #67: googlevideo "gzip" Accept-Encoding'ni 403 bilan rad etadi.
                setRequestProperty("Accept-Encoding", "identity")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.i(TAG, "download HTTP $code ext=${stream.ext} url=${stream.url.take(60)}")
                return false
            }
            val expect = conn.contentLength
            var got = 0L
            var lastEmit = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        // #109: bekor qilish (yoki ijro yo'l so'ragan) —
                        // yarim faylni tashlab chiqamiz.
                        if (gate != null && runCatching { gate() }.getOrDefault(0L) < 0L) {
                            runCatching { tmp.delete() }
                            return false
                        }
                        out.write(buf, 0, n)
                        got += n
                        if (progress != null && expect > 0 &&
                            got - lastEmit >= expect / 100 + 4096
                        ) {
                            lastEmit = got
                            progress(got, expect.toLong())
                        }
                        YuzNetPulse.add(n)
                    }
                }
            }
            Log.i(TAG, "download plain ${tmp.length()}B ext=${stream.ext}")
            // YuzGram #115: tugallanmagan fayl qabul QILINMAYDI (range yo'li
            // bilan bir xil qoida) — kesilgan mp3 keyin ijroda qotadi yoki
            // xato beradi.
            if (expect > 0 && got < expect) {
                Log.i(TAG, "download plain truncated got=$got expect=$expect")
                runCatching { tmp.delete() }
                return false
            }
            promote(context, tmp, id, stream.ext)
        } catch (e: Exception) {
            Log.i(TAG, "download plain ERR ${e.javaClass.simpleName} ${stream.url.take(60)}")
            runCatching { tmp.delete() }
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * #104: tayyor tmp'ni saqlangan faylga aylantirish. oldin dst band bo'lsa
     * renameTo false qaytarar edi — yuklash "muvaffaqiyatsiz" bo'lib, yarim
     * fayl qolib ketishi mumkin edi. Endi: dst'ni o'chiramiz, rename bo'lmasa
     * nusxa bilan qoplaymiz.
     */
    private fun promote(context: Context, tmp: File, id: Long, ext: String): Boolean {
        if (tmp.length() <= 0L) {
            runCatching { tmp.delete() }
            return false
        }
        val dst = File(dir(context), "$id.$ext")
        if (dst.exists()) runCatching { dst.delete() }
        if (runCatching { tmp.renameTo(dst) }.getOrDefault(false)) {
            Log.i(TAG, "download OK ${dst.length()}B -> ${dst.name}")
            return true
        }
        val copied = runCatching {
            tmp.copyTo(dst, overwrite = true)
            dst.length() > 0L
        }.getOrDefault(false)
        runCatching { tmp.delete() }
        if (copied) Log.i(TAG, "download OK (copy) ${dst.length()}B -> ${dst.name}")
        return copied
    }

    fun remove(context: Context, id: Long) {
        removeFiles(dir(context), id)
        removeIndex(context, id)
    }

    /** Saqlangan ro'yxat (eng yangi tepada). */
    fun downloadedTracks(context: Context): List<YuzTrack> {
        val arr = index(context)
        val out = ArrayList<YuzTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optLong("id")
            if (!isDownloaded(context, id)) continue
            out.add(
                YuzTrack(
                    id = id,
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    durationSec = o.optInt("dur"),
                    coverUrl = o.optString("cover"),
                    previewUrl = o.optString("preview"),
                    link = o.optString("link"),
                    videoId = o.optString("yt")
                )
            )
        }
        // YuzGram #115: yuklanayotgan (hali tugallanmagan) qo'shiqlar ham shu
        // ro'yxatning tepasida ko'rinadi — UI ularni inflightPct/rateLabel
        // orqali jonli holat bilan chizadi.
        val doneIds = HashSet<Long>()
        for (t in out) doneIds.add(t.id)
        val live = ArrayList<YuzTrack>()
        for ((id, s) in inflight) {
            if (id in doneIds) continue
            live.add(s.track)
        }
        live.sortByDescending { inflight[it.id]?.pct ?: 0 }
        return live + out.asReversed()
    }

    fun downloadedIds(context: Context): Set<Long> {
        val arr = index(context)
        val s = HashSet<Long>()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optLong("id") ?: continue
            if (isDownloaded(context, id)) s.add(id)
        }
        return s
    }

    /** Local file:// URI yoki network preview URI. */
    fun playUri(context: Context, track: YuzTrack): String {
        val f = localFile(context, track.id)
        return if (f != null) f.toURI().toString() else track.previewUrl
    }

    private fun addToIndex(context: Context, track: YuzTrack) = synchronized(indexLock) {
        val arr = index(context)
        for (i in 0 until arr.length()) {
            if (arr.optJSONObject(i)?.optLong("id") == track.id) return
        }
        val o = JSONObject()
        o.put("id", track.id)
        o.put("title", track.title)
        o.put("artist", track.artist)
        o.put("album", track.album)
        o.put("dur", track.durationSec)
        o.put("cover", track.coverUrl)
        o.put("preview", track.previewUrl)
        o.put("link", track.link)
        o.put("yt", track.videoId)
        o.put("at", System.currentTimeMillis())
        arr.put(o)
        saveIndex(context, arr)
        evict(context, track.id)
    }

    /**
     * #86: qo'shiq ijro etila boshlaganda "ishlatilgan" vaqtini yangilaymiz —
     * LRU shu son bo'yicha qaror qiladi (eng kam eshitilgan birinchi ketadi).
     * Har trek boshlanishida bitta async prefs yozuvi — arzon.
     */
    fun touch(context: Context, id: Long) = synchronized(indexLock) {
        val arr = index(context)
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("id") != id) continue
            if (System.currentTimeMillis() - o.optLong("at") < 60_000L) return
            o.put("at", System.currentTimeMillis())
            changed = true
            break
        }
        if (changed) saveIndex(context, arr)
    }

    /** [MAX_BYTES]'dan chiqib ketsa — eng eski ishlatilganlarni o'chiramiz. */
    private fun evict(context: Context, keepId: Long) {
        val d = dir(context)
        val arr = index(context)
        val entries = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries.add(it) }
        var size = 0L
        d.listFiles()?.forEach { size += it.length() }
        if (size <= MAX_BYTES) return
        entries.sortBy { it.optLong("at") }
        for (o in entries) {
            if (size <= MAX_BYTES) break
            val id = o.optLong("id")
            if (id == keepId) continue
            size -= removeFiles(d, id)
            removeIndex(context, id)
            Log.i(TAG, "evict LRU id=$id")
        }
    }

    private fun removeFiles(d: File, id: Long): Long {
        var freed = 0L
        for (e in EXT) {
            val f = File(d, "$id.$e")
            if (f.exists()) {
                freed += f.length()
                runCatching { f.delete() }
            }
        }
        return freed
    }

    private fun removeIndex(context: Context, id: Long) = synchronized(indexLock) {
        val arr = index(context)
        for (i in arr.length() - 1 downTo 0) {
            if (arr.optJSONObject(i)?.optLong("id") == id) arr.remove(i)
        }
        saveIndex(context, arr)
    }
}
