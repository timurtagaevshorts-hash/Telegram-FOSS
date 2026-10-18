package uz.kodlikino.top4.ui

import android.content.Context
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import uz.kodlikino.top4.utils.YuzMusicCache
import uz.kodlikino.top4.utils.YuzMusicEffects
import uz.kodlikino.top4.utils.YuzMusicRepository
import uz.kodlikino.top4.utils.YuzTrack

/**
 * YuzGram #62: musiqa — Deezer qidiruvi natijalari endi YouTube'ga o'tkazmaydi.
 * Qatorni bosish ilova ichidagi pleyerni ishga tushiradi (prev/play/next, seek,
 * takror), ekvalayzer tugmasi ovoz uslubini (Bass/Jazz/Rock/Vocal + bas kuchi)
 * sozlaydi, download tugmasi qo'shiq parchasini keshga saqlaydi; "Yuklanganlar"
 * tugmasi oflayd ro'yxatni ochadi.
 */
class YuzMusicActivity(private val initialQuery: String? = null) : BaseFragment() {

    private lateinit var ctx: Context
    private lateinit var etQuery: EditText
    private lateinit var statusView: TextView
    private lateinit var adapter: YuzMusicAdapter

    private var player: ExoPlayer? = null
    private val effects by lazy { YuzMusicEffects(ctx) }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busy = false

    private var currentList: List<YuzTrack> = emptyList()
    private var currentIndex = -1
    private var looping = true
    private var offlineMode = false

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
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 500)
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
            stopPlayback()
            removeSelfFromStack()
        }

        etQuery = root.findViewById(R.id.etMusiqaQuery)
        statusView = root.findViewById(R.id.tvMusiqaStatus)

        val rv = root.findViewById<RecyclerView>(R.id.rvMusiqa)
        rv.layoutManager = LinearLayoutManager(ctx)
        adapter = YuzMusicAdapter(
            onPlay = { track, pos -> playAt(pos) },
            onDownload = { track, _ -> downloadTrack(track) }
        )
        rv.adapter = adapter
        adapter.setDownloaded(YuzMusicCache.downloadedIds(ctx))

        root.findViewById<View>(R.id.btnMusiqaSearch)?.setOnClickListener { runSearch() }
        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else false
        }
        root.findViewById<View>(R.id.btnMusiqaSaved)?.setOnClickListener { showOfflineList() }

        setupPlayerBar(root)

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
                if (fromUser) {
                    seeking = true
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                val p = player ?: return
                val d = p.duration
                if (d > 0) p.seekTo((sb?.progress ?: 0) * 1000L)
            }
        })
        btnPlayPause?.setOnClickListener { togglePlayPause() }
        bar.findViewById<View>(R.id.btnPlayerPrev)?.setOnClickListener { playAt(currentIndex - 1) }
        bar.findViewById<View>(R.id.btnPlayerNext)?.setOnClickListener { playAt(currentIndex + 1) }
        btnLoop?.setOnClickListener {
            looping = !looping
            player?.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            btnLoop?.alpha = if (looping) 1.0f else 0.45f
        }
        btnDl?.setOnClickListener {
            currentList.getOrNull(currentIndex)?.let { downloadTrack(it) }
        }
        bar.findViewById<View>(R.id.btnPlayerEq)?.setOnClickListener { showEqDialog() }
    }

    private fun runSearch() {
        val q = etQuery.text.toString().trim()
        if (q.isEmpty() || busy) return
        hideKeyboard()
        busy = true
        offlineMode = false
        statusView.text = ctx.getString(R.string.musiqa_loading)
        scope.launch {
            val list = try {
                withContextIo { YuzMusicRepository.search(q) }
            } catch (_: Exception) {
                null
            }
            busy = false
            when {
                list == null -> statusView.text = ctx.getString(R.string.musiqa_error)
                list.isEmpty() -> statusView.text = ctx.getString(R.string.musiqa_empty)
                else -> {
                    statusView.text = ctx.getString(R.string.musiqa_results)
                    applyList(list)
                }
            }
        }
    }

    private fun loadChart() {
        if (busy) return
        busy = true
        offlineMode = false
        scope.launch {
            val list = try {
                withContextIo { YuzMusicRepository.chart() }
            } catch (_: Exception) {
                null
            }
            busy = false
            if (list != null && list.isNotEmpty()) {
                statusView.text = ctx.getString(R.string.musiqa_trending)
                applyList(list)
            } else {
                statusView.text = ctx.getString(R.string.musiqa_error)
            }
        }
    }

    private fun showOfflineList() {
        val list = YuzMusicCache.downloadedTracks(ctx)
        offlineMode = true
        if (list.isEmpty()) {
            statusView.text = ctx.getString(R.string.musiqa_saved_empty)
        } else {
            statusView.text = ctx.getString(R.string.musiqa_saved_btn)
        }
        applyList(list)
    }

    private fun applyList(list: List<YuzTrack>) {
        if (!playingTrackMatches(list)) stopPlayback()
        currentList = list
        currentIndex = -1
        adapter.submit(list)
    }

    private fun playingTrackMatches(list: List<YuzTrack>): Boolean {
        val p = player ?: return false
        if (currentIndex !in currentList.indices) return false
        val playing = currentList[currentIndex]
        return list.any { it.id == playing.id }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(ctx).build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        p.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    effects.ensureSession(p.audioSessionId)
                    updateProgress()
                } else if (state == Player.STATE_ENDED && p.repeatMode == Player.REPEAT_MODE_OFF) {
                    adapter.setPlaying(-1)
                    btnPlayPause?.setImageResource(R.drawable.ic_yuz_play)
                }
            }
        })
        player = p
        return p
    }

    private fun playAt(pos: Int) {
        if (pos !in currentList.indices) return
        val track = currentList[pos]
        val uri = YuzMusicCache.playUri(ctx, track)
        if (uri.isBlank()) {
            Toast.makeText(ctx, R.string.musiqa_no_preview, Toast.LENGTH_SHORT).show()
            return
        }
        val p = ensurePlayer()
        if (pos == currentIndex && p.isPlaying) {
            p.pause()
            btnPlayPause?.setImageResource(R.drawable.ic_yuz_play)
            adapter.setPlaying(-1)
            return
        }
        if (pos == currentIndex && !p.isPlaying && p.currentMediaItem != null) {
            p.playWhenReady = true
            btnPlayPause?.setImageResource(R.drawable.ic_yuz_pause)
            adapter.setPlaying(pos)
            return
        }
        currentIndex = pos
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true
        btnPlayPause?.setImageResource(R.drawable.ic_yuz_pause)
        adapter.setPlaying(pos)
        updateBarTrack(track)
        ui.post { effects.ensureSession(p.audioSessionId) }
        ui.postDelayed({ effects.ensureSession(p.audioSessionId) }, 400)
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    private fun togglePlayPause() {
        val p = player ?: run {
            if (currentList.isNotEmpty()) playAt(0)
            return
        }
        if (p.isPlaying) {
            p.pause()
            btnPlayPause?.setImageResource(R.drawable.ic_yuz_play)
        } else {
            p.playWhenReady = true
            btnPlayPause?.setImageResource(R.drawable.ic_yuz_pause)
        }
    }

    private fun updateBarTrack(track: YuzTrack) {
        playerBar?.visibility = View.VISIBLE
        tvPlayerTitle?.text = track.title
        tvPlayerArtist?.text = track.artist
        tvPlayerDur?.text = String.format("%d:%02d", track.durationSec / 60, track.durationSec % 60)
        seek?.progress = 0
        tvPlayerPos?.text = "0:00"
        btnDl?.alpha = if (track.id in YuzMusicCache.downloadedIds(ctx)) 1.0f else 0.6f
        btnLoop?.alpha = if (looping) 1.0f else 0.45f
        if (track.coverUrl.isNotBlank()) {
            ivPlayerCover?.let { Glide.with(it).load(track.coverUrl).into(it) }
        } else {
            ivPlayerCover?.setImageDrawable(null)
        }
    }

    private fun updateProgress() {
        val p = player ?: return
        if (seeking || p.duration <= 0) return
        val d = (p.duration / 1000).toInt()
        val pos = (p.currentPosition / 1000).toInt()
        seek?.max = d
        seek?.progress = pos
        tvPlayerPos?.text = String.format("%d:%02d", pos / 60, pos % 60)
        tvPlayerDur?.text = String.format("%d:%02d", d / 60, d % 60)
    }

    private fun downloadTrack(track: YuzTrack) {
        if (offlineMode) {
            YuzMusicCache.remove(ctx, track.id)
            adapter.setDownloaded(YuzMusicCache.downloadedIds(ctx))
            Toast.makeText(ctx, R.string.musiqa_dl_removed, Toast.LENGTH_SHORT).show()
            showOfflineList()
            return
        }
        if (YuzMusicCache.isDownloaded(ctx, track.id)) {
            Toast.makeText(ctx, R.string.musiqa_dl_exists, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(ctx, R.string.musiqa_dl_started, Toast.LENGTH_SHORT).show()
        YuzMusicCache.download(ctx, track) { ok ->
            adapter.setDownloaded(YuzMusicCache.downloadedIds(ctx))
            btnDl?.alpha = if (ok) 1.0f else 0.6f
            Toast.makeText(ctx, if (ok) R.string.musiqa_dl_done else R.string.musiqa_dl_fail,
                Toast.LENGTH_SHORT).show()
        }
    }

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

    private fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etQuery.windowToken, 0)
    }

    private suspend fun <T> withContextIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    private fun stopPlayback() {
        player?.pause()
        adapter.setPlaying(-1)
        btnPlayPause?.setImageResource(R.drawable.ic_yuz_play)
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
        ui.removeCallbacks(tick)
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        ui.removeCallbacks(tick)
        scope.cancel()
        runCatching { effects.release() }
        runCatching { player?.release() }
        player = null
    }
}
