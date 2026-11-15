package uz.kodlikino.top4.trust

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import uz.kodlikino.top4.utils.YuzGramAdsAuth

/**
 * Ishonchli kuzatuvning fon qatlami: asosiy ekran ochiq turganda
 * (a) kelgan so'rovlar sonini va faol ishonchni kuzatadi — tugma holati uchun,
 * (b) ruxsat berilgan bo'lsa, chatlar ro'yxatini davriy nashr qiladi.
 *
 * Faqat oldingi planda ishlaydi: [start] `onResume`, [stop] `onPause`da.
 */
object TrustSync {

    private const val TAG = "TrustSync"
    private const val INTERVAL_MS = 90_000L

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var inboxListener: ListenerRegistration? = null
    private var sentListener: ListenerRegistration? = null
    private var pending = 0
    private var incomingActive = false
    private var outgoingActive = false
    private var onStateChanged: ((Boolean, Int) -> Unit)? = null
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val account = org.telegram.messenger.UserConfig.selectedAccount
            scope?.launch {
                try {
                    TrustSnapshotBuilder.publish(account)
                } catch (e: Exception) {
                    Log.w(TAG, "publish: ${e.message}")
                }
            }
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    fun pendingCount(): Int = pending

    fun isActive(): Boolean = incomingActive || outgoingActive

    fun start(account: Int, onState: (Boolean, Int) -> Unit) {
        if (running) {
            onStateChanged = onState
            onState(isActive(), pending)
            return
        }
        running = true
        onStateChanged = onState
        TrustLiveCapture.installAll()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            val uid = try {
                YuzGramAdsAuth.ensureSignedIn(account)
            } catch (e: Exception) {
                Log.w(TAG, "signIn: ${e.message}")
                null
            }
            if (uid.isNullOrBlank()) return@launch
            inboxListener = TrustStore.listenInbox(uid) { list ->
                pending = list.count { it.isPending }
                val sharing = list.filter { it.isActive && it.shareMine }
                incomingActive = sharing.isNotEmpty()
                // Jonli ushlash faqat ruxsat bor paytda ishlaydi; watermark
                // eng erta rozilik vaqtidan olinadi.
                val since = sharing
                    .mapNotNull { it.updatedAt.takeIf { t -> t > 0L } }
                    .minOrNull() ?: 0L
                TrustLiveCapture.setSharing(account, incomingActive, since)
                main.post { onStateChanged?.invoke(isActive(), pending) }
            }
            sentListener = TrustStore.listenSent(uid) { list ->
                outgoingActive = list.any { it.isActive }
                main.post { onStateChanged?.invoke(isActive(), pending) }
            }
            main.postDelayed(ticker, 3_000L)
        }
    }

    fun stop() {
        running = false
        main.removeCallbacks(ticker)
        inboxListener?.remove()
        inboxListener = null
        sentListener?.remove()
        sentListener = null
        scope?.cancel()
        scope = null
    }
}