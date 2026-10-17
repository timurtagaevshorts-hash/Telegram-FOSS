package uz.kodlikino.top4.utils

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * YuzGram profili — Firestore `comment_profiles/{tg_<id>}` hujjati ustidagi
 * o'qish va (faqat O'ZI uchun) yaratish. Profil ekrani shu minimal kesimni
 * ishlatadi: nom / rasm / bio + "bu foydalanuvchi YuzGram foydalanuvchimi?"
 * aniqlashi (Telegram ma'lumotlari uchun to'ldiruvchi manba).
 *
 * Qoida (firestore.rules `comment_profiles/{uid}`):
 *  - read  — istalgan `signedIn()` (shuning uchun profil ko'rishdan oldin
 *    [YuzGramAdsAuth.ensureSignedIn] chaqiriladi);
 *  - create — faqat o'zi (`isTelegramUser() && isOwner(uid) && commentProfileCreateSafe`).
 *
 * Shu sababli [ensureSelfProfile] har bir YuzGram foydalanuvchisi uchun o'z
 * hujjatini bir marta yaratadi. Biz begona hujjat yarata olmaymiz (rule
 * taqiqlaydi), faqat o'zimiznikini.
 */
object YuzGramProfileStore {

    private const val TAG = "YuzGramProfileStore"
    private const val PROFILES = "comment_profiles"

    private val db = FirebaseFirestore.getInstance()

    data class Profile(
        val uid: String,
        val firstName: String,
        val lastName: String,
        val photoUrl: String,
        val bio: String,
        val isPremium: Boolean,
        val exists: Boolean
    ) {
        val displayName: String
            get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun empty(uid: String) = Profile(uid, "", "", "", "", false, false)

    /** Profil hujjatini o'qiydi; mavjud bo'lmasa `exists=false` bo'sh profil qaytadi. */
    suspend fun load(uid: String): Profile = withContext(Dispatchers.IO) {
        val id = uid.trim()
        if (id.isBlank()) return@withContext empty(id)
        try {
            val snap = db.collection(PROFILES).document(id).get().await()
            if (!snap.exists()) return@withContext empty(id)
            Profile(
                uid = id,
                firstName = snap.getString("firstName").orEmpty(),
                lastName = snap.getString("lastName").orEmpty(),
                photoUrl = snap.getString("photoUrl").orEmpty(),
                bio = snap.getString("bio").orEmpty(),
                isPremium = snap.getBoolean("isPremium") ?: false,
                exists = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            empty(id)
        }
    }

    /**
     * O'z profil hujjatini mavjud bo'lmasa yaratadi (idempotent). Mavjud bo'lsa
     * HECH NARSA o'zgartirmaymiz — update qoidasiga (commentProfilePatchKeys)
     * urilmaymiz; nom/rasm keyinroq o'zi tomonidan yangilanadi.
     *
     * @param photoUrl Telegram rasm URL'i yoki bo'sh — profil kartoshkasi uchun.
     */
    suspend fun ensureSelfProfile(
        uid: String,
        firstName: String,
        lastName: String,
        photoUrl: String
    ) = withContext(Dispatchers.IO) {
        val id = uid.trim()
        if (id.isBlank() || !id.startsWith("tg_")) return@withContext
        try {
            db.runTransaction { tx ->
                val ref = db.collection(PROFILES).document(id)
                if (tx.get(ref).exists()) return@runTransaction
                val name = listOf(firstName.trim(), lastName.trim())
                    .filter { it.isNotBlank() }.joinToString(" ")
                    .ifBlank { "YuzGram" }
                // commentProfileCreateSafe: premium maydonlari default false/'',
                // firstName bo'sh emas — shunda create o'tadi.
                tx.set(
                    ref,
                    hashMapOf(
                        "uid" to id,
                        "firstName" to name,
                        "lastName" to "",
                        "photoUrl" to photoUrl,
                        "bio" to "",
                        "isPremium" to false,
                        "monetizationVerified" to false,
                        "premiumPlanType" to "",
                        "createdAt" to System.currentTimeMillis()
                    )
                )
            }.await()
        } catch (e: Exception) {
            Log.w(TAG, "ensureSelfProfile: ${e.message}")
        }
    }
}
