package uz.kodlikino.top4.utils

import android.content.Context
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uz.kodlikino.top4.models.ReelNotification

/**
 * Reels bildirishnomalarini butun ilova bo'ylab yetkazadi: `LaunchActivity`
 * ochiq turganda `user_notifications/{uid}/items` tinglanadi, yangi layk /
 * izoh / javob kelganda tizim heads-up'i va ovoz chiqadi ([YuzReelNotify]).
 *
 * [uz.kodlikino.top4.ui.ReelNotificationsActivity] faqat ro'yxatni ko'rsatadi —
 * dublikat signal bermaydi. FCM yo'q: bildirishnoma faqat ilova ochiq
 * turganda keladi (Firestore listener), shuning uchun `onPause`da uziladi.
 */
object YuzReelNotifyHub {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val seen = HashSet<String>()
    private var listener: ListenerRegistration? = null
    private var attachedUid = ""
    private var generation = 0
    private var primed = false

    /**
     * Faollikni yoqadi. Firebase sessiyasi ochilmaguncha listener ulanmaydi —
     * qoidalar `user_notifications` o'qishni faqat egasiga ruxsat beradi.
     */
    fun attach(context: Context, account: Int) {
        val appContext = context.applicationContext
        val expected = YuzGramAdsAuth.expectedUid(account)
        if (expected.isBlank()) {
            detach()
            return
        }
        if (listener != null && attachedUid == expected) return
        if (attachedUid.isNotBlank() && attachedUid != expected) {
            seen.clear()
            primed = false
        }
        listener?.remove()
        listener = null
        attachedUid = expected
        val gen = ++generation
        scope.launch {
            try {
                val signedUid = YuzGramAdsAuth.ensureSignedIn(account)
                if (gen != generation || signedUid != expected) return@launch
                listener = ReelNotificationManager.listen(signedUid) { list ->
                    onSnapshot(appContext, list)
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Ilova fonda — tinglashni to'xtatamiz (batareya/trafik). */
    fun detach() {
        generation++
        listener?.remove()
        listener = null
    }

    private fun onSnapshot(context: Context, list: List<ReelNotification>) {
        // Birinchi snapshot — eski bildirishnomalar; ularni "yangi" deb
        // ko'rsatmaymiz, faqat ko'rilgan deb belgilaymiz.
        if (!primed) {
            primed = true
            list.forEach { seen.add(it.id) }
            return
        }
        var fresh = 0
        for (item in list) {
            if (seen.add(item.id)) {
                YuzReelNotify.show(context, item)
                fresh++
            }
        }
        if (fresh > 0) YuzReelNotify.playInAppAlert(context)
    }
}
