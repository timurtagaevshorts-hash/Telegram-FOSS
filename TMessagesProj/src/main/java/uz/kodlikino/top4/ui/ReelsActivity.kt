package uz.kodlikino.top4.ui

import android.content.Context
import android.graphics.Rect
import android.net.ConnectivityManager
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.LaunchActivity
import org.telegram.ui.Stories.StoriesController
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.models.ReelComment
import uz.kodlikino.top4.utils.ReelStorageHelper
import uz.kodlikino.top4.utils.ReelsRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzGramProfileStore
import uz.kodlikino.top4.utils.YuzStoryFeed
import uz.kodlikino.top4.utils.YuzViewedReels
import uz.kodlikino.top4.utils.YuzReelsOrder

/**
 * "Reels" bo'limi — YuzGram pastki navigatsiyasining alohida tab'i.
 *
 * KinoTOP4'dagi vertikal video feed shu Telegram [BaseFragment] ichiga
 * [ContextThemeWrapper] (Theme.YuzGramAds) ostida joylashtirildi:
 *  - vertikal [RecyclerView] + [PagerSnapHelper] — bitta sahifa butun ekranga
 *    "yopishadi" (oldingi loyihadagi reels feed xulqiyati); sahifalar [ReelsAdapter]
 *    orqali, video esa BITTA ExoPlayer bilan ishlaydi (quvvat/tejamkorlik);
 *  - #106: feed Firestore'dan EMAS — [YuzStoryFeed] orqali Telegram hikoyalaridan
 *    quriladi (do'stlar/kontaktlar/kanallar/guruhlar + o'z arxivimiz); like =
 *    Telegram reaksiyasi, ko'rish = Telegram max_read_id, statistika hikoyaning
 *    o'zidan (views/reactions_count);
 *  - izohlar Instagram uslubida shu ekran ichidagi pastki panelida ochiladi va
 *    Firebase'da saqlanadi (`reels/{story_key}/comments`) — kalit barqaror:
 *    `story_{dialogId}_{storyId}`; izoh/reply/like bildirishnomalari ishlaydi;
 *  - "Reel joylash" -> Telegram StoryRecorder ([YuzStories.openRecorder]).
 *
 * Nima uchun ViewPager2 EMAS: Telegram `androidx.recyclerview` AAR'ini exclude qilib,
 * o'z (vendored) nusxasidan foydalanadi. ViewPager2 esa rasmiy recyclerview 1.0.0 bilan
 * kompilyatsiya qilingan — API nommosligi runtime'da NoSuchMethodError bo'lib chiqishi
 * mumkin. Bu yerda faqat shu repodalla sinovdan o'tgan klasslar ishlatiladi.
 *
 * Pastki bar LaunchActivity darajasida (doimiy) — bu ekran o'z barini qo'shmaydi.
 *
 * Profil grid'idan ochilganda [filterUid] beriladi — feed faqat shu foydalanuvchi-
 * ning reel'larini ko'rsatadi va [startIndex] sahifasidan boshlaydi. Bo'sh [filterUid]
 * — umumiy (barcha) feed, pastki "Reels" tab'i.
 */
class ReelsActivity @JvmOverloads constructor(
    private val filterUid: String = "",
    private val startIndex: Int = 0,
    /** #76: bildirishnomadan ochilganda — shu reel'ga scrolleymiz. */
    private val targetReelId: String = "",
    /** Target reel izoh/reply bildirishnomasi bo'lsa — izohlar panelini ham ochamiz. */
    private val openTargetComments: Boolean = false
) : BaseFragment() {

    private var themedContext: Context? = null
    private var reelsList: RecyclerView? = null
    private var layoutManager: LinearLayoutManager? = null
    private var snapHelper: PagerSnapHelper? = null
    private var reelsTopBar: View? = null
    private var reelsHeader: View? = null
    private var storyFab: FrameLayout? = null
    private var fsClose: View? = null
    private var muteButton: ImageView? = null
    private var progressBar: ProgressBar? = null
    private var emptyView: TextView? = null
    private var adapter: ReelsAdapter? = null
    private var storyStripAdapter: YuzStoryStripAdapter? = null

    // Izohlar paneli (ekran ichida — alohida dialog EMAS, qarang fragment_reels.xml)
    private var commentsScrim: View? = null
    private var commentsPanel: View? = null
    private var commentsTitle: TextView? = null
    private var commentsList: RecyclerView? = null
    private var commentsLayoutManager: LinearLayoutManager? = null
    private var commentsAdapter: ReelCommentsAdapter? = null
    private var commentsProgress: ProgressBar? = null
    private var commentsEmpty: TextView? = null
    private var commentInput: EditText? = null
    private var sendButton: View? = null
    private var replyHint: TextView? = null

    private var commentsOpen = false
    private var commentsLoading = false
    private var sendingComment = false
    private var commentsReelId = ""
    private var loadedCommentCount = 0
    private var replyTarget: ReelComment? = null
    private var panelBottomMarginNormal = 0
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var windowFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null

    private var scope: CoroutineScope? = null
    private var authWarmedUp = false
    private var authJob: Job? = null
    private var currentReels: List<Reel> = emptyList()
    private var selectedPosition = startIndex.coerceAtLeast(0)
    private var pendingTargetId = targetReelId
    private var feedOrder: YuzReelsOrder? = null
    private var playbackVisible = false

    // #106: Telegram hikoyalari signalidan keyin tasmani kechiktirib qayta
    // qurish (har bir storiesUpdated'da emas — debounce).
    private var storyRefresh: Runnable? = null

    // #99: tarmoq qayta urilganda feed/videoni avto-tiklash.
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** #70: reel to'liq ekranda ochilganmi (sarlavha/storiya qatori yashirin). */
    private var reelFullscreen = false
    private var savedSystemUi = -1

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
        YuzTopInset.collapseActionBar(actionBar)

        reelsList = root.findViewById(R.id.rvReels)
        reelsTopBar = root.findViewById(R.id.reelsTopBar)
        reelsHeader = root.findViewById(R.id.reelsHeader)
        storyFab = root.findViewById(R.id.storyFab)
        fsClose = root.findViewById(R.id.btnFsClose)
        muteButton = root.findViewById(R.id.btnMute)
        progressBar = root.findViewById(R.id.progressReels)
        emptyView = root.findViewById(R.id.emptyReels)
        // Bo'sh/xato holatida xabarni bosish qayta yuklaydi (tarmoq shu yerda beqaror).
        emptyView?.setOnClickListener { loadReels() }

        // Status bari bilan "bosh qora joy" qolmasin — haqiqiy inset bo'yicha
        // (edge-to-edge bo'lsa to'liq offset, aks holda 0).
        YuzTopInset.applyPadding(reelsTopBar)

        if (filterUid.isBlank() && feedOrder == null) feedOrder = YuzStoryFeed.takeOrder(themed, currentAccount)
        val reelsAdapter = ReelsAdapter(
            appContext = themed,
            account = currentAccount,
            onLikeToggle = { reelId, likedNow -> toggleLike(reelId, likedNow) },
            onViewed = { reelId -> registerView(reelId) },
            onOpenComments = { reelId -> openComments(reelId) },
            myUid = currentUid(),
            onDelete = { reel -> confirmDelete(reel) },
            onOpenProfile = { reel -> openUploaderProfile(reel) },
            onExpandTap = { enterReelFullscreen() }
        )
        adapter = reelsAdapter
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus) adapter?.pausePlayback()
            else if (playbackVisible && !isPaused && reelsList?.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                adapter?.resumePlayback()
            }
        }
        windowFocusListener = focusListener
        root.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)

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
                    } else {
                        adapter?.pausePlayback()
                    }
                }
            })
            // Pastki bar: keyingi reel'ga o'tganda (pastga) g'oyib, oldingisiga
            // qaytganda (tepaga) darhol ko'rinadi — butun ilovadagi yagona xulq.
            try {
                YuzGramBarAutoHide.attachTo(list, this)
            } catch (_: Exception) {
            }
        }

        root.findViewById<View>(R.id.btnPostReel)?.setOnClickListener { openCreate() }

        // Telegram uslubidagi hikoya doiralari (o'zimiz + yaqinda hikoya
        // joylagan profillar). Profil ichidan ochilganda (filterUid) ko'rsatilmaydi.
        setupStoryStrip(themed)
        fsClose?.setOnClickListener { exitReelFullscreen() }

        // Ovoz tugmasi — feed davomida bitta player ishlatilgani uchun holat
        // [ReelsAdapter]da saqlanadi (sahifa almashganda ham yo'qolmaydi).
        muteButton?.let { btn ->
            btn.setOnClickListener {
                val isMuted = reelsAdapter.toggleMute()
                btn.setImageResource(
                    if (isMuted) R.drawable.ic_yuz_volume_off else R.drawable.ic_yuz_volume_on
                )
            }
            btn.setImageResource(
                if (reelsAdapter.isMuted()) R.drawable.ic_yuz_volume_off else R.drawable.ic_yuz_volume_on
            )
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // #113: izoh paneli buzilsa ham VIDEO TASMASI ishlaveradi.
        try {
            setupComments(root, themed)
        } catch (_: Exception) {
        }
        loadReels()
        try {
            warmUpAuth()
        } catch (_: Exception) {
        }
        registerNetworkWatch()
        registerStoryWatch()
        return root
    }

    private fun setupStoryStrip(themed: Context) {
        val strip = fragmentView?.findViewById<RecyclerView>(R.id.rvStoryStrip) ?: return
        if (filterUid.isNotBlank()) {
            strip.visibility = View.GONE
            return
        }
        val stripAdapter = YuzStoryStripAdapter(
            context = themed,
            account = currentAccount,
            onSelfAvatar = { hasStories ->
                val selfId = UserConfig.getInstance(currentAccount).clientUserId
                if (!hasStories || !YuzStories.viewPeer(this, selfId)) openStoryRecorder()
            },
            onAddStory = { openStoryRecorder() },
            onPeer = { dialogId ->
                if (!YuzStories.viewPeer(this, dialogId)) toast(R.string.story_none)
            }
        )
        storyStripAdapter = stripAdapter
        strip.layoutManager = LinearLayoutManager(themed, RecyclerView.HORIZONTAL, false)
        strip.adapter = stripAdapter
        strip.itemAnimator = null
        refreshStoryStrip()
    }

    private fun openStoryRecorder() {
        val fab = storyFab ?: return
        YuzStories.openRecorder(this, fab)
    }

    /**
     * Doiralar tasmasini Telegram statidan quradi: o'zimiz (birinchi),
     * asosiy hikoya ro'yxati, so'ng dialog ro'yxatidagi qolgan barcha
     * peer'lar — shu bilan kontakt bo'lmagan, lekin hikoyasi bor profillar
     * ham ko'rinadi. Yashirilgan (hidden) hikoyalar chiqarilmaydi.
     */
    private fun refreshStoryStrip() {
        if (filterUid.isNotBlank()) return
        val stripAdapter = storyStripAdapter ?: return
        val account = currentAccount
        try {
            val sc: StoriesController = MessagesController.getInstance(account).getStoriesController()
            try {
                sc.checkExpiredStories()
            } catch (_: Exception) {
            }
            val selfId = UserConfig.getInstance(account).clientUserId
            val selfHasStories = try {
                sc.hasSelfStories()
            } catch (_: Exception) {
                false
            }
            val items = ArrayList<YuzStoryCircle>()
            items.add(
                YuzStoryCircle(
                    dialogId = selfId,
                    name = "",
                    unseen = false,
                    isSelf = true,
                    hasStories = selfHasStories
                )
            )
            val seen = HashSet<Long>()
            if (selfId != 0L) seen.add(selfId)
            val hidden = HashSet<Long>()
            try {
                sc.getHiddenList()?.forEach { ps ->
                    try {
                        hidden.add(DialogObject.getPeerDialogId(ps.peer))
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            try {
                sc.getDialogListStories()?.forEach { ps ->
                    val id = try {
                        DialogObject.getPeerDialogId(ps.peer)
                    } catch (_: Exception) {
                        0L
                    }
                    if (id == 0L || id == selfId || id in hidden || !seen.add(id)) {
                        return@forEach
                    }
                    addStoryCircle(items, sc, account, id)
                }
            } catch (_: Exception) {
            }
            try {
                for (dialog in MessagesController.getInstance(account).getAllDialogs()) {
                    val id = dialog.id
                    if (id == 0L || id == selfId || id in seen || id in hidden) {
                        continue
                    }
                    val has = try {
                        sc.hasStories(id)
                    } catch (_: Exception) {
                        false
                    }
                    if (!has) {
                        continue
                    }
                    seen.add(id)
                    addStoryCircle(items, sc, account, id)
                }
            } catch (_: Exception) {
            }
            stripAdapter.submit(items)
        } catch (_: Exception) {
        }
    }

    private fun addStoryCircle(
        out: ArrayList<YuzStoryCircle>,
        sc: StoriesController,
        account: Int,
        dialogId: Long
    ) {
        val unseen = try {
            sc.getUnreadStoriesCount(dialogId) > 0
        } catch (_: Exception) {
            false
        }
        val name = try {
            YuzStoryFeed.peerTitle(account, dialogId)
        } catch (_: Exception) {
            ""
        }
        out.add(YuzStoryCircle(dialogId, name, unseen, isSelf = false, hasStories = true))
    }

    /**
     * #106: Telegram hikoyalari o'zgarganda (yangi hikoya, reaksiya, arxiv
     * javobi kelganda) tasmani qayta quramiz. 500ms debounce — server
     * paket-paket signal yuboradi, har birida feed qayta tiklanmasin.
     */
    private fun registerStoryWatch() {
        try {
            val nc = NotificationCenter.getInstance(currentAccount)
            nc.addObserver(storyObserver, NotificationCenter.storiesUpdated)
            nc.addObserver(storyObserver, NotificationCenter.storiesListUpdated)
            NotificationCenter.getGlobalInstance().addObserver(storyObserver, NotificationCenter.passcodeDismissed)
        } catch (_: Exception) {
        }
    }

    private fun unregisterStoryWatch() {
        try {
            val nc = NotificationCenter.getInstance(currentAccount)
            nc.removeObserver(storyObserver, NotificationCenter.storiesUpdated)
            nc.removeObserver(storyObserver, NotificationCenter.storiesListUpdated)
            NotificationCenter.getGlobalInstance().removeObserver(storyObserver, NotificationCenter.passcodeDismissed)
        } catch (_: Exception) {
        }
        storyRefresh?.let { fragmentView?.removeCallbacks(it) }
        storyRefresh = null
    }

    private val storyObserver = NotificationCenter.NotificationCenterDelegate { id, account, _ ->
        if (id == NotificationCenter.passcodeDismissed) {
            if (playbackVisible && !isPaused && reelsList?.scrollState == RecyclerView.SCROLL_STATE_IDLE) {
                adapter?.resumePlayback()
            }
            return@NotificationCenterDelegate
        }
        if (account != currentAccount) return@NotificationCenterDelegate
        if (id == NotificationCenter.storiesUpdated || id == NotificationCenter.storiesListUpdated) {
            scheduleStoryRefresh()
        }
    }

    private fun scheduleStoryRefresh() {
        val view = fragmentView ?: return
        storyRefresh?.let { view.removeCallbacks(it) }
        val r = Runnable {
            storyRefresh = null
            if (fragmentView != null) {
                refreshStoryStrip()
                loadReels()
            }
        }
        storyRefresh = r
        view.postDelayed(r, 500L)
    }

    /**
     * Izohlar panelini ulaydi: ro'yxat, yopish, javob berish va yuborish tugmalari.
     * Panel balandligi va pastki bo'shliq qat'iy o'lchanadi — pastki navigatsiya
     * bari butun ekran ustida doimiy turgani uchun kiritish maydoni ostida qolmasligi
     * kerak (qarang [YuzGramBottomBar.height]).
     */
    private fun setupComments(root: View, themed: Context) {
        commentsScrim = root.findViewById(R.id.commentsScrim)
        commentsPanel = root.findViewById(R.id.commentsPanel)
        commentsTitle = root.findViewById(R.id.tvCommentsTitle)
        commentsList = root.findViewById(R.id.rvComments)
        commentsProgress = root.findViewById(R.id.progressComments)
        commentsEmpty = root.findViewById(R.id.emptyComments)
        commentInput = root.findViewById(R.id.etComment)
        sendButton = root.findViewById(R.id.btnSendComment)
        replyHint = root.findViewById(R.id.tvReplyHint)

        val panel = commentsPanel ?: return
        val lp = panel.layoutParams as? ViewGroup.MarginLayoutParams
        if (lp != null) {
            lp.height = (AndroidUtilities.displaySize.y * 0.58f).toInt().coerceAtLeast(AndroidUtilities.dp(280f))
            lp.bottomMargin = YuzGramBottomBar.height()
            panel.layoutParams = lp
        }
        // Panel ichidagi tugmalar/klaviatura barni bosib olmasin: scrim panel ostida.
        commentsScrim?.setOnClickListener { closeComments() }
        commentsPanel?.let { p -> p.isClickable = true }

        root.findViewById<View>(R.id.btnCloseComments)?.setOnClickListener { closeComments() }

        val commentsRvAdapter = ReelCommentsAdapter(
            appContext = themed,
            account = currentAccount,
            onReply = { comment -> setReplyTarget(comment) },
            onOpenProfile = { comment -> openCommentProfile(comment) }
        )
        commentsAdapter = commentsRvAdapter
        val clm = LinearLayoutManager(themed, RecyclerView.VERTICAL, false)
        commentsLayoutManager = clm
        commentsList?.let { list ->
            list.layoutManager = clm
            list.adapter = commentsRvAdapter
        }
        // Xato/bo'sh xabarni bosish izohlarni qayta yuklaydi.
        commentsEmpty?.setOnClickListener {
            if (commentsReelId.isNotBlank()) loadComments(commentsReelId)
        }

        sendButton?.setOnClickListener { sendComment() }
        commentInput?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendComment()
                true
            } else {
                false
            }
        }
        attachKeyboardOffset(root)
    }

    /**
     * Klaviatura balandligini kuzatish. LaunchActivity manifest'da `adjustResize`
     * (telefonda ham) — klaviatura ochilganda fragmentroot qisqaradi va pastga
     * langangan panel o'zi yuqoriga chiqadi. Lekin panelda bar uchun qoldirilgan
     * pastki bo'shliq (70dp) klaviatura ustida "o'lik zona" bo'lib qolmasligi uchun
     * klaviatura ko'ringanda uni 6dp ga qisqartiramiz.
     *
     * Aniqlash usuli — klassik "ko'rinish maydoni" farqi: `getWindowVisibleDisplayFrame`
     * IME ni hisobga olmaydi, `rootView.height` butun ekran balandligini beradi.
     */
    private fun attachKeyboardOffset(root: View) {
        val panel = commentsPanel ?: return
        val lp = panel.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        panelBottomMarginNormal = lp.bottomMargin
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val p = commentsPanel ?: return@OnGlobalLayoutListener
            val params = p.layoutParams as? ViewGroup.MarginLayoutParams ?: return@OnGlobalLayoutListener
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val full = root.rootView.height
            if (full <= 0 || visible.bottom <= 0) return@OnGlobalLayoutListener
            val keyboard = full - visible.bottom
            val showing = keyboard > full * 0.15f
            val target = if (showing) AndroidUtilities.dp(6f) else panelBottomMarginNormal
            if (params.bottomMargin != target) {
                params.bottomMargin = target
                p.layoutParams = params
            }
        }
        keyboardListener = listener
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /** Snap tugagach: faol sahifani aniqlaymiz va videoni almashtiramiz. */
    private fun onPageSettled() {
        // #113: snap listener'i RecyclerView layout'idan CHAQILADI — bu yerdagi
        // xato try/catch'li chaqiruv zanjiridan tashqarida crash bo'ladi.
        try {
            val lm = layoutManager ?: return
            val snap = snapHelper ?: return
            val view = snap.findSnapView(lm) ?: return
            val position = lm.getPosition(view)
            if (position !in currentReels.indices) return
            if (position != selectedPosition && commentsOpen) closeComments()
            selectedPosition = position
            adapter?.setActivePosition(position)
            if (playbackVisible) adapter?.resumePlayback()
            refreshLikeState(currentReels.getOrNull(position)?.id)
            preloadNeighbours(position)
        } catch (_: Exception) {
        }
    }

    private fun openCreate() {
        // "Reel joylash" — Firebase'ga video yuklash ekrani (kuniga 5 ta,
        // premium uchun cheksiz). Hikoya joylash esa tepadagi doiralardan.
        try {
            presentFragment(CreateReelActivity(), false)
        } catch (_: Exception) {
            toast(R.string.reels_create_title)
        }
    }

    /**
     * #70: reel'ni to'liq ekranga ochish — sarlavha+storiya qatori va pastki
     * bar yashirinadi, system bar'lar pasaytiriladi. Videoning o'zi allaqachon
     * butun RecyclerView'ni egallagan, shuning uchun bu faqat "kchrani tozalash".
     */
    private fun enterReelFullscreen() {
        if (reelFullscreen) return
        reelFullscreen = true
        adapter?.fullscreen = true
        reelsHeader?.visibility = View.GONE
        fsClose?.visibility = View.VISIBLE
        (parentActivity as? LaunchActivity)?.setYuzGramBarScrollHidden(this, true)
        val decor = parentActivity?.window?.decorView
        if (decor != null) {
            savedSystemUi = decor.systemUiVisibility
            decor.systemUiVisibility = savedSystemUi or
                View.SYSTEM_UI_FLAG_LOW_PROFILE or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
    }

    /** Fullscreen'dan chiqish — har narsa tiklanadi (orqa tugma, ✕, pause, destroy). */
    private fun exitReelFullscreen() {
        if (!reelFullscreen) return
        reelFullscreen = false
        adapter?.fullscreen = false
        reelsHeader?.visibility = View.VISIBLE
        fsClose?.visibility = View.GONE
        (parentActivity as? LaunchActivity)?.setYuzGramBarScrollHidden(this, false)
        val decor = parentActivity?.window?.decorView
        if (decor != null && savedSystemUi >= 0) {
            decor.systemUiVisibility = savedSystemUi
            savedSystemUi = -1
        }
        // Tap bilan ovoz ochilgan bo'lishi mumkin — tugma ikonkasini sinxronlaymiz.
        val muted = adapter?.isMuted() == true
        muteButton?.setImageResource(
            if (muted) R.drawable.ic_yuz_volume_off else R.drawable.ic_yuz_volume_on
        )
        muteButton?.contentDescription =
            getString(if (muted) R.string.reels_sound_off else R.string.reels_sound_on)
    }

    /**
     * Telegram native profil oynasi — o'zi va begonalar uchun ham. YuzGram uid
     * formati "tg_<telegram_user_id>", shuning uchun undan to'g'ridan-to'g'ri
     * user_id olamiz.
     */
    private fun openNativeProfile(uid: String) {
        val peerId = uid.removePrefix("tg_").toLongOrNull() ?: return
        val args = android.os.Bundle()
        if (peerId > 0) {
            args.putLong("user_id", peerId)
            args.putBoolean(
                "my_profile",
                peerId == UserConfig.getInstance(currentAccount).clientUserId
            )
        } else {
            // Kanal/guruh hikoyasi — ProfileActivity chat rejimida ochiladi.
            args.putLong("chat_id", -peerId)
        }
        presentFragment(org.telegram.ui.ProfileActivity(args, null), false)
    }

    /** Reel muallifining profili — native Telegram oynasida. */
    private fun openUploaderProfile(reel: Reel) {
        val uid = reel.uploadedByUid
        if (uid.isBlank()) return
        openNativeProfile(uid)
    }

    /** Izoh muallifining profili — native Telegram oynasida. */
    private fun openCommentProfile(comment: ReelComment) {
        val uid = comment.userId
        if (uid.isBlank()) return
        if (commentsOpen) closeComments()
        openNativeProfile(uid)
    }

    /**
     * Tasma = Telegram hikoyalari + Firebase reel'lari:
     *  1) keshdagi Firebase feed (bo'lsa) + hikoyalar — birinchi kadrda,
     *     tarmoq javobini kutmasdan ko'rinadi;
     *  2) tarmoqdan yangilangan Firebase ro'yxati + hikoyalar.
     * Yangi (48 soat ichida joylangan) Firebase reel'lari boshida turadi,
     * keyin hikoyalar (YuzReelsOrder tartibida), keyin eski Firebase reel'lari.
     * Profil ichidan ochilganda (filterUid) faqat shu muallif kontenti.
     */
    private fun loadReels() {
        val s = scope ?: return
        val keepPosition = selectedPosition
        val keepId = currentReels.getOrNull(keepPosition)?.id
        if (currentReels.isEmpty()) progressBar?.visibility = View.VISIBLE
        s.launch {
            val stories = try {
                YuzStoryFeed.buildFeed(currentAccount, filterUid)
            } catch (_: Exception) {
                emptyList()
            }
            val cached = try {
                if (filterUid.isBlank()) ReelsRepository.peekReels()
                else ReelsRepository.peekReelsByUploader(filterUid)
            } catch (_: Exception) {
                null
            }
            if (fragmentView == null) return@launch
            // 1) Kesh bilan darhol render (hikoyalar lokal — tarmoq kutilmaydi).
            if (cached != null && currentReels.isEmpty()) {
                val quick = composeFeed(stories, cached)
                if (quick.isNotEmpty()) {
                    try {
                        renderReels(quick, keepPosition)
                    } catch (_: Exception) {
                    }
                }
            }
            // 2) Tarmoqdan Firebase reelslarini yangilaymiz; xato bo'lsa kesh
            // yoki faqat hikoyalar bilan davom etamiz.
            val firebase = try {
                if (filterUid.isBlank()) {
                    ReelsRepository.getReelsOrNull() ?: cached ?: emptyList()
                } else {
                    ReelsRepository.getReelsByUploader(filterUid)
                }
            } catch (_: Exception) {
                cached ?: emptyList()
            }
            if (fragmentView == null) return@launch
            val reels = composeFeed(stories, firebase)
            if (filterUid.isBlank()) {
                YuzViewedReels.prune(
                    themedContext ?: return@launch,
                    reels.map { it.id },
                    currentAccount
                )
            }
            // Tarkib o'zgarmagan — qayta submit playback'ni bekor restart qiladi.
            if (currentReels.isNotEmpty() && sameFeed(currentReels, reels)) {
                progressBar?.visibility = View.GONE
                if (reels.isEmpty()) {
                    emptyView?.visibility = View.VISIBLE
                    reelsList?.visibility = View.GONE
                } else {
                    emptyView?.visibility = View.GONE
                    reelsList?.visibility = View.VISIBLE
                }
                return@launch
            }
            // #110: renderReels pleyer yaratish/hikoya bog'lash sinxron yolini
            // ochadi — xato bo'lsa tasma bo'sh qolib ketsa-yu, ilova crash qilmasin.
            try {
                val retained = keepId?.let { id -> reels.indexOfFirst { it.id == id } } ?: -1
                renderReels(reels, if (retained >= 0) retained else keepPosition)
            } catch (_: Exception) {
                progressBar?.visibility = View.GONE
                emptyView?.visibility = View.VISIBLE
                emptyView?.text = getString(R.string.reels_empty)
                reelsList?.visibility = View.GONE
            }
        }
    }

    /** Hikoyalar tartibi saqlanadi; yangi Firebase reel'lari esa eng boshida. */
    private fun composeFeed(stories: List<Reel>, firebase: List<Reel>): List<Reel> {
        val arranged = try {
            feedOrder?.arrange(stories) ?: stories
        } catch (_: Exception) {
            stories
        }
        if (filterUid.isNotBlank()) {
            val scoped = ArrayList<Reel>(stories.size + firebase.size)
            scoped.addAll(stories)
            scoped.addAll(firebase)
            return scoped
        }
        val freshCutoff = System.currentTimeMillis() - FRESH_REEL_MS
        val fresh = ArrayList<Reel>()
        val older = ArrayList<Reel>()
        for (reel in firebase) {
            if (reel.createdAt >= freshCutoff) fresh.add(reel) else older.add(reel)
        }
        val out = ArrayList<Reel>(fresh.size + arranged.size + older.size)
        out.addAll(fresh)
        out.addAll(arranged)
        out.addAll(older)
        return out
    }

    /** Tasmani ko'rsatadi: [keepPosition] saqlangan holda snap + player + preload. */
    private fun renderReels(reels: List<Reel>, keepPosition: Int) {
        currentReels = reels
        adapter?.submit(reels)
        progressBar?.visibility = View.GONE
        val isEmpty = reels.isEmpty()
        emptyView?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        emptyView?.text = getString(R.string.reels_empty)
        reelsList?.visibility = if (isEmpty) View.GONE else View.VISIBLE
        if (!isEmpty) {
            var target = keepPosition.coerceIn(0, reels.size - 1)
            var fromNotification = ""
            if (pendingTargetId.isNotBlank()) {
                val idx = reels.indexOfFirst { it.id == pendingTargetId }
                if (idx >= 0) {
                    target = idx
                    fromNotification = pendingTargetId
                    pendingTargetId = ""
                }
            }
            val finalTarget = target
            selectedPosition = finalTarget
            layoutManager?.scrollToPositionWithOffset(finalTarget, 0)
            adapter?.setActivePosition(finalTarget)
            refreshLikeState(reels.getOrNull(finalTarget)?.id)
            preloadNeighbours(finalTarget)
            if (fromNotification.isNotEmpty() && openTargetComments) openComments(fromNotification)
        }
    }

    /** Keyingi/oldingi hikoya videolarini diskka oldindan tortadi. */
    private fun preloadNeighbours(center: Int) {
        if (!playbackVisible) return
        YuzStoryFeed.prepareNeighbours(currentAccount, currentReels, center, foreground = true)
    }

    /** id ketma-ketligi bir xilmi — faqat "tasma almashdimi"ni bilamiz. */
    private fun sameFeed(a: List<Reel>, b: List<Reel>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].id != b[i].id) return false
        }
        return true
    }

    /**
     * Faol reel uchun like holati: hikoya — Telegram reaksiyasi, Firebase —
     * `reels/{id}/likes/{uid}` hujjati.
     */
    private fun refreshLikeState(reelId: String?) {
        val rid = reelId ?: return
        val key = YuzStoryFeed.parseKey(rid)
        if (key != null) {
            try {
                val item = YuzStoryFeed.findStoryItem(currentAccount, key.first, key.second) ?: return
                val liked = item.sent_reaction != null && item.sent_reaction !is TLRPC.TL_reactionEmpty
                adapter?.applyLikeState(rid, liked, item.views?.reactions_count ?: 0)
            } catch (_: Exception) {
            }
            return
        }
        if (currentUid().isBlank()) return
        val s = scope ?: return
        val account = currentAccount
        s.launch {
            try {
                awaitAuth()
                val signedUid = YuzGramAdsAuth.ensureSignedIn(account)
                val liked = ReelsRepository.isLiked(rid, signedUid)
                if (fragmentView == null) return@launch
                adapter?.applyLikeState(rid, liked)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Like/unlike: hikoya — Telegram reaksiyasi (`❤`), Firebase reel —
     * Firestore `likes` hujjati va `likeCount` increment.
     */
    private fun toggleLike(reelId: String, likedNow: Boolean) {
        val key = YuzStoryFeed.parseKey(reelId)
        if (key != null) {
            val ok = try {
                YuzStoryFeed.likeStory(currentAccount, key.first, key.second, likedNow)
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                adapter?.applyLikeState(reelId, !likedNow)
                toast(R.string.reels_like_failed)
            }
            return
        }
        val uid = currentUid()
        if (uid.isBlank()) {
            adapter?.applyLikeState(reelId, !likedNow)
            toast(R.string.reels_sign_in_required)
            return
        }
        val s = scope ?: return
        val account = currentAccount
        s.launch {
            try {
                awaitAuth()
                val signedUid = YuzGramAdsAuth.ensureSignedIn(account)
                ReelsRepository.setLiked(reelId, signedUid, likedNow, authorName(account))
            } catch (_: Exception) {
                if (fragmentView == null) return@launch
                adapter?.applyLikeState(reelId, !likedNow)
                toast(R.string.reels_like_failed)
            }
        }
    }

    /** "Ko'rish": hikoya — Telegram max_read_id, Firebase — `views` increment. */
    private fun registerView(reelId: String) {
        val key = YuzStoryFeed.parseKey(reelId)
        if (key != null) {
            try {
                YuzStoryFeed.markViewed(currentAccount, key.first, key.second)
            } catch (_: Exception) {
            }
            return
        }
        val s = scope ?: return
        s.launch {
            try {
                ReelsRepository.registerView(reelId)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * O'z reel'ini o'chirish tasdig'i. [android.app.AlertDialog] — framework
     * dialog'i: Material/Telegram mavzusiga bog'liq emas, shuning uchun
     * Theme.YuzGramAds ostida ham xavfsiz ishlaydi.
     */
    private fun confirmDelete(reel: Reel) {
        val activity = parentActivity ?: return
        val title = reel.title.ifBlank { getString(R.string.reels_title) }
        try {
            android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.reels_delete)
                .setMessage(getString(R.string.reels_delete_confirm, title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reels_delete) { _, _ -> deleteReel(reel) }
                .show()
        } catch (_: Exception) {
            // Dialog ko'rsatib bo'lmasa — o'chirmaymiz (xavfsiz yo'l).
            toast(R.string.reels_delete_failed)
        }
    }

    /** Firestore yozuvi + Storage videosini o'chirib, feed'dan chiqaradi. */
    private fun deleteReel(reel: Reel) {
        val uid = currentUid()
        if (uid.isBlank()) {
            toast(R.string.reels_sign_in_required)
            return
        }
        val s = scope ?: return
        val account = currentAccount
        s.launch {
            try {
                awaitAuth()
                val signedUid = YuzGramAdsAuth.ensureSignedIn(account)
                ReelsRepository.deleteReel(reel.id)
                ReelStorageHelper.deleteVideo(signedUid, reel.videoUrl)
                if (fragmentView == null) return@launch
                adapter?.removeById(reel.id)
                adapter?.reactivate()
                toast(R.string.reels_deleted)
                if ((adapter?.itemCount ?: 0) == 0) loadReels()
            } catch (e: Exception) {
                if (fragmentView == null) return@launch
                val msg = e.message
                if (msg.isNullOrBlank()) toast(R.string.reels_delete_failed) else toast(msg)
            }
        }
    }

    /**
     * Izohlar panelini ochish (oldingi loyihadagi BottomSheet o'rniga).
     * Panel avval ochilgan reel'ga tegishli bo'lsa — qayta yuklamaymiz.
     */
    private fun openComments(reelId: String) {
        if (reelId.isBlank()) return
        val alreadyOpen = commentsOpen && commentsReelId == reelId
        commentsOpen = true
        commentsReelId = reelId
        replyTarget = null
        updateReplyHint()
        commentsScrim?.visibility = View.VISIBLE
        commentsPanel?.visibility = View.VISIBLE
        if (alreadyOpen) return
        commentsAdapter?.clear()
        loadedCommentCount = 0
        updateCommentsTitle()
        loadComments(reelId)
    }

    private fun closeComments() {
        if (!commentsOpen) return
        commentsOpen = false
        commentsReelId = ""
        replyTarget = null
        hideKeyboard()
        commentInput?.setText("")
        updateReplyHint()
        commentsScrim?.visibility = View.GONE
        commentsPanel?.visibility = View.GONE
    }

    /** Izohlarni Firestore'dan o'qiydi (`null` = xato, bo'sh ro'yxat = izoh yo'q). */
    private fun loadComments(reelId: String) {
        if (commentsLoading) return
        val s = scope ?: return
        commentsLoading = true
        if (loadedCommentCount == 0) commentsProgress?.visibility = View.VISIBLE
        s.launch {
            val comments = ReelsRepository.getComments(reelId)
            commentsLoading = false
            if (fragmentView == null || commentsReelId != reelId) return@launch
            commentsProgress?.visibility = View.GONE
            if (comments == null) {
                commentsAdapter?.clear()
                loadedCommentCount = 0
                commentsList?.visibility = View.GONE
                commentsEmpty?.visibility = View.VISIBLE
                commentsEmpty?.text = getString(R.string.reels_comments_load_failed)
                updateCommentsTitle()
                return@launch
            }
            commentsAdapter?.submit(comments)
            loadedCommentCount = comments.size
            commentsList?.visibility = if (comments.isEmpty()) View.GONE else View.VISIBLE
            commentsEmpty?.visibility = if (comments.isEmpty()) View.VISIBLE else View.GONE
            commentsEmpty?.text = getString(R.string.reels_comments_empty)
            updateCommentsTitle()
            if (comments.isNotEmpty()) {
                // Eng yangi izoh ko'rinsin (ro'yxat eskidana yangi tartibida).
                commentsLayoutManager?.scrollToPositionWithOffset(comments.size - 1, 0)
            }
        }
    }

    /**
     * Yangi izoh yozish. Qoida: `comments` create faqat Firebase sessiyasida va
     * `data.userId == auth.uid` da o'tadi — shuning uchun yozuvdan oldin sessiya
     * ochiladi (qarang [warmUpAuth] va [YuzGramAdsAuth.ensureSignedIn]).
     */
    private fun sendComment() {
        if (sendingComment) return
        val reelId = commentsReelId
        if (reelId.isBlank()) return
        val input = commentInput ?: return
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast(R.string.reels_comment_empty)
            return
        }
        val uid = currentUid()
        if (uid.isBlank()) {
            toast(R.string.reels_sign_in_required)
            return
        }
        val account = currentAccount
        val reply = replyTarget
        // Flat-thread (donor uslubida): javob doim ASOSIY izohga bog'lanadi —
        // reply-javobga javob berilsa ham root id qoladi, lekin "kimga" nomi
        // bevosita javob berilayotgan izoh bo'yicha ko'rinadi.
        val rootId = if (reply != null) reply.replyToCommentId.ifBlank { reply.id } else ""
        val draft = ReelComment(
            text = text,
            authorName = authorName(account),
            userId = uid,
            deviceId = uid,
            createdAt = System.currentTimeMillis(),
            replyToCommentId = rootId,
            replyToUserId = reply?.userId.orEmpty(),
            replyToAuthorName = reply?.authorName.orEmpty()
        )
        val s = scope ?: return
        sendingComment = true
        sendButton?.alpha = 0.5f
        s.launch {
            val saved = try {
                awaitAuth()
                val signedUid = YuzGramAdsAuth.ensureSignedIn(account)
                var photo = ""
                try { photo = YuzGramProfileStore.load(signedUid).photoUrl } catch (_: Exception) {}
                ReelsRepository.addComment(
                    reelId,
                    draft.copy(userId = signedUid, authorPhotoUrl = photo)
                )
            } catch (e: Exception) {
                sendingComment = false
                sendButton?.alpha = 1f
                // Ekran yopilsa xabar ko'rsatmaymiz.
                if (fragmentView == null) return@launch
                toast(e.message ?: getString(R.string.reels_comment_failed))
                return@launch
            }
            sendingComment = false
            sendButton?.alpha = 1f
            if (fragmentView == null || commentsReelId != reelId) return@launch
            input.setText("")
            replyTarget = null
            updateReplyHint()
            commentsAdapter?.append(saved)
            loadedCommentCount += 1
            commentsList?.visibility = View.VISIBLE
            commentsEmpty?.visibility = View.GONE
            // Javob root ostiga qo'yilishi mumkin — yangi izoh pozitsiyasiga scroll.
            val pos = commentsAdapter?.positionOf(saved.id)?.takeIf { it >= 0 }
                ?: ((commentsAdapter?.itemCount ?: 1) - 1)
            commentsLayoutManager?.scrollToPositionWithOffset(pos, 0)
            updateCommentsTitle()
            adapter?.addCommentCount(reelId, 1)
        }
    }

    /** Izohga javob berish rejimi (maydon fokus oladi, yonida bekor tugmasi chiqadi). */
    private fun setReplyTarget(comment: ReelComment) {
        if (comment.id.isBlank()) return
        replyTarget = comment
        updateReplyHint()
        commentInput?.requestFocus()
    }

    private fun updateReplyHint() {
        val hint = replyHint ?: return
        val target = replyTarget
        if (target == null) {
            hint.visibility = View.GONE
            hint.setOnClickListener(null)
            hint.isClickable = false
            return
        }
        hint.visibility = View.VISIBLE
        hint.text = getString(
            R.string.reels_comment_replying_to,
            target.authorName.ifBlank { getString(R.string.AppName) }
        )
        hint.setIconEnd(R.drawable.ic_yuz_close, sizeDp = 13, gapDp = 8)
        hint.isClickable = true
        hint.setOnClickListener {
            replyTarget = null
            updateReplyHint()
        }
    }

    /** Sarlavhadagi son — o'qilgan izohlar + yozilgan yangilar. */
    private fun updateCommentsTitle() {
        val title = commentsTitle ?: return
        title.text = if (loadedCommentCount <= 0) {
            getString(R.string.reels_comments)
        } else {
            getString(R.string.reels_comments_title, loadedCommentCount)
        }
    }

    /** Telegram profilidagi ism — izoh muallifi shu nom bilan ko'rinadi. */
    private fun authorName(account: Int): String {
        val user = UserConfig.getInstance(account).currentUser
        val first = user?.first_name.orEmpty().trim()
        val last = user?.last_name.orEmpty().trim()
        val full = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
        return full.ifBlank { user?.username.orEmpty().ifBlank { "YuzGram" } }
    }

    private fun hideKeyboard() {
        val input = commentInput ?: return
        val activity = parentActivity ?: return
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /**
     * Orqaga bosilganda: avval izohlar panelini yopamiz; root tab'da bo'lsak —
     * #80: ilovadan chiqmasdan Chat bo'limiga qaytamiz.
     * Diqqat: Telegram semantikası bo'yicha `false` = "orqa tugmani men ishlatdim,
     * fragmentni yopma" (qarang ActionBarLayout.onBackPressed — `true` bo'lsa
     * closeLastFragment() chaqiriladi).
     */
    override fun onBackPressed(): Boolean {
        if (reelFullscreen) {
            exitReelFullscreen()
            return false
        }
        if (commentsOpen) {
            closeComments()
            return false
        }
        if (YuzGramNav.isTabRoot(this)) {
            YuzGramNav.select(this, YuzGramBottomBar.TAB_ASOSIY)
            return false
        }
        return super.onBackPressed()
    }

    /**
     * Firebase sessiyasini oldindan ochib qo'yish: like/ko'rish yozuvlari
     * shuni talab qiladi, foydachi bosgunicha kutib qolmasligi uchun.
     * Bir vaqtning o'zida ikki `mintTelegramCustomToken` so'rovi ketmasligi uchun
     * job saqlanadi va yozuv yo'llari uni kutadi (`join`).
     */
    private fun warmUpAuth() {
        if (authWarmedUp) return
        authWarmedUp = true
        if (currentUid().isBlank()) return
        val s = scope ?: return
        val account = currentAccount
        authJob = s.launch {
            try {
                val uid = YuzGramAdsAuth.ensureSignedIn(account)
                // O'z tab'ida (umumiy feed) comment_profiles doc'ini yaratamiz —
                // profil sahifasi (ism/rasm/bio) shu hujjatdan foydalanadi.
                if (uid.isNotBlank() && filterUid.isBlank()) {
                    val user = UserConfig.getInstance(account).currentUser
                    YuzGramProfileStore.ensureSelfProfile(
                        uid = uid,
                        firstName = user?.first_name.orEmpty(),
                        lastName = user?.last_name.orEmpty(),
                        photoUrl = ""
                    )
                }
            } catch (_: Exception) {
                // Sessiya keyinroq yozuvda o'zi ochiladi (yoki tokensiz like urinishi
                // xabar bilan tugaydi) — bu yerda foydachini chalg'itmaymiz.
            }
        }
    }

    /** Davom etayotgan auth job'ini kutish (bir xili mint so'rovlarining oldini oladi). */
    private suspend fun awaitAuth() {
        val job = authJob ?: return
        if (job.isActive) job.join()
    }

    /**
     * #99: tarmoq uzilganda/ qaytganda — Feed'ni tinch holatda qayta yuklaymiz,
     * video bo'sa adapter avto-urinishi orqali tiklanadi (user hech narsa
     * bosishi shart emas).
     */
    private fun registerNetworkWatch() {
        if (Build.VERSION.SDK_INT < 23) return
        val cm = (parentActivity ?: context)?.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val view = fragmentView ?: return
                view.post {
                    if (fragmentView == null) return@post
                    if (currentReels.isEmpty()) loadReels() else adapter?.retryActive()
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Exception) {
            // Ruxsat/versiya tuzaqi — feed manual retry bilan ishlayveradi.
        }
    }

    private fun unregisterNetworkWatch() {
        val cm = (parentActivity ?: context)?.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
        try {
            if (Build.VERSION.SDK_INT >= 23 && networkCallback != null) cm?.unregisterNetworkCallback(networkCallback!!)
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    override fun onResume() {
        super.onResume()
        playbackVisible = true
        actionBar?.visibility = View.GONE
        if (fragmentView != null) {
            refreshStoryStrip()
            loadReels()
            if (reelsList?.scrollState == RecyclerView.SCROLL_STATE_IDLE) adapter?.resumePlayback()
            preloadNeighbours(selectedPosition)
        }
    }

    override fun onTransitionAnimationStart(isOpen: Boolean, backward: Boolean) {
        if (!isOpen) {
            playbackVisible = false
            adapter?.pausePlayback()
        }
        super.onTransitionAnimationStart(isOpen, backward)
    }

    override fun onBecomeFullyVisible() {
        super.onBecomeFullyVisible()
        if (isPaused) return
        playbackVisible = true
        if (reelsList?.scrollState == RecyclerView.SCROLL_STATE_IDLE) adapter?.resumePlayback()
    }

    override fun onBecomeFullyHidden() {
        playbackVisible = false
        adapter?.pausePlayback()
        super.onBecomeFullyHidden()
    }

    override fun onPause() {
        playbackVisible = false
        adapter?.pausePlayback()
        exitReelFullscreen()
        super.onPause()
    }

    override fun onFragmentDestroy() {
        exitReelFullscreen()
        unregisterNetworkWatch()
        unregisterStoryWatch()
        adapter?.release()
        adapter = null
        keyboardListener?.let { listener ->
            fragmentView?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
        }
        keyboardListener = null
        windowFocusListener?.let { listener ->
            fragmentView?.viewTreeObserver?.removeOnWindowFocusChangeListener(listener)
        }
        windowFocusListener = null
        // Izohlar paneli ham shu view bilan yo'qoladi — referensiyalarni bo'shatamiz.
        commentsOpen = false
        commentsReelId = ""
        replyTarget = null
        commentsAdapter = null
        commentsLayoutManager = null
        commentsList = null
        commentsScrim = null
        commentsPanel = null
        commentsTitle = null
        commentsProgress = null
        commentsEmpty = null
        commentInput = null
        sendButton = null
        replyHint = null
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

    /** [BaseFragment] View bo'lgani uchun `getString(resId)` yo'q — o'zimiz yasaymiz. */
    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }

    /** Formatlangan variant (`Izohlar (%1$d)` kabi) — shu overload bo'lmasa kotlinc xato beradi. */
    private fun getString(resId: Int, vararg args: Any): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId, *args)
    }

    private companion object {
        /** Yangi Firebase reel'lari tasmaning boshida turadigan oyna. */
        private const val FRESH_REEL_MS = 48L * 60L * 60L * 1000L
    }
}
