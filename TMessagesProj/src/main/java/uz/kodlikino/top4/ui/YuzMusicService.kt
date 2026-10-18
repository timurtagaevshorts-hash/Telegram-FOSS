package uz.kodlikino.top4.ui

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
        super.onCreate()
        val player = YuzMusicEngine.ensurePlayer(applicationContext)
        enginePlayer = player
        val launcher = packageManager.getLaunchIntentForPackage(packageName)
        val builder = MediaSession.Builder(this, player)
        if (launcher != null) {
            builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launcher, PendingIntent.FLAG_IMMUTABLE)
            )
        }
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
