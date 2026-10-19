package uz.kodlikino.top4.ui

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.BitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
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
        runCatching { session?.release() }
        session = null
        enginePlayer = null
        super.onDestroy()
    }
}
