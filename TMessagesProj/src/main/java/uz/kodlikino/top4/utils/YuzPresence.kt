package uz.kodlikino.top4.utils

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Faollik izi (presence) — `comment_profiles/{uid}` hujjatidagi `online` va
 * `lastActiveAt` maydonlari. Firestore qoidasi owner'ga aynan shu ikki kalitni
 * yozishga ruxsat beradi (`commentProfilePresencePatchAllowed`), shuning uchun
 * "bugun/faol" statistikasi HAQIQIY: admin dashboardi shu maydonlarni sanaydi.
 *
 * Yozish [ensureSignedIn] muvaffaqiyati bilan qo'zg'aladi, lekin har safar emas —
 * [MIN_INTERVAL_MS] throttle: telefon doim yozmaydi, faqat ~20 daqiqada bir.
 * Hujjat hali yo'q bo'lsa (foydalanuvchi reels'ga kirmagan) — create qoidasiga
 * mos minimal profil yaratamiz ([createSafe]).
 */
object YuzPresence {

    private const val TAG = "YuzPresence"
    private const val PREFS = "yuz_presence"
    private const val KEY_LAST = "last_write_ms"
    private const val MIN_INTERVAL_MS = 20L * 60L * 1000L

    private val db = FirebaseFirestore.getInstance()

    /**
     * @param firstName Telegram ismi — yangi profil hujjati yaratilsa, rule
     *        `firstName` bo'sh emasligini talab qiladi.
     */
    suspend fun touch(appContext: Context, uid: String, firstName: String, lastName: String) {
        if (uid.isBlank() || !uid.startsWith("tg_")) return
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST, 0L)
        val now = System.currentTimeMillis()
        if (now - last < MIN_INTERVAL_MS) return
        val ref = db.collection("comment_profiles").document(uid)
        try {
            withContext(Dispatchers.IO) {
                try {
                    ref.update(
                        mapOf("online" to true, "lastActiveAt" to now)
                    ).await()
                } catch (e: Exception) {
                    if (e is FirebaseFirestoreException &&
                        e.code == FirebaseFirestoreException.Code.NOT_FOUND
                    ) {
                        ref.set(createSafe(uid, firstName, lastName, now)).await()
                    } else {
                        throw e
                    }
                }
            }
            prefs.edit().putLong(KEY_LAST, now).apply()
        } catch (e: Exception) {
            // Presence muvaffaqiyatga bog'liq emas — xatoda jim o'tamiz.
            Log.w(TAG, "touch $uid: ${e.message}")
        }
    }

    /** commentProfileCreateSafe: uid mos, firstName to'la, premium maydonlari bo'sh. */
    private fun createSafe(uid: String, firstName: String, lastName: String, now: Long): Map<String, Any> {
        val name = listOf(firstName.trim(), lastName.trim())
            .filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "YuzGram" }
        return hashMapOf(
            "uid" to uid,
            "firstName" to name,
            "lastName" to "",
            "photoUrl" to "",
            "bio" to "",
            "isPremium" to false,
            "monetizationVerified" to false,
            "premiumPlanType" to "",
            "createdAt" to now,
            "online" to true,
            "lastActiveAt" to now
        )
    }
}
