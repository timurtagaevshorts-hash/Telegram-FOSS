package uz.kodlikino.top4.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.telegram.messenger.R
import uz.kodlikino.top4.models.Reel

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
    private val onOpenComments: (reelId: String) -> Unit
) : RecyclerView.Adapter<ReelsAdapter.PageHolder>() {

    private val items = ArrayList<Reel>()

    /** Optimistik UI holati — Firestore'dan qaytgan to'liq ro'yxatni buzmasligi uchun. */
    private val likeShown = HashMap<String, Int>()
    private val liked = HashMap<String, Boolean>()
    private val viewsShown = HashMap<String, Int>()
    private val commentShown = HashMap<String, Int>()
    private val viewedOnce = HashSet<String>()

    private var player: ExoPlayer? = null
    private var recyclerView: RecyclerView? = null
    private var activePos = -1
    private var activeHolder: PageHolder? = null

    /** Ovoz holati — sahifalar orasida saqlanadi (yangi videoda qayta qo'llanadi). */
    private var muted = false

    private val mainHandler = Handler(Looper.getMainLooper())

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
                    if (reel != null && viewedOnce.add(reel.id)) {
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
        // viewedOnce SAQLANADI — aks holda har bir refresh (onResume) ko'rishni
        // qaytadan hisoblab, sun'iy ravishda ko'rishlar sonini oshirardi.
        activePos = -1
        activeHolder = null
        notifyDataSetChanged()
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
        val created = ExoPlayer.Builder(appContext)
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

    private fun viewsLabel(count: Int): String = "👁 ${compactCount(count)}"

    inner class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val playerView: PlayerView = itemView.findViewById(R.id.playerView)
        val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        val pageProgress: ProgressBar = itemView.findViewById(R.id.pageProgress)
        private val tvPlayPauseBadge: TextView = itemView.findViewById(R.id.tvPlayPauseBadge)
        private val tvUploaderName: TextView = itemView.findViewById(R.id.tvUploaderName)
        private val tvAvatarInitial: TextView = itemView.findViewById(R.id.tvAvatarInitial)
        private val tvCaption: TextView = itemView.findViewById(R.id.tvCaption)
        private val btnLike: TextView = itemView.findViewById(R.id.btnLike)
        private val tvLikeCount: TextView = itemView.findViewById(R.id.tvLikeCount)
        private val btnComments: TextView = itemView.findViewById(R.id.btnComments)
        private val tvCommentCount: TextView = itemView.findViewById(R.id.tvCommentCount)
        val tvViews: TextView = itemView.findViewById(R.id.tvViews)

        var reel: Reel? = null

        init {
            playerView.useController = false
            playerView.setOnClickListener { togglePlay() }
        }

        fun bind(reel: Reel) {
            this.reel = reel
            val name = reel.uploadedByName.ifBlank { "YuzGram" }
            tvUploaderName.text = name
            tvAvatarInitial.text = name.take(1).uppercase()
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
                onLikeToggle(r.id, nowLiked)
            }
            refreshCommentUi()
            btnComments.setOnClickListener {
                val r = this.reel ?: return@setOnClickListener
                onOpenComments(r.id)
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
            btnLike.text = if (isLiked) "♥" else "♡"
            btnLike.setTextColor(if (isLiked) 0xFFFF4D6D.toInt() else Color.WHITE)
            tvLikeCount.text = compactCount(count)
        }

        fun setPlayingBadge(isPlaying: Boolean) {
            if (isPlaying) {
                tvPlayPauseBadge.visibility = View.GONE
            } else {
                tvPlayPauseBadge.text = "▶"
                tvPlayPauseBadge.visibility = View.VISIBLE
                mainHandler.postDelayed({
                    if (player?.isPlaying != true) tvPlayPauseBadge.visibility = View.GONE
                }, 900L)
            }
        }

        private fun togglePlay() {
            val p = player ?: return
            if (p.isPlaying) p.pause() else p.play()
        }
    }
}
