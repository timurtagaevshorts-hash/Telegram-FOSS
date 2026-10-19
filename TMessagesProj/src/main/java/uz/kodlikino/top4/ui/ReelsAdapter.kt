package uz.kodlikino.top4.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
import uz.kodlikino.top4.utils.YuzViewedReels
import uz.kodlikino.top4.utils.YuzVideoCache

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
                Player.STATE_BUFFERING -> holder.pageProgress.visibility = View.VISIBLE
                Player.STATE_READY -> {
                    holder.pageProgress.visibility = View.GONE
                    holder.ivThumbnail.visibility = View.GONE
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
        return PageHolder(view)
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
        val p = ensurePlayer()
        activeHolder?.playerView?.player = null
        activeHolder = h
        p.removeListener(playerListener)
        p.addListener(playerListener)
        p.setMediaItem(MediaItem.fromUri(reel.videoUrl))
        p.prepare()
        p.playWhenReady = true
        h.playerView.player = p
        h.pageProgress.visibility = View.VISIBLE
    }

    /** Sahifa qayta ko'rinmaganda chaqiriladi (masalan, CreateReel'dan qaytish). */
    fun reactivate() {
        val pos = activePos
        activePos = -1
        setActivePosition(pos)
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
        val loadControl = DefaultLoadControl.Builder()
            // media3 1.0.2 API: min=800ms, max=12s, playback=150ms, rebuffer=400ms
            // (setBufferForPlaybackMs faqat 1.1.0+ da bor — shu eski imzo bilan).
            .setBufferDurationsMs(800, 12000, 150, 400)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val created = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(
                ProgressiveMediaSource.Factory(YuzVideoCache.dataSourceFactory(appContext))
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
    private val likeOnColor = 0xFFFF4D6D.toInt()

    private fun dpF(value: Float): Float = AndroidUtilities.dp(value).toFloat()

    // Ko'z ikonkasi XML tarafda (drawableStart/ImageView) — bu yerda faqat son.
    private fun viewsLabel(count: Int): String = compactCount(count)

    inner class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val playerView: PlayerView = itemView.findViewById(R.id.playerView)
        val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
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
            if (r.videoUrl.isBlank()) return
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
                append("\n"); append(r.videoUrl)
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
                ivThumbnail.setBackgroundColor(0xFF0A1120.toInt())
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
            val mine = myUid.isNotBlank() && reel.uploadedByUid == myUid
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
            btnLike.setColorFilter(if (isLiked) likeOnColor else Color.WHITE)
            tvLikeCount.text = compactCount(count)
            // Tanlangan holat sonida ham ko'rinsin: foydalanuvchi ro'yxatdan
            // chiqib qaytganda "men buni layk qilganmanmi" degan savol
            // ikonkaning to'ldirilganligigina emas, sonning rangi bilan ham
            // javoblanadi.
            tvLikeCount.setTextColor(if (isLiked) likeOnColor else Color.WHITE)
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
