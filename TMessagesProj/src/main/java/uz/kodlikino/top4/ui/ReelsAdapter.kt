package uz.kodlikino.top4.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.telegram.messenger.R
import uz.kodlikino.top4.models.Reel
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.BackupImageView
import uz.kodlikino.top4.utils.NetworkReachability
import uz.kodlikino.top4.utils.YuzReelPreloader
import uz.kodlikino.top4.utils.YuzStoryFeed
import uz.kodlikino.top4.utils.YuzViewedReels
import uz.kodlikino.top4.utils.YuzVideoCache
import java.io.File

/**
 * Reels feed adapter'i — vertikal [RecyclerView] sahifalari uchun adapter
 * (har bir sahifa butun ekranga [androidx.recyclerview.widget.PagerSnapHelper]
 * bilan yopishadi).
 *
 * Ijro modeli (bir nechta Player o'rniga BITTA player — eng tejamkor va barqaror):
 *  - Butun feed uchun yagona [ExoPlayer]; faqat ko'rinib turgan sahifaning
 *    `PlayerView.player` maydoniga biriktiriladi, qolganlari `null` qo'yiladi.
 *  - Sahifa almashganda oldingi video to'xtaydi, yangi manba `setMediaItem` +
 *    `prepare()` + avtomatik o'ynatishga o'tadi (Reels standart xulqiyati).
 *  - `repeatMode = REPEAT_MODE_ONE` — video tugagach boshidan aylanadi.
 *  - Ekranni bosish play/pause. Qattiq controller yoqilmaydi, shuning uchun
 *    media3'ning murakkab controller layoutlari ishga tushmaydi (minSdk 21).
 *
 * Like/ko'rish ko'rsatkichlari optimistik yangilanadi; yozuv [ReelsRepository]
 * orqali Firestore'ga ketadi (calls orqali qaytadi).
 */
class ReelsAdapter(
    private val appContext: Context,
    private val onLikeToggle: (reelId: String, likedNow: Boolean) -> Unit,
    private val onViewed: (reelId: String) -> Unit,
    private val onOpenComments: (reelId: String) -> Unit,
    /** Joriy foydachi uid'si (`tg_<telegramId>`) — o'z reel'ini o'chirish uchun. */
    private val myUid: String,
    private val onDelete: (Reel) -> Unit,
    /** Muallif ustuni bosilganda — reel egasining YuzGram profilini ochadi. */
    private val onOpenProfile: (Reel) -> Unit,
    /** Sahifa kichik holatda bosilganda — ovozni ochish + to'liq ekran (chaqiruvchi ochadi). */
    private val onExpandTap: () -> Unit
) : RecyclerView.Adapter<ReelsAdapter.PageHolder>() {

    private val items = ArrayList<Reel>()

    /** Optimistik UI holati — Firestore'dan qaytgan to'liq ro'yxatni buzmasligi uchun. */
    private val likeShown = HashMap<String, Int>()
    private val liked = HashMap<String, Boolean>()
    private val viewsShown = HashMap<String, Int>()
    private val commentShown = HashMap<String, Int>()

    private var player: ExoPlayer? = null
    private var recyclerView: RecyclerView? = null
    private var activePos = -1
    private var activeHolder: PageHolder? = null

    /** Ovoz holati — sahifalar orasida saqlanadi (yangi videoda qayta qo'llanadi). */
    private var muted = true

    /**
     * Reel hozir to'liq ekranda ochilganmi. Kichik holatda bosish =/unmute +
     * fullscreen; fullscreen holatda bosish = play/pause (eski xulqiyat).
     */
    var fullscreen = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // #99: xato/urinish holati — faol sahifa bo'yicha saqlanadi.
    private var playAttempts = 0
    /** Kutilayotgan retry Runnable'i — Handler'da token yo'q, shuning uchun
     *  o'zi saqlanadi va removeCallbacks bilan bekor qilinadi. */
    private var pendingRetry: Runnable? = null

    private fun cancelPendingRetry() {
        pendingRetry?.let { mainHandler.removeCallbacks(it) }
        pendingRetry = null
    }

    /** #106: hikoya videosining lokal faylga tushishini kuzatuvchi poll. */
    private var storyPoll: Runnable? = null

    private fun cancelStoryPoll() {
        storyPoll?.let { mainHandler.removeCallbacks(it) }
        storyPoll = null
    }

    /**
     * Playback progress chizig'i — controller o'chirilgan (`use_controller=false`,
     * media3-ui RecyclerView tuzaqidan saqlanish uchun), shuning uchun pozitsiyani
     * o'zimiz 250ms da bir yangilaymiz. Player bo'lmasa yoki uzунlik hali
     * aniq bo'lmasa (`TIME_UNSET`) progress 0 da turadi.
     */
    private val progressTicker = object : Runnable {
        override fun run() {
            val p = player
            val holder = activeHolder
            if (p != null) {
                val duration = p.duration
                if (holder != null && duration > 0L && duration < Long.MAX_VALUE) {
                    holder.playbackBar.max = 1000
                    holder.playbackBar.progress =
                        ((p.currentPosition * 1000L) / duration).toInt().coerceIn(0, 1000)
                }
                mainHandler.postDelayed(this, 250L)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            activeHolder?.setPlayingBadge(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val holder = activeHolder ?: return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    holder.errorRow.visibility = View.GONE
                    holder.pageProgress.visibility = View.VISIBLE
                }
                Player.STATE_READY -> {
                    holder.pageProgress.visibility = View.GONE
                    holder.errorRow.visibility = View.GONE
                    holder.ivThumbnail.visibility = View.GONE
                    if (holder.reel?.storyIsVideo == true) {
                        // Hikoya postiri video ustida qolmasin.
                        holder.storyThumb.visibility = View.GONE
                    }
                    val reel = holder.reel
                    // Dedup SharedPreferences'da saqlanadi — ilova/qayta ochishda
                    // ham har reel BIR marta hisoblanadi (haqiqiy ko'rishlar).
                    if (reel != null && YuzViewedReels.markViewed(appContext, reel.id)) {
                        val next = (viewsShown[reel.id] ?: reel.views) + 1
                        viewsShown[reel.id] = next
                        holder.tvViews.text = viewsLabel(next)
                        onViewed(reel.id)
                    }
                }
                else -> holder.pageProgress.visibility = View.GONE
            }
        }

        /** #99: uzilish/xato endi "mangaligacha spinner" EMAS — retry UX. */
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val holder = activeHolder ?: return
            if (playAttempts < 3) {
                playAttempts++
                val delay = 900L * playAttempts
                cancelPendingRetry()
                val retry = Runnable {
                    pendingRetry = null
                    if (activeHolder === holder) activate(holder)
                }
                pendingRetry = retry
                mainHandler.postDelayed(retry, delay)
                Log.i("ReelsAdapter", "playError ${error.errorCode} attempt=$playAttempts retryIn=${delay}ms")
                return
            }
            holder.pageProgress.visibility = View.GONE
            val offline = !NetworkReachability.isOnline(appContext)
            holder.tvError.text = appContext.getString(
                if (offline) R.string.reels_err_offline else R.string.reels_err_failed
            )
            holder.errorRow.visibility = View.VISIBLE
            holder.errorRow.setOnClickListener { retryActive() }
            Log.w("ReelsAdapter", "playError FINAL code=${error.errorCode} msg=${error.message}")
        }
    }

    /** #99: "Qayta urinish" — xato panelidan keyin qo'ldan qayta boshlash. */
    fun retryActive() {
        val h = activeHolder ?: return
        // Ijro normal holatda — aralashmaymiz (videoni qayta boshlamaymiz).
        if (h.errorRow.visibility != View.VISIBLE) return
        cancelPendingRetry()
        playAttempts = 0
        activate(h)
    }

    fun submit(list: List<Reel>) {
        items.clear()
        items.addAll(list)
        likeShown.clear()
        liked.clear()
        viewsShown.clear()
        commentShown.clear()
        // viewedOnce SAQLANADI (prefs) — aks holda har refresh ko'rishni
        // qaytadan hisoblardi. O'chirilgan reel id'lari tozalanadi.
        YuzViewedReels.prune(appContext, list.map { it.id })
        activePos = -1
        activeHolder = null
        notifyDataSetChanged()
    }

    /**
     * O'chirilgan reel'ni feed'dan chiqaradi. Faol sahifa o'chirilsa player
     * bo'shatiladi — chaqiruvchi tomon `[reactivate()]` qayta faollashtiriladi.
     */
    fun removeById(reelId: String): Boolean {
        val idx = items.indexOfFirst { it.id == reelId }
        if (idx < 0) return false
        items.removeAt(idx)
        likeShown.remove(reelId)
        liked.remove(reelId)
        viewsShown.remove(reelId)
        commentShown.remove(reelId)
        if (activePos == idx) {
            activeHolder?.playerView?.player = null
            activeHolder = null
            activePos = -1
        } else if (activePos > idx) {
            activePos -= 1
        }
        notifyItemRemoved(idx)
        return true
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reel_page, parent, false)
        val holder = PageHolder(view)
        // BackupImageView'ning XML (Context, AttributeSet) konstruktori yo'q —
        // Telegram uni faqat code'da yaratadi. Shuning uchun hikoya postini
        // storyThumbHost konteyneriga qo'l bilan qo'shamiz.
        val biv = BackupImageView(parent.context)
        biv.setBackgroundColor(ContextCompat.getColor(parent.context, R.color.home_bg))
        biv.visibility = View.GONE
        holder.storyThumbHost.addView(
            biv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        holder.storyThumb = biv
        return holder
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val reel = items.getOrNull(position) ?: return
        holder.bind(reel)
        // Faqat faol sahifa player'ga ega bo'ladi — qolganlari bo'sh qoladi.
        if (holder !== activeHolder) {
            holder.playerView.player = null
        }
    }

    override fun onViewRecycled(holder: PageHolder) {
        super.onViewRecycled(holder)
        holder.playerView.player = null
        holder.storyThumb.clearImage()
        if (holder === activeHolder) activeHolder = null
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    /**
     * "Bu sahifa endi ko'rinib turibdi" signali (snap bo'lgan pozitsiya).
     * Holder hali rezyulyatsiya bo'lmagan bo'lishi mumkin — RecyclerView layouti
     * keyingi kadrdagi ishlaydi, shuning uchun cheklangan urinishlar bilan qayta
     * urinamiz (aks holda birinchi sahifa videoyoq qolardi).
     */
    fun setActivePosition(pos: Int) = setActivePosition(pos, 0)

    private fun setActivePosition(pos: Int, attempt: Int) {
        if (pos < 0 || pos >= itemCount) return
        if (pos == activePos && activeHolder != null) return
        activePos = pos
        val holder = findHolder(pos)
        if (holder != null) {
            activate(holder)
            return
        }
        if (attempt >= 6) return
        val target = recyclerView
        val retry = Runnable { if (activePos == pos) setActivePosition(pos, attempt + 1) }
        if (target != null) {
            target.postDelayed(retry, 50L)
        } else {
            mainHandler.postDelayed(retry, 50L)
        }
    }

    private fun activate(holder: PageHolder?) {
        val h = holder ?: return
        val pos = activePos
        val reel = items.getOrNull(pos) ?: return
        // Telegram'ning o'z (vendored) RecyclerView'ida getBindingAdapterPosition()
        // yo'q — shuning uchun adapterPosition ishlatiladi.
        if (h.adapterPosition != pos) return
        if (reel.isStory) {
            // #106: hikoya sahifasi — alohida ijro yo'li (local file / photo).
            activateStory(h, reel)
            return
        }
        cancelStoryPoll()
        val p = ensurePlayer()
        val prevHolder = activeHolder
        activeHolder?.playerView?.player = null
        activeHolder = h
        p.removeListener(playerListener)
        p.addListener(playerListener)
        p.setMediaItem(MediaItem.fromUri(reel.videoUrl))
        p.prepare()
        p.playWhenReady = true
        if (NetworkReachability.isOnline(appContext)) {
            YuzReelPreloader.warm(appContext, reel.videoUrl, 4_000_000L)
        }
        h.playerView.player = p
        h.errorRow.visibility = View.GONE
        cancelPendingRetry()
        if (h !== prevHolder) playAttempts = 0
        h.pageProgress.visibility = View.VISIBLE
    }

    /** Sahifa qayta ko'rinmaganda chaqiriladi (masalan, CreateReel'dan qaytish). */
    fun reactivate() {
        val pos = activePos
        activePos = -1
        setActivePosition(pos)
    }

    /**
     * #106: hikoya sahifasini faollashtirish. Foto hikoya — pleyer kerak emas,
     * faqat ImageReceiver qatori va "ko'rildi" belgisi. Video hikoya — media3
     * `tg://` sxemasini tushunmaydi (Telegram'ning o'za pleyeri vendored
     * exoplayer2 + ExtendedDefaultDataSource bilan ishlaydi), shuning uchun
     * fayl Telegram'ning o'z [FileLoader]'i bilan attachment papkasiga
     * tushiriladi va lokal `file://` sifatida ijro etiladi.
     */
    private fun activateStory(h: PageHolder, reel: Reel) {
        cancelStoryPoll()
        cancelPendingRetry()
        val pos = activePos
        val prevHolder = activeHolder
        activeHolder?.playerView?.player = null
        activeHolder = h
        h.errorRow.visibility = View.GONE
        if (h !== prevHolder) playAttempts = 0
        val p = ensurePlayer()
        p.removeListener(playerListener)
        p.addListener(playerListener)
        val account = UserConfig.selectedAccount
        if (!reel.storyIsVideo) {
            // Foto hikoya: oldingi videoni to'xtatamiz, pleyer pause holatida qoladi.
            p.pause()
            h.storyThumb.visibility = View.VISIBLE
            h.pageProgress.visibility = View.GONE
            markStoryViewedOnce(h, reel)
            return
        }
        h.storyThumb.visibility = View.VISIBLE
        val ready = YuzStoryFeed.resolveVideoFile(account, reel)
        if (ready != null) {
            playStoryFile(h, p, ready)
            return
        }
        h.pageProgress.visibility = View.VISIBLE
        var ticks = 0
        val poll = object : Runnable {
            override fun run() {
                storyPoll = null
                if (activeHolder !== h || activePos != pos) return
                val f = YuzStoryFeed.resolveVideoFile(account, reel)
                if (f != null) {
                    playStoryFile(h, ensurePlayer(), f)
                    return
                }
                YuzStoryFeed.startVideoDownload(account, reel, true)
                if (++ticks >= 60) {
                    // ~24 soniya bo'lmadi — oddiy retry paneli ko'rsatiladi.
                    h.pageProgress.visibility = View.GONE
                    val offline = !NetworkReachability.isOnline(appContext)
                    h.tvError.text = appContext.getString(
                        if (offline) R.string.reels_err_offline else R.string.reels_err_failed
                    )
                    h.errorRow.visibility = View.VISIBLE
                    h.errorRow.setOnClickListener { retryActive() }
                    return
                }
                storyPoll = this
                mainHandler.postDelayed(this, 400L)
            }
        }
        YuzStoryFeed.startVideoDownload(account, reel, true)
        storyPoll = poll
        mainHandler.postDelayed(poll, 350L)
    }

    private fun playStoryFile(h: PageHolder, p: ExoPlayer, f: File) {
        storyPoll = null
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
        p.prepare()
        p.playWhenReady = true
        h.playerView.player = p
        h.pageProgress.visibility = View.VISIBLE
    }

    /** Foto hikoya ko'rishlari — video bilan bir xil dedup (prefs). */
    private fun markStoryViewedOnce(h: PageHolder, reel: Reel) {
        if (!YuzViewedReels.markViewed(appContext, reel.id)) return
        val next = (viewsShown[reel.id] ?: reel.views) + 1
        viewsShown[reel.id] = next
        h.tvViews.text = viewsLabel(next)
        onViewed(reel.id)
    }

    fun pausePlayback() {
        player?.pause()
    }

    fun resumePlayback() {
        val p = player ?: return
        if (p.playWhenReady) p.play()
    }

    /** Firestore'dan like holati qaytganda UI'ni yangilaydi. */
    fun applyLikeState(reelId: String, isLiked: Boolean, serverCount: Int? = null) {
        if (liked[reelId] == isLiked && serverCount == null) return
        liked[reelId] = isLiked
        if (serverCount != null) likeShown[reelId] = serverCount
        val holder = activeHolder
        if (holder?.reel?.id == reelId) {
            holder.refreshLikeUi()
        }
    }

    /**
     * Izohlar sonini lokal ravishda oshiradi/kamaytiradi (yangi izoh yozilganda).
     * Asos sifatda serverdagi `commentCount` olinadi, keyingi qo'shishlar uning
     * ustiga yig'iladi — har safar reload talab qilinmaydi.
     */
    fun addCommentCount(reelId: String, by: Int) {
        val base = commentShown[reelId] ?: items.firstOrNull { it.id == reelId }?.commentCount ?: 0
        commentShown[reelId] = (base + by).coerceAtLeast(0)
        val holder = activeHolder
        if (holder?.reel?.id == reelId) {
            holder.refreshCommentUi()
        }
    }

    fun release() {
        mainHandler.removeCallbacks(progressTicker)
        cancelPendingRetry()
        cancelStoryPoll()
        activeHolder?.playerView?.player = null
        activeHolder = null
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        // Eski loyiha mexanizmi: (1) video [YuzVideoCache] disk keshi orqali
        // o'qiladi — bir marta yuklangan reel keyingi ko'rishda deyarli darhol
        // boshlanadi; (2) [DefaultLoadControl] o'yinni "katta yig'ib" emas,
        // birinchi imkon bo'ladigan joyda boshlaydi (bufferForPlayback ~150ms).
        // #99: sus tarmoqda kichik va sekin-to'ydigan buffer — birinchi kadr
        // tezroq chiqadi, qayta-bufferlash kamayadi; Wi-Fi'da eski katta qiymat.
        val slowNet = NetworkReachability.kind(appContext) == NetworkReachability.NetKind.CELLULAR
        val loadControl = DefaultLoadControl.Builder()
            // media3 1.0.2 API: min, max, playback, rebuffer (ms)
            .setBufferDurationsMs(
                if (slowNet) 500 else 800,
                if (slowNet) 6000 else 12000,
                if (slowNet) 120 else 150,
                if (slowNet) 300 else 400
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val created = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(
                ProgressiveMediaSource.Factory(
                    // #106: hikoya fayllari lokal `file://` keladi — ular
                    // YuzVideoCache'dan O'TMAYDI (kesh faqat HTTP uchun),
                    // qolgan URL'lar avvalgidek kesh orqali.
                    object : DataSource.Factory {
                        private val base = YuzVideoCache.dataSourceFactory(appContext)
                        override fun createDataSource(): DataSource =
                            RoutingDataSource(base.createDataSource())
                    }
                )
            )
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioBecomingNoisy = */ true
            )
            .build()
        created.repeatMode = Player.REPEAT_MODE_ONE
        created.volume = if (muted) 0f else 1f
        player = created
        mainHandler.post(progressTicker)
        return created
    }

    /**
     * Ovozni yoqish/o'chirish. Player hali yaratilmagan bo'lsa ham holat saqlanadi —
     * keyin `ensurePlayer()` uni qo'llaydi.
     * @return yangi holat (`true` = o'chiq)
     */
    fun toggleMute(): Boolean {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
        return muted
    }

    fun isMuted(): Boolean = muted

    private fun findHolder(pos: Int): PageHolder? =
        recyclerView?.findViewHolderForAdapterPosition(pos) as? PageHolder

    private fun compactCount(count: Int): String = when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
        count >= 1_000 -> String.format("%.1fk", count / 1_000f)
        else -> count.toString()
    }

    /** Tanlangan layk rangi — ikon va son bir xil belgini beradi. */
    private val likeOnColor = ContextCompat.getColor(appContext, R.color.yuz_like)
    private val likeOffColor = ContextCompat.getColor(appContext, R.color.yuz_text)

    private fun dpF(value: Float): Float = AndroidUtilities.dp(value).toFloat()

    // Ko'z ikonkasi XML tarafda (drawableStart/ImageView) — bu yerda faqat son.
    private fun viewsLabel(count: Int): String = compactCount(count)

    inner class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val playerView: PlayerView = itemView.findViewById(R.id.playerView)
        val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        val storyThumbHost: FrameLayout = itemView.findViewById(R.id.storyThumbHost)
        lateinit var storyThumb: BackupImageView
        val pageProgress: ProgressBar = itemView.findViewById(R.id.pageProgress)
        val playbackBar: ProgressBar = itemView.findViewById(R.id.playbackBar)
        private val tvPlayPauseBadge: ImageView = itemView.findViewById(R.id.tvPlayPauseBadge)
        private val tvUploaderName: TextView = itemView.findViewById(R.id.tvUploaderName)
        private val tvAvatarInitial: TextView = itemView.findViewById(R.id.tvAvatarInitial)
        private val uploaderRow: View = itemView.findViewById(R.id.uploaderRow)
        private val tvCaption: TextView = itemView.findViewById(R.id.tvCaption)
        private val btnLike: ImageView = itemView.findViewById(R.id.btnLike)
        private val tvLikeCount: TextView = itemView.findViewById(R.id.tvLikeCount)
        private val btnComments: ImageView = itemView.findViewById(R.id.btnComments)
        private val tvCommentCount: TextView = itemView.findViewById(R.id.tvCommentCount)
        val tvViews: TextView = itemView.findViewById(R.id.tvViews)
        private val btnShare: ImageView = itemView.findViewById(R.id.btnShare)
        private val btnReelMenu: ImageView = itemView.findViewById(R.id.btnReelMenu)
        private val tvLikeBurst: ImageView = itemView.findViewById(R.id.tvLikeBurst)
        private val reelCtaRow: LinearLayout = itemView.findViewById(R.id.reelCtaRow)
        private val btnWatchMovie: TextView = itemView.findViewById(R.id.btnWatchMovie)
        private val btnAd: TextView = itemView.findViewById(R.id.btnAd)
        // #99: ijro uzilganda ko'rinadigan "Qayta urinish" qatori.
        val errorRow: LinearLayout = itemView.findViewById(R.id.reelErrorRow)
        val tvError: TextView = itemView.findViewById(R.id.tvReelError)

        var reel: Reel? = null

        /**
         * To'liq ekranda: bir bosish = play/pause, ikki bosish = like.
         * Kichik ko'rinishda: bir bosish = ovozni ochish + fullscreen rejimiga
         * o'tish (foydalanuvchi videosiz "shinqillab" qolmasin).
         */
        private val tapDetector = GestureDetector(appContext, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (fullscreen) {
                    togglePlay()
                } else {
                    if (muted) toggleMute()
                    onExpandTap()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                likeFromDoubleTap()
                return true
            }
        })

        init {
            playerView.useController = false
            bindTouch()
        }

        /**
         * Bosishlarni gesture detector orqali o'qiymiz.
         * PlayerView `clickable` bo'lishi shart: aks holda u ACTION_DOWN'ni
         * iste'mol qilmaydi va detector qolgan hodisalarni olmaydi.
         * RecyclerView drag'ni keyin o'zi tortib oladi — vertikal scroll ishlaydi.
         */
        @SuppressLint("ClickableViewAccessibility")
        private fun bindTouch() {
            playerView.isClickable = true
            playerView.setOnTouchListener { _, event ->
                tapDetector.onTouchEvent(event)
                true
            }
        }

        private fun likeFromDoubleTap() {
            val r = reel ?: return
            val already = liked[r.id] ?: r.isLikedByMe
            if (!already) {
                liked[r.id] = true
                likeShown[r.id] = (likeShown[r.id] ?: r.likeCount) + 1
                refreshLikeUi()
                popLike()
                onLikeToggle(r.id, true)
            }
            showLikeBurst()
        }

        private fun showLikeBurst() {
            tvLikeBurst.animate().cancel()
            tvLikeBurst.alpha = 1f
            tvLikeBurst.scaleX = 1.15f
            tvLikeBurst.scaleY = 1.15f
            tvLikeBurst.visibility = View.VISIBLE
            tvLikeBurst.animate()
                .alpha(0f)
                .scaleX(1.7f)
                .scaleY(1.7f)
                .setDuration(520L)
                .withEndAction { tvLikeBurst.visibility = View.GONE }
                .start()
        }

        /** Reel'ni tizim ulashish menyusiga chiqaradi (sarlavha + hashtag + URL). */
        private fun shareReel() {
            val r = reel ?: return
            // #106: hikoyada URL yo'q — matnli ulashish (sarlavha + muallif).
            if (r.videoUrl.isBlank() && !r.isStory) return
            val header = r.title.ifBlank { appContext.getString(R.string.reels_title) }
            val body = buildString {
                append(header)
                if (r.hashtags.isNotBlank()) {
                    append("\n"); append(r.hashtags)
                }
                val by = r.uploadedByName.takeIf { it.isNotBlank() }
                if (by != null) {
                    append("\n"); append("— ").append(by)
                }
                if (r.videoUrl.isNotBlank()) {
                    append("\n"); append(r.videoUrl)
                }
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, body)
            }
            try {
                appContext.startActivity(
                    Intent.createChooser(send, appContext.getString(R.string.reels_share))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                Toast.makeText(appContext, R.string.reels_share_failed, Toast.LENGTH_SHORT).show()
            }
        }

        /** Tashqi havola (kino/sponsor) — brauzer yoki o'ynatuvchi o'zi tanlaydi. */
        private fun openUrl(url: String) {
            if (url.isBlank()) return
            try {
                appContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                Toast.makeText(appContext, R.string.reels_link_failed, Toast.LENGTH_SHORT).show()
            }
        }

        fun bind(reel: Reel) {
            this.reel = reel
            playbackBar.progress = 0
            errorRow.visibility = View.GONE
            // Holder boshqa reel uchun qayta ishlatilganda animatsiya
            // qiymatlari bilan kelishi mumkin — maydonni tozalaymiz.
            btnLike.animate().cancel()
            btnLike.scaleX = 1f
            btnLike.scaleY = 1f
            tvLikeCount.animate().cancel()
            tvLikeCount.translationY = 0f
            cancelBadgeHide()
            tvPlayPauseBadge.animate().cancel()
            tvPlayPauseBadge.alpha = 0f
            tvPlayPauseBadge.visibility = View.GONE
            val name = reel.uploadedByName.ifBlank { "YuzGram" }
            tvUploaderName.text = name
            tvAvatarInitial.text = name.take(1).uppercase()
            uploaderRow.setOnClickListener {
                val r = this.reel ?: return@setOnClickListener
                if (r.uploadedByUid.isNotBlank()) onOpenProfile(r)
            }
            tvCaption.text = reel.caption()
            tvViews.text = viewsLabel(viewsShown[reel.id] ?: reel.views)
            if (reel.isStory) {
                // #106: hikoya posti — Telegram'ning o'z ImageReceiver manbasi
                // (Glide/HTTP yo'q): striped thumb, to'liq rasm, shifrlash.
                ivThumbnail.visibility = View.GONE
                ivThumbnail.setImageDrawable(null)
                storyThumb.visibility = View.VISIBLE
                bindStoryThumb(reel)
            } else {
                storyThumb.clearImage()
                storyThumb.visibility = View.GONE
                ivThumbnail.visibility = View.VISIBLE
                if (reel.thumbnailUrl.isNotBlank()) {
                    Glide.with(ivThumbnail)
                        .load(reel.thumbnailUrl)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .placeholder(R.drawable.bg_classified_image_placeholder)
                        .error(R.drawable.bg_classified_image_placeholder)
                        .into(ivThumbnail)
                } else {
                    ivThumbnail.setImageDrawable(null)
                    ivThumbnail.setBackgroundColor(ContextCompat.getColor(appContext, R.color.yuz_bg))
                }
            }
            refreshLikeUi()
            btnLike.setOnClickListener {
                val r = reel ?: return@setOnClickListener
                val nowLiked = !(liked[r.id] ?: r.isLikedByMe)
                liked[r.id] = nowLiked
                val base = likeShown[r.id] ?: r.likeCount
                likeShown[r.id] = if (nowLiked) base + 1 else base - 1
                refreshLikeUi()
                if (nowLiked) popLike()
                onLikeToggle(r.id, nowLiked)
            }
            refreshCommentUi()
            btnComments.setOnClickListener {
                val r = this.reel ?: return@setOnClickListener
                onOpenComments(r.id)
            }
            btnShare.setOnClickListener { shareReel() }
            // "⋮" faqat reel egasiga ko'rinadi (Firestore qoidasi ham shuni tekshiradi).
            // #106: hikoyalar o'chirilmaydi — ular Telegram'da boshqariladi.
            val mine = !reel.isStory && myUid.isNotBlank() && reel.uploadedByUid == myUid
            btnReelMenu.visibility = if (mine) View.VISIBLE else View.GONE
            if (mine) {
                btnReelMenu.setOnClickListener {
                    val r = this.reel ?: return@setOnClickListener
                    onDelete(r)
                }
            } else {
                btnReelMenu.setOnClickListener(null)
                btnReelMenu.isClickable = false
            }
            bindCta(reel)
        }

        /**
         * #106: hikoya postiri — [org.telegram.ui.Stories.PeerStoriesView]'dagi
         * bilan bir xil `setImage` chaqiruvi (storyItem — parentObject,
         * FileLoader hikoya reference'ini shundan oladi).
         */
        private fun bindStoryThumb(reel: Reel) {
            val account = UserConfig.selectedAccount
            val item = YuzStoryFeed.findStoryItem(account, reel.storyDialogId, reel.storyId)
            if (item == null) {
                storyThumb.clearImage()
                return
            }
            val filter = "yuzreel"
            try {
                if (reel.storyIsVideo) {
                    val doc = item.media?.getDocument()
                    if (doc == null) {
                        storyThumb.clearImage()
                        return
                    }
                    val thumbSize = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 1000)
                    val striped = ImageLoader.createStripedBitmap(doc.thumbs)
                    storyThumb.imageReceiver.setImage(
                        null, null,
                        ImageLocation.getForDocument(doc), filter + "_pframe",
                        if (thumbSize != null) ImageLocation.getForDocument(thumbSize, doc) else null,
                        filter, striped, 0L, null, item, 0
                    )
                } else {
                    val photo = item.media?.photo
                    if (photo == null || photo.sizes == null || photo.sizes.isEmpty()) {
                        storyThumb.clearImage()
                        return
                    }
                    val striped = ImageLoader.createStripedBitmap(photo.sizes)
                    val size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, Integer.MAX_VALUE)
                    storyThumb.imageReceiver.setImage(
                        null, null,
                        ImageLocation.getForPhoto(size, photo), filter,
                        null, null, striped, 0L, null, item, 0
                    )
                }
            } catch (_: Exception) {
                storyThumb.clearImage()
            }
        }

        /**
         * KinoTOP4'dan ko'chirilgan CTA qatori: reel'da `movieUrl` bo'lsa
         * "Kinoni ko'rish" tugmasi, `adTitle`+`adUrl` bo'lsa sponsor plani
         * chiqadi. Ikkalasi ham bo'sh — qator ko'rinmaydi.
         */
        private fun bindCta(reel: Reel) {
            val hasMovie = reel.movieUrl.isNotBlank()
            val hasAd = reel.adTitle.isNotBlank() && reel.adUrl.isNotBlank()
            btnWatchMovie.visibility = if (hasMovie) View.VISIBLE else View.GONE
            if (hasMovie) btnWatchMovie.setIconStart(R.drawable.ic_yuz_film, sizeDp = 13, gapDp = 6)
            btnAd.visibility = if (hasAd) View.VISIBLE else View.GONE
            if (hasAd) btnAd.text = reel.adTitle
            reelCtaRow.visibility = if (hasMovie || hasAd) View.VISIBLE else View.GONE
            btnWatchMovie.setOnClickListener {
                if (hasMovie) openUrl(reel.movieUrl)
            }
            btnAd.setOnClickListener {
                if (hasAd) openUrl(reel.adUrl)
            }
        }

        /** Izohlar soni — Firestore'dagi `commentCount` yoki lokal optimistik qiymat. */
        fun refreshCommentUi() {
            val r = reel ?: return
            tvCommentCount.text = compactCount(commentShown[r.id] ?: r.commentCount)
        }

        fun refreshLikeUi() {
            val r = reel ?: return
            val isLiked = liked[r.id] ?: r.isLikedByMe
            val count = likeShown[r.id] ?: r.likeCount
            btnLike.setImageResource(
                if (isLiked) R.drawable.ic_yuz_heart_filled else R.drawable.ic_yuz_heart
            )
            btnLike.setColorFilter(if (isLiked) likeOnColor else likeOffColor)
            tvLikeCount.text = compactCount(count)
            // Tanlangan holat sonida ham ko'rinsin: foydalanuvchi ro'yxatdan
            // chiqib qaytganda "men buni layk qilganmanmi" degan savol
            // ikonkaning to'ldirilganligigina emas, sonning rangi bilan ham
            // javoblanadi.
            tvLikeCount.setTextColor(if (isLiked) likeOnColor else likeOffColor)
        }

        /**
         * Layk bosilganda ikon qisqacha "urib" qaytadi (pastki navigatsiya
         * bari animatePop'i bilan bir xil harakat tili) va son bir zumda
         * ko'tarilib tushadi.
         */
        fun popLike() {
            btnLike.animate().cancel()
            btnLike.scaleX = 1.34f
            btnLike.scaleY = 1.34f
            btnLike.animate().scaleX(1f).scaleY(1f).setDuration(260L)
                .setInterpolator(OvershootInterpolator(2.2f)).start()
            tvLikeCount.animate().cancel()
            tvLikeCount.translationY = 0f
            tvLikeCount.animate().translationY(-dpF(4f)).setDuration(110L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    tvLikeCount.animate().translationY(0f).setDuration(190L)
                        .setInterpolator(OvershootInterpolator(1.6f)).start()
                }.start()
        }

        /**
         * Kechiktirilgan yashirinish laguchi — saqlanadi, chunki qayta-qayta
         * bosish eski runnable'ni yangi ko'rinishning ustidan o'tkazib
         * yubormasligi kerak.
         */
        private var badgeHide: Runnable? = null

        private fun cancelBadgeHide() {
            badgeHide?.let { mainHandler.removeCallbacks(it) }
            badgeHide = null
        }

        private fun fadeOutBadge() {
            cancelBadgeHide()
            if (tvPlayPauseBadge.visibility != View.VISIBLE) return
            tvPlayPauseBadge.animate().cancel()
            tvPlayPauseBadge.animate().alpha(0f).setDuration(150L)
                .withEndAction { tvPlayPauseBadge.visibility = View.GONE }
                .start()
        }

        /**
         * Play/pause belgisi. Qattiq `VISIBLE/GONE` o'rniga cross-fade: 72dp'lik
         * dyha video ustida "sakrab" yo'qolishi g'alati ko'rinadi, fade esa
         * boshqa qatlamlar (poster, yurak) bilan bir harakat tilida gapiradi.
         */
        fun setPlayingBadge(isPlaying: Boolean) {
            cancelBadgeHide()
            if (isPlaying) {
                fadeOutBadge()
                return
            }
            tvPlayPauseBadge.setImageResource(R.drawable.ic_yuz_play)
            val wasHidden = tvPlayPauseBadge.visibility != View.VISIBLE
            tvPlayPauseBadge.visibility = View.VISIBLE
            tvPlayPauseBadge.animate().cancel()
            if (wasHidden) {
                tvPlayPauseBadge.alpha = 0f
                tvPlayPauseBadge.scaleX = 0.84f
                tvPlayPauseBadge.scaleY = 0.84f
            }
            tvPlayPauseBadge.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(190L).setInterpolator(DecelerateInterpolator()).start()
            val hide = Runnable {
                badgeHide = null
                if (player?.isPlaying != true) fadeOutBadge()
            }
            badgeHide = hide
            mainHandler.postDelayed(hide, 900L)
        }

        private fun togglePlay() {
            val p = player ?: return
            if (p.isPlaying) p.pause() else p.play()
        }
    }
}

/**
 * #106: sxema bo'yicha manba yo'naltiruvchi. Lokal fayllar (FileLoader
 * tushirgan hikoya videolari `file://`) to'g'ridan-to'g'ri [FileDataSource]
 * bilan o'qiladi — ularni HTTP-kesh orqali o'tkazish xato; qolgan barcha
 * URL'lar o'rab olingan fabrikaga (YuzVideoCache) beriladi.
 */
private class RoutingDataSource(private val remote: DataSource) : DataSource {
    private var current: DataSource = remote

    override fun open(dataSpec: DataSpec): Long {
        current = if (dataSpec.uri?.scheme == "file") FileDataSource() else remote
        return current.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current.read(buffer, offset, length)

    override fun getUri(): Uri? = current.uri

    override fun addTransferListener(listener: TransferListener) {
        current.addTransferListener(listener)
        remote.addTransferListener(listener)
    }

    override fun close() {
        current.close()
    }
}
