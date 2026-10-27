package uz.kodlikino.top4.utils

import android.app.Activity
import android.content.Context
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.UserConfig

/**
 * Play Market'da yangi versiya chiqsa foydalanuvchidan yangilashni so'raydi.
 *
 * Google Play In-App Updates (IMMEDIATE oqim) ishlatiladi — dialogni Play'ning
 * o'zi chizadi: "Yangilash" bosilsa ilova joyida yangilanadi, "Keyinroq"
 * bosilsa ishlashda davom etadi.
 *
 * Cheklovlar (Play hujjati bo'yicha): faqat Play'dan o'rnatilgan build'da
 * ishlaydi — CI APK'da Play "yangilanish yo'q" deb javob beradi, shuning uchun
 * bu yerdagi xatolar jim yutiladi va hech qachon UI'ni buzmaydi.
 *
 * Tekshiruv 6 soatda bir marta (har onResume'da Play'ga so'rov ketmasin).
 * Yangilanish yuklanayotgan bo'lsa throttle chetlab o'tiladi — ilovaga
 * qaytganda jarayon darhol tiklanadi.
 */
object YuzAppUpdate {

    private const val PREFS = "yuz_app_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

    @Volatile
    private var inFlight = false

    /** IMMEDIATE oqim ochilgan — bekor qilinmaguncha tekshiruvni chetlab o'tamiz. */
    @Volatile
    private var awaitingImmediate = false

    @JvmStatic
    fun check(activity: Activity?) {
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed || inFlight) return
        if (!userReady()) return

        val prefs = ApplicationLoader.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs?.getLong(KEY_LAST_CHECK, 0L) ?: 0L
        if (!awaitingImmediate && now - last < CHECK_INTERVAL_MS) return
        if (!ApplicationLoader.isNetworkOnline()) return

        inFlight = true
        if (!awaitingImmediate) {
            prefs?.edit()?.putLong(KEY_LAST_CHECK, now)?.apply()
        }
        try {
            val manager = AppUpdateManagerFactory.create(act.applicationContext)
            manager.appUpdateInfo
                .addOnSuccessListener { info -> onInfo(act, manager, info) }
                .addOnFailureListener { e -> FileLog.e(e) }
                .addOnCompleteListener { inFlight = false }
        } catch (e: Exception) {
            FileLog.e(e)
            inFlight = false
        }
    }

    private fun onInfo(activity: Activity, manager: AppUpdateManager, info: AppUpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        val availability = info.updateAvailability()
        val available = availability == UpdateAvailability.UPDATE_AVAILABLE
        val resuming = availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
        if (!available && !resuming) {
            // Yangilanish yo'q (yoki allaqachon o'rnatilgan) — throttle qayta ishlaydi.
            awaitingImmediate = false
            return
        }
        if (!info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            awaitingImmediate = false
            return
        }

        awaitingImmediate = true
        try {
            manager.startUpdateFlow(
                info,
                activity,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
            ).addOnSuccessListener { result ->
                // "Keyinroq" bosildi — keyingi 6 soatgacha bezovta qilmaymiz.
                if (result == Activity.RESULT_CANCELED) {
                    awaitingImmediate = false
                }
            }.addOnFailureListener { e ->
                FileLog.e(e)
                awaitingImmediate = false
            }
        } catch (e: Exception) {
            FileLog.e(e)
            awaitingImmediate = false
        }
    }

    /** Login ekranida Play oynasini chiqarmaymiz — foydalanuvchi hali kirmagan. */
    private fun userReady(): Boolean = try {
        UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated
    } catch (_: Exception) {
        false
    }
}
