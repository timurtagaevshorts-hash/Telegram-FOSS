package uz.kodlikino.top4.ui

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.utils.YuzMusicEngine

/**
 * "Funksiyalar" hub — YuzGram pastki navigatsiyasining 3-tab'i (sobiq "Elonlar").
 * Bir ekrandan ichki bo'limlarga kiradi: Elonlar, Ish kartasi, Instagram video.
 * Har bir bo'lim alohida [BaseFragment] sifatida ustga qo'yiladi (presentFragment),
 * orqaga qaytish hub'ga olib keladi. Pastki bar LaunchActivity darajasida doimiy.
 */
class FunksiyalarActivity : BaseFragment() {

    private var musicObserver: YuzMusicEngine.Observer? = null

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_funksiyalar, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        // Pastki bar: ro'yxatni pastga surganda g'oyib, tepaga surganda darhol
        // qaytadi (sahifa tepasiga chiqish shart emas).
        if (root is NestedScrollView) {
            YuzGramBarAutoHide.attachTo(root, this)
        }

        // Status bari bilan tepada "bosh qora joy" qolmasin — haqiqiy inset.
        YuzTopInset.applyPadding(root.getChildAt(0))

        root.findViewById<View>(R.id.cardAi)?.setOnClickListener {
            presentFragment(YuzAiActivity(), false)
        }
        root.findViewById<View>(R.id.cardElonlar)?.setOnClickListener {
            presentFragment(ElonlarActivity(), false)
        }
        root.findViewById<View>(R.id.cardIsh)?.setOnClickListener {
            presentFragment(JobCardsActivity(), false)
        }
        root.findViewById<View>(R.id.cardMusiqa)?.setOnClickListener {
            presentFragment(YuzMusicActivity(), false)
        }
        root.findViewById<View>(R.id.cardInstagram)?.setOnClickListener {
            presentFragment(InstagramSaveActivity(), false)
        }
        root.findViewById<View>(R.id.cardKinolar)?.setOnClickListener {
            presentFragment(KinolarActivity(), false)
        }

        playIntro(root)
        return root
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        if (musicObserver == null) {
            musicObserver = object : YuzMusicEngine.Observer {
                override fun onItemChanged(item: MediaItem?) {
                    AndroidUtilities.runOnUIThread { updateMusicState() }
                }

                override fun onPlayingChanged(playing: Boolean) {
                    AndroidUtilities.runOnUIThread { updateMusicState() }
                }
            }.also { YuzMusicEngine.addObserver(it) }
        }
        updateMusicState()
    }

    override fun onPause() {
        musicObserver?.let { YuzMusicEngine.removeObserver(it) }
        musicObserver = null
        super.onPause()
    }

    /** Musiqa plitkasida jonli holat: ijro / buffer / pauza. */
    private fun updateMusicState() {
        val root = fragmentView ?: return
        val desc = root.findViewById<TextView>(R.id.musicTileDesc) ?: return
        val eq = root.findViewById<ImageView>(R.id.musicTileEq) ?: return
        val track = YuzMusicEngine.currentTrack()
        val player = YuzMusicEngine.playerOrNull()
        if (track == null || player?.currentMediaItem == null ||
            player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED
        ) {
            desc.text = root.context.getString(R.string.func_musiqa_desc)
            eq.visibility = View.GONE
            return
        }
        val playing = player.isPlaying
        val textRes = when {
            playing -> R.string.func_musiqa_playing_desc
            player.playbackState == Player.STATE_BUFFERING -> R.string.func_musiqa_buffering_desc
            else -> R.string.func_musiqa_paused_desc
        }
        desc.text = root.context.getString(textRes, track.title)
        eq.visibility = if (playing) View.VISIBLE else View.GONE
    }

    /** Bitta sahifa kirish momenti: ketma-ket, qisqa fade+rise. */
    private fun playIntro(root: View) {
        val content = (root as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return
        if (!animatorsEnabled(root.context)) return
        val distance = 12f * root.resources.displayMetrics.density
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i) ?: continue
            child.alpha = 0f
            child.translationY = distance
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(40L + i * 40L)
                .setDuration(300L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun animatorsEnabled(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) > 0f
        }

    /**
     * #80: Profil tab'i root'da orqaga bosilsa — ilovadan chiqishdan oldin
     * Chat bo'limiga qaytamiz. `false` = tugmani ishlatdik, fragment yopilmaydi.
     */
    override fun onBackPressed(): Boolean {
        if (YuzGramNav.isTabRoot(this)) {
            YuzGramNav.select(this, YuzGramBottomBar.TAB_ASOSIY)
            return false
        }
        return super.onBackPressed()
    }
}
