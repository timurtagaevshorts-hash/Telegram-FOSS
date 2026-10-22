package uz.kodlikino.top4.utils

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import org.telegram.messenger.FileLog
import org.telegram.messenger.UserConfig
import java.util.concurrent.Executors

/**
 * Firebase Auth sessiyasini faol Telegram akkauntiga bog'lab turadi.
 *
 * Firestore qoidalari yozuvni `tg_<clientUserId>` uid'iga bog'laydi; Telegram'da
 * akkaunt almashsa uid ham o'zgarishi shart. [YuzGramAdsAuth.ensureSignedIn]
 * boshqa uid bilan sessiyani yopib, yangisini ochadi — bu obyekt uni akkaunt
 * almashinuvida va ilova ochilishida avtomatik chaqiradi.
 *
 * Barcha ishlar bitta fon oqimida ketadi: ketma-ket chaqiruvlar tartibi
 * saqlanadi, oxirgi akkaunt g'olib bo'ladi.
 */
object YuzGramAccountSync {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yuz-firebase-account").apply { isDaemon = true }
    }

    /**
     * Firebase sessiyasi mavjud bo'lsa (oldingi akkauntdan qolgan bo'lsa),
     * uni [account] uid'iga o'tkazadi. Sessiya bo'lmasa hech narsa qilmaydi —
     * birinchi Firebase amali o'zi kerakli akkaunt bilan kiradi.
     */
    @JvmStatic
    fun syncActiveAccount(account: Int) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) return
        executor.execute {
            val expected = try {
                YuzGramAdsAuth.expectedUid(account)
            } catch (_: Exception) {
                return@execute
            }
            if (expected.isEmpty()) return@execute
            val auth = try {
                FirebaseAuth.getInstance()
            } catch (_: Exception) {
                return@execute
            }
            val current = auth.currentUser?.uid ?: return@execute
            if (current == expected) return@execute
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
