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
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.utils.YuzMusicCache
import uz.kodlikino.top4.utils.YuzMusicEngine
import uz.kodlikino.top4.utils.YuzTrack

/**
 * YuzGram #128: "Yuklab olinganlar" papkasi — keshdagi treklar mustaqil,
 * chiroyli ekranda: tepada hajm pilli ("12 trek • 84 MB"), qatorlar bosqichma-
 * bosqich paydo bo'ladigan fade-in animatsiyasi bilan, bo'sh holatda markazda
 * neon ikonli tushuntirish. Qatorni bosish — pleyerda ijro + to'liq ekran;
 * yuklash tugmasi — trekkni keshdan o'chiradi.
 */
class YuzDownloadsActivity : BaseFragment() {

    private lateinit var ctx: Context
    private lateinit var adapter: YuzMusicAdapter
    private var rv: RecyclerView? = null
    private var tvSummary: TextView? = null
    private var emptyBox: View? = null
    private var tracks: List<YuzTrack> = emptyList()
    private var destroyed = false
    private val ui = Handler(Looper.getMainLooper())
    // #128: boshqa ekrandan boshlangan yuklash tugasa — papka jonli yangilanadi.
    private var lastIds: Set<Long> = emptySet()
    private val tick = object : Runnable {
        override fun run() {
            checkNewDownloads()
            ui.postDelayed(this, 1000)
        }
    }

    private val observer = object : YuzMusicEngine.Observer {
        override fun onItemChanged(item: MediaItem?) {
            syncPlayingMark()
        }

        override fun onPlayingChanged(playing: Boolean) {
            syncPlayingMark()
        }

        override fun onStatus(text: String?) {}
    }

    override fun createView(context: Context): View {
        ctx = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        val root = LayoutInflater.from(ctx).inflate(R.layout.fragment_yuz_downloads, null)
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)
        YuzTopInset.applyPadding(root.findViewById<View>(R.id.dlTopBar))

        root.findViewById<View>(R.id.btnDlBack).setOnClickListener { removeSelfFromStack() }
        tvSummary = root.findViewById(R.id.tvDlSummary)
        emptyBox = root.findViewById(R.id.dlEmptyBox)

        val list = root.findViewById<RecyclerView>(R.id.rvDownloads)
        rv = list
        list.layoutManager = LinearLayoutManager(ctx)
        adapter = YuzMusicAdapter(
            onPlay = { _, pos -> rowPlay(pos) },
            onDownload = { track, _ -> removeTrack(track) },
            onLongPress = { track, _ -> rowMenu(track) }
        )
        list.adapter = adapter
        // #128: qatorlar tepadan pastga bosqichli fade-in bilan kiradi.
        val fade = android.view.animation.AnimationUtils.loadAnimation(
            ctx, android.R.anim.fade_in
        )
        list.layoutAnimation = android.view.animation.LayoutAnimationController(fade, 0.05f)

        reload()
        YuzMusicEngine.addObserver(observer)
        syncPlayingMark()
        return root
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
        reload()
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        destroyed = true
        ui.removeCallbacks(tick)
        YuzMusicEngine.removeObserver(observer)
    }

    // ------------------------------------------------------------- data

    private fun reload() {
        tracks = YuzMusicCache.downloadedTracks(ctx)
        adapter.submit(tracks)
        adapter.setDownloaded(tracks.map { it.id }.toSet())
        lastIds = tracks.map { it.id }.toSet()
        emptyBox?.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        rv?.scheduleLayoutAnimation()
        updateSummary()
        syncPlayingMark()
    }

    /** Reyestrdagi yuklashlar tugasa — papka yangi trekkni o'zi ko'rsatadi. */
    private fun checkNewDownloads() {
        val ids = YuzMusicCache.downloadedIds(ctx)
        if (ids != lastIds) reload()
    }

    private fun updateSummary() {
        val count = tracks.size
        val bytes = tracks.map { localBytes(it.id) }.sum()
        val mb = bytes / (1024.0 * 1024.0)
        val size = when {
            bytes <= 0 -> "0 MB"
            mb >= 1024 -> String.format("%.1f GB", mb / 1024.0)
            mb >= 1 -> String.format("%.0f MB", mb)
            else -> String.format("%.0f KB", bytes / 1024.0)
        }
        tvSummary?.text = ctx.getString(R.string.musiqa_dl_summary, count, size)
    }

    private fun localBytes(id: Long): Long =
        runCatching { YuzMusicCache.localFile(ctx, id)?.length() ?: 0L }.getOrDefault(0L)

    // ------------------------------------------------------------- actions

    private fun rowPlay(pos: Int) {
        if (pos !in tracks.indices) return
        val track = tracks[pos]
        val p = YuzMusicEngine.ensurePlayer(ctx)
        val playing = YuzMusicEngine.currentTrack()
        if (playing != null && playing.id == track.id) {
            // Ijrodagi trekni qayta bosish — plerga o'tadi (pause emas,
            // mini-bar/pleyerda boshqariladi).
            runCatching { presentFragment(YuzPlayerActivity()) }
            return
        }
        YuzMusicEngine.playQueue(ctx, tracks, pos)
        runCatching { ctx.startService(Intent(ctx, YuzMusicService::class.java)) }
        runCatching { presentFragment(YuzPlayerActivity()) }
    }

    private fun removeTrack(track: YuzTrack) {
        YuzMusicCache.remove(ctx, track.id)
        Toast.makeText(ctx, R.string.musiqa_dl_removed, Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun rowMenu(track: YuzTrack) {
        val labels = arrayOf(
            ctx.getString(R.string.musiqa_menu_add_queue),
            ctx.getString(R.string.musiqa_dl_removed)
        )
        val dlg = AlertDialog.Builder(ctx, getResourceProvider())
            .setTitle(track.title)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> {
                        YuzMusicEngine.enqueue(ctx, track)
                        runCatching {
                            ctx.startService(Intent(ctx, YuzMusicService::class.java))
                        }
                        Toast.makeText(ctx, R.string.musiqa_queue_added, Toast.LENGTH_SHORT).show()
                    }
                    else -> removeTrack(track)
                }
            }
            .setNegativeButton(ctx.getString(android.R.string.cancel), null)
            .create()
        showDialog(dlg)
    }

    private fun syncPlayingMark() {
        val cur = YuzMusicEngine.currentTrack() ?: run {
            adapter.setPlaying(-1)
            return
        }
        adapter.setPlaying(tracks.indexOfFirst { it.id == cur.id })
    }
}
