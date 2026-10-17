package uz.kodlikino.top4.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import uz.kodlikino.top4.models.Reel

/**
 * Profilidagi reel'lar 3 ustunli grid adapter'i.
 *
 * [manageMode] yoqilganda (faqat o'z profilida) har katakda qizil ✕ chiqadi —
 * bosilsa [onDelete] orqali reel o'chiriladi. O'chirish rejimi bo'lmasa katakni
 * bosish [onClick] (indeks) orqali o'sha reel'dan boshlab scoped feed ochadi.
 *
 * Katak kvadrat bo'lishi uchun balandlik eniga `(ekran - ajratgich) / 3` qilib
 * qo'yiladi — 3 ustunli GridLayoutManager eni shu.
 */
class ProfileReelsAdapter(
    private val appContext: Context,
    private val onClick: (index: Int) -> Unit,
    private val onDelete: (Reel) -> Unit
) : RecyclerView.Adapter<ProfileReelsAdapter.Cell>() {

    private val items = ArrayList<Reel>()

    /** Boshqaruv rejimi — ✕ belgilarini ko'rsatadi. */
    var manageMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChangedSafe()
            }
        }

    private fun notifyDataSetChangedSafe() {
        try {
            notifyDataSetChanged()
        } catch (_: IllegalStateException) {
            // RecyclerView layout davomida chaqirilsa — keyingi kargacha qoldiramiz.
        }
    }

    fun submit(list: List<Reel>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChangedSafe()
    }

    fun removeAt(index: Int) {
        if (index < 0 || index >= items.size) return
        items.removeAt(index)
        notifyItemRemoved(index)
        notifyItemRangeChanged(index, items.size)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cell {
        val view = LayoutInflater.from(appContext)
            .inflate(R.layout.item_reel_grid, parent, false)
        return Cell(view)
    }

    override fun onBindViewHolder(holder: Cell, position: Int) {
        val reel = items.getOrNull(position) ?: return
        // Kvadrat katak: 3 ustun uchun eni / 3.
        val side = (appContext.resources.displayMetrics.widthPixels -
            AndroidUtilities.dp(4f) * 3) / 3
        holder.itemView.layoutParams = holder.itemView.layoutParams?.apply {
            height = side
        }
        holder.bind(reel, position)
    }

    private fun compact(count: Int): String = when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
        count >= 1_000 -> String.format("%.1fk", count / 1_000f)
        else -> count.toString()
    }

    inner class Cell(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumb: ImageView = itemView.findViewById(R.id.ivReelThumb)
        private val views: TextView = itemView.findViewById(R.id.tvReelViews)
        private val del: ImageView = itemView.findViewById(R.id.tvReelDelete)

        fun bind(reel: Reel, position: Int) {
            views.text = compact(reel.views)
            if (reel.thumbnailUrl.isNotBlank()) {
                Glide.with(thumb)
                    .load(reel.thumbnailUrl)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(R.drawable.bg_classified_image_placeholder)
                    .error(R.drawable.bg_classified_image_placeholder)
                    .into(thumb)
            } else {
                thumb.setImageDrawable(null)
                thumb.setBackgroundColor(0xFF0A1120.toInt())
            }
            del.visibility = if (manageMode) View.VISIBLE else View.GONE
            del.setOnClickListener {
                val idx = adapterPosition
                if (idx in items.indices) onDelete(items[idx])
            }
            itemView.setOnClickListener {
                val idx = adapterPosition
                if (idx in items.indices) onClick(idx)
            }
        }
    }
}
