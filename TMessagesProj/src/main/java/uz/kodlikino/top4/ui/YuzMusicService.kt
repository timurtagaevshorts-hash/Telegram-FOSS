package uz.kodlikino.top4.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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

    // #122b: media3 1.0.2 xizmatni startForeground()ga faqat o'z
    // bildirishnomasi tuzilganda ko'taradi; session qo'shilgan payt player
    // ALLAQACHON o'ynayotgan bo'lsa esa updateNotification'ni qayta
    // ishga tushirmaydi — natijada 5s ichida startForeground() bo'lmaydi va
    // Android 14 ForegroundServiceDidNotStartInTimeException otadi (pult
    // skrinshotlari 10:58 va 12:08). MediaSessionService.onStartCommand
    // FINAL — shuning uchun onCreate'dan o'zimizni-qaayta rejalashtiruvchi
    // nazoratchi yurgizamiz: 2.5s'da tekshiramiz, kuylayotgan bo'lsa va
    // media3 hali bildirishnoma qo'ymagan bo'lsa o'zimiz qo'yib
    // startForeground qilamiz; kuylamayotgan bo'lsa stopSelf (talab bekor).
    private val fallbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val fallbackStart = object : Runnable {
        override fun run() {
            promoteOrStop()
            val p = enginePlayer
            if (p != null && (p.playWhenReady || p.isPlaying)) {
                fallbackHandler.postDelayed(this, 2500L)
            }
        }
    }

    private fun promoteOrStop() {
        val p = enginePlayer
        if (p == null || !(p.playWhenReady || p.isPlaying) || p.playbackState == Player.STATE_ENDED) {
            // Kuylamayapti — xizmatni tinch yo'amiz (startForeground
            // talabi ham shu bilan bekor bo'ladi).
            runCatching { stopSelf() }
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        if (nm?.activeNotifications?.any { it.id == YuzMusicNotification.ID } == true) return
        val ch = android.app.NotificationChannel(
            YuzMusicNotification.CHANNEL_ID,
            getString(R.string.musiqa_notif_channel),
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        runCatching { nm?.createNotificationChannel(ch) }
        val n = androidx.core.app.NotificationCompat.Builder(this, YuzMusicNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.player)
            .setContentTitle(getString(R.string.musiqa_notif_channel))
            .setContentText("YuzGram musiqa")
            .setOngoing(true)
            .setSilent(true)
            .setColor(ContextCompat.getColor(this, R.color.yuz_action))
            .build()
        runCatching { startForeground(YuzMusicNotification.ID, n) }
            .onFailure { runCatching { stopSelf() } }
    }

    override fun onCreate() {
        // #87: o'z provider'imiz — media3'ning sukut bildirishnomasi o'rniga.
        // Xizmat uni session build() qilingan payt o'qiydi, shuning uchun
        // build()'dan oldin bo'lishi yetarli.
        setMediaNotificationProvider(YuzMusicNotification(this))
        super.onCreate()
        // #122b: nazoratchi xizmat yashagan davom ishlaydi (o'zini qayta
        // rejalashtiradi); startForeground talabi 5s — birinchi tekshiruv
        // 2.5s'da, yetarli.
        fallbackHandler.postDelayed(fallbackStart, 2500L)
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
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Foydalanuvchi ilovani "aylanmagan" qilsa ham musiqa to'xtamaydi;
        // kuylamayotgan bo'lsa xizmatni to'xtatamiz (ijro tugagan=END ham).
        val p = enginePlayer
        if (session == null || p == null || !p.playWhenReady
            || p.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        fallbackHandler.removeCallbacks(fallbackStart) // #122b
        runCatching { session?.release() }
        session = null
        enginePlayer = null
        super.onDestroy()
    }
}
