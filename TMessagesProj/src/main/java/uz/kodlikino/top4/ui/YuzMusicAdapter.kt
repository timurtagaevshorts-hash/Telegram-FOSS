package uz.kodlikino.top4.ui

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.telegram.messenger.R
import uz.kodlikino.top4.utils.YuzTrack

/**
 * YuzGram #62: musiqiy natijalar ro'yxati. Qatorni bosish — qo'shiqni ilova
 * ichidagi pleyerda ijro etadi (YouTube endi ochilmaydi); download tugmasi —
 * keshga saqlash (yoki yuklanganlar ro'yxatida — o'chirish).
 */
class YuzMusicAdapter(
    private val onPlay: (YuzTrack, Int) -> Unit,
    private val onDownload: (YuzTrack, Int) -> Unit,
    // YuzGram #67: qatorni bosib turish — playlist/navbat menyusi.
    private val onLongPress: (YuzTrack, Int) -> Unit
) : RecyclerView.Adapter<YuzMusicAdapter.Vh>() {

    private val items = ArrayList<YuzTrack>()
    private var playingPos = -1
    private var downloadedIds: Set<Long> = emptySet()
    // YuzGram #98: yuklab olinayotgan qatorlar — tugma pulsatsiya qiladi.
    private var downloadingIds: Set<Long> = emptySet()

    fun setDownloading(ids: Set<Long>) {
        downloadingIds = ids.toSet()
        notifyDataSetChanged()
    }

    fun submit(list: List<YuzTrack>) {
        items.clear()
        items.addAll(list)
        playingPos = -1
        notifyDataSetChanged()
    }

    fun setPlaying(pos: Int) {
        val old = playingPos
        playingPos = pos
        if (old in items.indices) notifyItemChanged(old)
        if (pos in items.indices) notifyItemChanged(pos)
    }

    fun playingPosition(): Int = playingPos

    fun setDownloaded(ids: Set<Long>) {
        downloadedIds = ids
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_musiqa, parent, false)
        return Vh(v)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val t = items[position.coerceIn(0, items.size - 1)]
        holder.title.text = t.title
        val sub = StringBuilder()
        if (t.artist.isNotBlank()) sub.append(t.artist)
        if (t.album.isNotBlank()) {
            if (sub.isNotEmpty()) sub.append(" • ")
            sub.append(t.album)
        }
        holder.artist.text = sub
        holder.dur.text = if (t.durationSec <= 0) "" else
            String.format("%d:%02d", t.durationSec / 60, t.durationSec % 60)
        if (t.coverUrl.isNotBlank()) {
            Glide.with(holder.cover).load(t.coverUrl).into(holder.cover)
        } else {
            holder.cover.setImageDrawable(null)
        }
        holder.play.setImageResource(
            if (position == playingPos) R.drawable.ic_yuz_pause else R.drawable.ic_yuz_play
        )
        val has = t.id in downloadedIds
        val busy = t.id in downloadingIds
        if (busy) {
            if (holder.pulse == null) {
                holder.pulse = ObjectAnimator.ofFloat(holder.dl, "alpha", 0.9f, 0.18f).apply {
                    duration = 520L
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                }
            }
            if (holder.pulse!!.isRunning.not()) holder.pulse!!.start()
        } else {
            holder.pulse?.cancel()
            holder.pulse = null
            holder.dl.alpha = if (has) 1.0f else 0.55f
        }
        holder.dl.setOnClickListener { onDownload(t, holder.adapterPosition) }
        holder.itemView.setOnClickListener { onPlay(t, holder.adapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongPress(t, holder.adapterPosition)
            true
        }
    }

    class Vh(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.ivMusiqaCover)
        val title: TextView = v.findViewById(R.id.tvMusiqaTitle)
        val artist: TextView = v.findViewById(R.id.tvMusiqaArtist)
        val dur: TextView = v.findViewById(R.id.tvMusiqaDur)
        val dl: ImageButton = v.findViewById(R.id.btnMusiqaDl)
        var pulse: ObjectAnimator? = null
        val play: ImageButton = v.findViewById(R.id.btnMusiqaPlay)
    }
}
