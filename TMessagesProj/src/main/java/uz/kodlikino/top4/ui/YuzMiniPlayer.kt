package uz.kodlikino.top4.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import com.bumptech.glide.Glide
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.LaunchActivity
import uz.kodlikino.top4.utils.YuzMusicEngine

/**
 * YuzGram #69: global mini-pleyer — ilovaning istalgan joyida (chatlar,
 * funksiyalar, profil...) tepada kichik ko'rsatkich: muqova, nom, progress
 * chizig'i va play/pause. Bosilsa to'liq ekranli pleyer ochiladi.
 *
 * YuzMusicEngine.ensurePlayer() birinchi qo'ng'irog'ida Application
 * lifecycle callback sifatida ro'yxatdan o'tadi (engine'dan), shu yerda
 * LaunchActivity resum'da decor kontentiga qo'shiladi, pause'da olinadi.
 */
object YuzMiniPlayer : Application.ActivityLifecycleCallbacks, YuzMusicEngine.Observer {

    private var bar: View? = null
    private var host: LaunchActivity? = null
    private var ivCover: ImageView? = null
    private var tvTitle: TextView? = null
    private var tvArtist: TextView? = null
    private var progress: View? = null
    private var btnPlay: ImageButton? = null

    private val suppressed = HashSet<Any>()
    private var manualHide = false
    private val ui = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 400)
        }
    }

    // ------------------------------------------------------- lifecycle

    override fun onActivityResumed(activity: Activity) {
        if (activity is LaunchActivity) attach(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity === host) detach()
    }

    override fun onActivityCreated(a: Activity, s: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}

    // ------------------------------------------------------- attach

    private fun attach(a: LaunchActivity) {
        if (bar != null && host === a) {
            update()
            return
        }
        detach()
        host = a
        val content = a.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            ?: return
        val v = LayoutInflater.from(a)
            .inflate(R.layout.view_yuz_mini_player, content, false)
        ivCover = v.findViewById(R.id.ivMiniCover)
        ivCover?.imageTintList = null
        tvTitle = v.findViewById(R.id.tvMiniTitle)
        tvArtist = v.findViewById(R.id.tvMiniArtist)
        progress = v.findViewById(R.id.miniProgress)
        progress?.pivotX = 0f
        btnPlay = v.findViewById(R.id.btnMiniPlay)
        v.findViewById<View>(R.id.btnMiniPlay).setOnClickListener { toggle() }
        v.findViewById<View>(R.id.btnMiniClose).setOnClickListener {
            manualHide = true
            update()
        }
        v.findViewById<View>(R.id.miniRoot).setOnClickListener { openPlayer() }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP
            setMargins(AndroidUtilities.dp(8f), AndroidUtilities.dp(2f),
                AndroidUtilities.dp(8f), 0)
        }
        YuzTopInset.applyPadding(v)
        content.addView(v, lp)
        bar = v
        v.visibility = View.GONE
        v.translationY = -AndroidUtilities.dp(24f).toFloat()
        YuzMusicEngine.addObserver(this)
        ui.removeCallbacks(tick)
        ui.post(tick)
        update()
    }

    private fun detach() {
        ui.removeCallbacks(tick)
        bar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        if (bar != null) YuzMusicEngine.removeObserver(this)
        bar = null
        host = null
    }

    // ------------------------------------------------------- state

    fun setSuppressed(token: Any, on: Boolean) {
        if (on) suppressed.add(token) else suppressed.remove(token)
        if (bar != null) update()
    }

    private fun toggle() {
        val p = YuzMusicEngine.playerOrNull() ?: return
        if (p.currentMediaItem == null) return
        if (p.isPlaying) p.pause() else p.playWhenReady = true
    }

    private fun openPlayer() {
        LaunchActivity.instance?.presentFragment(YuzPlayerActivity())
    }

    private fun update() {
        val b = bar ?: return
        val track = YuzMusicEngine.currentTrack()
        val show = track != null && suppressed.isEmpty() && !manualHide
        if (!show) {
            if (b.visibility != View.GONE) {
                b.animate().alpha(0f).translationY(-AndroidUtilities.dp(24f).toFloat())
                    .setDuration(130L).withEndAction { b.visibility = View.GONE }.start()
            }
            return
        }
        val p = YuzMusicEngine.playerOrNull()
        b.findViewById<ImageButton>(R.id.btnMiniPlay).setImageResource(
            if (p != null && p.isPlaying) R.drawable.ic_yuz_pause else R.drawable.ic_yuz_play
        )
        tvTitle?.text = track!!.title
        tvArtist?.text = YuzMusicEngine.lastStatusText() ?: track.artist
        if (track.coverUrl.isNotBlank()) {
            ivCover?.let { Glide.with(it).load(track.coverUrl).into(it) }
        }
        if (b.visibility != View.VISIBLE) {
            b.alpha = 0f
            b.visibility = View.VISIBLE
            b.animate().alpha(1f).translationY(0f).setDuration(160L).start()
        }
        updateProgress()
    }

    private fun updateProgress() {
        val p = YuzMusicEngine.playerOrNull() ?: run { progress?.scaleX = 0f; return }
        val d = p.duration
        progress?.scaleX = if (d > 0) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
    }

    // ------------------------------------------------------- observer

    override fun onItemChanged(item: MediaItem?) {
        manualHide = false
        if (bar != null) update()
    }

    override fun onPlayingChanged(playing: Boolean) {
        if (bar != null) ui.post { update() }
    }

    override fun onStatus(text: String?) {
        if (text == null && YuzMusicEngine.currentTrack() == null) return
        if (bar != null) update()
    }
}
