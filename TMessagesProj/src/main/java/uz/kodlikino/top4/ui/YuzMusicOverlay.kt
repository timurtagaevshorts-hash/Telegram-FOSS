package uz.kodlikino.top4.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.bumptech.glide.Glide
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import uz.kodlikino.top4.utils.YuzAnim
import uz.kodlikino.top4.utils.YuzMusicEngine

/**
 * Ilova fonda bo'lganda musiqa boshqaruvi — ekranning yuqori qismida shaffof
 * ixcham parda (orqaga / ijro-pauza / oldinga). Faqat musiqa HAQIQATAN ijro
 * etilayotganda (playWhenReady) va "ustidan chizish" ruxsati berilgan bo'lsa
 * ko'rinadi; pauza qilinsa yoki ilovaga qaytilganda yashiriladi.
 */
object YuzMusicOverlay : Application.ActivityLifecycleCallbacks, YuzMusicEngine.Observer {

    private val ui = Handler(Looper.getMainLooper())
    private var app: Application? = null
    private var view: View? = null
    private var ivCover: ImageView? = null
    private var tvTitle: TextView? = null
    private var tvArtist: TextView? = null
    private var btnPlay: ImageButton? = null
    private var progress: View? = null
    private var started = 0
    private var visible = false
    private var dismissed = false
    private var listenedPlayer: Player? = null

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            if (visible) ui.postDelayed(this, 500L)
        }
    }

    /** Engine bir marta chaqiradi (pleyer yaratilganda). */
    fun install(context: Context) {
        if (app != null) return
        val a = context.applicationContext as? Application ?: return
        app = a
        a.registerActivityLifecycleCallbacks(this)
        YuzMusicEngine.addObserver(this)
    }

    // ------------------------------------------------------- lifecycle

    override fun onActivityStarted(activity: Activity) {
        started++
        ui.post {
            dismissed = false
            reconcile()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        ui.post { reconcile() }
    }

    override fun onActivityResumed(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityCreated(a: Activity, s: Bundle?) {}
    override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}

    // ------------------------------------------------------- observer

    override fun onItemChanged(item: MediaItem?) {
        ui.post {
            dismissed = false
            reconcile()
        }
    }

    override fun onPlayingChanged(playing: Boolean) {
        ui.post { reconcile() }
    }

    override fun onPreparingChanged(preparing: Boolean) {
        ui.post { reconcile() }
    }

    override fun onStatus(text: String?) {
        ui.post { if (visible) update() }
    }

    // ------------------------------------------------------- window

    private fun canShow(): Boolean {
        val a = app ?: return false
        if (started > 0 || dismissed) return false
        if (!Settings.canDrawOverlays(a)) return false
        val p = YuzMusicEngine.playerOrNull() ?: return false
        if (p.currentMediaItem == null) return false
        // Faqat HAQIQIY ijro paytida: pauza (yoki tayyor emas) holatda
        // boshqa ilovalar ustida boshqaruv paneli qolmasin.
        if (!p.playWhenReady) return false
        if (p.playbackState == Player.STATE_IDLE ||
            p.playbackState == Player.STATE_ENDED
        ) return false
        return YuzMusicEngine.currentTrack() != null
    }

    /** Pleyer holati o'zgarishlarini (pauza/pley) to'g'ridan-to'g'ri tinglaymiz:
     *  buferlash paytida pauza qilinsa ham parda darhol yashiriladi. */
    private fun listen(p: Player) {
        if (listenedPlayer === p) return
        listenedPlayer = p
        p.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                ui.post { reconcile() }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                ui.post { reconcile() }
            }
        })
    }

    private fun reconcile() {
        YuzMusicEngine.playerOrNull()?.let { listen(it) }
        if (canShow()) {
            show()
            update()
        } else {
            hide()
        }
    }

    private fun show() {
        val a = app ?: return
        if (view == null) {
            val v = LayoutInflater.from(a).inflate(R.layout.view_yuz_music_overlay, null)
            ivCover = v.findViewById(R.id.ivOverlayCover)
            tvTitle = v.findViewById(R.id.tvOverlayTitle)
            tvArtist = v.findViewById(R.id.tvOverlayArtist)
            btnPlay = v.findViewById(R.id.btnOverlayPlay)
            progress = v.findViewById(R.id.overlayProgress)
            progress?.pivotX = 0f
            v.findViewById<View>(R.id.btnOverlayPrev).setOnClickListener {
                YuzMusicEngine.playerOrNull()?.seekToPrevious()
            }
            v.findViewById<View>(R.id.btnOverlayPlay).setOnClickListener {
                YuzMusicEngine.togglePlay(a)
            }
            v.findViewById<View>(R.id.btnOverlayNext).setOnClickListener {
                YuzMusicEngine.playerOrNull()?.seekToNextMediaItem()
            }
            v.findViewById<View>(R.id.btnOverlayClose).setOnClickListener {
                dismissed = true
                hide()
            }
            v.findViewById<View>(R.id.overlayCard).setOnClickListener {
                runCatching {
                    a.startActivity(
                        Intent(a, YuzPlayerHostActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            val wm = a.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            val added = runCatching { wm.addView(v, windowParams()) }.isSuccess
            if (!added) return
            v.visibility = View.GONE
            view = v
        }
        val v = view ?: return
        if (visible) return
        v.animate().cancel()
        visible = true
        v.visibility = View.VISIBLE
        v.alpha = 0f
        v.translationY = -AndroidUtilities.dp(12f).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(180L).start()
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    private fun hide() {
        val v = view ?: return
        if (!visible) {
            if (v.visibility != View.GONE) v.visibility = View.GONE
            return
        }
        visible = false
        ui.removeCallbacks(tick)
        v.animate().cancel()
        v.animate().alpha(0f).translationY(-AndroidUtilities.dp(12f).toFloat())
            .setDuration(140L)
            .withEndAction { if (!visible) v.visibility = View.GONE }
            .start()
    }

    private fun windowParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(6f)
        }
    }

    private fun update() {
        val track = YuzMusicEngine.currentTrack() ?: return
        tvTitle?.text = track.title
        tvArtist?.text = YuzMusicEngine.lastStatusText() ?: track.artist
        val p = YuzMusicEngine.playerOrNull()
        if (YuzMusicEngine.isPreparing()) {
            YuzAnim.setPlayLoading(btnPlay, true)
        } else {
            YuzAnim.setPlayLoading(btnPlay, false)
            btnPlay?.setImageResource(
                if (p != null && p.isPlaying) R.drawable.ic_yuz_pause
                else R.drawable.ic_yuz_play
            )
        }
        if (track.coverUrl.isNotBlank()) {
            ivCover?.let { Glide.with(it).load(track.coverUrl).into(it) }
        } else {
            ivCover?.setImageDrawable(null)
        }
        updateProgress()
    }

    private fun updateProgress() {
        val p = YuzMusicEngine.playerOrNull() ?: run {
            progress?.scaleX = 0f
            return
        }
        val d = p.duration
        progress?.scaleX =
            if (d > 0) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
    }
}
