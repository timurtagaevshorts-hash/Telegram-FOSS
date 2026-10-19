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

    /** Yo'ldan boshlab [onDone] UI ipida chaqiriladi (true = muvaffaqiyat). */
    fun download(context: Context, track: YuzTrack, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        Thread {
            val ok = runCatching { downloadBlocking(app, track) }.getOrDefault(false)
            main.post { onDone(ok) }
        }.start()
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
     */
    fun downloadBlocking(context: Context, track: YuzTrack, gate: (() -> Long)? = null): Boolean {
        if (isDownloaded(context, track.id)) return true
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
                val r = fetchRanges(context, track.id, s, track.videoId, gate)
                if (r == CANCELED) {
                    Log.i(TAG, "download canceled ${track.videoId} (ijro yo'l so'radi)")
                    return false
                }
                if (r == OK) {
                    addToIndex(context, track)
                    return true
                }
            } else if (fetchPlain(context, track.id, s)) {
                addToIndex(context, track)
                return true
            }
        }
        return false
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
        gate: (() -> Long)?
    ): Int {
        val tmp = File(dir(context), "$id.tmp")
        runCatching { if (tmp.exists()) tmp.delete() }
        // refresh FAQAT shu videoId uchun: muqobil videoning baytlari
        // yarim faylga aralashib ketmasligi kerak.
        val refresh: ((Long, String) -> YuzAudioStream?)? =
            if (videoId.isNotBlank()) sameVideo(videoId) else null
        val reader = YuzRangeReader(
            http, stream.url, stream.ua, 0L, -1L, stream.approxBytes, refresh,
            windows = true, gate = gate
        )
        var complete = false
        try {
            reader.open()
            val expect = reader.length
            var got = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var guard = 0
                while (guard++ < 65536) {
                    val n = reader.read(buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    got += n
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

    /** Oddiy HTTPS (Deezer preview) — bitta GET, hajm kichik. */
    private fun fetchPlain(context: Context, id: Long, stream: YuzAudioStream): Boolean {
        val tmp = File(dir(context), "$id.tmp")
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
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        YuzNetPulse.add(n)
                    }
                }
            }
            Log.i(TAG, "download plain ${tmp.length()}B ext=${stream.ext}")
            promote(context, tmp, id, stream.ext)
        } catch (e: Exception) {
            Log.i(TAG, "download plain ERR ${e.javaClass.simpleName} ${stream.url.take(60)}")
            runCatching { tmp.delete() }
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun promote(context: Context, tmp: File, id: Long, ext: String): Boolean {
        val dst = File(dir(context), "$id.$ext")
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            return false
        }
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
        return out.asReversed()
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

    private fun addToIndex(context: Context, track: YuzTrack) {
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
    fun touch(context: Context, id: Long) {
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

    private fun removeIndex(context: Context, id: Long) {
        val arr = index(context)
        for (i in arr.length() - 1 downTo 0) {
            if (arr.optJSONObject(i)?.optLong("id") == id) arr.remove(i)
        }
        saveIndex(context, arr)
    }
}
