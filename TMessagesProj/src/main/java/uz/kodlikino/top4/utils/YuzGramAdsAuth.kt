package uz.kodlikino.top4.utils

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.telegram.messenger.UserConfig

/**
 * YuzGram "elonlar" yozuvlari uchun Firebase Auth ko'prigi.
 *
 * Firestore/Storage qoidalari (`classified_ads` create/update, `classified_ads/{uid}/...`
 * upload) `request.auth.uid == ownerUid` talab qiladi — ya'ni yozuv uchun Firebase Auth
 * sessiyasi kerak. YuzGram Telegram-only (TDLib) bo'lgani uchun Firebase Auth'ga
 * o'zi kirmaydi; shuning uchun Telegram identitetini (`telegramId`) deploy qilingan
 * `mintTelegramCustomToken` callable funksiyasiga almashtiramiz — u `tg_<telegramId>`
 * uid'li custom token qaytaradi — so'ng `signInWithCustomToken()` bilan kira­miz.
 *
 * Natijada Firebase uid == biz yozadigan ownerUid (`tg_<clientUserId>`) bo'ladi.
 */
object YuzGramAdsAuth {

    private val auth = FirebaseAuth.getInstance()

    // Firebase sessiyasi butun jarayon uchun bitta — bir vaqtda ikki modul
    // (masalan, Reels va e'lonlar) signIn qilsa, biri ikkinchisining sessiyasini
    // o'chirib yuborardi. Barcha kirishlar shu mutex orqali ketma-ket ketadi.
    private val sessionLock = Mutex()

    fun currentTelegramId(account: Int): Long =
        UserConfig.getInstance(account).clientUserId

    fun expectedUid(account: Int): String {
        val id = currentTelegramId(account)
        return if (id > 0L) "tg_$id" else ""
    }

    /**
     * Firebase Auth sessiyasi mavjudligini ta'minlaydi va uid (`tg_<telegramId>`) qaytaradi.
     * Xato bo'lsa o'qiladigan [IllegalStateException] bilan fail qiladi.
     */
    suspend fun ensureSignedIn(account: Int): String = sessionLock.withLock {
        val id = currentTelegramId(account)
        if (id <= 0L) {
            throw IllegalStateException("Telegram'da ro'yxatdan o'tmagan ekansiz")
        }
        val uid = "tg_$id"
        val user = UserConfig.getInstance(account).currentUser
        if (auth.currentUser?.uid == uid) {
            touchPresence(uid, user)
            return@withLock uid
        }

        val params = hashMapOf<String, Any>(
            "telegramId" to id,
            "firstName" to (user?.first_name ?: ""),
            "lastName" to (user?.last_name ?: ""),
            "username" to (user?.username ?: "")
        )

        // Avval token olinadi, keyingina eski sessiya yopiladi: mint xato
        // bersa foydalanuvchi avvalgi sessiyasini yo'qotmaydi.
        val callResult = withContext(Dispatchers.IO) {
            FirebaseFunctions.getInstance()
                .getHttpsCallable("mintTelegramCustomToken")
                .call(params)
                .await()
        }
        val token = extractToken(callResult?.data)
            ?: throw IllegalStateException("Firebase token olinmadi. Internetni tekshirib, qayta urinib ko'ring.")

        withContext(Dispatchers.IO) {
            if (auth.currentUser?.uid != uid) {
                auth.signOut()
                auth.signInWithCustomToken(token).await()
            }
        }
        val signedUid = auth.currentUser?.uid
        if (signedUid != uid) {
            throw IllegalStateException("Firebase sessiya yaratilmadi (${signedUid ?: "yo'q"})")
        }
        touchPresence(uid, user)
        return@withLock uid
    }

    /**
     * Faollik izi — admin dashboard statistikasi shu yozuvlardan sanaladi.
     * Throttle [YuzPresence] ichida; any xato suhbatni to'xtatmasligi kerak.
     */
    private suspend fun touchPresence(uid: String, user: org.telegram.tgnet.TLRPC.User?) {
        val ctx = org.telegram.messenger.ApplicationLoader.applicationContext ?: return
        try {
            YuzPresence.touch(ctx, uid, user?.first_name ?: "", user?.last_name ?: "")
        } catch (_: Exception) {
        }
    }

    private fun extractToken(data: Any?): String? {
        return (data as? Map<*, *>)?.get("token") as? String
    }
}
