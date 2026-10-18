package uz.kodlikino.top4.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * YuzGram #62: qo'shiqlarni keshga yuklab olish (oflayd tinglash).
 * YuzGram #63: YouTube qatorlari endi TO'LIQ audio sifatida saqlanadi
 * (M4A/WebM), Deezer zaxirasi eski mp3 preview formatida qoladi.
 * Fayllar ichki xotirada, indeks SharedPreferences'da JSON sifatida.
 */
object YuzMusicCache {

    private const val PREFS = "yuz_music_dl"
    private const val DIR = "yuzmusic"
    private val main = Handler(Looper.getMainLooper())
    private val EXT = arrayOf("m4a", "mp3", "webm")

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
     * qo'shiq (m4a/webm) tushadi; Deezer qatori eski mp3 preview bo'lib qoladi.
     */
    fun downloadBlocking(context: Context, track: YuzTrack): Boolean {
        if (isDownloaded(context, track.id)) return true
        val src = when {
            track.videoId.isNotBlank() ->
                YuzYouTubeApi.resolveStream(track.videoId)?.let { it.url to it.ext }
            track.previewUrl.isNotBlank() -> track.previewUrl to "mp3"
            else -> null
        } ?: return false
        val (url, ext) = src
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 30000
                requestMethod = "GET"
                setRequestProperty("User-Agent", YuzYouTubeApi.UA)
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return false
            val tmp = File(dir(context), "${track.id}.tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            val dst = File(dir(context), "${track.id}.$ext")
            if (!tmp.renameTo(dst)) {
                tmp.delete()
                return false
            }
            addToIndex(context, track)
            true
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun remove(context: Context, id: Long) {
        val d = dir(context)
        for (e in EXT) runCatching { File(d, "$id.$e").delete() }
        val arr = index(context)
        for (i in arr.length() - 1 downTo 0) {
            if (arr.optJSONObject(i)?.optLong("id") == id) arr.remove(i)
        }
        saveIndex(context, arr)
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
        arr.put(o)
        saveIndex(context, arr)
    }
}
