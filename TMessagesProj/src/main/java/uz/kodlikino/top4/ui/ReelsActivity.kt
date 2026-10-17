package uz.kodlikino.top4.ui

import android.content.Context
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.models.ReelComment
import uz.kodlikino.top4.utils.ReelStorageHelper
import uz.kodlikino.top4.utils.ReelsRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzGramProfileStore

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
 *  - izohlar (`💬`) shu ekran ichidagi pastki panel ochib ko'rsatiladi — KinoTOP4'dagi
 *    `ReelCommentsBottomSheet` o'rniga; yozish `reels/{id}/comments` ga ketadi;
 *  - "Reel joylash" -> [CreateReelActivity].
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
    private val startIndex: Int = 0
) : BaseFragment() {

    private var themedContext: Context? = null
    private var reelsList: RecyclerView? = null
    private var layoutManager: LinearLayoutManager? = null
    private var snapHelper: PagerSnapHelper? = null
    private var reelsTopBar: View? = null
    private var muteButton: TextView? = null
    private var progressBar: ProgressBar? = null
    private var emptyView: TextView? = null
    private var adapter: ReelsAdapter? = null

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

    private var scope: CoroutineScope? = null
    private var loading = false
    private var authWarmedUp = false
    private var authJob: Job? = null
    private var currentReels: List<Reel> = emptyList()
    private var selectedPosition = startIndex.coerceAtLeast(0)

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
        // Bo'sh/xato holatida xabarni bosish qayta yuklaydi (tarmoq shu yerda beqaror).
        emptyView?.setOnClickListener { loadReels() }

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
            onViewed = { reelId -> registerView(reelId) },
            onOpenComments = { reelId -> openComments(reelId) },
            myUid = currentUid(),
            onDelete = { reel -> confirmDelete(reel) },
            onOpenProfile = { reel -> openUploaderProfile(reel) }
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
        setupComments(root, themed)
        loadReels()
        warmUpAuth()
        return root
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
        val lm = layoutManager ?: return
        val snap = snapHelper ?: return
        val view = snap.findSnapView(lm) ?: return
        val position = lm.getPosition(view)
        if (position < 0 || position == selectedPosition) return
        selectedPosition = position
        // Boshqa reel'ga o'tdik: eski reel izohlari endi kerak emas.
        if (commentsOpen) closeComments()
        adapter?.setActivePosition(position)
        refreshLikeState(currentReels.getOrNull(position)?.id)
    }

    private fun openCreate() {
        presentFragment(CreateReelActivity(), false)
    }

    /** Reel muallifining YuzGram profili. Reel YuzGram orqali yuklangan — demak maqsad aniq YuzGram foydalanuvchisi. */
    private fun openUploaderProfile(reel: Reel) {
        val uid = reel.uploadedByUid
        if (uid.isBlank()) return
        val isSelf = uid == currentUid()
        presentFragment(
            YuzGramProfileActivity(
                uid = uid,
                seedName = reel.uploadedByName,
                seedPhotoUrl = reel.uploadedByPhotoUrl,
                isSelf = isSelf,
                knownYuzGram = true
            ),
            false
        )
    }

    /** Izoh muallifining YuzGram profili (izoh signedIn sessiyada yozilgan → aniq YuzGram foydalanuvchisi). */
    private fun openCommentProfile(comment: ReelComment) {
        val uid = comment.userId
        if (uid.isBlank()) return
        if (commentsOpen) closeComments()
        val isSelf = uid == currentUid()
        presentFragment(
            YuzGramProfileActivity(
                uid = uid,
                seedName = comment.authorName,
                seedPhotoUrl = comment.authorPhotoUrl,
                isSelf = isSelf,
                knownYuzGram = true
            ),
            false
        )
    }

    private fun loadReels() {
        if (loading) return
        val s = scope ?: return
        val keepPosition = selectedPosition
        loading = true
        if (currentReels.isEmpty()) progressBar?.visibility = View.VISIBLE
        s.launch {
            val reels: List<Reel>? = if (filterUid.isNotBlank()) {
                // Scoped feed (profil grid'idan): faqat shu muallifning reel'lari.
                // getReelsByUploader xatoda bo'sh ro'yxat qaytaradi — null bo'lmaydi.
                ReelsRepository.getReelsByUploader(filterUid)
            } else {
                ReelsRepository.getReelsOrNull()
            }
            loading = false
            if (fragmentView == null) return@launch
            if (reels == null) {
                // O'qish xatosi — "bo'sh" deb aldamaymiz, bosilganda qayta urinadi.
                progressBar?.visibility = View.GONE
                if (currentReels.isEmpty()) {
                    emptyView?.visibility = View.VISIBLE
                    reelsList?.visibility = View.GONE
                }
                emptyView?.text = getString(R.string.reels_load_failed)
                return@launch
            }
            currentReels = reels
            adapter?.submit(reels)
            progressBar?.visibility = View.GONE
            val isEmpty = reels.isEmpty()
            emptyView?.visibility = if (isEmpty) View.VISIBLE else View.GONE
            emptyView?.text = getString(R.string.reels_empty)
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
        val account = currentAccount
        s.launch {
            try {
                // Firestore qoidasi: `likes/{uid}` yozish faqat Firebase Auth
                // sessiyasida o'tadi (`request.auth.uid == userId`).
                awaitAuth()
                YuzGramAdsAuth.ensureSignedIn(account)
                ReelsRepository.setLiked(reelId, uid, likedNow)
            } catch (e: Exception) {
                // Ekran yopilsa (scope bekor qilinsa) foydachiga xabar ko'rsatmaymiz.
                if (fragmentView == null) return@launch
                // Optimistik UI'ni orqaga qaytaramiz.
                adapter?.applyLikeState(reelId, !likedNow)
                toast(R.string.reels_like_failed)
            }
        }
    }

    private fun registerView(reelId: String) {
        val s = scope ?: return
        val account = currentAccount
        s.launch {
            // `views` increment ham qoidaga ko'ra signedIn() talab qiladi.
            try {
                awaitAuth()
                YuzGramAdsAuth.ensureSignedIn(account)
            } catch (_: Exception) {
                // Ko'rish hisobi muhim emas — xato feed'ni buzmasligi kerak.
            }
            ReelsRepository.registerView(reelId)
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
        val draft = ReelComment(
            text = text,
            authorName = authorName(account),
            userId = uid,
            deviceId = uid,
            createdAt = System.currentTimeMillis(),
            replyToCommentId = reply?.id.orEmpty(),
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
                ReelsRepository.addComment(reelId, draft.copy(userId = signedUid))
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
            commentsLayoutManager?.scrollToPositionWithOffset(
                (commentsAdapter?.itemCount ?: 1) - 1, 0
            )
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
        hint.text = getString(R.string.reels_comment_replying_to,
            target.authorName.ifBlank { "YuzGram" }) + "   ✕"
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
     * Orqaga bosilganda: avval izohlar panelini yopamiz.
     * Diqqat: Telegram semantikası bo'yicha `false` = "orqa tugmani men ishlatdim,
     * fragmentni yopma" (qarang ActionBarLayout.onBackPressed — `true` bo'lsa
     * closeLastFragment() chaqiriladi).
     */
    override fun onBackPressed(): Boolean {
        if (commentsOpen) {
            closeComments()
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
                // O'z tab'ida ( umumiy feed ) comment_profiles doc'ini yaratamiz —
                // shunda boshqa YuzGram foydalanuvchilari bizga obuna bola oladi
                // (follow() maqsad hujjatiga followerCount+1 yozadi, doc bo'lishi kerak).
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
        keyboardListener?.let { listener ->
            fragmentView?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
        }
        keyboardListener = null
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
}
