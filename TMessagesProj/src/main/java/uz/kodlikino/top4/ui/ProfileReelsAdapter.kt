package uz.kodlikino.top4.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.utils.YuzReelUploads
import java.io.File

/**
 * Profilidagi reel'lar 3 ustunli grid adapter'i.
 *
 * Ikki turdagi katak:
 *  - **yuklanmoqda** ([YuzReelUploads] trekeridan) — eng boshida turadi, lokal
 *    kadr + progress. Yuklash tugagach yozuv trekerdan o'chadi va o'sha joyda
 *    Firestore'dan kelgan oddiy reel katagi paydo bo'ladi;
 *  - **joylangan reel** — [Reel] ro'yxati.
 *
 * [manageMode] yoqilganda (faqat o'z profilida) joylangan katakda qizil ✕
 * chiqadi — bosilsa [onDelete]. Yuklanayotgan katakdagi ✕ esa yuklashni bekor
 * qiladi ([onPendingCancel]).
 *
 * Katak kvadrat bo'lishi uchun balandlik eniga `(ekran - ajratgich) / 3` qilib
 * qo'yiladi — 3 ustunli GridLayoutManager eni shu.
 */
class ProfileReelsAdapter(
    private val appContext: Context,
    private val onClick: (index: Int) -> Unit,
    private val onDelete: (Reel) -> Unit,
    private val onPendingTap: (YuzReelUploads.Upload) -> Unit = {},
    private val onPendingCancel: (YuzReelUploads.Upload) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_PENDING = 0
        private const val TYPE_REEL = 1
        private const val PAYLOAD_PROGRESS = "progress"
        /** Grid ochilishida birinchi ekran posterlarini oldindan yuklaymiz. */
        private const val PREFETCH_COUNT = 9
    }

    private val reels = ArrayList<Reel>()
    private val pending = ArrayList<YuzReelUploads.Upload>()

    /** Katak o'lchami bir marta hisoblanadi (har bind'da displayMetrics o'qilmaydi). */
    private val cellSide: Int by lazy { squareSide() }

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
        reels.clear()
        reels.addAll(list)
        notifyDataSetChangedSafe()
        prefetchThumbnails(list)
    }

    /**
     * Birinchi ekran posterlarini past prioritetda oldindan yuklaymiz: grid
     * ochilganda kataklar bo'sh joydan emas, tayyor rasmdan chiziladi.
     */
    private fun prefetchThumbnails(list: List<Reel>) {
        val side = cellSide
        list.asSequence()
            .map { it.thumbnailUrl }
            .filter { it.isNotBlank() }
            .distinct()
            .take(PREFETCH_COUNT)
            .forEach { url ->
                try {
                    Glide.with(appContext)
                        .load(url)
                        .override(side, side)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .priority(Priority.LOW)
                        .preload()
                } catch (_: Exception) {
                }
            }
    }

    /** Yuklanayotgan reels ro'yxati (trekerdan). */
    fun submitPending(list: List<YuzReelUploads.Upload>) {
        val sameSet = pending.size == list.size &&
            pending.indices.all { pending[it].id == list[it].id }
        pending.clear()
        pending.addAll(list)
        if (!sameSet) {
            notifyDataSetChangedSafe()
            return
        }
        // Faqat progress/label — Glide qayta yuklanmaydi.
        for (i in pending.indices) {
            try {
                notifyItemChanged(i, PAYLOAD_PROGRESS)
            } catch (_: IllegalStateException) {
                return
            }
        }
    }

    fun removeAt(index: Int) {
        if (index < 0 || index >= reels.size) return
        reels.removeAt(index)
        notifyDataSetChangedSafe()
    }

    override fun getItemCount(): Int = pending.size + reels.size

    override fun getItemViewType(position: Int): Int =
        if (position < pending.size) TYPE_PENDING else TYPE_REEL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(appContext)
        return if (viewType == TYPE_PENDING) {
            PendingCell(inflater.inflate(R.layout.item_reel_pending, parent, false))
        } else {
            Cell(inflater.inflate(R.layout.item_reel_grid, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is PendingCell -> pending.getOrNull(position)?.let { holder.bind(it) }
            is Cell -> {
                val reelIndex = position - pending.size
                reels.getOrNull(reelIndex)?.let { reel ->
                    holder.square()
                    holder.bind(reel, reelIndex)
                }
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (holder is PendingCell && payloads.contains(PAYLOAD_PROGRESS)) {
            pending.getOrNull(position)?.let { holder.bindProgress(it) }
            return
        }
        onBindViewHolder(holder, position)
    }

    private fun compact(count: Int): String = when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000f)
        count >= 1_000 -> String.format("%.1fk", count / 1_000f)
        else -> count.toString()
    }

    /** Kvadrat katak: 3 ustun uchun eni / 3. */
    private fun squareSide(): Int =
        (appContext.resources.displayMetrics.widthPixels - AndroidUtilities.dp(4f) * 3) / 3

    inner class PendingCell(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumb: ImageView = itemView.findViewById(R.id.ivPendingThumb)
        private val label: TextView = itemView.findViewById(R.id.tvPendingLabel)
        private val progress: ProgressBar = itemView.findViewById(R.id.pendingProgress)
        private val cancel: ImageView = itemView.findViewById(R.id.btnPendingCancel)

        fun bind(upload: YuzReelUploads.Upload) {
            itemView.layoutParams = itemView.layoutParams?.apply {
                height = cellSide
            }
            if (upload.thumbPath.isNotBlank()) {
                Glide.with(thumb)
                    .load(File(upload.thumbPath))
                    .override(cellSide, cellSide)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .placeholder(R.drawable.bg_classified_image_placeholder)
                    .error(R.drawable.bg_classified_image_placeholder)
                    .into(thumb)
            } else {
                thumb.setImageResource(R.drawable.bg_classified_image_placeholder)
            }
            bindProgress(upload)
            cancel.visibility = View.VISIBLE
            cancel.setOnClickListener { onPendingCancel(upload) }
            itemView.setOnClickListener { onPendingTap(upload) }
        }

        fun bindProgress(upload: YuzReelUploads.Upload) {
            val failed = upload.failed
            val danger = ContextCompat.getColor(appContext, R.color.yuz_danger)
            val normal = ContextCompat.getColor(appContext, R.color.yuz_text)
            label.setTextColor(if (failed) danger else normal)
            label.text = if (failed) {
                appContext.getString(R.string.reels_pending_failed)
            } else {
                appContext.getString(R.string.reels_pending_title) + " · " + upload.progress + "%"
            }
            progress.visibility = if (failed) View.INVISIBLE else View.VISIBLE
            progress.progress = upload.progress
        }
    }

    inner class Cell(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumb: ImageView = itemView.findViewById(R.id.ivReelThumb)
        private val views: TextView = itemView.findViewById(R.id.tvReelViews)
        private val del: ImageView = itemView.findViewById(R.id.tvReelDelete)

        fun square() {
            itemView.layoutParams = itemView.layoutParams?.apply {
                height = cellSide
            }
        }

        fun bind(reel: Reel, position: Int) {
            views.text = compact(reel.views)
            if (reel.thumbnailUrl.isNotBlank()) {
                Glide.with(thumb)
                    .load(reel.thumbnailUrl)
                    .override(cellSide, cellSide)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.bg_classified_image_placeholder)
                    .error(R.drawable.bg_classified_image_placeholder)
                    .into(thumb)
            } else {
                // Holder qayta ishlatilganda oldingi reelning rasmi qolib ketmasin.
                Glide.with(thumb).clear(thumb)
                thumb.setImageDrawable(null)
                thumb.setBackgroundColor(ContextCompat.getColor(appContext, R.color.yuz_bg))
            }
            del.visibility = if (manageMode) View.VISIBLE else View.GONE
            del.setOnClickListener { onDelete(reel) }
            itemView.setOnClickListener { onClick(position) }
        }
    }
}
