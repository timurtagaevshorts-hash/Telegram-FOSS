package uz.kodlikino.top4.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import org.telegram.messenger.R
import uz.kodlikino.top4.models.Reel
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.YuzStoryStreamDataSource
import org.telegram.ui.Components.BackupImageView
import uz.kodlikino.top4.utils.NetworkReachability
import uz.kodlikino.top4.utils.YuzReelPreloader
import uz.kodlikino.top4.utils.YuzStoryFeed
import uz.kodlikino.top4.utils.YuzVideoCache
import uz.kodlikino.top4.utils.YuzViewedReels
import java.io.File
import kotlin.math.abs

/**
 * Reels feed adapter'i — vertikal [RecyclerView] sahifalari uchun adapter
 * (har bir sahifa butun ekranga [androidx.recyclerview.widget.PagerSnapHelper]
 * bilan yopishadi).
 *
 * Ijro modeli (ikki pleyerli "iliq hovuz"):
 *  - Faol [ExoPlayer] ko'rinib turgan sahifaning `PlayerView.player`iga
 *    biriktiriladi; ikkinchisi fon rejimida KEYINGI reel'ni oldindan
 *    tayyorlaydi (`warmNext`). Sahifa almashganda pleyerlar joy almashadi —
 *    `prepare()` ham, tarmoq kutishi ham bo'lmaydi.
 *  - `repeatMode = REPEAT_MODE_ONE` — video tugagach boshidan aylanadi.
 *  - Ekranni bosish play/pause; bosib turib gorizontal surish — Story
 *    Scrubbing (vaqtni tanlash, `SeekParameters.CLOSEST_SYNC` bilan tez).
 *  - Qattiq controller yoqilmaydi (minSdk 21).
 *
 * Like/ko'rish ko'rsatkichlari optimistik yangilanadi; yozuv [ReelsRepository]
 * orqali Firestore'ga ketadi (calls orqali qaytadi).
 */
class ReelsAdapter(
    private val appContext: Context,
    private val account: Int,
    private val onLikeToggle: (reelId: String, likedNow: Boolean) -> Unit,
    private val onViewed: (reelId: String) -> Unit,
    private val onOpenComments: (reelId: String) -> Unit,
    /** Joriy foydachi uid'si (`tg_<telegramId>`) — o'z reel'ini o'chirish uchun. */
    private val myUid: String,
    private val onDelete: (Reel) -> Unit,
    /** Muallif ustuni bosilganda — reel egasining YuzGram profilini ochadi. */
    private val onOpenProfile: (Reel) -> Unit,
    /** "Do'stlarga yuborish" — Instagram uslubidagi Telegram chat tanlagichi. */
    private val onShareReel: (Reel) -> Unit,
    /** Sahifa kichik holatda bosilganda — ovozni ochish + to'liq ekran (chaqiruvchi ochadi). */
    private val onExpandTap: () -> Unit
) : RecyclerView.Adapter<ReelsAdapter.PageHolder>() {

    private val items = ArrayList<Reel>()

    /** Optimistik UI holati — server/Telegram sonlari kelguncha. */
    private val likeShown = HashMap<String, Int>()
    private val liked = HashMap<String, Boolean>()
    private val commentShown = HashMap<String, Int>()

    /**
     * Ikki pleyerli hovuz: faol + "iliq" (keyingi reel oldindan tayyorlangan).
     * Sahifa almashganda iliq pleyer faolga aylanadi — video tayyor holatda
     * boshlanadi, `prepare()` kutishisiz. Bo'shagan pleyer keyingi sahifani
     * oldindan tayyorlab qo'yadi (Instagram/Telegram uslubi).
     */
    private val players = arrayOfNulls<ExoPlayer>(2)
    private val playerListeners = arrayOfNulls<Player.Listener>(2)
    private var activePlayerIndex = 0
    private val player: ExoPlayer? get() = players[activePlayerIndex]
    private val warmPlayer: ExoPlayer? get() = players[1 - activePlayerIndex]
    private var warmRunnable: Runnable? = null
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
        set(value) {
            if (field == value) return
            field = value
            // Markaziy ovoz belgisi faqat kichik ko'rinishda ko'rinadi.
            activeHolder?.refreshSoundUi()
        }

    private val mainHandler = Handler(Looper.getMainLooper())
    // #120: release() dan keyin post etilgan retry Runnable'lar faollashuvni
    // qayta boshlab, o'lib ketgan ekranda yangi ExoPlayer yaratardi.
    private var released = false
    private var playbackEnabled = false
    private var userPaused = false
    private var generation = 0
    private var pendingActivation: Runnable? = null
    private var pendingStoryFileName: String? = null
    private var renderedReelId: String? = null

    private val downloadObserver = NotificationCenter.NotificationCenterDelegate { id, receivedAccount, args ->
        val fileName = pendingStoryFileName ?: return@NotificationCenterDelegate
        if (released || receivedAccount != account || args.firstOrNull() != fileName) {
            return@NotificationCenterDelegate
        }
        val h = activeHolder ?: return@NotificationCenterDelegate
        if (!canPlay(h)) return@NotificationCenterDelegate
        if (id == NotificationCenter.fileLoaded) {
            try {
                val reel = h.reel ?: return@NotificationCenterDelegate
                val ready = YuzStoryFeed.resolveVideoFile(account, reel, args.getOrNull(1) as? File)
                if (ready != null) startMedia(h, playerFor(reel.id), Uri.fromFile(ready), reel.id)
                else showActivateError(h)
            } catch (_: Exception) {
                showActivateError(h)
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            cancelStoryPoll()
            pendingStoryFileName = null
            showActivateError(h)
        }
    }

    init {
        NotificationCenter.getInstance(account).addObserver(downloadObserver, NotificationCenter.fileLoaded)
        NotificationCenter.getInstance(account).addObserver(downloadObserver, NotificationCenter.fileLoadFailed)
    }

    private fun canPlay(h: PageHolder): Boolean =
        !released && playbackEnabled && !ApplicationLoader.mainInterfacePaused && !SharedConfig.appLocked &&
            activeHolder === h && h.reel?.id == items.getOrNull(activePos)?.id && h.adapterPosition == activePos &&
            h.itemView.isAttachedToWindow && h.itemView.isShown && h.itemView.hasWindowFocus() &&
            h.itemView.windowVisibility == View.VISIBLE

    private fun cancelActivation() {
        pendingActivation?.let { mainHandler.removeCallbacks(it) }
        pendingActivation = null
        cancelWarm()
    }

    private fun silence() {
        player?.volume = 0f
        player?.pause()
    }

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
            if (released || !playbackEnabled) return
            if (holder == null || !canPlay(holder)) {
                silence()
                mainHandler.postDelayed(this, 250L)
                return
            }
            if (p != null) {
                val duration = p.duration
                if (duration > 0L && duration < Long.MAX_VALUE) {
                    holder.playbackBar.max = 1000
                    holder.playbackBar.progress =
                        ((p.currentPosition * 1000L) / duration).toInt().coerceIn(0, 1000)
                }
                mainHandler.postDelayed(this, 250L)
            }
        }
    }

    private fun playingHolder(): PageHolder? {
        val h = activeHolder ?: return null
        if (!canPlay(h) || player?.currentMediaItem?.mediaId != h.reel?.id) return null
        return h
    }

    /**
     * Har bir pleyer o'z listener'iga ega. Iliq pleyer fon rejimida
     * tayyorlanadi — uning holati UI'ga ta'sir qilmasligi kerak, shuning
     * uchun faqat faol pleyer hodisalari qabul qilinadi.
     */
    private fun createPlayerListener(p: ExoPlayer): Player.Listener = object : Player.Listener {
        private fun isLive(): Boolean = !released && player === p

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isLive()) return
            playingHolder()?.setPlayingBadge(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!isLive()) return
            val holder = playingHolder() ?: return
            holder.pageProgress.visibility =
                if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_READY) holder.errorRow.visibility = View.GONE
        }

        override fun onRenderedFirstFrame() {
            if (!isLive()) return
            val holder = playingHolder() ?: return
            val reel = holder.reel ?: return
            renderedReelId = reel.id
            holder.pageProgress.visibility = View.GONE
            holder.errorRow.visibility = View.GONE
            holder.ivThumbnail.visibility = View.GONE
            if (reel.storyIsVideo) holder.storyThumb.visibility = View.GONE
            registerViewOnce(reel)
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            if (!isLive()) return
            val holder = playingHolder() ?: return
            silence()
            if (playAttempts < 3) {
                playAttempts++
                val delay = 900L * playAttempts
                val token = generation
                cancelPendingRetry()
                val retry = Runnable {
                    pendingRetry = null
                    if (token == generation && canPlay(holder)) {
                        try {
                            activate(holder)
                        } catch (_: Exception) {
                            showActivateError(holder)
                        }
                    }
                }
                pendingRetry = retry
                mainHandler.postDelayed(retry, delay)
                return
            }
            showActivateError(holder)
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
        try {
            activate(h)
        } catch (_: Exception) {
            showActivateError(h)
        }
    }

    fun submit(list: List<Reel>) {
        val previousId = items.getOrNull(activePos)?.id
        val retained = list.indexOfFirst { it.id == previousId }
        silence()
        generation++
        cancelActivation()
        cancelPendingRetry()
        cancelStoryPoll()
        pendingStoryFileName = null
        cancelWarm()
        activeHolder?.playerView?.player = null
        if (retained < 0) {
            player?.clearMediaItems()
            warmPlayer?.clearMediaItems()
            userPaused = false
        }
        renderedReelId = null
        items.clear()
        items.addAll(list)
        likeShown.clear()
        liked.clear()
        commentShown.keys.retainAll(list.map { it.id }.toSet())
        activePos = retained
        activeHolder = null
        notifyDataSetChanged()
    }

    /**
     * Loop uchun yangi aralashma qo'shadi. Faol sahifa/pleyer tegilmaydi —
     * `notifyItemRangeInserted` mavjud holderlarni saqlab qoladi.
     */
    fun append(list: List<Reel>) {
        if (list.isEmpty() || released) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
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
        commentShown.remove(reelId)
        if (activePos == idx) {
            silence()
            generation++
            cancelActivation()
            cancelPendingRetry()
            cancelStoryPoll()
            pendingStoryFileName = null
            cancelWarm()
            player?.clearMediaItems()
            warmPlayer?.clearMediaItems()
            activeHolder?.playerView?.player = null
            activeHolder = null
            // O'chirilgan sahifa o'rniga keyingi (yoki oldingi) reel keladi —
            // `reactivate()` shu indeksdan davom etadi. Ilgari -1 qolib,
            // reactivate() hech narsa qilmasdi va feed qorayib qolardi.
            activePos = if (items.isEmpty()) -1 else idx.coerceAtMost(items.size - 1)
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
        // Telegram avatari: BackupImageView XML'da yaratilmaydi (faqat Context
        // konstruktori bor), shuning uchun code'da qo'shamiz. ImageReceiver
        // akkauntni bilishi shart — aks holda avatar yuklanmaydi.
        val avatar = BackupImageView(parent.context)
        avatar.setRoundRadius(AndroidUtilities.dp(17f))
        avatar.getImageReceiver().setCurrentAccount(account)
        avatar.visibility = View.GONE
        holder.avatarHost.addView(
            avatar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        holder.avatarImage = avatar
        return holder
    }

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val reel = items.getOrNull(position) ?: return
        // #113: bind RecyclerView LAYOUT pass'ida ishlaydi — tashqaridagi
        // try/catch'lar bunga yetmaydi, shuning uchun shu cho'qqida ushlaymiz.
        try {
            holder.bind(reel)
        } catch (_: Exception) {
            holder.itemView.setBackgroundColor(android.graphics.Color.BLACK)
            runCatching {
                holder.errorRow.visibility = View.GONE
                holder.pageProgress.visibility = View.GONE
            }
        }
        // Faqat faol sahifa player'ga ega bo'ladi — qolganlari bo'sh qoladi.
        if (holder !== activeHolder) {
            try {
                holder.playerView.player = null
            } catch (_: Exception) {
            }
        }
    }

    override fun onViewRecycled(holder: PageHolder) {
        super.onViewRecycled(holder)
        // #113: recycling ham layout-dispatch'da yuradi.
        holder.cancelScrubState()
        try {
            holder.playerView.player = null
            holder.storyThumb.clearImage()
        } catch (_: Exception) {
        }
        if (holder === activeHolder) {
            silence()
            generation++
            cancelActivation()
            cancelStoryPoll()
            cancelPendingRetry()
            pendingStoryFileName = null
            activeHolder = null
        }
    }

    override fun onViewDetachedFromWindow(holder: PageHolder) {
        holder.cancelScrubState()
        if (holder === activeHolder) {
            silence()
            generation++
            cancelActivation()
            cancelStoryPoll()
            cancelPendingRetry()
            pendingStoryFileName = null
            holder.playerView.player = null
            activeHolder = null
        }
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewAttachedToWindow(holder: PageHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder.adapterPosition == activePos && playbackEnabled) setActivePosition(activePos)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        pausePlayback()
        activeHolder?.playerView?.player = null
        activeHolder = null
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    /**
     * "Bu sahifa endi ko'rinib turibdi" signali (snap bo'lgan pozitsiya).
     * Holder hali rezyulyatsiya bo'lmagan bo'lishi mumkin — RecyclerView layouti
     * keyingi kadrdagi ishlaydi, shuning uchun cheklangan urinishlar bilan qayta
     * urinamiz (aks holda birinchi sahifa videoyoq qolardi).
     */
    fun setActivePosition(pos: Int) {
        if (released || pos !in items.indices) return
        if (pos != activePos) {
            cancelWarm()
            activeHolder?.cancelScrubState()
            silence()
            generation++
            cancelActivation()
            cancelPendingRetry()
            cancelStoryPoll()
            pendingStoryFileName = null
            activeHolder?.playerView?.player = null
            activeHolder = null
            player?.clearMediaItems()
            renderedReelId = null
            userPaused = false
            playAttempts = 0
            activePos = pos
        }
        // Keyingi/oldingi reel'larning bosh qismini oldindan keshga tortamiz —
        // sahifa almashtirilganda video darhol boshlanadi (hikoyalar bu yerda
        // videoUrl bo'sh bo'lgani uchun o'tkazib yuboriladi; ularni
        // YuzStoryFeed.prepareNeighbours alohida tayyorlaydi).
        // Preloader IO koroutinada kechroq o'qiydi — jonli `items` ro'yxatini
        // berish o'chirish/qo'shish paytida ConcurrentModificationException
        // beradi, shuning uchun surat (snapshot) uzatamiz.
        YuzReelPreloader.preloadAround(appContext, items.toList(), pos)
        if (playbackEnabled) bindActive(pos, generation, 0)
    }

    private fun bindActive(pos: Int, token: Int, attempt: Int) {
        if (released || !playbackEnabled || generation != token || activePos != pos) return
        cancelActivation()
        val holder = findHolder(pos)
        if (holder != null && holder.reel?.id == items.getOrNull(pos)?.id &&
            holder.itemView.isAttachedToWindow && holder.itemView.isShown
        ) {
            activeHolder = holder
            try {
                activate(holder)
            } catch (_: Exception) {
                showActivateError(holder)
            }
            return
        }
        if (attempt >= 10) return
        val retry = Runnable {
            pendingActivation = null
            bindActive(pos, token, attempt + 1)
        }
        pendingActivation = retry
        mainHandler.postDelayed(retry, 50L)
    }

    /** Sahifani "video yo'q" holatiga tushiradi — crash o'rniga retry paneli. */
    private fun showActivateError(h: PageHolder) {
        silence()
        try {
            h.pageProgress.visibility = View.GONE
            val offline = !NetworkReachability.isOnline(appContext)
            h.tvError.text = appContext.getString(
                if (offline) R.string.reels_err_offline else R.string.reels_err_failed
            )
            h.errorRow.visibility = View.VISIBLE
            h.errorRow.setOnClickListener { retryActive() }
            // Xato paneli markazda — ovoz belgisi uning ustiga tushmasin.
            h.hideSoundUi()
        } catch (_: Exception) {
        }
    }

    private fun activate(holder: PageHolder?) {
        val h = holder ?: return
        if (!canPlay(h)) return
        val reel = items.getOrNull(activePos) ?: return
        if (reel.isStory) activateStory(h, reel) else activateRemote(h, reel)
    }

    /**
     * Firebase reel'i — HTTP orqali progressiv oqim. Avval to'liq yuklab olish
     * shart emas: ExoPlayer birinchi bufer bilan ijrodi boshlaydi, qolganini
     * oqim davomida tortadi (past internetda ham darhol ochiladi).
     */
    private fun activateRemote(h: PageHolder, reel: Reel) {
        if (!canPlay(h)) return
        if (reel.videoUrl.isBlank()) {
            showActivateError(h)
            return
        }
        try {
            // Disk kesh: oldindan yuklangan bo'laklar keshdan o'qiladi, qolgani
            // oqim davomida yoziladi. Qayta ko'rishda video deyarli darhol ochiladi.
            startSource(h, playerFor(reel.id), buildRemoteSource(reel), reel.id)
        } catch (_: Exception) {
            showActivateError(h)
        }
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
        if (!canPlay(h)) return
        cancelStoryPoll()
        cancelPendingRetry()
        pendingStoryFileName = null
        h.errorRow.visibility = View.GONE
        if (!reel.storyIsVideo) {
            silence()
            h.playerView.player = null
            h.storyThumb.visibility = View.VISIBLE
            h.pageProgress.visibility = View.GONE
            registerViewOnce(reel)
            // Foto hikoya pleyer talab qilmaydi, lekin keyingi reel iliq
            // tayyorlanishi kerak — sahifa almashishi bir zumda bo'lsin.
            scheduleWarm(300L)
            return
        }
        val ready = YuzStoryFeed.resolveVideoFile(account, reel)
        if (ready != null) {
            startMedia(h, playerFor(reel.id), Uri.fromFile(ready), reel.id)
            return
        }
        // Oqim: fayl to'liq yuklanishini kutmasdan ijro etamiz — past
        // internetda ham hikoya darhol boshlanadi. FileLoader'o'zi
        // attachment papkasiga yozadi, DataSource esa kelgan bo'laklarni
        // pleyerga uzatadi (`YuzStoryStreamDataSource`).
        val storyItem = try {
            YuzStoryFeed.findStoryItem(account, reel.storyDialogId, reel.storyId)
        } catch (_: Exception) {
            null
        }
        if (storyItem != null) {
            try {
                val source = ProgressiveMediaSource.Factory {
                    YuzStoryStreamDataSource(account, storyItem)
                }.createMediaSource(
                    MediaItem.Builder()
                        .setUri(
                            YuzStoryStreamDataSource.buildUri(
                                reel.storyDialogId,
                                reel.storyId
                            )
                        )
                        .setMediaId(reel.id)
                        .build()
                )
                h.storyThumb.visibility = View.VISIBLE
                h.pageProgress.visibility = View.VISIBLE
                startSource(h, playerFor(reel.id), source, reel.id)
                return
            } catch (_: Exception) {
                // Oqim manbasi qurilmasa — pastdagi "yuklab, keyin ijro" yo'li.
            }
        }
        silence()
        h.storyThumb.visibility = View.VISIBLE
        h.pageProgress.visibility = View.VISIBLE
        pendingStoryFileName = YuzStoryFeed.videoFileName(account, reel)
        if (!YuzStoryFeed.startVideoDownload(account, reel, true)) {
            pendingStoryFileName = null
            showActivateError(h)
            return
        }
        val token = generation
        var ticks = 0
        val poll = object : Runnable {
            override fun run() {
                storyPoll = null
                if (token != generation || !canPlay(h)) return
                try {
                    val f = YuzStoryFeed.resolveVideoFile(account, reel)
                    if (f != null) {
                        pendingStoryFileName = null
                        startMedia(h, playerFor(reel.id), Uri.fromFile(f), reel.id)
                        return
                    }
                    if (++ticks >= 60 && !FileLoader.getInstance(account).isLoadingFile(
                            YuzStoryFeed.videoFileName(account, reel)
                        )
                    ) {
                        pendingStoryFileName = null
                        showActivateError(h)
                        return
                    }
                } catch (_: Exception) {
                    pendingStoryFileName = null
                    showActivateError(h)
                    return
                }
                storyPoll = this
                mainHandler.postDelayed(this, 400L)
            }
        }
        storyPoll = poll
        mainHandler.postDelayed(poll, 350L)
    }

    /**
     * Tayyor [MediaSource]'ni pleyerga beradi (oqim yoki HTTP manba).
     * [startMedia]'dan farqi — `setMediaItem` emas, `setMediaSource` ishlatiladi,
     * chunki `yuzstory://` va HTTP uchun pleyerning standart file-only fabrikasi
     * yaramaydi.
     */
    private fun startSource(h: PageHolder, p: ExoPlayer, source: MediaSource, reelId: String) {
        if (!canPlay(h) || player !== p) return
        cancelStoryPoll()
        cancelPendingRetry()
        pendingStoryFileName = null
        h.playerView.player = p
        // Scrub'dan keyin pleyerda CLOSEST_SYNC qolgan bo'lishi mumkin —
        // oddiy ijro uchun aniq seek'ka qaytaramiz.
        p.setSeekParameters(SeekParameters.EXACT)
        if (p.currentMediaItem?.mediaId != reelId || p.playbackState == Player.STATE_IDLE) {
            silence()
            renderedReelId = null
            p.setMediaSource(source)
            p.prepare()
        }
        // Iliq pleyer shu media'ni allaqachon buferlagan bo'lsa — holat
        // READY bo'ladi va video bir zumda boshlanadi.
        h.pageProgress.visibility = if (p.playbackState == Player.STATE_READY) View.GONE else View.VISIBLE
        if (renderedReelId == reelId) {
            h.storyThumb.visibility = View.GONE
            h.ivThumbnail.visibility = View.GONE
        }
        p.volume = if (muted || userPaused) 0f else 1f
        p.playWhenReady = !userPaused
        h.refreshSoundUi()
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
        scheduleWarm()
    }

    private var remoteFactory: CacheDataSource.Factory? = null

    /**
     * Firebase Storage/HTTP uchun keshli manba. [YuzVideoCache] bilan bir xil
     * disk keshi [YuzReelPreloader] ham ishlatadi — oldindan yuklangan videolar
     * pleyerga keshdan darhol beriladi.
     */
    private fun remoteDataSourceFactory(): CacheDataSource.Factory {
        remoteFactory?.let { return it }
        val factory = YuzVideoCache.dataSourceFactory(appContext)
        remoteFactory = factory
        return factory
    }

    private fun startMedia(h: PageHolder, p: ExoPlayer, uri: Uri, reelId: String) {
        if (!canPlay(h) || player !== p) return
        cancelStoryPoll()
        cancelPendingRetry()
        pendingStoryFileName = null
        h.playerView.player = p
        p.setSeekParameters(SeekParameters.EXACT)
        if (p.currentMediaItem?.mediaId != reelId || p.playbackState == Player.STATE_IDLE) {
            silence()
            renderedReelId = null
            p.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(reelId).build())
            p.prepare()
        }
        h.pageProgress.visibility = if (p.playbackState == Player.STATE_READY) View.GONE else View.VISIBLE
        if (renderedReelId == reelId) {
            h.storyThumb.visibility = View.GONE
            h.ivThumbnail.visibility = View.GONE
        }
        p.volume = if (muted || userPaused) 0f else 1f
        p.playWhenReady = !userPaused
        h.refreshSoundUi()
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
        scheduleWarm()
    }

    /**
     * Ko'rish dedup'i — bir o'rnatishda bir marta. Ko'rsatilgan sonni bu yerda
     * o'zgartirmaymiz: Firebase hisoblagichi (hikoyada noyob ko'ruvchi hujjati)
     * yagona manba.
     */
    private fun registerViewOnce(reel: Reel) {
        if (!YuzViewedReels.markViewed(appContext, reel.id, account)) return
        onViewed(reel.id)
    }

    fun pausePlayback() {
        playbackEnabled = false
        cancelWarm()
        activeHolder?.cancelScrubState()
        silence()
        generation++
        cancelActivation()
        cancelPendingRetry()
        cancelStoryPoll()
        pendingStoryFileName = null
        activeHolder?.playerView?.player = null
        renderedReelId = null
        mainHandler.removeCallbacks(progressTicker)
    }

    fun resumePlayback() {
        if (released) return
        playbackEnabled = true
        if (activePos in items.indices) bindActive(activePos, generation, 0)
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
     * Feed qayta yuklanganda (ID'lar o'zgarmagan) yangi sonlarni faol videoni
     * qayta ochmasdan joyida qo'llaydi. `notifyDataSetChanged` bu yerda
     * ishlatilmaydi — u pleyer holatini nolga tashlab yuboradi.
     */
    fun applyStats(list: List<Reel>) {
        if (items.isEmpty()) return
        val byId = list.associateBy { it.id }
        val activeId = activeHolder?.reel?.id
        var activeChanged = false
        for (i in items.indices) {
            val fresh = byId[items[i].id] ?: continue
            val old = items[i]
            // Hikoya modeli statistikani bilmaydi — sonlar Firebase'dan
            // ([applyServerStats]) yuklanadi. Feed qayta qurilganda ular
            // nolga tushib ketmasligi uchun eski qiymatlar saqlanadi.
            val merged = if (fresh.isStory) {
                fresh.copy(
                    views = old.views,
                    likeCount = old.likeCount,
                    commentCount = old.commentCount,
                    isLikedByMe = old.isLikedByMe,
                    statsAvailable = old.statsAvailable
                )
            } else {
                fresh
            }
            if (old.views == merged.views && old.likeCount == merged.likeCount &&
                old.commentCount == merged.commentCount && old.statsAvailable == merged.statsAvailable &&
                old.isLikedByMe == merged.isLikedByMe
            ) continue
            items[i] = merged
            // Hikoyada feed statistikani olib kelmaydi — optimistik qatlam
            // (yangi layk/izoh) Firebase javobigacha saqlanishi kerak.
            if (!merged.isStory) {
                likeShown.remove(fresh.id)
                commentShown.remove(fresh.id)
                liked.remove(fresh.id)
            }
            if (fresh.id == activeId) activeChanged = true
        }
        if (activeChanged) {
            items.firstOrNull { it.id == activeId }?.let { activeHolder?.applyStats(it) }
        }
    }

    /** Like yozuvi muvaffaqiyatsiz — optimistik sonni ham asl holatiga qaytaramiz. */
    fun revertLike(reelId: String, isLiked: Boolean) {
        liked[reelId] = isLiked
        likeShown.remove(reelId)
        val holder = activeHolder
        if (holder?.reel?.id == reelId) holder.refreshLikeUi()
    }

    /** Firestore snapshot'i keldi — haqiqiy hisoblagichlarni ko'rsatadi. */
    fun applyServerStats(reelId: String, views: Int, likeCount: Int, commentCount: Int) {
        val idx = items.indexOfFirst { it.id == reelId }
        if (idx < 0) return
        val old = items[idx]
        if (old.views == views && old.likeCount == likeCount && old.commentCount == commentCount) return
        items[idx] = old.copy(views = views, likeCount = likeCount, commentCount = commentCount)
        // Serverdagi son o'zgargan bo'lsa, endi u manba — optimistik qatlam
        // faqat yozuv hali yetib bormagan holat uchun qoladi.
        if (old.likeCount != likeCount) likeShown.remove(reelId)
        if (old.commentCount != commentCount) commentShown.remove(reelId)
        val holder = activeHolder
        if (holder?.reel?.id == reelId) holder.applyStats(items[idx])
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
        pausePlayback()
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        NotificationCenter.getInstance(account).removeObserver(downloadObserver, NotificationCenter.fileLoaded)
        NotificationCenter.getInstance(account).removeObserver(downloadObserver, NotificationCenter.fileLoadFailed)
        activeHolder?.playerView?.player = null
        activeHolder = null
        for (i in players.indices) {
            val p = players[i] ?: continue
            playerListeners[i]?.let { p.removeListener(it) }
            p.release()
            players[i] = null
            playerListeners[i] = null
        }
        activePlayerIndex = 0
    }

    /** Faol slot pleyeri — PlayerView'ga shu biriktiriladi. */
    private fun ensurePlayer(): ExoPlayer {
        players[activePlayerIndex]?.let { return it }
        return createPlayer(activePlayerIndex)
    }

    private fun createPlayer(slot: Int): ExoPlayer {
        val slowNet = NetworkReachability.kind(appContext) == NetworkReachability.NetKind.CELLULAR
        val loadControl = DefaultLoadControl.Builder()
            // media3 1.0.2 API: min, max, playback, rebuffer (ms)
            .setBufferDurationsMs(
                if (slowNet) 700 else 1200,
                if (slowNet) 12000 else 30000,
                if (slowNet) 100 else 100,
                if (slowNet) 350 else 200
            )
            // Orqaga bufer — barmoq bilan orqaga surilganda qayta yuklash bo'lmaydi.
            .setBackBuffer(if (slowNet) 5000 else 15000, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val created = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory { FileDataSource() })
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        created.repeatMode = Player.REPEAT_MODE_ONE
        created.volume = 0f
        created.playWhenReady = false
        val listener = createPlayerListener(created)
        created.addListener(listener)
        players[slot] = created
        playerListeners[slot] = listener
        return created
    }

    /** Firebase reel'i — keshlangan HTTP progressiv manba. */
    private fun buildRemoteSource(reel: Reel): MediaSource =
        ProgressiveMediaSource.Factory(remoteDataSourceFactory())
            .createMediaSource(
                MediaItem.Builder()
                    .setUri(Uri.parse(reel.videoUrl))
                    .setMediaId(reel.id)
                    .build()
            )

    /**
     * Iloji bo'lsa iliq pleyerni faolga almashtiradi: sahifa almashganda
     * `prepare()` ham, tarmoq kutishi ham bo'lmaydi. Aks holda joriy faol
     * pleyerda yangi manba ochiladi.
     */
    private fun playerFor(reelId: String): ExoPlayer {
        val active = ensurePlayer()
        if (active.currentMediaItem?.mediaId == reelId) return active
        val idleIndex = 1 - activePlayerIndex
        val idle = players[idleIndex]
        if (idle != null && idle.currentMediaItem?.mediaId == reelId &&
            idle.playbackState != Player.STATE_IDLE
        ) {
            activeHolder?.playerView?.player = null
            active.volume = 0f
            active.pause()
            activePlayerIndex = idleIndex
            return idle
        }
        return active
    }

    private fun cancelWarm() {
        warmRunnable?.let { mainHandler.removeCallbacks(it) }
        warmRunnable = null
    }

    /**
     * Faol sahifa barqarorlashgach keyingi reel'ni iliq pleyerga oldindan
     * yuklaymiz. Kechikish — snap animatsiyasi tugasin, tarmoq esa hozirgi
     * videoga berilsin.
     */
    private fun scheduleWarm(delayMs: Long = 400L) {
        cancelWarm()
        if (released || !playbackEnabled) return
        val r = Runnable {
            warmRunnable = null
            warmNext()
        }
        warmRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun warmNext() {
        if (released || !playbackEnabled || activeHolder?.isScrubbing == true) return
        val nextPos = when {
            activePos + 1 in items.indices -> activePos + 1
            activePos - 1 in items.indices -> activePos - 1
            else -> return
        }
        val reel = items[nextPos]
        val idleIndex = 1 - activePlayerIndex
        val idle = players[idleIndex] ?: createPlayer(idleIndex)
        if (idle.currentMediaItem?.mediaId == reel.id) return
        idle.stop()
        idle.volume = 0f
        idle.playWhenReady = false
        idle.setSeekParameters(SeekParameters.EXACT)
        try {
            when {
                reel.isStory && !reel.storyIsVideo -> return
                reel.isStory -> {
                    val ready = YuzStoryFeed.resolveVideoFile(account, reel)
                    if (ready != null) {
                        idle.setMediaItem(
                            MediaItem.Builder()
                                .setUri(Uri.fromFile(ready))
                                .setMediaId(reel.id)
                                .build()
                        )
                    } else {
                        val item = YuzStoryFeed.findStoryItem(account, reel.storyDialogId, reel.storyId)
                            ?: return
                        idle.setMediaSource(
                            ProgressiveMediaSource.Factory {
                                YuzStoryStreamDataSource(account, item)
                            }.createMediaSource(
                                MediaItem.Builder()
                                    .setUri(
                                        YuzStoryStreamDataSource.buildUri(
                                            reel.storyDialogId,
                                            reel.storyId
                                        )
                                    )
                                    .setMediaId(reel.id)
                                    .build()
                            )
                        )
                    }
                }
                reel.videoUrl.isBlank() -> return
                else -> idle.setMediaSource(buildRemoteSource(reel))
            }
            idle.prepare()
        } catch (_: Exception) {
            // Iliq tayyorlash — yordamchi yo'l: xatosi faol ijroni buzmaydi.
        }
    }

    /**
     * Ovozni yoqish/o'chirish. Player hali yaratilmagan bo'lsa ham holat saqlanadi —
     * keyin `ensurePlayer()` uni qo'llaydi.
     * @return yangi holat (`true` = o'chiq)
     */
    fun toggleMute(): Boolean {
        muted = !muted
        val h = activeHolder
        player?.volume = if (muted || userPaused || h == null || !canPlay(h)) 0f else 1f
        h?.refreshSoundUi()
        return muted
    }

    fun isMuted(): Boolean = muted

    private fun findHolder(pos: Int): PageHolder? =
        recyclerView?.findViewHolderForAdapterPosition(pos) as? PageHolder

    private fun compactCount(count: Int): String = when {
        // #120: vergulli lokallarda (ru/uz) "%.1f" "1,2M" berar edi —
        // loyihadagi boshqa son formatlari kabi Locale.US.
        count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000f)
        count >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", count / 1_000f)
        else -> count.toString()
    }

    /** Tanlangan layk rangi — ikon va son bir xil belgini beradi. */
    private val likeOnColor = ContextCompat.getColor(appContext, R.color.yuz_like)
    private val likeOffColor = ContextCompat.getColor(appContext, R.color.yuz_on_media)

    private fun dpF(value: Float): Float = AndroidUtilities.dp(value).toFloat()

    // Ko'z ikonkasi XML tarafda (drawableStart/ImageView) — bu yerda faqat son.
    private fun viewsLabel(count: Int): String = compactCount(count)

    private companion object {
        /** Story Scrubbing: bosib turish shu vaqtdan keyin faollashadi (ms). */
        const val SCRUB_HOLD_MS = 320L
    }

    inner class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val playerView: PlayerView = itemView.findViewById(R.id.playerView)
        val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        val storyThumbHost: FrameLayout = itemView.findViewById(R.id.storyThumbHost)
        lateinit var storyThumb: BackupImageView
        val pageProgress: ProgressBar = itemView.findViewById(R.id.pageProgress)
        val playbackBar: ProgressBar = itemView.findViewById(R.id.playbackBar)
        private val tvPlayPauseBadge: ImageView = itemView.findViewById(R.id.tvPlayPauseBadge)
        private val tvUploaderName: TextView = itemView.findViewById(R.id.tvUploaderName)
        val avatarHost: FrameLayout = itemView.findViewById(R.id.reelAvatarHost)
        private val tvAvatarInitial: TextView = itemView.findViewById(R.id.tvAvatarInitial)
        private val ivAvatarPhoto: ImageView = itemView.findViewById(R.id.ivAvatarPhoto)
        private val btnSoundCenter: ImageView = itemView.findViewById(R.id.btnSoundCenter)
        lateinit var avatarImage: BackupImageView
        private val uploaderRow: View = itemView.findViewById(R.id.uploaderRow)
        private val tvCaption: TextView = itemView.findViewById(R.id.tvCaption)
        private val btnLike: ImageView = itemView.findViewById(R.id.btnLike)
        private val tvLikeCount: TextView = itemView.findViewById(R.id.tvLikeCount)
        private val btnComments: ImageView = itemView.findViewById(R.id.btnComments)
        private val tvCommentCount: TextView = itemView.findViewById(R.id.tvCommentCount)
        private val viewsRow: View = itemView.findViewById(R.id.viewsRow)
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

        // --- Story Scrubbing: bosib turib, barmoq bilan vaqtni tanlash ---
        private val scrubHud: LinearLayout = itemView.findViewById(R.id.reelScrubHud)
        private val tvScrubTime: TextView = itemView.findViewById(R.id.tvScrubTime)
        private val scrubBar: ProgressBar = itemView.findViewById(R.id.scrubBar)
        private val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop.toFloat()
        private var holdRunnable: Runnable? = null
        private var downX = 0f
        private var downY = 0f
        private var scrubbing = false
        private var preScrubPlaying = false
        private var scrubAnchorX = 0f
        private var scrubStartMs = 0L
        private var scrubDurationMs = 0L
        private var scrubTargetMs = 0L
        private var lastSeekAt = 0L
        private var lastTickSecond = -1

        val isScrubbing: Boolean get() = scrubbing

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
            // Markazdagi ovoz belgisi — kichik ko'rinishda bosilganda ovoz
            // yonadi va reel to'liq ekranga o'tadi (videoni bosish bilan bir xil).
            btnSoundCenter.setOnClickListener {
                if (muted) toggleMute()
                onExpandTap()
            }
        }

        fun hideSoundUi() {
            btnSoundCenter.visibility = View.GONE
        }

        /** Markaziy ovoz belgisini joriy holatga moslaydi (fullscreen'da yashirin). */
        fun refreshSoundUi() {
            if (fullscreen) {
                btnSoundCenter.visibility = View.GONE
                return
            }
            btnSoundCenter.visibility = View.VISIBLE
            btnSoundCenter.setImageResource(
                if (muted) R.drawable.ic_yuz_volume_off else R.drawable.ic_yuz_volume_on
            )
            btnSoundCenter.contentDescription = appContext.getString(
                if (muted) R.string.reels_sound_off else R.string.reels_sound_on
            )
        }

        /** Muallif avatari: Telegram avatari -> YuzGram rasm -> bosh harf. */
        /** Poster decode o'lchami — ekranning yarmi (past internetda 2–4x tez). */
        private fun thumbWidth(): Int = (AndroidUtilities.displaySize.x / 2).coerceAtLeast(360)

        private fun thumbHeight(): Int = (AndroidUtilities.displaySize.y / 2).coerceAtLeast(640)

        private fun bindAvatar(reel: Reel) {
            val dialogId = if (reel.isStory) reel.storyDialogId
            else reel.uploadedByUid.removePrefix("tg_").toLongOrNull() ?: 0L
            val pair = if (dialogId != 0L) YuzStoryFeed.avatarFor(account, dialogId) else null
            if (pair != null) {
                avatarImage.visibility = View.VISIBLE
                avatarImage.setForUserOrChat(pair.first, pair.second)
                ivAvatarPhoto.visibility = View.GONE
                ivAvatarPhoto.setImageDrawable(null)
                tvAvatarInitial.visibility = View.GONE
                return
            }
            avatarImage.visibility = View.GONE
            avatarImage.clearImage()
            val photo = reel.uploadedByPhotoUrl
            if (photo.isNotBlank()) {
                ivAvatarPhoto.visibility = View.VISIBLE
                tvAvatarInitial.visibility = View.GONE
                Glide.with(ivAvatarPhoto)
                    .load(photo)
                    .apply(RequestOptions.circleCropTransform())
                    .override(AndroidUtilities.dp(34f), AndroidUtilities.dp(34f))
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(ivAvatarPhoto)
            } else {
                Glide.with(ivAvatarPhoto).clear(ivAvatarPhoto)
                ivAvatarPhoto.visibility = View.GONE
                ivAvatarPhoto.setImageDrawable(null)
                tvAvatarInitial.visibility = View.VISIBLE
            }
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
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        scheduleHold()
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (scrubbing) {
                            scrubTo(event.x)
                            return@setOnTouchListener true
                        }
                        // Barmoq siljidi — bu bosib turish emas, scroll/tap.
                        if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                            cancelHold()
                        }
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        cancelHold()
                        if (scrubbing) {
                            cancelScrubState(apply = true)
                            // Scrub tugadi — tap detector'ga UP bermaymiz,
                            // aks holda u buni oddiy bosish deb play/pause qiladi.
                            return@setOnTouchListener true
                        }
                    }
                }
                tapDetector.onTouchEvent(event)
                true
            }
        }

        private fun scheduleHold() {
            cancelHold()
            if (reel == null) return
            val r = Runnable {
                holdRunnable = null
                beginScrub(downX)
            }
            holdRunnable = r
            mainHandler.postDelayed(r, SCRUB_HOLD_MS)
        }

        private fun cancelHold() {
            holdRunnable?.let { mainHandler.removeCallbacks(it) }
            holdRunnable = null
        }

        /**
         * 320ms bosib turilgach scrub faollashadi: ijro pauza qilinadi, ovoz
         * o'chadi va barmoq harakati vaqtni tanlaydi (CLOSEST_SYNC — kalit
         * kadrlarga sakrash, ya'ni tez).
         */
        private fun beginScrub(x: Float) {
            if (scrubbing) return
            val r = reel ?: return
            if (r.isStory && !r.storyIsVideo) return
            if (!canPlay(this)) return
            val p = player ?: return
            if (p.currentMediaItem?.mediaId != r.id) return
            val duration = p.duration
            if (duration <= 0L || duration == C.TIME_UNSET) return
            scrubbing = true
            scrubAnchorX = x
            scrubStartMs = p.currentPosition.coerceIn(0L, duration)
            scrubTargetMs = scrubStartMs
            scrubDurationMs = duration
            preScrubPlaying = p.playWhenReady
            lastSeekAt = 0L
            lastTickSecond = (scrubStartMs / 1000L).toInt()
            p.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            p.volume = 0f
            p.playWhenReady = false
            updateScrubUi()
            scrubHud.animate().cancel()
            scrubHud.alpha = 0f
            scrubHud.visibility = View.VISIBLE
            scrubHud.animate().alpha(1f).setDuration(110L).start()
            itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // HUD markazda — ovoz belgisi uning ostida qolib ketmasin.
            hideSoundUi()
            // Gorizontal surishni RecyclerView tortib olmasin.
            itemView.parent?.requestDisallowInterceptTouchEvent(true)
        }

        private fun scrubTo(x: Float) {
            if (!scrubbing) return
            val p = player ?: return
            val d = scrubDurationMs
            if (d <= 0L) return
            val span = (itemView.width.coerceAtLeast(1) * 0.75f).coerceAtLeast(dpF(120f))
            val delta = ((x - scrubAnchorX) / span) * d
            scrubTargetMs = (scrubStartMs + delta.toLong()).coerceIn(0L, d)
            updateScrubUi()
            val now = SystemClock.uptimeMillis()
            if (lastSeekAt == 0L || now - lastSeekAt >= 60L) {
                lastSeekAt = now
                try {
                    p.seekTo(scrubTargetMs)
                } catch (_: Exception) {
                }
            }
            val second = (scrubTargetMs / 1000L).toInt()
            if (second != lastTickSecond) {
                lastTickSecond = second
                itemView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }

        private fun updateScrubUi() {
            tvScrubTime.text = "${scrubLabel(scrubTargetMs)} / ${scrubLabel(scrubDurationMs)}"
            if (scrubDurationMs > 0L) {
                scrubBar.progress =
                    ((scrubTargetMs * 1000L) / scrubDurationMs).toInt().coerceIn(0, 1000)
            }
        }

        private fun scrubLabel(ms: Long): String {
            val total = (ms / 1000L).coerceAtLeast(0L)
            return String.format(java.util.Locale.US, "%d:%02d", total / 60L, total % 60L)
        }

        private fun hideScrubHud() {
            scrubHud.animate().cancel()
            scrubHud.visibility = View.GONE
            scrubHud.alpha = 1f
            scrubBar.progress = 0
        }

        /**
         * Scrub holatini majburiy yakunlaydi (bind/recycle/detach/release).
         * `apply=false` — sahifa almashmoqda: seek qilinmaydi, faqat tozalanadi.
         */
        fun cancelScrubState(apply: Boolean = false) {
            cancelHold()
            if (!scrubbing) {
                hideScrubHud()
                return
            }
            scrubbing = false
            val p = player
            val mine = p != null && p.currentMediaItem?.mediaId == reel?.id
            if (p != null && mine) {
                try {
                    p.setSeekParameters(SeekParameters.EXACT)
                    if (apply) p.seekTo(scrubTargetMs)
                    // Scrub ovozni 0 qilib, pauza holatida qoldirgan edi —
                    // holat har qanday yo'lda tiklanadi.
                    p.volume = if (muted || userPaused) 0f else 1f
                    p.playWhenReady = preScrubPlaying && !userPaused && this === activeHolder
                } catch (_: Exception) {
                }
            }
            // Scrub paytida yashirilgan markaziy ovoz belgisi qaytadi.
            if (this === activeHolder) refreshSoundUi()
            hideScrubHud()
            itemView.parent?.requestDisallowInterceptTouchEvent(false)
            if (apply) scheduleWarm(250L)
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

        /**
         * "Do'stlarga yuborish" — Telegram'ning Instagram uslubidagi chat
         * tanlagichi [ReelsActivity]da ochiladi (ShareAlert): hikoya xabari
         * yoki yuklagan odam profili havolasi bilan.
         */
        private fun shareReel() {
            val r = reel ?: return
            if (r.videoUrl.isBlank() && !r.isStory) return
            onShareReel(r)
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
            cancelScrubState()
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
            bindAvatar(reel)
            uploaderRow.setOnClickListener {
                val r = this.reel ?: return@setOnClickListener
                if (r.uploadedByUid.isNotBlank()) onOpenProfile(r)
            }
            tvCaption.text = reel.caption()
            tvViews.text = viewsLabel(reel.views)
            refreshStatsVisibility()
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
                    // Poster ekranning yarmida decode qilinadi: to'liq o'lchamli
                    // decode past internetda sezilarli kechikish beradi.
                    Glide.with(ivThumbnail)
                        .load(reel.thumbnailUrl)
                        .override(thumbWidth(), thumbHeight())
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .priority(Priority.HIGH)
                        .placeholder(R.drawable.bg_classified_image_placeholder)
                        .error(R.drawable.bg_classified_image_placeholder)
                        .into(ivThumbnail)
                } else {
                    Glide.with(ivThumbnail).clear(ivThumbnail)
                    ivThumbnail.setImageDrawable(null)
                    ivThumbnail.setBackgroundColor(ContextCompat.getColor(ivThumbnail.context, R.color.yuz_bg))
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
            refreshSoundUi()
        }

        /**
         * #106: hikoya postiri — [org.telegram.ui.Stories.PeerStoriesView]'dagi
         * bilan bir xil `setImage` chaqiruvi (storyItem — parentObject,
         * FileLoader hikoya reference'ini shundan oladi).
         */
        private fun bindStoryThumb(reel: Reel) {
            storyThumb.imageReceiver.setCurrentAccount(account)
            val item = YuzStoryFeed.findStoryItem(account, reel.storyDialogId, reel.storyId)
            if (item == null) {
                storyThumb.clearImage()
                return
            }
            // #121: filter DOIM "kenglik_balandlik..." raqamli formatda
            // bo'lishi shart — ImageLoader CacheOutTask animatsiya yo'lida
            // args[0]/args[1]ni Float.parseFloat qiladi; "yuzreel" filtri
            // NumberFormatException bilan fon ipini yiqqan edi.
            val filter = "1000_1000"
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

        /**
         * Sonlar Firebase'dan keladi; manba hali yuklanmagan/o'chirilgan
         * holatda soxta 0 o'rniga qatorni yashiramiz (`statsAvailable`).
         */
        private fun refreshStatsVisibility() {
            val r = reel ?: return
            val visible = if (r.statsAvailable) View.VISIBLE else View.GONE
            viewsRow.visibility = visible
            tvLikeCount.visibility = visible
        }

        /** Instagramcha "jonli" yangilanish: pleyer tegilmaydi, faqat sonlar. */
        fun applyStats(fresh: Reel) {
            reel = fresh
            tvViews.text = viewsLabel(fresh.views)
            refreshStatsVisibility()
            refreshLikeUi()
            refreshCommentUi()
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
            // Scrub paytida pauza texnik sabab — yirik play belgisi chiqmaydi.
            if (isPlaying || scrubbing) {
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
            if (!canPlay(this)) return
            userPaused = !userPaused
            if (userPaused) {
                silence()
            } else {
                try {
                    activate(this)
                } catch (_: Exception) {
                    showActivateError(this)
                }
            }
        }
    }
}
