package uz.kodlikino.top4.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.BitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import org.telegram.messenger.R
import uz.kodlikino.top4.utils.YuzArtwork
import uz.kodlikino.top4.utils.YuzMusicEngine

/**
 * YuzGram #67: fon ijrosi xizmati — media3 MediaSessionService.
 * Kuylab turgan ExoPlayer'ni (YuzMusicEngine) MediaSession'ga o'raydi:
 * bildirdi-chnyochi + qulf ekranidagi boshqaruv tugmalari shu orqali, va
 * ekran bloklanganda / ilovadan chiqib ketganda ham ijro davom etadi
 * (foregroundServiceType=mediaPlayback).
 *
 * Dvigatel singleton — service o'lsa ham player yashaydi; aksincha ham.
 */
class YuzMusicService : MediaSessionService() {

    private var session: MediaSession? = null
    private var enginePlayer: Player? = null

    // #141: media3 o'z MediaStyle bildirishnomasini (oldingi/ijro/keyingi
    // tugmalari bilan) faqat session xizmatga addSession() qilingan bo'lsa
    // quradi. Ilgari bu chaqiruv yo'q edi — shu sababli ilovadan chiqqach
    // faqat tugmasiz zaxira bildirishnoma qolardi. Controller ulanishi
    // asinxron, shuning uchun birinchi soniyalarda bir necha marta
    // onUpdateNotification bilan majburlaymiz; 5s FGS muddati tugamasidan
    // oxirgi chora sifatida oddiy bildirishnoma qo'yamiz.
    private var promoteAttempts = 0
    private var emergencyPosted = false
    private var endedSince = 0L

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            val p = enginePlayer
            if (p == null || playbackFinished(p)) {
                runCatching { stopSelf() }
                return
            }
            val posted = mediaNotificationPosted()
            if (posted) {
                promoteAttempts = 0
            } else {
                session?.let { s -> runCatching { onUpdateNotification(s, true) } }
                promoteAttempts++
                if (!emergencyPosted && promoteAttempts >= EMERGENCY_AFTER) {
                    emergencyPosted = true
                    postEmergencyNotification()
                }
            }
            watchdogHandler.postDelayed(this, if (posted || emergencyPosted) IDLE_POLL_MS else BOOT_POLL_MS)
        }
    }

    private fun mediaNotificationPosted(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        val own = runCatching { nm.activeNotifications }
            .getOrNull()?.firstOrNull { it.id == YuzMusicNotification.ID } ?: return false
        // Zaxira bildirishnoma ham shu id'da bo'ladi — MediaStyle'da esa
        // oldingi/ijro/keyingi tugmalari bor.
        return !emergencyPosted || !own.notification.actions.isNullOrEmpty()
    }

    private fun playbackFinished(p: Player): Boolean {
        when (p.playbackState) {
            Player.STATE_IDLE -> return true
            Player.STATE_ENDED -> {
                if (!p.playWhenReady) return true
                val now = SystemClock.elapsedRealtime()
                if (endedSince == 0L) endedSince = now
                // Radio navbatni to'ldirishga ulgursin.
                return now - endedSince > ENDED_GRACE_MS
            }
            else -> endedSince = 0L
        }
        return false
    }

    private fun postEmergencyNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    YuzMusicNotification.CHANNEL_ID,
                    getString(R.string.musiqa_notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val n = NotificationCompat.Builder(this, YuzMusicNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.player)
            .setContentTitle(getString(R.string.musiqa_notif_channel))
            .setContentText("YuzGram musiqa")
            .setOngoing(true)
            .setSilent(true)
            .setColor(ContextCompat.getColor(this, R.color.yuz_action))
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 43,
                    Intent(this, YuzPlayerHostActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        runCatching { startForeground(YuzMusicNotification.ID, n) }
    }

    override fun onCreate() {
        // #87: o'z provider'imiz — media3'ning sukut bildirishnomasi o'rniga.
        // Xizmat uni session build() qilingan payt o'qiydi, shuning uchun
        // build()'dan oldin bo'lishi yetarli.
        setMediaNotificationProvider(YuzMusicNotification(this))
        super.onCreate()
        val player = YuzMusicEngine.ensurePlayer(applicationContext)
        enginePlayer = player
        // #69: bildirishnoma/qulf ekranidagi qatorga bosish to'liq
        // ekranli plerni ochadi (shaffof host orqali).
        val builder = MediaSession.Builder(this, player)
        builder.setSessionActivity(
            PendingIntent.getActivity(
                this, 42,
                Intent(this, YuzPlayerHostActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        // #87: media3 1.0.2 muqovani faqat BitmapLoader orqali oladi, uning
        // sukut implentatsiyasi esa http(s) URL'larni tortmaydi — shuning
        // uchun o'zimizniki (kesh + async; tayyor bo'lgach bildirishnoma
        // o'z-o'zidan qayta quriladi).
        builder.setBitmapLoader(object : BitmapLoader {
            override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
                YuzArtwork.load(applicationContext, uri.toString())

            override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
                YuzArtwork.decode(data)
        })
        val built = builder.build()
        session = built
        // #141: media3'ning notification manager'i shu chaqiruvsiz session'ni
        // umuman tanimaydi — na MediaStyle bildirishnoma, na startForeground.
        addSession(built)
        watchdogHandler.postDelayed(watchdog, BOOT_POLL_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Foydalanuvchi ilovani "aylanmagan" qilsa ham musiqa to'xtamaydi;
        // pauza qilingan bo'lsa xizmatni to'xtatamiz. ENDED holatda esa
        // radio navbatni to'ldirishi mumkin — qarorni watchdog qabul qiladi
        // (STATE_ENDED + playWhenReady u yerda grace bilan kuzatiladi).
        val p = enginePlayer
        if (session == null || p == null || !p.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(watchdog)
        runCatching { session?.release() }
        session = null
        enginePlayer = null
        YuzMusicEngine.onMusicServiceStopped()
        super.onDestroy()
    }

    private companion object {
        const val BOOT_POLL_MS = 700L
        const val IDLE_POLL_MS = 4000L
        const val EMERGENCY_AFTER = 5
        const val ENDED_GRACE_MS = 20000L
    }
}
