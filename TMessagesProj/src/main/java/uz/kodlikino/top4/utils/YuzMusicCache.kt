package uz.kodlikino.top4.utils

import android.content.Context
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

    enum class DownloadResult { DONE, FAILED, CANCELED }

    private val indexLock = Any()
    private const val PAR_MIN = 512L * 1024
    private val sequence = java.util.concurrent.atomic.AtomicLong()

    private class Inflight(val track: YuzTrack) {
        val cancellation = YuzDownloadCancellation()
        val order = sequence.incrementAndGet()
        val completions = ArrayList<(DownloadResult) -> Unit>()
        val progressListeners = ArrayList<(Int) -> Unit>()
        var result: DownloadResult? = null
        @Volatile var pct = 0
        @Volatile var rate = 0
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
        val existing = inflight.putIfAbsent(track.id, fresh)
        val operation = existing ?: fresh
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
            val wait = backgroundGate?.invoke() ?: 0L
            if (wait < 0L) cancellation.cancel()
            wait
        }
        val ok = runCatching {
            cancellation.scoped {
                downloadBlocking(context, operation.track, gate, operation::update, cancellation)
            }
        }.getOrDefault(false)
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

    /** #104: tugallanmagan .tmp qoldiqlari (eski nom va yangi "id-<nano>.tmp"). */
    private fun cleanupStaleTmp(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - 86_400_000L
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
        // #104: har urinishga O'Z tmp nomi — ikki ip bir faylga yozib,
        // uni buzishi endi imkonsiz.
        val tmp = File(dir(context), "$id-${System.nanoTime()}.tmp")
        runCatching { if (tmp.exists()) tmp.delete() }
        // refresh FAQAT shu videoId uchun: muqobil videoning baytlari
        // yarim faylga aralashib ketmasligi kerak.
        val refresh: ((Long, String) -> YuzAudioStream?)? =
            if (videoId.isNotBlank()) sameVideo(videoId, stream) else null
        // #109: hajmi aniq katta faylni avval ikki parallel ulanishda
        // tortib ko'ramiz. Muvaffaqiyatsizlik bo'lib birorta bayt kelmasa
        // — oddiy ketma-ket yo'lga qaytamiz (arzon); o'rtada uzilsa —
        // keyingi nomzodga o'tamiz (ketma-ket ham shu joyni bosar edi).
        if (stream.approxBytes > PAR_MIN) {
            val r = fetchRangesSplit(context, tmp, track, stream, refresh, gate, progress, cancellation)
            if (r != FAIL) return r
            if (tmp.length() > 0L) {
                runCatching { tmp.delete() }
                return FAIL
            }
            runCatching { tmp.delete() }
        }
        val reader = YuzRangeReader(
            http, stream.url, stream.ua, 0L, -1L, stream.approxBytes, refresh,
            windows = true, gate = gate, cancellation = cancellation
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
        if (reader.canceled || cancellation.canceled) {
            runCatching { tmp.delete() }
            return CANCELED
        }
        return if (complete) {
            if (publish(context, tmp, track, stream.ext, cancellation)) OK else FAIL
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
        track: YuzTrack,
        stream: YuzAudioStream,
        refresh: ((Long, String) -> YuzAudioStream?)?,
        gate: (() -> Long)?,
        progress: ((Long, Long) -> Unit)?,
        cancellation: YuzDownloadCancellation
    ): Int {
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
        // setLength QO'YMAYMIZ: fayl hajmi yozilgan baytlardan o'sadi —
        // chaqiruvchi "birorta bayt keldi-yo'qmi"ni tmp.length() bo'yicha
        // adashmasdan biladi (seek EOF ortiga yozish ham kengaytiradi).
        val raf = try {
            java.io.RandomAccessFile(tmp, "rw")
        } catch (_: Exception) {
            return FAIL
        }
        val upper = Thread {
            val ok = runCatching {
                cancellation.scoped { readHalf(rb, raf, got, lastEmit, total, progress) }
            }.getOrDefault(false)
            upperFailed.set(!ok)
            if (!ok) ra.abort()
        }
        upper.start()
        val lowerOk = runCatching { readHalf(ra, raf, got, lastEmit, total, progress) }
            .getOrDefault(false)
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
        val complete = lowerOk && !upperFailed.get() && got.get() >= total
        Log.i(TAG, "download split got=${got.get()} expect=$total cancel=$canceled ok=$complete")
        if (canceled) {
            runCatching { tmp.delete() }
            return CANCELED
        }
        if (complete && publish(context, tmp, track, stream.ext, cancellation)) return OK
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

    private fun promote(context: Context, tmp: File, id: Long, ext: String): Boolean {
        if (tmp.length() <= 0L) return false
        val dst = File(dir(context), "$id.$ext")
        // Both paths share a directory; only an atomic rename exposes saved audio.
        if (!tmp.renameTo(dst)) return false
        Log.i(TAG, "download OK ${dst.length()}B -> ${dst.name}")
        return true
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

    /** Local file:// URI yoki network preview URI. */
    fun playUri(context: Context, track: YuzTrack): String {
        val f = localFile(context, track.id)
        return if (f != null) f.toURI().toString() else track.previewUrl
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
