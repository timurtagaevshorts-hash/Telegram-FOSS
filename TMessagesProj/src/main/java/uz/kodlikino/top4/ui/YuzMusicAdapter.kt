package uz.kodlikino.top4.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.telegram.messenger.R
import uz.kodlikino.top4.utils.YuzMusicCache
import uz.kodlikino.top4.utils.YuzTrack

/** Music rows keep their identity when downloads move above catalog results. */
class YuzMusicAdapter(
    private val onPlay: (YuzTrack, Int) -> Unit,
    private val onDownload: (YuzTrack, Int) -> Unit,
    private val onLongPress: (YuzTrack, Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_SHIMMER = 1
        const val SHIMMER_ID = Long.MIN_VALUE
        const val PAYLOAD_DOWNLOAD = "download"
        const val PAYLOAD_PLAYING = "playing"
    }

    private data class DownloadState(val pct: Int, val rate: String, val canceling: Boolean)

    private var items: List<YuzTrack> = emptyList()
    private val positions = HashMap<Long, Int>()
    private var playingId: Long? = null
    private var playing = false
    private var downloadedIds: Set<Long> = emptySet()
    private var downloads: Map<Long, DownloadState> = emptyMap()
    private var loadingMore = false

    init {
        setHasStableIds(true)
    }

    fun setLoadingMore(on: Boolean) {
        if (loadingMore == on) return
        loadingMore = on
        if (on) notifyItemInserted(items.size) else notifyItemRemoved(items.size)
    }

    /** Snapshot global state, including cancellation and speed-only changes. */
    fun setDownloading(ids: Set<Long>) {
        val next = ids.associateWith { id ->
            DownloadState(
                YuzMusicCache.inflightPct(id).coerceIn(0, 100),
                YuzMusicCache.rateLabel(id),
                YuzMusicCache.isCanceling(id)
            )
        }
        if (downloads == next) return
        val changed = (downloads.keys + next.keys).filter { downloads[it] != next[it] }
        downloads = next
        notifyDownloads(changed)
    }

    fun setProgress(id: Long, pct: Int) {
        val old = downloads[id] ?: return
        val next = DownloadState(
            pct.coerceIn(0, 100), YuzMusicCache.rateLabel(id), YuzMusicCache.isCanceling(id)
        )
        if (old == next) return
        downloads = downloads + (id to next)
        notifyDownloads(listOf(id))
    }

    /** Include the footer in the diff so it survives insertions and reorders. */
    fun submit(list: List<YuzTrack>) {
        val next = list.distinctBy { it.id }
        val old = items
        if (old == next) return
        val footer = loadingMore
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size + if (footer) 1 else 0
            override fun getNewListSize() = next.size + if (footer) 1 else 0

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldFooter = oldItemPosition == old.size
                val newFooter = newItemPosition == next.size
                return if (oldFooter || newFooter) oldFooter && newFooter
                else old[oldItemPosition].id == next[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                if (oldItemPosition == old.size) true
                else old[oldItemPosition] == next[newItemPosition]
        })
        items = next
        positions.clear()
        items.forEachIndexed { index, track -> positions[track.id] = index }
        diff.dispatchUpdatesTo(this)
    }

    fun append(list: List<YuzTrack>) {
        submit(items + list)
    }

    fun setPlaying(pos: Int, isPlaying: Boolean = true) {
        val id = items.getOrNull(pos)?.id
        val active = id != null && isPlaying
        if (playingId == id && playing == active) return
        val old = playingId
        playingId = id
        playing = active
        setOfNotNull(old, id).forEach { changed ->
            positions[changed]?.let { notifyItemChanged(it, PAYLOAD_PLAYING) }
        }
    }

    fun playingPosition(): Int = playingId?.let { positions[it] } ?: -1

    fun setDownloaded(ids: Set<Long>) {
        if (downloadedIds == ids) return
        val changed = (downloadedIds - ids) + (ids - downloadedIds)
        downloadedIds = ids.toSet()
        notifyDownloads(changed)
    }

    private fun notifyDownloads(ids: Collection<Long>) {
        ids.forEach { id -> positions[id]?.let { notifyItemChanged(it, PAYLOAD_DOWNLOAD) } }
    }

    override fun getItemCount(): Int = items.size + if (loadingMore) 1 else 0

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id ?: SHIMMER_ID

    override fun getItemViewType(position: Int): Int =
        if (position >= items.size) TYPE_SHIMMER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_SHIMMER) {
            return ShimmerVh(YuzShimmer(parent.context).apply { rowCount = 1 })
        }
        return RowVh(LayoutInflater.from(parent.context).inflate(R.layout.item_musiqa, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder !is RowVh) return
        val track = items.getOrNull(position) ?: return
        holder.track = track
        holder.title.text = track.title
        if (track.coverUrl.isNotBlank()) {
            Glide.with(holder.cover).load(track.coverUrl).into(holder.cover)
        } else {
            Glide.with(holder.cover).clear(holder.cover)
            holder.cover.setImageDrawable(null)
        }
        bindPlaying(holder, track)
        bindDownload(holder, track)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>
    ) {
        if (holder !is RowVh || payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val track = items.getOrNull(position) ?: return
        holder.track = track
        if (PAYLOAD_PLAYING in payloads) bindPlaying(holder, track)
        if (PAYLOAD_DOWNLOAD in payloads) bindDownload(holder, track)
    }

    private fun bindPlaying(h: RowVh, track: YuzTrack) {
        val active = track.id == playingId && playing
        h.play.setImageResource(if (active) R.drawable.ic_yuz_pause else R.drawable.ic_yuz_play)
        h.play.contentDescription = "${h.play.context.getString(
            if (active) R.string.yuz_a11y_pause else R.string.yuz_a11y_play
        )}: ${track.title}"
    }

    private fun bindDownload(h: RowVh, track: YuzTrack) {
        val context = h.itemView.context
        val state = downloads[track.id]
        val busy = state != null
        val canceling = state?.canceling == true
        val has = !busy && track.id in downloadedIds
        h.artist.text = if (canceling) context.getString(R.string.musiqa_dl_canceling)
        else listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" • ")
        h.dur.text = when {
            state != null -> if (state.rate.isEmpty() || canceling) "↓ ${state.pct}%"
                else "↓ ${state.pct}% ${state.rate}"
            track.durationSec <= 0 -> ""
            else -> String.format("%d:%02d", track.durationSec / 60, track.durationSec % 60)
        }
        h.dl.setImageResource(when {
            busy -> R.drawable.ic_yuz_close
            has -> R.drawable.ic_yuz_check
            else -> R.drawable.ic_yuz_download
        })
        h.dl.isEnabled = !canceling
        h.dl.alpha = if (busy || has) 1f else 0.55f
        h.dl.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(
            context, if (has) R.color.yuz_action else R.color.yuz_text_secondary
        ))
        val action = context.getString(when {
            canceling -> R.string.musiqa_dl_canceling
            busy -> R.string.musiqa_pl_dl_cancel
            has -> R.string.musiqa_pl_dl_done
            else -> R.string.yuz_a11y_download
        })
        h.dl.contentDescription = "$action: ${track.title}"
        h.dlBox.visibility = if (busy) View.VISIBLE else View.GONE
        h.dlFill.scaleX = (state?.pct ?: 0) / 100f
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is RowVh) holder.track = null
        super.onViewRecycled(holder)
    }

    class ShimmerVh(v: View) : RecyclerView.ViewHolder(v)

    inner class RowVh(v: View) : RecyclerView.ViewHolder(v) {
        var track: YuzTrack? = null
        val cover: ImageView = v.findViewById(R.id.ivMusiqaCover)
        val title: TextView = v.findViewById(R.id.tvMusiqaTitle)
        val artist: TextView = v.findViewById(R.id.tvMusiqaArtist)
        val dur: TextView = v.findViewById(R.id.tvMusiqaDur)
        val dl: ImageButton = v.findViewById(R.id.btnMusiqaDl)
        val play: ImageButton = v.findViewById(R.id.btnMusiqaPlay)
        val dlBox: View = v.findViewById(R.id.boxMusiqaDlProgress)
        val dlFill: View = v.findViewById(R.id.vwMusiqaDlFill)

        private fun currentTrack(): YuzTrack? {
            val pos = adapterPosition
            if (pos == RecyclerView.NO_POSITION) return null
            val bound = track ?: return null
            return bound.takeIf { items.getOrNull(pos)?.id == it.id }
        }

        init {
            dlFill.pivotX = 0f
            dl.setOnClickListener { currentTrack()?.let { onDownload(it, adapterPosition) } }
            val playClick = View.OnClickListener {
                currentTrack()?.let { onPlay(it, adapterPosition) }
            }
            play.setOnClickListener(playClick)
            itemView.setOnClickListener(playClick)
            itemView.setOnLongClickListener {
                val bound = currentTrack() ?: return@setOnLongClickListener false
                onLongPress(bound, adapterPosition)
                true
            }
        }
    }
}
