package uz.kodlikino.top4.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.utils.YuzMusicRepository
import uz.kodlikino.top4.utils.YuzTrack
import java.net.URLEncoder

/**
 * Musiqa topish — qo'shiq nomini yozsangiz (yoki AI orqali kirsangiz) Deezer
 * bo'yicha qidiriladi; play tugmasi 30 soniyalik preview'ni eshitadi,
 * qatorni bosish esa to'liq versiyani YouTube'da ochadi.
 */
class YuzMusicActivity(private val initialQuery: String? = null) : BaseFragment() {

    private lateinit var ctx: Context
    private lateinit var etQuery: EditText
    private lateinit var statusView: TextView
    private lateinit var adapter: YuzMusicAdapter

    private var player: ExoPlayer? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var busy = false

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
            onPlay = { track, pos -> playPreview(track, pos) },
            onOpenYouTube = { track -> openYouTube(track) }
        )
        rv.adapter = adapter

        root.findViewById<View>(R.id.btnMusiqaSearch)?.setOnClickListener { runSearch() }
        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else false
        }

        val q = initialQuery?.trim().orEmpty()
        if (q.isNotEmpty()) {
            etQuery.setText(q)
            runSearch()
        } else {
            loadChart()
        }
        return root
    }

    private fun runSearch() {
        val q = etQuery.text.toString().trim()
        if (q.isEmpty() || busy) return
        hideKeyboard()
        busy = true
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
                    stopPlayback()
                    adapter.submit(list)
                }
            }
        }
    }

    private fun loadChart() {
        if (busy) return
        busy = true
        scope.launch {
            val list = try {
                withContextIo { YuzMusicRepository.chart() }
            } catch (_: Exception) {
                null
            }
            busy = false
            if (list != null && list.isNotEmpty()) {
                statusView.text = ctx.getString(R.string.musiqa_trending)
                adapter.submit(list)
            } else {
                statusView.text = ctx.getString(R.string.musiqa_error)
            }
        }
    }

    private fun playPreview(track: YuzTrack, pos: Int) {
        if (track.previewUrl.isBlank()) {
            Toast.makeText(ctx, R.string.musiqa_no_preview, Toast.LENGTH_SHORT).show()
            return
        }
        val p = player ?: ExoPlayer.Builder(ctx).build().also {
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        adapter.setPlaying(-1)
                    }
                }
            })
            player = it
        }
        if (pos == adapter.playingPosition()) {
            stopPlayback()
            return
        }
        stopPlayback()
        p.setMediaItem(MediaItem.fromUri(track.previewUrl))
        p.prepare()
        p.playWhenReady = true
        adapter.setPlaying(pos)
    }

    private fun stopPlayback() {
        player?.pause()
        adapter.setPlaying(-1)
    }

    private fun openYouTube(track: YuzTrack) {
        val query = (track.title + " " + track.artist).trim()
        val url = "https://www.youtube.com/results?search_query=" +
            URLEncoder.encode(query, "UTF-8")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.link)))
            } catch (_: Exception) {
                Toast.makeText(ctx, R.string.musiqa_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etQuery.windowToken, 0)
    }

    private suspend fun <T> withContextIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    override fun onPause() {
        super.onPause()
        stopPlayback()
    }

    override fun onFragmentDestroy() {
        super.onFragmentDestroy()
        scope.cancel()
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }
}
