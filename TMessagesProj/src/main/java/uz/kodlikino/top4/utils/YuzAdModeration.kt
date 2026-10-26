package uz.kodlikino.top4.utils

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog

/**
 * `settings/app.adsModerationEnabled` — e'lonlar tekshiruvdan o'tadimi.
 *
 * Default — tekshiruv yoniq: yangi e'lon `pending` holatida yaratiladi va
 * admin tasdiqlagachgina hammaga ko'rinadi. Admin panelda "tekshiruvsiz
 * joylash" yoqilsa, e'lon darhol `approved` bo'ladi.
 */
object YuzAdModeration {

    private const val SETTINGS_COLLECTION = "settings"
    private const val SETTINGS_DOC = "app"
    private const val FIELD_ENABLED = "adsModerationEnabled"
    private const val PREFS = "yuz_ad_moderation"
    private const val KEY_ENABLED = "enabled"

    @Volatile
    private var memoryEnabled: Boolean? = null

    private val db = FirebaseFirestore.getInstance()

    /** Oxirgi ma'lum qiymat — tarmoqni kutmasdan qaror qabul qilish uchun. */
    fun cachedEnabled(): Boolean =
        memoryEnabled ?: prefs()?.getBoolean(KEY_ENABLED, true) ?: true

    /**
     * E'lon joylashdan oldin majburiy yangilanadi: admin bayroqni almashtirgan
     * bo'lsa, eski kesh bilan yozilgan `status` admin qoidasiga zid bo'lib
     * qolmasin.
     */
    suspend fun loadEnabled(forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh) {
            memoryEnabled?.let { return it }
        }
        return try {
            val snapshot = db.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC).get().await()
            val enabled = if (!snapshot.exists()) {
                true
            } else {
                snapshot.getBoolean(FIELD_ENABLED) ?: true
            }
            store(enabled)
            enabled
        } catch (e: Exception) {
            FileLog.e(e)
            cachedEnabled()
        }
    }

    suspend fun adminLoad(): Result<Boolean> = try {
        val snapshot = db.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC).get().await()
        val enabled = if (!snapshot.exists()) {
            true
        } else {
            snapshot.getBoolean(FIELD_ENABLED) ?: true
        }
        store(enabled)
        Result.success(enabled)
    } catch (e: Exception) {
        FileLog.e(e)
        Result.failure(e)
    }

    /** Faqat admin huquqi bilan — `settings/app` qoidalari buni ta'minlaydi. */
    suspend fun adminSave(enabled: Boolean): Result<Boolean> = try {
        db.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC).set(
            mapOf(
                FIELD_ENABLED to enabled,
                "adsModerationUpdatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
        store(enabled)
        Result.success(enabled)
    } catch (e: Exception) {
        FileLog.e(e)
        Result.failure(e)
    }

    private fun store(enabled: Boolean) {
        memoryEnabled = enabled
        prefs()?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    private fun prefs() = ApplicationLoader.applicationContext
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
