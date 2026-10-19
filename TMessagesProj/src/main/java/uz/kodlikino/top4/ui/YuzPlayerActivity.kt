package uz.kodlikino.top4.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.utils.YuzMusicCache
import uz.kodlikino.top4.utils.YuzMusicEffects
import uz.kodlikino.top4.utils.YuzMusicEngine
import uz.kodlikino.top4.utils.YuzTrack

/**
 * YuzGram #69: to'liq ekranli professional pleyer — muqova, seek, shuffle,
 * repeat (OFF/ONE/ALL), tezlik, navbat paneli, sevimlilar, yuklab olish,
 * ekvalayzer, uyqu taymeri va radio kaliti. Ijro boshlanishi bilan avtomatik
 * ochiladi; mini-pleyerni bosish ham shu yerga olib keladi.
 */
class YuzPlayerActivity : BaseFragment() {

    private lateinit var ctx: Context
    private lateinit var root: View
    private val effects by lazy { YuzMusicEffects(ctx) }

    private var ivCover: ImageView? = null
    private var tvTitle: TextView? = null
    private var tvArtist: TextView? = null
    private var seek: SeekBar? = null
    private var tvPos: TextView? = null
    private var tvDur: TextView? = null
    private var btnPlay: ImageButton? = null
    private var btnShuffle: ImageButton? = null
    private var btnRepeat: ImageButton? = null
    private var tvSpeedLabel: TextView? = null
    private var tvSleepLabel: TextView? = null
    private var tvRadio: TextView? = null
    private var ivHeart: ImageView? = null
    private var speedPanel: LinearLayout? = null
    private var queuePanel: View? = null
    private var queueRv: RecyclerView? = null
    private var tvQueueEmpty: TextView? = null
    private var qAdapter: QueueAdapter? = null

    private var seeking = false
    private var speed = 1.0f
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            ui.postDelayed(this, 400)
        }
    }

    private val observer = object : YuzMusicEngine.Observer {
        override fun onItemChanged(item: MediaItem?) {
            val t = item?.localConfiguration?.tag as? YuzTrack
            if (t != null) bind(t)
            qAdapter?.notifyDataSetChanged()
            syncButtons()
        }

        override fun onPlayingChanged(playing: Boolean) {
            btnPlay?.setImageResource(
                if (playing) R.drawable.ic_yuz_pause else R.drawable.ic_yuz_play
            )
        }

        override fun onStatus(text: String?) {
            tvArtist?.text = text ?: YuzMusicEngine.currentTrack()?.artist ?: ""
        }
    }

    override fun createView(context: Context): View {
        ctx = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        root = LayoutInflater.from(ctx).inflate(R.layout.fragment_yuz_player, null)
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)
        YuzTopInset.applyPadding(root.findViewById<View>(R.id.plTopBar))

        ivCover = root.findViewById(R.id.plCover)
        tvTitle = root.findViewById(R.id.tvPlTitle)
        tvArtist = root.findViewById(R.id.tvPlArtist)
        seek = root.findViewById(R.id.plSeek)
        tvPos = root.findViewById(R.id.tvPlPos)
        tvDur = root.findViewById(R.id.tvPlDur)
        btnPlay = root.findViewById(R.id.btnPlPlay)
        btnShuffle = root.findViewById(R.id.btnPlShuffle)
        btnRepeat = root.findViewById(R.id.btnPlRepeat)
        tvSpeedLabel = root.findViewById(R.id.tvPlSpeedLabel)
        tvSleepLabel = root.findViewById(R.id.tvPlSleepLabel)
        tvRadio = root.findViewById(R.id.tvPlRadio)
        ivHeart = root.findViewById(R.id.ivPlHeart)
        speedPanel = root.findViewById(R.id.plSpeedPanel)
        queuePanel = root.findViewById(R.id.plQueuePanel)
        queueRv = root.findViewById(R.id.plQueueRv)
        tvQueueEmpty = root.findViewById(R.id.plQueueEmpty)
        // Navbat paneli balandligi — ekraning ~46% (XML foizni FrameLayout
        // farzandida qo'llamaydi, shuning uchun kodda o'rnatamiz).
        queuePanel?.let { p ->
            val lp = p.layoutParams
            lp.height = (org.telegram.messenger.AndroidUtilities.displaySize.y * 0.46f).toInt()
            p.layoutParams = lp
        }

        tvTitle?.isSelected = true

        root.findViewById<View>(R.id.btnPlClose).setOnClickListener {
            removeSelfFromStack()
        }
        btnPlay?.setOnClickListener {
            val p = YuzMusicEngine.playerOrNull() ?: return@setOnClickListener
            if (p.currentMediaItem == null) return@setOnClickListener
            if (p.isPlaying) p.pause() else p.playWhenReady = true
        }
        root.findViewById<View>(R.id.btnPlPrev).setOnClickListener {
            YuzMusicEngine.playerOrNull()?.seekToPrevious()
        }
        root.findViewById<View>(R.id.btnPlNext).setOnClickListener {
            YuzMusicEngine.playerOrNull()?.seekToNextMediaItem()
        }
        btnShuffle?.setOnClickListener {
            val p = YuzMusicEngine.playerOrNull() ?: return@setOnClickListener
            p.shuffleModeEnabled = !p.shuffleModeEnabled
            syncButtons()
        }
        btnRepeat?.setOnClickListener {
            val p = YuzMusicEngine.playerOrNull() ?: return@setOnClickListener
            p.repeatMode = when (p.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            syncButtons()
        }

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
                if (p.duration > 0) p.seekTo((sb?.progress ?: 0) * 1000L)
            }
        })

        buildSpeedPanel()
        root.findViewById<View>(R.id.btnPlSpeed).setOnClickListener { toggleSpeedPanel() }
        root.findViewById<View>(R.id.btnPlQueue).setOnClickListener { toggleQueuePanel() }
        root.findViewById<View>(R.id.btnQueueClose).setOnClickListener {
            queuePanel?.visibility = View.GONE
        }
        root.findViewById<View>(R.id.btnPlHeart).setOnClickListener { heart() }
        root.findViewById<View>(R.id.btnPlDl).setOnClickListener { download() }
        root.findViewById<View>(R.id.btnPlEq).setOnClickListener { showEq() }
        root.findViewById<View>(R.id.btnPlSleep).setOnClickListener { showSleep() }
        tvRadio?.setOnClickListener {
            YuzMusicEngine.radio = !YuzMusicEngine.radio
            syncButtons()
        }

        queueRv?.layoutManager = LinearLayoutManager(ctx)
        qAdapter = QueueAdapter()
        queueRv?.adapter = qAdapter

        val p = YuzMusicEngine.ensurePlayer(ctx)
        if (p.playbackState == Player.STATE_READY) attachEffects()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) attachEffects()
            }
        })
        YuzMusicEngine.addObserver(observer)

        YuzMusicEngine.currentTrack()?.let { bind(it) }
        syncButtons()
        updateProgress()
        return root
    }

    // ------------------------------------------------------------- binding

    private fun bind(t: YuzTrack) {
        tvTitle?.text = t.title
        tvArtist?.text = YuzMusicEngine.lastStatusText() ?: t.artist
        tvDur?.text = fmt(t.durationSec)
        if (t.coverUrl.isNotBlank()) {
            ivCover?.let { Glide.with(it).load(t.coverUrl).into(it) }
        }
        ivHeart?.setImageResource(
            if (inFavorites(t)) R.drawable.ic_yuz_heart_filled else R.drawable.ic_yuz_heart
        )
        qAdapter?.notifyDataSetChanged()
        syncQueueEmpty()
    }

    private fun syncButtons() {
        val p = YuzMusicEngine.playerOrNull()
        btnPlay?.setImageResource(
            if (p != null && p.isPlaying) R.drawable.ic_yuz_pause else R.drawable.ic_yuz_play
        )
        btnShuffle?.alpha = if (p != null && p.shuffleModeEnabled) 1.0f else 0.45f
        when (p?.repeatMode) {
            Player.REPEAT_MODE_ONE -> {
                btnRepeat?.imageTintList = accent()
            }
            Player.REPEAT_MODE_ALL -> {
                btnRepeat?.imageTintList = ColorStateList.valueOf(color(R.color.yuz_text))
            }
            else -> {
                btnRepeat?.imageTintList = ColorStateList.valueOf(color(R.color.yuz_text_muted))
            }
        }
        val radioOn = YuzMusicEngine.radio
        tvRadio?.text = ctx.getString(if (radioOn) R.string.musiqa_pl_radio_on else R.string.musiqa_pl_radio_off)
        tvRadio?.setBackgroundResource(
            if (radioOn) R.drawable.bg_yuz_pill_active else R.drawable.bg_yuz_pill
        )
        tvRadio?.setTextColor(
            color(if (radioOn) R.color.yuz_text_on_accent else R.color.yuz_text_secondary)
        )
        val cur = YuzMusicEngine.currentTrack()
        if (cur != null) ivHeart?.setImageResource(
            if (inFavorites(cur)) R.drawable.ic_yuz_heart_filled else R.drawable.ic_yuz_heart
        )
    }

    private fun color(res: Int) = ContextCompat.getColor(ctx, res)

    private fun accent() = ColorStateList.valueOf(color(R.color.yuz_action))

    private fun attachEffects() {
        val p = YuzMusicEngine.playerOrNull() ?: return
        effects.ensureSession(p.audioSessionId)
    }

    private fun updateProgress() {
        val p = YuzMusicEngine.playerOrNull() ?: return
        if (seeking || p.duration <= 0) return
        val d = (p.duration / 1000).toInt()
        val pos = (p.currentPosition / 1000).toInt()
        seek?.max = d
        seek?.progress = pos
        tvPos?.text = fmt(pos)
        tvDur?.text = fmt(d)
    }

    private fun fmt(sec: Int) = String.format("%d:%02d", sec / 60, sec % 60)

    // ------------------------------------------------------------- panels

    private fun toggleSpeedPanel() {
        val sp = speedPanel ?: return
        val show = sp.visibility != View.VISIBLE
        if (show) syncSpeedChips(sp)
        sp.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun buildSpeedPanel() {
        val sp = speedPanel ?: return
        for (v in floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)) {
            val chip = YuzChips.pillText(ctx, speedLabel(v), v == speed)
            chip.setOnClickListener {
                val p = YuzMusicEngine.playerOrNull()
                p?.playbackParameters = PlaybackParameters(v)
                speed = v
                tvSpeedLabel?.text = if (v == 1f) ctx.getString(R.string.musiqa_pl_speed)
                else ctx.getString(R.string.musiqa_pl_speed_value, speedLabel(v))
                syncSpeedChips(sp)
                sp.visibility = View.GONE
            }
            sp.addView(chip)
        }
    }

    private fun speedLabel(v: Float) = if (v == 1f) "1x" else "${v}x"

    private fun syncSpeedChips(sp: LinearLayout) {
        val values = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        for (i in 0 until sp.childCount) {
            val v = values.getOrElse(i) { 1f }
            val checked = v == speed
            (sp.getChildAt(i) as? TextView)?.apply {
                setBackgroundResource(
                    if (checked) R.drawable.bg_yuz_pill_active else R.drawable.bg_yuz_pill
                )
                setTextColor(
                    ContextCompat.getColor(
                        ctx, if (checked) R.color.yuz_text_on_accent else R.color.yuz_text_secondary
                    )
                )
                setTypeface(typeface, if (checked) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun toggleQueuePanel() {
        val qp = queuePanel ?: return
        val show = qp.visibility != View.VISIBLE
        qp.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            qAdapter?.notifyDataSetChanged()
            syncQueueEmpty()
        }
    }

    private fun syncQueueEmpty() {
        tvQueueEmpty?.visibility =
            if (YuzMusicEngine.queueTracks.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.VH>() {
        inner class VH(val layout: LinearLayout, val tv1: TextView, val tv2: TextView) :
            RecyclerView.ViewHolder(layout)

        override fun getItemCount(): Int = YuzMusicEngine.queueTracks.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = LinearLayout(ctx)
            layout.orientation = LinearLayout.VERTICAL
            layout.setBackgroundResource(R.drawable.bg_yuz_row_ripple)
            layout.isClickable = true
            layout.setPadding(
                AndroidUtilities.dp(20f), AndroidUtilities.dp(12f),
                AndroidUtilities.dp(20f), AndroidUtilities.dp(12f)
            )
            val tv1 = TextView(ctx)
            tv1.textSize = 14f
            tv1.setTextColor(color(R.color.yuz_text))
            tv1.isSingleLine = true
            tv1.ellipsize = android.text.TextUtils.TruncateAt.END
            val tv2 = TextView(ctx)
            tv2.textSize = 11f
            tv2.setTextColor(color(R.color.yuz_text_secondary))
            tv2.isSingleLine = true
            layout.addView(tv1)
            layout.addView(tv2)
            layout.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return VH(layout, tv1, tv2)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = YuzMusicEngine.queueTracks[position]
            holder.tv1.text = t.title
            holder.tv2.text = t.artist
            val cur = YuzMusicEngine.playerOrNull()?.currentMediaItemIndex == position
            holder.tv1.setTextColor(color(if (cur) R.color.yuz_action else R.color.yuz_text))
            holder.layout.setOnClickListener {
                YuzMusicEngine.playAt(position)
                queuePanel?.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------- actions

    private fun inFavorites(t: YuzTrack): Boolean {
        return YuzMusicEngine.playlists(ctx)
            .firstOrNull { it.name == "Sevimli" }
            ?.tracks?.any { it.id == t.id } == true
    }

    private fun heart() {
        val t = YuzMusicEngine.currentTrack() ?: return
        val was = inFavorites(t)
        if (was) {
            val pl = YuzMusicEngine.playlists(ctx).firstOrNull { it.name == "Sevimli" }
            if (pl != null) {
                val rest = pl.tracks.filter { it.id != t.id }
                YuzMusicEngine.playlistRemove(ctx, "Sevimli")
                for (x in rest) YuzMusicEngine.playlistAdd(ctx, "Sevimli", x)
            }
        } else {
            YuzMusicEngine.playlistAdd(ctx, "Sevimli", t)
        }
        Toast.makeText(ctx,
            ctx.getString(
                if (was) R.string.musiqa_fav_removed else R.string.musiqa_fav_added
            ),
            Toast.LENGTH_SHORT).show()
        syncButtons()
    }

    private fun download() {
        val t = YuzMusicEngine.currentTrack() ?: return
        if (YuzMusicCache.isDownloaded(ctx, t.id)) {
            Toast.makeText(ctx, R.string.musiqa_dl_exists, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(ctx, R.string.musiqa_dl_started, Toast.LENGTH_SHORT).show()
        YuzMusicCache.download(ctx, t) { ok ->
            Toast.makeText(ctx,
                if (ok) R.string.musiqa_dl_done else R.string.musiqa_dl_fail,
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEq() {
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
        val dlg = AlertDialog.Builder(ctx, getResourceProvider())
            .setTitle(ctx.getString(R.string.musiqa_eq_title))
            .setView(v)
            .setPositiveButton(ctx.getString(android.R.string.ok), null)
            .create()
        showDialog(dlg)
    }

    private fun showSleep() {
        val minutes = longArrayOf(15, 30, 45, 60, 0)
        val labels = Array(minutes.size) { i ->
            val m = minutes[i]
            if (m == 0L) ctx.getString(R.string.musiqa_sleep_off)
            else ctx.getString(R.string.musiqa_sleep_minutes, m.toInt())
        }
        val dlg = AlertDialog.Builder(ctx, getResourceProvider())
            .setTitle(ctx.getString(R.string.musiqa_sleep_title))
            .setItems(labels) { _, which ->
                val mins = minutes[which]
                YuzMusicEngine.scheduleSleep(mins * 60_000L)
                tvSleepLabel?.text = if (mins == 0L) ctx.getString(R.string.musiqa_pl_sleep)
                else ctx.getString(R.string.musiqa_sleep_label_value, mins.toInt())
                Toast.makeText(ctx,
                    ctx.getString(
                        if (mins == 0L) R.string.musiqa_sleep_cleared
                        else R.string.musiqa_sleep_set, mins.toInt()
                    ),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(ctx.getString(android.R.string.cancel), null)
            .create()
        showDialog(dlg)
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        YuzMiniPlayer.setSuppressed(this, true)
        ui.post(tick)
        syncButtons()
    }

    override fun onPause() {
        super.onPause()
        YuzMiniPlayer.setSuppressed(this, false)
        ui.removeCallbacks(tick)
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        ui.removeCallbacks(tick)
        YuzMusicEngine.removeObserver(observer)
    }
}
