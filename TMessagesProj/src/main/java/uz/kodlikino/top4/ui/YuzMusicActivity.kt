package uz.kodlikino.top4.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.utils.YuzAnim
import uz.kodlikino.top4.utils.YuzMusicCache
import uz.kodlikino.top4.utils.YuzMusicEffects
import uz.kodlikino.top4.utils.YuzMusicEngine
import uz.kodlikino.top4.utils.YuzMusicRepository
import uz.kodlikino.top4.utils.YuzTrack

/**
 * YuzGram #62/#63: ilova ichidagi musiqa pleyeri — qidiruv YouTube InnerTube
 * orqali, qatorni bosish o'z pleyerimizda to'liq qo'shiqni boshlaydi.
 *
 * YuzGram #67: ijro endi YuzMusicEngine'da (yuzgram xizmati YuzMusicService) —
 * ekran bloklanganda va ilovadan chiqqanda ham musiqa to'xtamaydi; butun
 * ro'yxat navbat bo'ladi (qo'shiq tugasa — keyingisi avtomatik), navbat
 * tugasa radio o'xshash qo'shiqlarni topib davom ettiradi; qatorni bosib
 * turib playlist/navbatga qo'shish mumkin; "Kolleksiyalar" playlistlar va
 * radio kalitini ochadi. Yuklashlar va saqlanganlar asosiy ro'yxatda.
 */
class YuzMusicActivity(private val initialQuery: String? = null) : BaseFragment() {

    private lateinit var ctx: Context
    private lateinit var etQuery: EditText
    private lateinit var statusView: TextView
    private lateinit var adapter: YuzMusicAdapter
    private var rvList: RecyclerView? = null
    private var shimmer: View? = null
    private var hitsBox: View? = null
    private var skeletonLoading = false
    private var centerLoading = false
    private var loadingPage = false

    private val effects by lazy { YuzMusicEffects.shared(ctx) }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busy = false

    // Source results and rendered rows have independent lifecycles.
    private var catalogList: List<YuzTrack> = emptyList()
    private var currentList: List<YuzTrack> = emptyList()
    private var statusLabel = ""

    // YuzGram #104: ro'yxat manbai + "ko'proq yuklash" holati.
    // listSource: 0 = Kolleksiyalar (loadMore o'chiq), 1 = qidiruv, 2 = ommabop.
    private var destroyed = false
    private var listSource = 0
    // YuzGram #116: tezkor qidiruv chiplari. presetIndex: 0=Ommabop,
    // 1=Eng ko'p eshitilgan, 2=Russian xitlari, -1=foydalanuvchi qidiruvi.
    private var chipGroup: com.google.android.material.chip.ChipGroup? = null
    private var presetIndex = 0
    private var presetSeeds: Array<String>? = null
    private var lastQuery: String? = null
    private val shownIds = HashSet<Long>()
    private var moreRound = 0
    private var moreAvailable = true
    private var catalogGeneration = 0

    private var playerBar: View? = null
    private var ivPlayerCover: ImageView? = null
    private var tvPlayerTitle: TextView? = null
    private var tvPlayerArtist: TextView? = null
    private var tvPlayerPos: TextView? = null
    private var tvPlayerDur: TextView? = null
    private var seek: SeekBar? = null
    private var btnPlayPause: ImageButton? = null
    private var btnLoop: ImageButton? = null
    private var btnDl: ImageButton? = null
    private var seeking = false
    private var looping = false
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            refreshDownloading()
            ui.postDelayed(this, 500)
        }
    }
    private var fxListener: Player.Listener? = null

    private val observer = object : YuzMusicEngine.Observer {
        override fun onItemChanged(item: MediaItem?) {
            val track = item?.localConfiguration?.tag as? YuzTrack
            if (track != null) updateBarTrack(track)
            syncPlayingMark()
        }

        override fun onPlayingChanged(playing: Boolean) {
            // #128: play/pause almashinuvi uzluksiz — ikon so'nadi, yangisi
            // "pop" bilan chiqadi.
            YuzAnim.swapIcon(
                btnPlayPause,
                if (playing) R.drawable.ic_yuz_pause else R.drawable.ic_yuz_play
            )
            syncPlayingMark()
        }

        override fun onStatus(text: String?) {
            val cur = YuzMusicEngine.currentTrack()
            tvPlayerArtist?.text = text ?: cur?.artist ?: ""
        }
    }

    override fun createView(context: Context): View {
        ctx = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        val root = LayoutInflater.from(ctx)
            .inflate(R.layout.fragment_musiqa, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)
        YuzTopInset.applyPadding(root.findViewById<View>(R.id.musiqaTopBar))

        root.findViewById<View>(R.id.btnMusiqaBack)?.setOnClickListener {
            removeSelfFromStack()
        }

        etQuery = root.findViewById(R.id.etMusiqaQuery)
        statusView = root.findViewById(R.id.tvMusiqaStatus)
        shimmer = root.findViewById(R.id.shimmerMusiqa)
        hitsBox = root.findViewById(R.id.hitsLoading)

        val rv = root.findViewById<RecyclerView>(R.id.rvMusiqa)
        rvList = rv
        currentList = emptyList()
        rv.layoutManager = LinearLayoutManager(ctx)
        adapter = YuzMusicAdapter(
            onPlay = { track, _ -> rowPlay(track.id) },
            onDownload = { track, _ -> downloadTrack(track) },
            onLongPress = { track, _ -> rowMenu(track) }
        )
        rv.adapter = adapter
        adapter.setDownloaded(YuzMusicCache.downloadedIds(ctx))
        // YuzGram #104: ro'yxat oxiriga yaqlashsa yangi qatorlar yuklanadi.
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 6) loadMore()
            }
        })

        root.findViewById<View>(R.id.btnMusiqaSearch)?.setOnClickListener { runSearch() }
        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else false
        }
        root.findViewById<View>(R.id.btnMusiqaCollections)?.setOnClickListener { showCollections() }

        // YuzGram #116: tayyor qidiruv tugmalari.
        chipGroup = root.findViewById(R.id.chipGroupMusiqaPreset)
        renderPresets()

        setupPlayerBar(root)

        YuzMusicEngine.ensurePlayer(ctx).apply {
            val l = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (destroyed) return
                    if (state == Player.STATE_READY) attachEffects()
                }
            }
            // #115: listener singleton pleyerga qo'shiladi — o'chirmasa
            // bu sahifa butun umrini yodda ushlab, buzilgan view'larga
            // urilar edi.
            fxListener = l
            addListener(l)
            if (playbackState == Player.STATE_READY) attachEffects()
        }
        YuzMusicEngine.addObserver(observer)
        syncFromPlayer()
        // Saved tracks and global downloads are visible before any network request.
        refreshDownloading()

        val q = initialQuery?.trim().orEmpty()
        if (q.isNotEmpty()) {
            etQuery.setText(q)
            runSearch()
        } else {
            loadChart()
        }
        return root
    }

    private fun setupPlayerBar(root: ViewGroup) {
        val bar = root.findViewById<View>(R.id.musicPlayerBar) ?: return
        playerBar = bar
        ivPlayerCover = bar.findViewById(R.id.ivPlayerCover)
        tvPlayerTitle = bar.findViewById(R.id.tvPlayerTitle)
        tvPlayerArtist = bar.findViewById(R.id.tvPlayerArtist)
        tvPlayerPos = bar.findViewById(R.id.tvPlayerPos)
        tvPlayerDur = bar.findViewById(R.id.tvPlayerDur)
        seek = bar.findViewById(R.id.playerSeek)
        btnPlayPause = bar.findViewById(R.id.btnPlayerPlay)
        btnLoop = bar.findViewById(R.id.btnPlayerLoop)
        btnDl = bar.findViewById(R.id.btnPlayerDl)

        bar.visibility = View.GONE
        seek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) seeking = true
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                val p = YuzMusicEngine.playerOrNull() ?: return
                val d = p.duration
                if (d > 0) p.seekTo((sb?.progress ?: 0) * 1000L)
            }
        })
        btnPlayPause?.setOnClickListener { togglePlayPause() }
        bar.findViewById<View>(R.id.btnPlayerPrev)?.setOnClickListener {
            YuzMusicEngine.playerOrNull()?.seekToPrevious()
        }
        bar.findViewById<View>(R.id.btnPlayerNext)?.setOnClickListener {
            YuzMusicEngine.playerOrNull()?.seekToNextMediaItem()
        }
        btnLoop?.setOnClickListener {
            looping = !looping
            YuzMusicEngine.playerOrNull()?.repeatMode =
                if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            btnLoop?.alpha = if (looping) 1.0f else 0.45f
        }
        btnDl?.setOnClickListener {
            YuzMusicEngine.currentTrack()?.let { downloadTrack(it) }
        }
        bar.findViewById<View>(R.id.btnPlayerEq)?.setOnClickListener { showEqDialog() }
        // #69: bar qovuriga bosish ham to'liq plerni ochadi.
        val openPl = View.OnClickListener { openPlayer() }
        ivPlayerCover?.setOnClickListener(openPl)
        tvPlayerTitle?.setOnClickListener(openPl)
        tvPlayerArtist?.setOnClickListener(openPl)
    }

    // ------------------------------------------------------------ playback

    private fun rowPlay(id: Long) {
        val pos = currentList.indexOfFirst { it.id == id }
        if (pos < 0) return
        val track = currentList[pos]
        val p = YuzMusicEngine.ensurePlayer(ctx)
        val playing = YuzMusicEngine.currentTrack()
        if (playing != null && playing.id == track.id) {
            if (p.isPlaying) p.pause() else p.playWhenReady = true
            return
        }
        if (YuzMusicCache.localFile(ctx, track.id) == null && track.videoId.isBlank() &&
            track.previewUrl.isBlank()
        ) {
            Toast.makeText(ctx, R.string.musiqa_play_failed, Toast.LENGTH_SHORT).show()
            return
        }
        YuzMusicEngine.playQueue(ctx, currentList, pos)
        ensureService()
        updateBarTrack(track)
        // #69: qo'shiq boshlanganda to'liq ekranli pler ochiladi.
        openPlayer()
    }

    /** #69: professional to'liq ekranli plerga o'tadi. */
    private fun openPlayer() {
        runCatching { presentFragment(YuzPlayerActivity()) }
    }

    private fun ensureService() {
        runCatching { ctx.startService(Intent(ctx, YuzMusicService::class.java)) }
    }

    private fun togglePlayPause() {
        val p = YuzMusicEngine.playerOrNull()
        if (p == null || p.currentMediaItem == null) {
            currentList.firstOrNull()?.let { rowPlay(it.id) }
            return
        }
        if (p.isPlaying) p.pause() else p.playWhenReady = true
    }

    private fun attachEffects() {
        val p = YuzMusicEngine.playerOrNull() ?: return
        effects.ensureSession(p.audioSessionId)
    }

    /** Dvigatel holatini ekranga qaytaradi (qayta kirganda fon ijroini ko'rsatadi). */
    private fun syncFromPlayer() {
        val p = YuzMusicEngine.playerOrNull() ?: return
        val track = YuzMusicEngine.currentTrack()
        if (track != null) {
            updateBarTrack(track)
            btnPlayPause?.setImageResource(if (p.isPlaying) R.drawable.ic_yuz_pause else R.drawable.ic_yuz_play)
        } else {
            playerBar?.visibility = View.GONE
        }
        looping = p.repeatMode == Player.REPEAT_MODE_ONE
        btnLoop?.alpha = if (looping) 1.0f else 0.45f
        syncPlayingMark()
        updateProgress()
    }

    private fun syncPlayingMark() {
        val cur = YuzMusicEngine.currentTrack() ?: run {
            adapter.setPlaying(-1, false)
            return
        }
        val pos = currentList.indexOfFirst { it.id == cur.id }
        adapter.setPlaying(pos, YuzMusicEngine.playerOrNull()?.isPlaying == true)
        syncDlBtn(cur.id)
    }

    /** Cancellation remains pending until the cache worker has cleaned up. */
    private fun syncDlBtn(id: Long) {
        val b = btnDl ?: return
        val busy = YuzMusicCache.isInflight(id)
        val canceling = YuzMusicCache.isCanceling(id)
        val has = !busy && YuzMusicCache.isDownloaded(ctx, id)
        b.setImageResource(
            when {
                busy -> R.drawable.ic_yuz_close
                has -> R.drawable.ic_yuz_check
                else -> R.drawable.ic_yuz_download
            }
        )
        b.imageTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(
                ctx, if (has) R.color.yuz_action else R.color.yuz_text
            )
        )
        b.isEnabled = !canceling
        b.contentDescription = ctx.getString(when {
            canceling -> R.string.musiqa_dl_canceling
            busy -> R.string.musiqa_pl_dl_cancel
            has -> R.string.musiqa_pl_dl_done
            else -> R.string.yuz_a11y_download
        })
        b.alpha = if (busy || has) 1.0f else 0.6f
    }

    private fun updateBarTrack(track: YuzTrack) {
        val bar = playerBar ?: return
        if (bar.visibility != View.VISIBLE) {
            // #128: bar birinchi marta paydo bo'lganda pastdan silliq ko'tariladi.
            YuzAnim.slideInUp(bar, AndroidUtilities.dp(96f).toFloat())
        } else {
            bar.visibility = View.VISIBLE
        }
        tvPlayerTitle?.text = track.title
        tvPlayerArtist?.text = YuzMusicEngine.lastStatusText() ?: track.artist
        tvPlayerDur?.text = String.format("%d:%02d", track.durationSec / 60, track.durationSec % 60)
        seek?.progress = 0
        tvPlayerPos?.text = "0:00"
        syncDlBtn(track.id)
        btnLoop?.alpha = if (looping) 1.0f else 0.45f
        if (track.coverUrl.isNotBlank()) {
            ivPlayerCover?.let {
                Glide.with(it).load(track.coverUrl)
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(240))
                    .into(it)
            }
        } else {
            ivPlayerCover?.setImageDrawable(null)
        }
    }

    private fun updateProgress() {
        val p = YuzMusicEngine.playerOrNull() ?: return
        if (seeking || p.duration <= 0) return
        val d = (p.duration / 1000).toInt()
        val pos = (p.currentPosition / 1000).toInt()
        seek?.max = d
        seek?.progress = pos
        tvPlayerPos?.text = String.format("%d:%02d", pos / 60, pos % 60)
        tvPlayerDur?.text = String.format("%d:%02d", d / 60, d % 60)
    }

    /** Global state is authoritative, including downloads started in the full player. */
    private fun refreshDownloading() {
        if (destroyed || !::adapter.isInitialized) return
        adapter.setDownloading(YuzMusicCache.inflightIds())
        adapter.setDownloaded(YuzMusicCache.downloadedIds(ctx))
        renderList()
        syncPlayingMark()
    }

    // -------------------------------------------------------------- search

    private fun runSearch() {
        val q = etQuery.text.toString().trim()
        if (q.isEmpty() || busy) return
        hideKeyboard()
        busy = true
        lastQuery = q
        listSource = 1
        presetSeeds = null
        if (presetIndex != -1) {
            presetIndex = -1
            renderPresets()
        }
        applyList(emptyList())
        val generation = catalogGeneration
        setStatus(R.string.musiqa_searching)
        setLoading(skeleton = true, center = false)
        scope.launch {
            val list = try {
                withContextIo { YuzMusicRepository.search(q) }
            } catch (_: Exception) {
                null
            }
            if (destroyed) return@launch
            busy = false
            setLoading(skeleton = false, center = false)
            if (generation != catalogGeneration) return@launch
            applyList(list.orEmpty())
            when {
                list == null -> setStatus(R.string.musiqa_error)
                list.isEmpty() -> setStatus(R.string.musiqa_empty)
                else -> setStatus(R.string.musiqa_results)
            }
        }
    }

    private fun loadChart() {
        if (busy) return
        busy = true
        lastQuery = null
        listSource = 2
        presetSeeds = null
        applyList(emptyList())
        val generation = catalogGeneration
        setStatus(R.string.musiqa_hits_loading)
        setLoading(skeleton = false, center = true)
        scope.launch {
            val list = try {
                withContextIo { YuzMusicRepository.chart() }
            } catch (_: Exception) {
                null
            }
            if (destroyed) return@launch
            busy = false
            setLoading(skeleton = false, center = false)
            if (generation != catalogGeneration) return@launch
            applyList(list.orEmpty())
            setStatus(if (list.isNullOrEmpty()) R.string.musiqa_error else R.string.musiqa_trending)
        }
    }

    /** #116: chip qatorini chizish — tanlangan tugma bo'lg'ab o'ladi. */
    private fun renderPresets() {
        val g = chipGroup ?: return
        val items = listOf(
            YuzChips.Item("ommabop", ctx.getString(R.string.musiqa_chip_ommabop)),
            YuzChips.Item("most", ctx.getString(R.string.musiqa_chip_most)),
            YuzChips.Item("ru", ctx.getString(R.string.musiqa_chip_ru))
        )
        YuzChips.fill(ctx, g, items, presetIndex) { idx -> presetClicked(idx) }
    }

    private fun presetClicked(idx: Int) {
        if (busy || idx == presetIndex) return
        presetIndex = idx
        renderPresets()
        when (idx) {
            0 -> loadChart()
            1 -> loadPreset(YuzMusicRepository.seedsMostPlayed, R.string.musiqa_chip_most)
            else -> loadPreset(YuzMusicRepository.seedsRussianHits, R.string.musiqa_chip_ru)
        }
    }

    /** #116: tanlangan tugmaning seed hovuzi bilan ro'yxat yuklash. */
    private fun loadPreset(seeds: Array<String>, labelRes: Int) {
        if (busy) return
        busy = true
        lastQuery = null
        listSource = 2
        presetSeeds = seeds
        etQuery.setText("")
        applyList(emptyList())
        val generation = catalogGeneration
        setStatus(R.string.musiqa_hits_loading)
        setLoading(skeleton = false, center = true)
        scope.launch {
            val list = try {
                withContextIo { YuzMusicRepository.preset(seeds, emptySet(), 25) }
            } catch (_: Exception) {
                null
            }
            if (destroyed) return@launch
            busy = false
            setLoading(skeleton = false, center = false)
            if (generation != catalogGeneration) return@launch
            applyList(list.orEmpty())
            setStatus(if (list.isNullOrEmpty()) R.string.musiqa_error else labelRes)
        }
    }

    private fun setStatus(res: Int) {
        statusLabel = ctx.getString(res)
        statusView.text = statusLabel
    }

    /**
     * YuzGram #98: yuklanish holati. [skeleton] — qidiruvda qatorlar o'rnini
     * bosuvchi skelet; [center] — bo'sh ekranda markazdagi neon animatsiya
     * (ommabop xitlar kelganda).
     */
    private fun setLoading(skeleton: Boolean, center: Boolean) {
        skeletonLoading = skeleton
        centerLoading = center
        updateLoadingVisibility()
    }

    private fun updateLoadingVisibility() {
        val empty = currentList.isEmpty()
        // Overlay skeletons must never cover saved tracks or active downloads.
        shimmer?.visibility = if (skeletonLoading && empty) View.VISIBLE else View.GONE
        hitsBox?.visibility = if (centerLoading && empty) View.VISIBLE else View.GONE
        rvList?.visibility = if (centerLoading && empty) View.INVISIBLE else View.VISIBLE
        adapter.setLoadingMore(loadingPage || (!empty && (skeletonLoading || centerLoading)))
    }

    /** Replace source results only for a new query, preset, or playlist. */
    private fun applyList(list: List<YuzTrack>) {
        catalogGeneration++
        catalogList = list.distinctBy { it.id }
        shownIds.clear()
        shownIds.addAll(catalogList.map { it.id })
        moreRound = 0
        moreAvailable = true
        refreshDownloading()
    }

    /** Cache refreshes do not reset pagination or rebuild the playback queue. */
    private fun renderList() {
        val next = YuzMusicCache.unifiedTracks(ctx, catalogList)
        if (next != currentList) {
            currentList = next
            adapter.submit(next)
        }
        updateLoadingVisibility()
    }

    /**
     * YuzGram #104: pastki navigatsiya — oxiriga yaqlashganda qo'shimcha
     * qatorlar tortiladi (qidiruv variantlari yoki boshqa ommabop seedlar),
     * ko'rilgan qatorlar takrorlanmaydi.
     */
    private fun loadMore() {
        if (busy || !moreAvailable || listSource == 0 || catalogList.isEmpty()) return
        busy = true
        moreRound++
        loadingPage = true
        updateLoadingVisibility()
        val round = moreRound
        val q = lastQuery
        val seeds = presetSeeds
        val exclude = HashSet(shownIds)
        val generation = catalogGeneration
        scope.launch {
            val more = try {
                withContextIo {
                    when {
                        q != null -> YuzMusicRepository.searchMore(q, exclude, round)
                        seeds != null -> YuzMusicRepository.preset(seeds, exclude, 20)
                        else -> YuzMusicRepository.chartMore(exclude)
                    }
                }
            } catch (_: Exception) {
                null
            }
            if (destroyed) return@launch
            busy = false
            loadingPage = false
            updateLoadingVisibility()
            if (generation != catalogGeneration) return@launch
            if (more.isNullOrEmpty()) {
                if (more != null) moreAvailable = false
            } else {
                shownIds.addAll(more.map { it.id })
                catalogList = (catalogList + more).distinctBy { it.id }
                refreshDownloading()
            }
        }
    }

    // ------------------------------------------------------- collections UI

    /** Collections contain only playlists and the radio toggle. */
    private fun showCollections() {
        val playlists = YuzMusicEngine.playlists(ctx)
        val labels = ArrayList<String>()
        for (pl in playlists) labels.add("${pl.name} (${pl.tracks.size})")
        labels.add(ctx.getString(R.string.musiqa_radio_toggle,
            if (YuzMusicEngine.radio) "OFF" else "ON"))
        val dlg = AlertDialog.Builder(ctx, getResourceProvider())
            .setTitle(ctx.getString(R.string.musiqa_collections))
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which in playlists.indices -> {
                        val pl = playlists[which]
                        listSource = 0
                        lastQuery = null
                        presetSeeds = null
                        statusLabel = pl.name
                        statusView.text = "${pl.name} (${pl.tracks.size})"
                        applyList(pl.tracks)
                    }
                    which == playlists.size -> {
                        YuzMusicEngine.radio = !YuzMusicEngine.radio
                        Toast.makeText(ctx,
                            if (YuzMusicEngine.radio) R.string.musiqa_radio_on
                            else R.string.musiqa_radio_off, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(ctx.getString(android.R.string.cancel), null)
            .create()
        showDialog(dlg)
    }

    /** Qatorni bosib turish menyusi. */
    private fun rowMenu(track: YuzTrack) {
        val inflight = YuzMusicCache.isInflight(track.id)
        val downloaded = !inflight && YuzMusicCache.isDownloaded(ctx, track.id)
        val labels = arrayOf(
            ctx.getString(R.string.musiqa_menu_add_queue),
            ctx.getString(R.string.musiqa_menu_add_playlist),
            ctx.getString(when {
                YuzMusicCache.isCanceling(track.id) -> R.string.musiqa_dl_canceling
                inflight -> R.string.musiqa_pl_dl_cancel
                downloaded -> R.string.Delete
                else -> R.string.musiqa_menu_dl
            })
        )
        val dlg = AlertDialog.Builder(ctx, getResourceProvider())
            .setTitle(track.title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> {
                        YuzMusicEngine.enqueue(ctx, track)
                        ensureService()
                        Toast.makeText(ctx, R.string.musiqa_queue_added, Toast.LENGTH_SHORT).show()
                    }
                    1 -> playlistPicker(track)
                    2 -> if (downloaded) {
                        // Removal is explicit and must never cancel an unrelated new worker.
                        if (!YuzMusicCache.isInflight(track.id) &&
                            YuzMusicCache.isDownloaded(ctx, track.id)
                        ) {
                            YuzMusicCache.remove(ctx, track.id)
                            refreshDownloading()
                            Toast.makeText(ctx, R.string.musiqa_dl_removed, Toast.LENGTH_SHORT).show()
                        }
                    } else downloadTrack(track)
                }
            }
            .setNegativeButton(ctx.getString(android.R.string.cancel), null)
            .create()
        showDialog(dlg)
    }

    private fun playlistPicker(track: YuzTrack) {
        val playlists = YuzMusicEngine.playlists(ctx)
        val labels = ArrayList<String>()
        for (pl in playlists) labels.add(pl.name)
        labels.add(ctx.getString(R.string.musiqa_new_playlist))
        val dlg = AlertDialog.Builder(ctx, getResourceProvider())
            .setTitle(ctx.getString(R.string.musiqa_menu_add_playlist))
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < playlists.size) {
                    addToPlaylist(playlists[which].name, track)
                } else {
                    askPlaylistName(track)
                }
            }
            .setNegativeButton(ctx.getString(android.R.string.cancel), null)
            .create()
        showDialog(dlg)
    }

    private fun askPlaylistName(track: YuzTrack) {
        val input = EditText(ctx)
        input.hint = ctx.getString(R.string.musiqa_playlist_hint)
        input.setPadding(AndroidUtilities.dp(20f), AndroidUtilities.dp(8f),
            AndroidUtilities.dp(20f), AndroidUtilities.dp(8f))
        val pad = AndroidUtilities.dp(10f)
        val wrap = android.widget.FrameLayout(ctx)
        wrap.addView(input, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(pad, pad, pad, pad) })
        val dlg = AlertDialog.Builder(ctx, getResourceProvider())
            .setTitle(ctx.getString(R.string.musiqa_new_playlist))
            .setView(wrap)
            .setPositiveButton(ctx.getString(R.string.musiqa_ok)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) addToPlaylist(name, track)
            }
            .setNegativeButton(ctx.getString(android.R.string.cancel), null)
            .create()
        showDialog(dlg)
    }

    private fun addToPlaylist(name: String, track: YuzTrack) {
        // #126: playlist faylini to'liq qayta yozish — disk IO, UI ipini
        // bloklamasligi uchun fon ipiga ko'chirildi.
        scope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                YuzMusicEngine.playlistAdd(ctx, name, track)
            }
            Toast.makeText(ctx, ctx.getString(R.string.musiqa_added_playlist, name),
                Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------------- download

    private fun downloadTrack(track: YuzTrack) {
        if (YuzMusicCache.isCanceling(track.id)) {
            refreshDownloading()
            return
        }
        if (YuzMusicCache.isInflight(track.id)) {
            YuzMusicCache.cancel(track.id)
            refreshDownloading()
            Toast.makeText(ctx, R.string.musiqa_dl_canceling, Toast.LENGTH_SHORT).show()
            return
        }
        if (YuzMusicCache.isDownloaded(ctx, track.id)) {
            Toast.makeText(ctx, R.string.musiqa_dl_exists, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(ctx, R.string.musiqa_dl_started, Toast.LENGTH_SHORT).show()
        YuzMusicCache.download(ctx, track) { result ->
            if (destroyed) return@download
            refreshDownloading()
            when (result) {
                YuzMusicCache.DownloadResult.DONE -> {
                    if (YuzMusicEngine.currentTrack()?.id == track.id &&
                        !YuzMusicCache.isInflight(track.id)
                    ) YuzAnim.pop(btnDl)
                    Toast.makeText(ctx, R.string.musiqa_dl_done, Toast.LENGTH_SHORT).show()
                }
                YuzMusicCache.DownloadResult.FAILED ->
                    Toast.makeText(ctx, R.string.musiqa_dl_fail, Toast.LENGTH_SHORT).show()
                YuzMusicCache.DownloadResult.CANCELED -> Unit
            }
        }
        // The backend registers before returning, so the new row is immediately first.
        refreshDownloading()
        rvList?.scrollToPosition(0)
    }

    // -------------------------------------------------------------------- eq

    private fun showEqDialog() {
        val act = parentActivity ?: return
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_yuz_eq, null)
        val group = v.findViewById<RadioGroup>(R.id.eqPresetGroup)
        group.check(when (effects.preset) {
            YuzMusicEffects.PRESET_BASS -> R.id.eqPresetBass
            YuzMusicEffects.PRESET_JAZZ -> R.id.eqPresetJazz
            YuzMusicEffects.PRESET_ROCK -> R.id.eqPresetRock
            YuzMusicEffects.PRESET_VOCAL -> R.id.eqPresetVocal
            else -> R.id.eqPresetFlat
        })
        group.setOnCheckedChangeListener { _, id ->
            val preset = when (id) {
                R.id.eqPresetBass -> YuzMusicEffects.PRESET_BASS
                R.id.eqPresetJazz -> YuzMusicEffects.PRESET_JAZZ
                R.id.eqPresetRock -> YuzMusicEffects.PRESET_ROCK
                R.id.eqPresetVocal -> YuzMusicEffects.PRESET_VOCAL
                else -> YuzMusicEffects.PRESET_FLAT
            }
            effects.setPreset(preset)
        }
        val bass = v.findViewById<SeekBar>(R.id.eqBassSeek)
        bass.progress = effects.bassStrength
        bass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) effects.setBass(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        val hint = v.findViewById<TextView>(R.id.tvEqHint)
        if (!effects.hasEqualizer && !effects.hasBassBoost) {
            hint.setText(R.string.musiqa_eq_unavailable)
        }
        val dlg = AlertDialog.Builder(act, getResourceProvider())
            .setTitle(ctx.getString(R.string.musiqa_eq_title))
            .setView(v)
            .setPositiveButton(ctx.getString(android.R.string.ok), null)
            .create()
        showDialog(dlg)
    }

    // ----------------------------------------------------------------- misc

    private fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etQuery.windowToken, 0)
    }

    private suspend fun <T> withContextIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    override fun onPause() {
        // #67: ijro FONDA davom etadi — bu yerda to'xtatmaymiz.
        super.onPause()
        ui.removeCallbacks(tick)
        // #69: bu ekrandan chiqqanda global mini bar qayta ko'rinadi.
        YuzMiniPlayer.setSuppressed(this, false)
    }

    override fun onResume() {
        super.onResume()
        // #69: pastda o'z player bari bor — ustidagi mini bar ortiqcha.
        YuzMiniPlayer.setSuppressed(this, true)
        ui.removeCallbacks(tick)
        refreshDownloading()
        syncFromPlayer()
        ui.post(tick)
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        // #104: fondagi yuklash tugaganda bu sahifa uchun UI ishlar qilmasin.
        destroyed = true
        ui.removeCallbacks(tick)
        scope.cancel()
        YuzMusicEngine.removeObserver(observer)
        // #115: qo'shilgan pleyer listener'ini ham qaytaramiz.
        fxListener?.let { YuzMusicEngine.playerOrNull()?.removeListener(it) }
        fxListener = null
        // Player va ekvalayzer ENGINE'da qoladi — fon ijrosi uzilmasligi uchun
        // bu yerda release QILINMAYDI.
    }
}
