package uz.kodlikino.top4.utils

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Admin panelga kirish — eski KinoTOP4 `claimAdminSession` Cloud Function'iga
 * qo'ng'iroq qiladi. Parol tekshiruvi 100% server-side (`admin_credentials`
 * kolleksiyasi), APK'da parol saqlanmaydi. Muvaffaqiyatda funksiya
 * `admins/{uid}` sessiya hujjatini yozadi — shu hujjat `movies` create/delete
 * Firestore qoidasidagi `isAdminUser()` ni yoqadi.
 *
 * Chaqiruvdan oldin Firebase Auth sessiyasi bo'lishi shart — [YuzGramAdsAuth.ensureSignedIn]
 * uni `tg_<telegramId>` uid bilan ta'minlaydi (deploy qilingan funksiya bir xil
 * Firebase loyihasida — shuning uchun eski login/parol ishlaydi).
 */
object KinoAdminAuth {

    private const val TAG = "KinoAdminAuth"

    suspend fun claimSession(account: Int, username: String, password: String): Result<Unit> {
        return try {
            YuzGramAdsAuth.ensureSignedIn(account)
            val data = withContext(Dispatchers.IO) {
                FirebaseFunctions.getInstance()
                    .getHttpsCallable("claimAdminSession")
                    .call(
                        hashMapOf<String, Any>(
                            "username" to username.trim(),
                            "password" to password
                        )
                    )
                    .await()
                    ?.data
            }
            val ok = (data as? Map<*, *>)?.get("ok") == true
            if (ok) Result.success(Unit)
            else Result.failure(IllegalStateException("Login yoki parol xato"))
        } catch (e: Exception) {
            Log.e(TAG, "claimSession: ${e.message}", e)
            val msg = e.message?.substringAfter(":")?.trim()?.ifBlank { null }
                ?: e.message
                ?: "Login yoki parol xato"
            Result.failure(IllegalStateException(msg))
        }
    }
}
