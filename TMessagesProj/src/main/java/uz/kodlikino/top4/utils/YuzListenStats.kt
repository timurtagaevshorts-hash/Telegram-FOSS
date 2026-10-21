package uz.kodlikino.top4.utils

import android.content.Context
import org.json.JSONObject

/**
 * YuzGram #117: tinglov tarixi — qaysi ijrochi va qo'shiq necha marta
 * ijro etilgan. Radio tavsiyalari shu profilga qaraydi: foydalanuvchi
 * eng ko'p eshitadigan qo'shiqlarga o'xshash navbat yig'iladi.
 *
 * Saqlash: bitta SharedPreferences'da ikki JSON xarita (ijrochi->son,
 * "ijrochi|sarlavha"->son), CAP=40 dan katta bo'lsa eng kam sonlilari
 * tozalanadi — fayl cheksiz o'smaydi.
 */
object YuzListenStats {

    private const val PREFS = "yuz_listen_stats"
    private const val KEY_ARTISTS = "artists"
    private const val KEY_TRACKS = "tracks"
    private const val CAP = 40
    private const val MIN_COUNT = 2

    @Synchronized
    fun record(context: Context, t: YuzTrack) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val artist = t.artist.trim().lowercase()
        val ja = readMap(sp, KEY_ARTISTS)
        val jt = readMap(sp, KEY_TRACKS)
        if (artist.isNotEmpty()) bump(ja, artist)
        if (artist.isNotEmpty() && t.title.isNotBlank()) {
            bump(jt, artist + "|" + t.title.trim().lowercase())
        }
        trim(ja)
        trim(jt)
        sp.edit().putString(KEY_ARTISTS, ja.toString())
            .putString(KEY_TRACKS, jt.toString()).apply()
    }

    /** Ko'p eshitilgan ijrochilar (kichik harflarda), [exclude] chetda. */
    fun topArtists(context: Context, n: Int, exclude: Set<String>): List<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching {
            ranked(readMap(sp, KEY_ARTISTS))
                .filter { it.first.lowercase() !in exclude }
                .take(n).map { it.first }
        }.getOrDefault(emptyList())
    }

    /** Ko'p eshitilgan qo'shiqlar: "ijrochi sarlavha" ko'rinishida. */
    fun topTracks(context: Context, n: Int): List<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return runCatching {
            ranked(readMap(sp, KEY_TRACKS)).take(n).map { it.first.replace("|", " ") }
        }.getOrDefault(emptyList())
    }

    private fun readMap(sp: android.content.SharedPreferences, key: String): JSONObject =
        runCatching { JSONObject(sp.getString(key, "{}")) }.getOrDefault(JSONObject())

    private fun bump(j: JSONObject, key: String) {
        j.put(key, j.optInt(key, 0) + 1)
    }

    private fun ranked(j: JSONObject): List<Pair<String, Int>> =
        j.keys().asSequence().map { it to j.optInt(it, 0) }
            .filter { it.second >= MIN_COUNT }
            .sortedByDescending { it.second }
            .toList()

    private fun trim(j: JSONObject) {
        if (j.length() <= CAP) return
        val keys = j.keys().asSequence().toList()
        val sorted = keys.sortedByDescending { j.optInt(it, 0) }
        for (k in sorted.drop(CAP)) j.remove(k)
    }
}
