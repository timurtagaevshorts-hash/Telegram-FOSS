package uz.kodlikino.top4.utils

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import org.telegram.messenger.FileLog
import org.telegram.messenger.UserConfig
import java.util.concurrent.Executors

/**
 * Firebase Auth sessiyasini faol Telegram akkauntiga bog'lab turadi va shu
 * sessiya bilan faollik izini (`lastActiveAt`) yozadi.
 *
 * Firestore qoidalari yozuvni `tg_<clientUserId>` uid'iga bog'laydi; Telegram'da
 * akkaunt almashsa uid ham o'zgarishi shart. [YuzGramAdsAuth.ensureSignedIn]
 * boshqa uid bilan sessiyani yopib, yangisini ochadi — bu obyekt uni akkaunt
 * almashinuvida va ilova ochilishida avtomatik chaqiradi.
 *
 * Ilova ochilishida sessiya bo'lmasa ham kiramiz: faollik izi faqat Firebase
 * sessiyasi bilan yoziladi, admin statistikasi esa shu izga tayanadi. Sessiya
 * mavjud bo'lsa `ensureSignedIn` tarmoqqa chiqmaydi (uid mos bo'lsa darhol
 * qaytadi), presence yozuvi esa [YuzPresence] ichida 20 daqiqaga cheklangan.
 *
 * Barcha ishlar bitta fon oqimida ketadi: ketma-ket chaqiruvlar tartibi
 * saqlanadi, oxirgi akkaunt g'olib bo'ladi.
 */
object YuzGramAccountSync {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yuz-firebase-account").apply { isDaemon = true }
    }

    @JvmStatic
    fun syncActiveAccount(account: Int) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return
        executor.execute {
            val signedIn = try {
                UserConfig.getInstance(account).clientUserId > 0L
            } catch (_: Exception) {
                false
            }
            if (!signedIn) return@execute
            try {
                runBlocking { YuzGramAdsAuth.ensureSignedIn(account) }
            } catch (e: Exception) {
                FileLog.e("YuzGramAccountSync: ${e.message}")
            }
        }
    }

    /** Telegram'da akkaunt qolmadi — Firebase sessiyasi ham yopiladi. */
    @JvmStatic
    fun clear() {
        executor.execute {
            try {
                FirebaseAuth.getInstance().signOut()
            } catch (_: Exception) {
            }
        }
    }
}
