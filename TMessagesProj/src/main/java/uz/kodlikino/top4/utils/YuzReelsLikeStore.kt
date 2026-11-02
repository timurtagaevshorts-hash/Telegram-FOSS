package uz.kodlikino.top4.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * "Men layk bosgan" reels — lokal kesh.
 *
 * Manba har doim Firestore: `reels/{id}/likes/{uid}` hujjati ilova yangilanganda
 * va qayta o'rnatilganda ham saqlanib qoladi (qoidalar read'ni hammaga ochiq).
 * Bu kesh faqat BIRINCHI KADR uchun: feed ochilganda yurakchalar darhol to'g'ri
 * ko'rinadi (tarmoq javobini kutmaydi), oflaynda ham yo'qolmaydi. Serverdan
 * kelgan javob keshni ustidan yozadi.
 *
 * Kalit `account + uid` — Telegram akkaunti almashsa holat aralashmaydi.
 */
object YuzReelsLikeStore {

    private const val PREFS = "reels_likes"
    private const val KEY_LIKED = "liked_ids"
    private const val MAX_KEEP = 2000
    private const val SERVER_LIMIT = 1000L

    private fun prefs(context: Context, account: Int, uid: String): SharedPreferences =
        context.applicationContext.getSharedPreferences(
            "${PREFS}_${account}_${safeKey(uid)}",
            Context.MODE_PRIVATE
        )

    private fun safeKey(uid: String): String =
        uid.replace(Regex("[^A-Za-z0-9_]"), "_").take(48)

    /** Lokal keshdagi layk id'lari. */
    fun likedIds(context: Context, account: Int, uid: String): Set<String> {
        if (uid.isBlank()) return emptySet()
        return try {
            HashSet(prefs(context, account, uid).getStringSet(KEY_LIKED, emptySet()) ?: emptySet())
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun isLiked(context: Context, account: Int, uid: String, reelId: String): Boolean {
        if (uid.isBlank() || reelId.isBlank()) return false
        return likedIds(context, account, uid).contains(reelId)
    }

    /** Layk/unlayk — darhol keshlanadi (yozuv muvaffaqiyatsiz bo'lsa qaytariladi). */
    fun setLiked(context: Context, account: Int, uid: String, reelId: String, liked: Boolean) {
        if (uid.isBlank() || reelId.isBlank()) return
        try {
            val p = prefs(context, account, uid)
            val current = HashSet(p.getStringSet(KEY_LIKED, emptySet()) ?: emptySet())
            val changed = if (liked) current.add(reelId) else current.remove(reelId)
            if (!changed) return
            if (current.size > MAX_KEEP) {
                current.removeAll(current.take(current.size - MAX_KEEP).toSet())
            }
            p.edit().putStringSet(KEY_LIKED, current).apply()
        } catch (_: Exception) {
        }
    }

    /** Bir nechta server natijasini birlashtiradi (bo'lak-bo'lak tekshiruv uchun). */
    fun merge(context: Context, account: Int, uid: String, states: Map<String, Boolean>) {
        if (uid.isBlank() || states.isEmpty()) return
        try {
            val p = prefs(context, account, uid)
            val current = HashSet(p.getStringSet(KEY_LIKED, emptySet()) ?: emptySet())
            var changed = false
            for ((id, liked) in states) {
                if (id.isBlank()) continue
                val delta = if (liked) current.add(id) else current.remove(id)
                changed = changed || delta
            }
            if (!changed) return
            if (current.size > MAX_KEEP) {
                current.removeAll(current.take(current.size - MAX_KEEP).toSet())
            }
            p.edit().putStringSet(KEY_LIKED, current).apply()
        } catch (_: Exception) {
        }
    }

    /**
     * Foydalanuvchining BARCHA layklari — bitta collection-group so'rovi.
     * Hujjat yo'li `reels/{reelId}/likes/{uid}`; ota-hujjat id'si = reel id.
     *
     * @return `null` — so'rov muvaffaqiyatsiz (tarmoq/qoida) — chaqiruvchi
     * sahifa bo'yicha tekshiruvga o'tadi.
     */
    suspend fun fetchLikedIds(uid: String): Set<String>? {
        if (uid.isBlank()) return null
        return try {
            val snap = FirebaseFirestore.getInstance()
                .collectionGroup("likes")
                .whereEqualTo("userId", uid)
                .limit(SERVER_LIMIT)
                .get()
                .await()
            snap.documents.mapNotNull { doc ->
                doc.reference.parent.parent?.id?.takeIf { it.isNotBlank() }
            }.toHashSet()
        } catch (_: Exception) {
            null
        }
    }
}
