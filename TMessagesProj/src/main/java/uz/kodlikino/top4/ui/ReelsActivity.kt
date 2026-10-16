package uz.kodlikino.top4.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.utils.ReelsRepository

/**
 * "Reels" bo'limi — YuzGram pastki navigatsiyasining alohida tab'i.
 *
 * KinoTOP4'dagi vertikal video feed shu Telegram [BaseFragment] ichiga
 * [ContextThemeWrapper] (Theme.YuzGramAds) ostida joylashtirildi:
 *  - vertikal [RecyclerView] + [PagerSnapHelper] — bitta sahifa butun ekranga
 *    "yopishadi" (oldingi loyihadagi reels feed xulqiyati); sahifalar [ReelsAdapter]
 *    orqali, video esa BITTA ExoPlayer bilan ishlaydi (quvvat/tejamkorlik);
 *  - feed Firestore `reels` kolleksiyasidan o'qiladi — KinoTOP4 bilan umumiy tasma;
 *  - like/ko'rish Firestore'ga yoziladi (rules `views`/`likeCount` update'ga ruxsat beradi);
 *  - "Reel joylash" -> [CreateReelActivity].
 *
 * Nima uchun ViewPager2 EMAS: Telegram `androidx.recyclerview` AAR'ini exclude qilib,
 * o'z (vendored) nusxasidan foydalanadi. ViewPager2 esa rasmiy recyclerview 1.0.0 bilan
 * kompilyatsiya qilingan — API nommosligi runtime'da NoSuchMethodError bo'lib chiqishi
 * mumkin. Bu yerda faqat shu repodalla sinovdan o'tgan klasslar ishlatiladi.
 *
 * Pastki bar LaunchActivity darajasida (doimiy) — bu ekran o'z barini qo'shmaydi.
 */
class ReelsActivity : BaseFragment() {

    private var themedContext: Context? = null
    private var reelsList: RecyclerView? = null
    private var layoutManager: LinearLayoutManager? = null
    private var snapHelper: PagerSnapHelper? = null
    private var reelsTopBar: View? = null
    private var muteButton: TextView? = null
    private var progressBar: ProgressBar? = null
    private var emptyView: TextView? = null
    private var adapter: ReelsAdapter? = null

    private var scope: CoroutineScope? = null
    private var loading = false
    private var currentReels: List<Reel> = emptyList()
    private var selectedPosition = 0

    private fun currentUid(): String {
        val id = UserConfig.getInstance(currentAccount).clientUserId
        return if (id > 0L) "tg_$id" else ""
    }

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_reels, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE

        reelsList = root.findViewById(R.id.rvReels)
        reelsTopBar = root.findViewById(R.id.reelsTopBar)
        muteButton = root.findViewById(R.id.btnMute)
        progressBar = root.findViewById(R.id.progressReels)
        emptyView = root.findViewById(R.id.emptyReels)

        // Status bari ostiga tushmasligi uchun overlay'ga tepa bo'shliq.
        reelsTopBar?.let { bar ->
            bar.setPadding(
                bar.paddingLeft,
                AndroidUtilities.statusBarHeight + bar.paddingTop,
                bar.paddingRight,
                bar.paddingBottom
            )
        }

        val reelsAdapter = ReelsAdapter(
            appContext = themed,
            onLikeToggle = { reelId, likedNow -> toggleLike(reelId, likedNow) },
            onViewed = { reelId -> registerView(reelId) }
        )
        adapter = reelsAdapter

        val lm = LinearLayoutManager(themed, RecyclerView.VERTICAL, false)
        layoutManager = lm
        val snap = PagerSnapHelper()
        snapHelper = snap
        reelsList?.let { list ->
            list.layoutManager = lm
            list.adapter = reelsAdapter
            list.clipToPadding = false
            snap.attachToRecyclerView(list)
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                    if (state == RecyclerView.SCROLL_STATE_IDLE) {
                        onPageSettled()
                    }
                }
            })
        }

        root.findViewById<View>(R.id.btnPostReel)?.setOnClickListener { openCreate() }

        // Ovoz tugmasi — feed davomida bitta player ishlatilgani uchun holat
        // [ReelsAdapter]da saqlanadi (sahifa almashganda ham yo'qolmaydi).
        muteButton?.let { btn ->
            btn.setOnClickListener {
                val isMuted = reelsAdapter.toggleMute()
                btn.setText(if (isMuted) R.string.reels_sound_off else R.string.reels_sound_on)
            }
            btn.setText(if (reelsAdapter.isMuted()) R.string.reels_sound_off else R.string.reels_sound_on)
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        loadReels()
        return root
    }

    /** Snap tugagach: faol sahifani aniqlaymiz va videoni almashtiramiz. */
    private fun onPageSettled() {
        val lm = layoutManager ?: return
        val snap = snapHelper ?: return
        val view = snap.findSnapView(lm) ?: return
        val position = lm.getPosition(view)
        if (position < 0 || position == selectedPosition) return
        selectedPosition = position
        adapter?.setActivePosition(position)
        refreshLikeState(currentReels.getOrNull(position)?.id)
    }

    private fun openCreate() {
        presentFragment(CreateReelActivity(), false)
    }

    private fun loadReels() {
        if (loading) return
        val s = scope ?: return
        val keepPosition = selectedPosition
        loading = true
        if (currentReels.isEmpty()) progressBar?.visibility = View.VISIBLE
        s.launch {
            val reels = ReelsRepository.getReels()
            loading = false
            if (fragmentView == null) return@launch
            currentReels = reels
            adapter?.submit(reels)
            progressBar?.visibility = View.GONE
            val isEmpty = reels.isEmpty()
            emptyView?.visibility = if (isEmpty) View.VISIBLE else View.GONE
            reelsList?.visibility = if (isEmpty) View.GONE else View.VISIBLE
            if (!isEmpty) {
                val target = keepPosition.coerceIn(0, reels.size - 1)
                selectedPosition = target
                layoutManager?.scrollToPositionWithOffset(target, 0)
                adapter?.setActivePosition(target)
                refreshLikeState(reels.getOrNull(target)?.id)
            }
        }
    }

    /** Faol reel uchun like holatini Firestore'dan o'qiydi (bitta hujjat get()). */
    private fun refreshLikeState(reelId: String?) {
        val uid = currentUid()
        if (reelId.isNullOrBlank() || uid.isBlank()) return
        val s = scope ?: return
        s.launch {
            val liked = ReelsRepository.isLiked(reelId, uid)
            if (fragmentView == null) return@launch
            adapter?.applyLikeState(reelId, liked)
        }
    }

    private fun toggleLike(reelId: String, likedNow: Boolean) {
        val uid = currentUid()
        if (uid.isBlank()) {
            toast(R.string.reels_sign_in_required)
            return
        }
        val s = scope ?: return
        s.launch {
            try {
                ReelsRepository.setLiked(reelId, uid, likedNow)
            } catch (e: Exception) {
                // Optimistik UI'ni orqaga qaytaramiz.
                if (fragmentView != null) adapter?.applyLikeState(reelId, !likedNow)
                toast(R.string.reels_like_failed)
            }
        }
    }

    private fun registerView(reelId: String) {
        val s = scope ?: return
        s.launch { ReelsRepository.registerView(reelId) }
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        if (fragmentView != null) {
            // CreateReel'dan qaytishda yoki ilova oldinga chiqqanda yangilaymiz.
            loadReels()
            adapter?.resumePlayback()
        }
    }

    override fun onPause() {
        adapter?.pausePlayback()
        super.onPause()
    }

    override fun onFragmentDestroy() {
        adapter?.release()
        adapter = null
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }

    private fun toast(text: String) {
        val ctx = parentActivity ?: context ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    private fun toast(resId: Int) {
        val ctx = themedContext ?: context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }
}
