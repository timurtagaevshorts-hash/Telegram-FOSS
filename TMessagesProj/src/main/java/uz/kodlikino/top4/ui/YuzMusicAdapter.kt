package uz.kodlikino.top4.ui

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
 * Musiqa natijalari ro'yxati. Qatorni bosish — YouTube; play tugmasi — 30s preview.
 */
class YuzMusicAdapter(
    private val onPlay: (YuzTrack, Int) -> Unit,
    private val onOpenYouTube: (YuzTrack) -> Unit
) : RecyclerView.Adapter<YuzMusicAdapter.Vh>() {

    private val items = ArrayList<YuzTrack>()
    private var playingPos = -1

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

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_musiqa, parent, false)
        return Vh(v)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val t = items[holder.bindingAdapterPosition.coerceAtLeast(0)]
        holder.title.text = t.title
        val sub = StringBuilder()
        if (t.artist.isNotBlank()) sub.append(t.artist)
        if (t.album.isNotBlank()) {
            if (sub.isNotEmpty()) sub.append(" • ")
            sub.append(t.album)
        }
        holder.artist.text = sub
        holder.dur.text = String.format("%d:%02d", t.durationSec / 60, t.durationSec % 60)
        if (t.coverUrl.isNotBlank()) {
            Glide.with(holder.cover).load(t.coverUrl).into(holder.cover)
        } else {
            holder.cover.setImageDrawable(null)
        }
        holder.play.setImageResource(
            if (holder.bindingAdapterPosition == playingPos) R.drawable.ic_yuz_pause else R.drawable.ic_yuz_play
        )
        holder.play.setOnClickListener {
            onPlay(t, holder.bindingAdapterPosition)
        }
        holder.itemView.setOnClickListener { onOpenYouTube(t) }
    }

    class Vh(v: View) : RecyclerView.ViewHolder(v) {
        val cover: ImageView = v.findViewById(R.id.ivMusiqaCover)
        val title: TextView = v.findViewById(R.id.tvMusiqaTitle)
        val artist: TextView = v.findViewById(R.id.tvMusiqaArtist)
        val dur: TextView = v.findViewById(R.id.tvMusiqaDur)
        val play: ImageButton = v.findViewById(R.id.btnMusiqaPlay)
    }
}
