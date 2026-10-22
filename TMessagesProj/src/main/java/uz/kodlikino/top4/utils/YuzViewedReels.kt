package uz.kodlikino.top4.utils

import android.content.Context
import android.content.SharedPreferences
import uz.kodlikino.top4.models.Reel
import kotlin.random.Random

/**
 * Ko'rishlarni deduplikatsiya — qaysi reel'ni allaqachon ko'rganimiz
 * SharedPreferences'da SAQLANADI (ilova qayta ochilsa ham). Donor
 * `ReelsFragment` (PREFS "reels_views") g'oyasining alohida klass ko'rinishi.
 *
 * Nima uchun Firestore'da per-user `views/{uid}` yozib bo'lmaydi: rules reels
 * subkolleksiyalarini client'tan yozishni taqiqlaydi (catch-all deny),
 * `views`/`likeCount`/`commentCount` update'lar esa hasOnly([views,...]) —
 * shuning uchun lokal dedup + increment yagona imkon.
 */
object YuzViewedReels {

    private const val PREFS = "reels_views"
    private const val KEY_VIEWED = "viewed_ids"
    private const val KEY_KNOWN = "known_ids"
    private const val MAX_KEEP = 1500

    private fun prefs(context: Context, account: Int): SharedPreferences =
        context.applicationContext.getSharedPreferences("${PREFS}_$account", Context.MODE_PRIVATE)

    /**
     * Reel'ni "ko'rildi" deb belgilaydi.
     * @return `true` — BIRINCHI marta (hisobga olish kerak), `false` — avval ko'rilgan.
     */
    fun markViewed(context: Context, reelId: String, account: Int): Boolean {
        if (reelId.isBlank()) return false
        val p = prefs(context, account)
        val current = HashSet(p.getStringSet(KEY_VIEWED, emptySet()) ?: emptySet())
        if (!current.add(reelId)) return false
        if (current.size > MAX_KEEP) {
            // Eski id'larni qisqartiramiz (HashSet tartibsiz — chekish chegaraviy zararsiz).
            current.removeAll(current.take(current.size - MAX_KEEP).toSet())
        }
        p.edit().putStringSet(KEY_VIEWED, current).apply()
        return true
    }

    /** Feed'da mavjud bo'lmagan (o'chirilgan) id'larni tozalaydi. */
    fun prune(context: Context, liveIds: Collection<String>, account: Int) {
        val p = prefs(context, account)
        val known = HashSet(p.getStringSet(KEY_KNOWN, emptySet()) ?: emptySet())
        val live = liveIds.toSet()
        val viewed = HashSet(p.getStringSet(KEY_VIEWED, emptySet()) ?: emptySet())
        val dropped = known - live
        if (dropped.isEmpty() && known == live) return
        viewed.removeAll(dropped)
        p.edit()
            .putStringSet(KEY_VIEWED, viewed)
            .putStringSet(KEY_KNOWN, live)
            .apply()
    }

    fun hasViewed(context: Context, reelId: String, account: Int): Boolean {
        if (reelId.isBlank()) return false
        return prefs(context, account).getStringSet(KEY_VIEWED, emptySet())?.contains(reelId) == true
    }

    fun knownIds(context: Context, account: Int): Set<String> =
        HashSet(prefs(context, account).getStringSet(KEY_KNOWN, emptySet()) ?: emptySet())
}

class YuzReelsOrder(
    private val knownAtOpen: Set<String>,
    private val random: Random = Random.Default
) {
    private val ranks = HashMap<String, Long>()
    private var firstNewestAt: Long? = null

    fun arrange(reels: List<Reel>): List<Reel> {
        val unique = reels.distinctBy { it.id }
        if (knownAtOpen.isEmpty() && firstNewestAt == null) {
            firstNewestAt = unique.filter { !it.storyArchived }.maxOfOrNull { it.createdAt }
        }
        val fresh = unique.filter {
            !it.storyArchived && if (knownAtOpen.isEmpty()) {
                firstNewestAt?.let { newest -> it.createdAt >= newest } == true
            } else {
                it.id !in knownAtOpen
            }
        }.sortedWith(compareByDescending<Reel> { it.createdAt }.thenBy { it.id })
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        val rest = unique.filter { it.id !in freshIds }
        rest.forEach { ranks.getOrPut(it.id) { random.nextLong() } }
        return fresh + rest.sortedWith(compareBy<Reel> { ranks.getValue(it.id) }.thenBy { it.id })
    }
}
