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
 * YuzGram #62: qo'shiq preview'larini keshga yuklab olish (oflayd tinglash).
 * Fayllar ichki xotirada saqlanadi, indeks SharedPreferences'da JSON sifatida.
 */
object YuzMusicCache {

    private const val PREFS = "yuz_music_dl"
    private const val DIR = "yuzmusic"
    private val main = Handler(Looper.getMainLooper())

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

    private fun fileFor(context: Context, id: Long): File =
        File(dir(context), "$id.mp3")

    fun isDownloaded(context: Context, id: Long): Boolean =
        fileFor(context, id).exists()

    /** Yo'ldan boshlab [onDone] UI ipida chaqiriladi (true = muvaffaqiyat). */
    fun download(context: Context, track: YuzTrack, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        Thread {
            val ok = runCatching { downloadBlocking(app, track) }.getOrDefault(false)
            main.post { onDone(ok) }
        }.start()
    }

    /** IO ipida chaqiladigan blokiy yuklab olish. */
    fun downloadBlocking(context: Context, track: YuzTrack): Boolean {
        if (track.previewUrl.isBlank()) return false
        if (isDownloaded(context, track.id)) return true
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(track.previewUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "YuzGram/1.0")
                instanceFollowRedirects = true
            }
            if (conn.responseCode !in 200..299) return false
            val tmp = File(dir(context), "${track.id}.tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            val dst = fileFor(context, track.id)
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
        runCatching { fileFor(context, id).delete() }
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
                    link = o.optString("link")
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
        val f = fileFor(context, track.id)
        return if (f.exists()) f.toURI().toString() else track.previewUrl
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
