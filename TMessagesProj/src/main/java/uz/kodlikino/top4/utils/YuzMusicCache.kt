package uz.kodlikino.top4.utils

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
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
    // Eski versiyalar "webma"/"opus" kabi xom kengaytmalarni yozgan —
    // ularni ham tan olamiz, aks holda fayl bor-u, ilova "yuklanmagan" derdi.
    private val KNOWN_EXT = EXT + arrayOf("webma", "opus", "ogg", "mp4", "aac")

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
    // #157: noma'lum kengaytmali fayllar uchun papka skanini 5 soniya eslab
    // qolamiz. playQueue 150 trek uchun localFile chaqiradi va har biri to'liq
    // listFiles() + 2-3 stat qilardi — ijro boshlanishidan oldin ASOSIY oqimda
    // 100-300 ms to'xtalish (refreshDownloading'da ham takroran).
    @Volatile
    private var scanMap: Map<Long, File>? = null

    @Volatile
    private var scanAt = 0L
    private const val SCAN_TTL = 5_000L

    private fun scanOf(d: File): Map<Long, File> {
        val cachedScan = scanMap
        if (cachedScan != null &&
            android.os.SystemClock.elapsedRealtime() - scanAt < SCAN_TTL
        ) {
            return cachedScan
        }
        val built = HashMap<Long, File>()
        for (f in d.listFiles().orEmpty()) {
            if (!f.isFile || f.length() <= 0L || f.name.endsWith(".tmp")) continue
            if (f.name.substringAfterLast('.', "").lowercase() !in KNOWN_EXT) continue
            val fid = f.name.substringBeforeLast('.', "").toLongOrNull() ?: continue
            if (!built.containsKey(fid)) built[fid] = f
        }
        scanMap = built
        scanAt = android.os.SystemClock.elapsedRealtime()
        return built
    }

    fun localFile(context: Context, id: Long): File? {
        val d = dir(context)
        for (e in EXT) {
            val f = File(d, "$id.$e")
            // Bo'sh fayl yuklangan hisoblanmaydi — aks holda pleyer uni
            // ochishga urinib, "ijro etilmayapti" holatiga qulflanardi.
            if (f.isFile && f.length() > 0L) return f
        }
        // #133: noma'lum/eski kengaytma (webma, opus...) — nom "$id."
        // bilan boshlansa va audio kengaytmasi bo'lsa, faylni tan olamiz.
        return scanOf(d)[id]
    }

    fun isDownloaded(context: Context, id: Long): Boolean = localFile(context, id) != null

    enum class DownloadResult { DONE, FAILED, CANCELED }

    private val indexLock = Any()
    private const val PAR_MIN = 512L * 1024
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    // #133: UI 500ms'lik tick'da faqat shu sanoqni solishtiradi — disk IO
    // va prefs JSON o'qishsiz yakunlangan yuklashni payqaydi.
    private val doneSeq = java.util.concurrent.atomic.AtomicLong()

    fun doneVersion(): Long = doneSeq.get()

    private class Inflight(val track: YuzTrack) {
        val cancellation = YuzDownloadCancellation()
        val order = sequence.incrementAndGet()
        val completions = ArrayList<(DownloadResult) -> Unit>()
        val progressListeners = ArrayList<(Int) -> Unit>()
        var result: DownloadResult? = null
        @Volatile var pct = 0
        @Volatile var rate = 0
        // #155: foydalanuvchi o'zi bosgan yuklash. Fon (prebuffer/silent)
        // operatsiyasiga UI'dan attach bo'lsa ham shu flag ko'tariladi:
        // (1) qattiq backgroundGate (bekor qiluvchi) o'rniga yumshoq eshik
        //     ishlatiladi — ijro buffering'i foydalanuvchi yuklashini
        //     CANCELED qila olmaydi;
        // (2) FAILED bo'lsa progress saqlangan holda auto-retry yuradi.
        @Volatile var userJoined = false
        private var lastGot = 0L
        private var lastAt = 0L

        @Synchronized
        fun update(got: Long, expect: Long) {
            cancellation.check()
            if (got < lastGot) return
            val oldPct = pct
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
            if (oldPct != pct) {
                val value = pct
                val listeners = progressListeners.toList()
                main.post {
                    if (inflight[track.id] === this && !cancellation.canceled) {
                        for (listener in listeners) runCatching { listener(value) }
                    }
                }
            }
        }
    }
    private val inflight = java.util.concurrent.ConcurrentHashMap<Long, Inflight>()

    fun isInflight(id: Long): Boolean = inflight.containsKey(id)
    fun isCanceling(id: Long): Boolean = inflight[id]?.cancellation?.canceled == true
    fun inflightIds(): Set<Long> = inflight.keys.toSet()
    fun inflightPct(id: Long): Int = inflight[id]?.pct ?: 0

    fun rateLabel(id: Long): String {
        val b = inflight[id]?.rate ?: 0
        if (b <= 0) return ""
        val kb = b / 1024
        return if (kb >= 1000) String.format("%.1fM/s", kb / 1000.0) else "${kb}K/s"
    }

    fun cancel(id: Long) {
        inflight[id]?.cancellation?.cancel()
    }

    fun download(context: Context, track: YuzTrack, onProgress: ((Int) -> Unit)? = null, onDone: (DownloadResult) -> Unit) {
        val fresh = Inflight(track)
        fresh.userJoined = true
        val existing = inflight.putIfAbsent(track.id, fresh)
        val operation = existing ?: fresh
        // #155: fon prebuffer'iga attach bo'lgan taqdirda ham yuklash
        // "foydalanuvchiniki"ga aylanadi — bekor qilinmaydi, retry oladi.
        operation.userJoined = true
        synchronized(operation) {
            val result = operation.result
            if (result != null) {
                main.post { runCatching { onDone(result) } }
            } else {
                operation.completions.add(onDone)
                if (onProgress != null) operation.progressListeners.add(onProgress)
            }
            Unit
        }
        if (existing != null) return
        val app = context.applicationContext
        Thread { runDownload(app, operation, null) }.start()
    }

    fun downloadBlockingExclusive(context: Context, track: YuzTrack, gate: (() -> Long)? = null): Boolean {
        val operation = Inflight(track)
        if (inflight.putIfAbsent(track.id, operation) != null) return false
        return runDownload(context.applicationContext, operation, gate) == DownloadResult.DONE
    }

    private fun runDownload(context: Context, operation: Inflight, backgroundGate: (() -> Long)?): DownloadResult {
        val cancellation = operation.cancellation
        val gate = {
            cancellation.check()
            // #133: foydalanuvchi ochgan yuklash ham ijro bilan bir xil
            // tarmoqni bo'lishadi. Fon yo'li o'z eshigini beradi; oldingi
            // rejimda esa Yumshoq eshik hech qachon bekor qilmaydi, faqat
            // pleyer buffering'da yoki zaxirasi kam bo'lganda qisqa dam
            // beradi — sekin internetda ikkalasi ham ishlaydi.
            // #155: foydalanuvchi yuklashi (userJoined) hech qachon qattiq
            // eshikka tushmaydi — buffering uni BEKOR QILA olmaydi.
            val wait = if (operation.userJoined) {
                YuzMusicEngine.softDownloadGate()
            } else {
                backgroundGate?.invoke() ?: YuzMusicEngine.softDownloadGate()
            }
            if (wait < 0L) cancellation.cancel()
            wait
        }
        // #155: foydalanuvchi yuklashi FAILED bo'lsa — progress saqlanadi
        // (tmp qoladi, keyingi urinish davom etadi) va 12 sekunddan keyin
        // yana sinab ko'riladi: zaif/o'zgaruvchan tarmoqda bitta raund
        // kamlik qiladi, "to'xtab qoldi" ko'rinar edi. Jami 3 raund.
        var ok = false
        var round = 0
        while (true) {
            ok = runCatching {
                cancellation.scoped {
                    downloadBlocking(context, operation.track, gate, operation::update, cancellation)
                }
            }.getOrDefault(false)
            round++
            if (ok || cancellation.canceled || !operation.userJoined || round >= 3) break
            Log.i(TAG, "download round $round FAILED vid=${operation.track.videoId} — 12s'dan keyin qayta")
            try {
                Thread.sleep(12_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        val result = when {
            ok -> DownloadResult.DONE
            cancellation.canceled -> DownloadResult.CANCELED
            else -> DownloadResult.FAILED
        }
        val listeners = synchronized(operation) {
            operation.result = result
            inflight.remove(operation.track.id, operation)
            operation.progressListeners.clear()
            operation.completions.toList().also { operation.completions.clear() }
        }
        if (result == DownloadResult.DONE) doneSeq.incrementAndGet()
        main.post { for (listener in listeners) runCatching { listener(result) } }
        return result
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
        progress: ((Long, Long) -> Unit)? = null,
        cancellation: YuzDownloadCancellation = YuzDownloadCancellation()
    ): Boolean {
        cancellation.check()
        if (isDownloaded(context, track.id)) return true
        awaitGate(gate, cancellation)
        cleanupStaleTmp(context)
        val streams = try {
            YuzYouTubeApi.resolveStreamCandidates(track, 8)
        } catch (_: Exception) {
            emptyList()
        }
        cancellation.check()
        Log.i(TAG, "downloadBlocking ${track.videoId} candidates=${streams.size}")
        val attempts = ArrayList<YuzAudioStream>()
        attempts.addAll(streams)
        if (track.previewUrl.isNotBlank()) {
            attempts.add(YuzAudioStream(track.previewUrl, "mp3", 0L, ""))
        }
        for (s in attempts) {
            awaitGate(gate, cancellation)
            if (s.url.contains("googlevideo")) {
                val r = fetchRanges(context, track, s, s.sourceVideoId, gate, progress, cancellation)
                if (r == CANCELED) {
                    Log.i(TAG, "download canceled ${track.videoId} (ijro yo'l so'radi)")
                    return false
                }
                if (r == OK) return true
            } else if (fetchPlain(context, track, s, gate, progress, cancellation)) {
                return true
            }
            cancellation.check()
        }
        return false
    }

    /** #104/#155: tugallanmagan .tmp qoldiqlari — 72 soatdan eski bo'lsa
     * (resume imkonini saqlab, uzoq muddatli axlatni yig'ib boramiz). */
    private fun cleanupStaleTmp(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - 3 * 86_400_000L
            dir(context).listFiles()?.forEach { f ->
                if (f.name.endsWith(".tmp") && f.lastModified() < cutoff) f.delete()
            }
        }
    }

    private fun sameVideo(videoId: String, stream: YuzAudioStream): (Long, String) -> YuzAudioStream? =
        { at: Long, cur: String ->
            val itag = YuzYouTubeApi.itagOf(cur)
            if (itag == null) null else {
                YuzYouTubeApi.refreshStream(videoId, at, cur)?.takeIf {
                    YuzYouTubeApi.itagOf(it.url) == itag && it.ext == stream.ext &&
                        (stream.approxBytes <= 0L || it.approxBytes <= 0L ||
                            stream.approxBytes == it.approxBytes)
                }
            }
        }

    /** googlevideo: kichik Range oynalari + rad etilsa shu baytga yangi URL. */
    private fun fetchRanges(
        context: Context,
        track: YuzTrack,
        stream: YuzAudioStream,
        videoId: String,
        gate: (() -> Long)?,
        progress: ((Long, Long) -> Unit)?,
        cancellation: YuzDownloadCancellation
    ): Int {
        val id = track.id
        // #155: tmp nomi endi FORMATGA BOG'LIQ VA BARQAROR: "$id-<itag>.tmp".
        // (1) Ikki ip bir faylga yoza olmaydi — inflight single-flight buni
        //     oldin ham ta'minlar edi;
        // (2) Uzilgan/qolgan yuklash KEYINGI urinishda (hatto ilova qayta
        //     ochilganda) shu fayldan DAVOM ETADI — avval har chaqiruvda yangi
        //     nanoTime nom yaratilar va tortilgan baytlar yo'qolar edi.
        //     Turli nomzod (itag) = turli fayl: formatlar aralashmaydi.
        val fp = YuzYouTubeApi.itagOf(stream.url) ?: stream.ext.ifBlank { "raw" }
        val tmp = File(dir(context), "$id-$fp.tmp")
        // refresh FAQAT shu videoId uchun: muqobil videoning baytlari
        // yarim faylga aralashib ketmasligi kerak.
        val refresh: ((Long, String) -> YuzAudioStream?)? =
            if (videoId.isNotBlank()) sameVideo(videoId, stream) else null
        // #109: hajmi aniq katta faylni avval ikki parallel ulanishda
        // tortib ko'ramiz.
        // #133: split uzilsa ham pastki yarmdan yozilgan UZLUKSIZ prefiks
        // saqlanadi — ketma-ket yo'l o'sha joydan davom etadi.
        // #155: mavjud tmp bo'lsa split O'TKAZIB yuboriladi (fayl allaqachon
        // uzluksiz prefiks — ketma-ket davom ettirish xavfsiz va arzon).
        var resumeAt = 0L
        runCatching {
            if (tmp.isFile && tmp.length() > 0L) resumeAt = tmp.length()
        }
        if (resumeAt <= 0L && stream.approxBytes > PAR_MIN) {
            val split = fetchRangesSplit(context, tmp, track, stream, refresh, gate, progress, cancellation)
            if (split.code != FAIL) return split.code
            resumeAt = split.lowerEnd
            if (resumeAt <= 0L) runCatching { tmp.delete() }
        }
        return fetchRangesSequential(
            context, tmp, track, stream, refresh, gate, progress, cancellation, resumeAt
        )
    }

    /** Split natijasi: kod + qayta boshlash mumkin bo'lgan uzluksiz prefiks. */
    private class SplitOutcome(val code: Int, val lowerEnd: Long, val total: Long)

    /**
     * #133: ketma-ket, UZILISHGA CHIDAMLI yuklash. Har urinish tmp fayldagi
     * uzluksiz prefiksdан ([startAt]) davom etadi; URL rad etilsa reader
     * o'zi yangi URL oladi, umuman uzilsa tashqi tsikl backoff bilan qayta
     * ochadi. Faqat bekor qilinganda fayl o'chiriladi — tarmoq xatosi
     * progressni yo'qotmaydi.
     */
    private fun fetchRangesSequential(
        context: Context,
        tmp: File,
        track: YuzTrack,
        stream: YuzAudioStream,
        refresh: ((Long, String) -> YuzAudioStream?)?,
        gate: (() -> Long)?,
        progress: ((Long, Long) -> Unit)?,
        cancellation: YuzDownloadCancellation,
        startAt: Long
    ): Int {
        var got = startAt.coerceAtLeast(0L)
        var total = stream.approxBytes
        var lastEmit = 0L
        var attempt = 0
        val maxAttempts = 6
        while (attempt < maxAttempts) {
            cancellation.check()
            if (attempt > 0) {
                awaitGate(gate, cancellation)
                try {
                    Thread.sleep((700L shl (attempt - 1).coerceAtMost(3)).coerceAtMost(6000L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("download interrupted")
                }
            }
            attempt++
            var knownTotal = total
            var sawEof = false
            // #152: limit=-1 — clen= taxminiga EMAS, serverning haqiqiy
            // EOF'iga (416/Content-Range total) qadar o'qiydi. Avval limit
            // yolg'on clen'dan hisoblanardi: kichik clen'da fayl yarim
            // holda "tugallangan" deb qabul qilinardi.
            val reader = YuzRangeReader(
                http, stream.url, stream.ua, got, -1L,
                stream.approxBytes, refresh, true, gate, cancellation
            )
            var raf: java.io.RandomAccessFile? = null
            try {
                reader.open()
                val remaining = reader.length
                if (remaining >= 0L) knownTotal = got + remaining
                raf = java.io.RandomAccessFile(tmp, "rw")
                // #109: 256KB buffer — chaqiruvlar sonini 4 barobar qisqartiradi.
                val buf = ByteArray(256 * 1024)
                var guard = 0
                while (guard++ < 65536) {
                    val n = reader.read(buf, 0, buf.size)
                    if (n <= 0) {
                        sawEof = reader.isEof()
                        break
                    }
                    val at = reader.position() - n
                    synchronized(raf) {
                        raf.seek(at)
                        raf.write(buf, 0, n)
                    }
                    got = at + n
                    // #104: ~1% qadamlar bilan foizni xabar qilamiz.
                    if (progress != null && knownTotal > 0L &&
                        got - lastEmit >= knownTotal / 100 + 8192
                    ) {
                        lastEmit = got
                        progress(got, knownTotal)
                    }
                    // #86: fon yuklashi ham taroq tezligini o'lchaydi —
                    // oyna/deadline/format shundan hisoblanadi.
                    YuzNetPulse.add(n)
                }
                if (knownTotal > 0L) total = knownTotal
                // #152: 416/total tuzatilgandan keyin reader.length ham
                // server hajmini aks ettiradi — urinish oxirida yangilaymiz.
                val st = reader.serverTotal
                if (st > 0L) total = st
                Log.i(
                    TAG,
                    "download seq try=$attempt got=$got expect=${if (total > 0) total else -1} " +
                        "ext=${stream.ext} eof=$sawEof"
                )
            } catch (e: Exception) {
                Log.i(
                    TAG,
                    "download seq ERR try=$attempt ${e.javaClass.simpleName} got=$got " +
                        "${e.message?.take(48)}"
                )
            } finally {
                runCatching { raf?.close() }
                reader.close()
            }
            if (reader.canceled || cancellation.canceled) {
                // #155: progress O'CHIRILMAYDI — keyingi yuklash shu yerdan
                // davom etadi (barqaror tmp nomi itag bo'yicha topadi).
                return CANCELED
            }
            // #152: sawEof endi FAQAT haqiqiy oxirda true (416 yoki server
            // totali) — yolg'on clen katta bo'lganda "got >= total" hech
            // qachon bajarilmasdi va yuklash 6 urinishdan keyin o'chib
            // ketardi ("yarimida to'xtab qolmoqda" sababi).
            val complete = got > 0L && (sawEof || (total > 0L && got >= total))
            if (complete && publish(context, tmp, track, stream.ext, cancellation)) return OK
        }
        // #155: FAIL'da fayl o'chirilmaydi — auto-retry/keyingi bosish shu
        // prefiksdan davom etadi. Eski qoldiqlarni cleanupStaleTmp yig'adi.
        Log.i(TAG, "download seq FAIL got=$got expect=${if (total > 0) total else -1} (tmp saqlandi)")
        return FAIL
    }

    /**
     * #109: faylni ikki yarimga bo'lib har yarmini ALOHIDA YuzRangeReader
     * (alohida HTTP ulanish) bilan tortadi. Yozishlar bitta
     * RandomAccessFile'ga seek+synchronized bilan tushadi — o'rtada
     * baytlar aralashmaydi. To'liqlik: jami bayt >= kutilgan hajm.
     * Bekor qilish: biror reader gate'dan -1 olsa CANCELED.
     * #133: [SplitOutcome.lowerEnd] — pastki yarmdan yozilgan uzluksiz
     * prefiks; split yiqilsa ketma-ket yuklash shu joydan davom etadi.
     */
    private fun fetchRangesSplit(
        context: Context,
        tmp: File,
        track: YuzTrack,
        stream: YuzAudioStream,
        refresh: ((Long, String) -> YuzAudioStream?)?,
        gate: (() -> Long)?,
        progress: ((Long, Long) -> Unit)?,
        cancellation: YuzDownloadCancellation
    ): SplitOutcome {
        val total = stream.approxBytes
        val mid = total / 2
        val ra = YuzRangeReader(
            http, stream.url, stream.ua, 0L, mid, total, refresh, true, gate, cancellation
        )
        val rb = YuzRangeReader(
            http, stream.url, stream.ua, mid, total - mid, total, refresh, true, gate, cancellation
        )
        val got = java.util.concurrent.atomic.AtomicLong(0L)
        val lastEmit = java.util.concurrent.atomic.AtomicLong(0L)
        val upperFailed = java.util.concurrent.atomic.AtomicBoolean(false)
        // #133: yozilgan UZLUKSIZ prefiksni alohida kuzatamiz — bayt o'qilib
        // yozishdan oldin uzilish bo'lsa, progress oshib ketmasin.
        val lowerWritten = java.util.concurrent.atomic.AtomicLong(0L)
        val upperWritten = java.util.concurrent.atomic.AtomicLong(mid)
        // setLength QO'YMAYMIZ: fayl hajmi yozilgan baytlardan o'sadi.
        val raf = try {
            java.io.RandomAccessFile(tmp, "rw")
        } catch (_: Exception) {
            ra.close()
            rb.close()
            return SplitOutcome(FAIL, 0L, total)
        }
        val upper = Thread {
            val ok = runCatching {
                cancellation.scoped {
                    readHalf(rb, raf, upperWritten, got, lastEmit, total, progress)
                }
            }.getOrDefault(false)
            upperFailed.set(!ok)
            if (!ok) ra.abort()
        }
        upper.start()
        val lowerOk = runCatching {
            readHalf(ra, raf, lowerWritten, got, lastEmit, total, progress)
        }.getOrDefault(false)
        if (!lowerOk) rb.abort()
        var interrupted = false
        try {
            while (true) {
                try {
                    upper.join()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                    ra.abort()
                    rb.abort()
                }
            }
        } finally {
            runCatching { raf.close() }
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (interrupted) throw IOException("split download interrupted")
        val canceled = ra.canceled || rb.canceled || cancellation.canceled
        // #152: yakun mezonlari — ikkala yarm o'z HAAQIQIY EOF'iga yetdi
        // (readHalf reader.isEof() qaytaradi) VA yig'indi server tasdiqlagan
        // hajmga yetdi. Avval faqat `got >= total` (clen) tekshirilardi:
        // clen yolg'on bo'lganda to'liq tortilgan fayl ham FAIL bo'lardi.
        val realTotal = maxOf(ra.serverTotal, rb.serverTotal)
        val complete = lowerOk && !upperFailed.get() && got.get() > 0L &&
            (realTotal <= 0L || got.get() >= realTotal)
        val lowerEnd = lowerWritten.get().coerceIn(0L, mid)
        Log.i(
            TAG,
            "download split got=${got.get()} expect=$total cancel=$canceled " +
                "ok=$complete lowerEnd=$lowerEnd"
        )
        if (canceled) {
            // #155: bekor qilinganda ham uzluksiz pastki prefiks saqlanadi —
            // foydalanuvchi qayta bosganda shu joydan davom etadi.
            val le = lowerWritten.get().coerceIn(0L, mid)
            if (le > 0L) {
                runCatching { java.io.RandomAccessFile(tmp, "rw").use { it.setLength(le) } }
            } else {
                runCatching { tmp.delete() }
            }
            return SplitOutcome(CANCELED, le, total)
        }
        if (complete && publish(context, tmp, track, stream.ext, cancellation)) {
            return SplitOutcome(OK, total, total)
        }
        // #133/#155: tmp O'CHIRILMAYDI — uzluksiz prefiksgacha KESILADI.
        // Kesishmasa fayl uzunligida yuqori yarmning baytlari qolardi va
        // uzunlikka asoslangan resume o'rtadagi teshiklarni o'tkazib yuborardi.
        if (lowerEnd > 0L) {
            runCatching { java.io.RandomAccessFile(tmp, "rw").use { it.setLength(lowerEnd) } }
        } else {
            runCatching { tmp.delete() }
        }
        return SplitOutcome(FAIL, lowerEnd, total)
    }

    /** #109: bitta yarmni oxirigacha o'qib, to'g'ri offsetga yozadi. */
    private fun readHalf(
        reader: YuzRangeReader,
        raf: java.io.RandomAccessFile,
        written: java.util.concurrent.atomic.AtomicLong,
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
                written.set(at + n)
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

    private fun awaitGate(gate: (() -> Long)?, cancellation: YuzDownloadCancellation) {
        while (true) {
            cancellation.check()
            val wait = gate?.invoke() ?: 0L
            if (wait < 0L) {
                cancellation.cancel()
                cancellation.check()
            }
            if (wait == 0L) return
            Thread.sleep(wait.coerceAtMost(100L))
        }
    }

    private fun fetchPlain(
        context: Context,
        track: YuzTrack,
        stream: YuzAudioStream,
        gate: (() -> Long)?,
        progress: ((Long, Long) -> Unit)?,
        cancellation: YuzDownloadCancellation
    ): Boolean {
        val tmp = File(dir(context), "${track.id}-${System.nanoTime()}.tmp")
        val call = http.newCall(Request.Builder().url(stream.url)
            .header("User-Agent", stream.ua)
            .header("Accept-Encoding", "identity").build())
        try {
            awaitGate(gate, cancellation)
            cancellation.attach(call)
            call.execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val expect = body.contentLength()
                var got = 0L
                var lastEmit = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            awaitGate(gate, cancellation)
                            val n = input.read(buffer)
                            cancellation.check()
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            got += n
                            if (progress != null && expect > 0 &&
                                got - lastEmit >= expect / 100 + 4096
                            ) {
                                lastEmit = got
                                progress(got, expect)
                            }
                            YuzNetPulse.add(n)
                        }
                    }
                }
                if (got <= 0L || (expect >= 0L && got != expect)) return false
                return publish(context, tmp, track, stream.ext, cancellation)
            }
        } catch (e: Exception) {
            Log.i(TAG, "download plain ERR ${e.javaClass.simpleName}")
            return false
        } finally {
            cancellation.detach(call)
            call.cancel()
            tmp.delete()
        }
    }

    private fun publish(context: Context, tmp: File, track: YuzTrack, ext: String,
                        cancellation: YuzDownloadCancellation): Boolean {
        return try {
            cancellation.commit {
                if (!promote(context, tmp, track.id, ext)) return@commit false
                addToIndex(context, track)
                true
            }
        } catch (_: IOException) {
            false
        } finally {
            tmp.delete()
        }
    }

    /**
     * #133: server/format nomidan kelgan kengaytmani kesh tanaydigan
     * uchlikka keltiramiz. Ilgari "webma"/"opus" xom yozilardi va
     * [localFile] bunday faylni topa olmasdi — yuklangan qo'shiq
     * yo'qolgan ko'rinardi.
     */
    private fun normalizeExt(ext: String): String {
        val e = ext.lowercase().trim()
        return when {
            e.contains("webm") || e.contains("opus") || e.contains("ogg") ||
                e.contains("vorbis") -> "webm"
            e.contains("mp3") || e.contains("mpeg") -> "mp3"
            else -> "m4a"
        }
    }

    private fun promote(context: Context, tmp: File, id: Long, ext: String): Boolean {
        if (tmp.length() <= 0L) return false
        val dst = File(dir(context), "$id.${normalizeExt(ext)}")
        // Both paths share a directory; only an atomic rename exposes saved audio.
        if (dst.exists()) runCatching { dst.delete() }
        if (!tmp.renameTo(dst)) return false
        scanMap = null
        Log.i(TAG, "download OK ${dst.length()}B -> ${dst.name}")
        return true
    }

    fun remove(context: Context, id: Long) {
        scanMap = null
        removeFiles(dir(context), id)
        // #155: resume-qoldiqlari ($id-<itag>.tmp) ham o'chsin — keyingi
        // yuklash toza boshlanadi.
        runCatching {
            dir(context).listFiles()?.forEach { f ->
                if (f.name.startsWith("$id-") && f.name.endsWith(".tmp")) f.delete()
            }
        }
        removeIndex(context, id)
        // UI keshi "yuklangan" holatini shu sanoq orqali yangilaydi.
        doneSeq.incrementAndGet()
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
        val live = inflight.values.sortedByDescending { it.order }.map { it.track }
        return (live + out.asReversed()).distinctBy { it.id }
    }

    fun unifiedTracks(context: Context, catalog: List<YuzTrack>): List<YuzTrack> =
        (downloadedTracks(context) + catalog).distinctBy { it.id }

    fun downloadedIds(context: Context): Set<Long> {
        val arr = index(context)
        val s = HashSet<Long>()
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optLong("id") ?: continue
            if (isDownloaded(context, id)) s.add(id)
        }
        return s
    }

    /** Local file URI yoki network preview URI. */
    fun playUri(context: Context, track: YuzTrack): String {
        val f = localFile(context, track.id)
        return if (f != null) Uri.fromFile(f).toString() else track.previewUrl
    }

    private fun addToIndex(context: Context, track: YuzTrack) = synchronized(indexLock) {
        val arr = index(context)
        for (i in arr.length() - 1 downTo 0) {
            if (arr.optJSONObject(i)?.optLong("id") == track.id) arr.remove(i)
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
        scanMap = null
        val d = dir(context)
        val arr = index(context)
        val entries = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries.add(it) }
        var size = 0L
        // #152: .tmp (hali yozilayotgan) fayllar LRU hajmiga KIRMAYDI —
        // aks holda bitta katta yuklash 220MB chegarani "yeb", saqlangan
        // qo'shiqlarni muddatidan oldin o'chirardi.
        d.listFiles()?.forEach { if (!it.name.endsWith(".tmp")) size += it.length() }
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
        // #152: bitta listFiles sikli — avval EXT sikli + listFiles sikli
        // bir faylni IKKI marta sanardi (EXT ⊂ KNOWN_EXT), evict hisobi
        // buzilib chegara oshib ketardi.
        var freed = 0L
        d.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("$id.") &&
                f.name.substringAfterLast('.', "").lowercase() in KNOWN_EXT
            ) {
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
