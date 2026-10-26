package uz.kodlikino.top4

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.card.MaterialCardView
import org.telegram.messenger.R
import uz.kodlikino.top4.models.ClassifiedAd

/**
 * E'lonlar feed'i. Ro'yxat ikki qismga bo'linadi — "Sizning e'lonlaringiz" va
 * "Boshqa e'lonlar" — lekin bo'lim sarlavhalari faqat ikkala guruh ham mavjud
 * bo'lganda ko'rsatiladi. Yangilanish DiffUtil orqali, shuning uchun yangi e'lon
 * kelganda ro'yxat qayta chizilmaydi (rasmlar miltillamaydi).
 */
class ClassifiedAdsAdapter(
    private val onAdClick: (ClassifiedAd) -> Unit,
    private val onEditClick: ((ClassifiedAd) -> Unit)? = null,
    private val onDeleteClick: ((ClassifiedAd) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Section(val title: String, val count: Int, val own: Boolean) : Row()
        data class Ad(val ad: ClassifiedAd, val own: Boolean) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val prefetchedImages = HashSet<String>()
    private var myUid: String = ""
    private var context: Context? = null
    private var thumbSizePx: Int = 0

    fun setMyUid(uid: String) {
        myUid = uid.trim()
    }

    /** Ro'yxat birinchi marta to'lishidan oldin chaqiriladi (bo'lim sarlavhalari uchun). */
    fun attach(context: Context) {
        this.context = context
        thumbSizePx = (THUMB_DP * context.resources.displayMetrics.density).toInt()
    }

    fun submit(ads: List<ClassifiedAd>, showSections: Boolean = true) {
        val ctx = context
        val mine = ads.filter { isMine(it) }
        val others = ads.filterNot { isMine(it) }
        val newRows = ArrayList<Row>(ads.size + 2)
        if (showSections && ctx != null && mine.isNotEmpty() && others.isNotEmpty()) {
            newRows.add(Row.Section(ctx.getString(R.string.classified_section_my_ads), mine.size, true))
            mine.forEach { newRows.add(Row.Ad(it, true)) }
            newRows.add(
                Row.Section(ctx.getString(R.string.classified_section_other_ads), others.size, false)
            )
            others.forEach { newRows.add(Row.Ad(it, false)) }
        } else {
            ads.forEach { newRows.add(Row.Ad(it, isMine(it))) }
        }

        val diff = DiffUtil.calculateDiff(DiffCallback(rows, newRows))
        rows.clear()
        rows.addAll(newRows)
        diff.dispatchUpdatesTo(this)

        prefetchFirstImages(ads)
    }

    private fun isMine(ad: ClassifiedAd): Boolean =
        myUid.isNotBlank() && ad.ownerUid == myUid

    /** Birinchi ekranga tushadigan rasmlarni oldindan keshga tushiradi. */
    private fun prefetchFirstImages(ads: List<ClassifiedAd>) {
        val ctx = context ?: return
        val sizePx = thumbSizePx
        if (sizePx <= 0) return
        ads.asSequence()
            .mapNotNull { ad -> ad.imageUrls.firstOrNull { it.isNotBlank() } }
            .filter { prefetchedImages.add(it) }
            .take(PREFETCH_COUNT)
            .forEach { url ->
                Glide.with(ctx)
                    .load(url)
                    .override(sizePx)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .priority(Priority.LOW)
                    .preload()
            }
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Section -> TYPE_SECTION
        is Row.Ad -> TYPE_AD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (context == null) attach(parent.context)
        return if (viewType == TYPE_SECTION) {
            SectionViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_classified_ad_section, parent, false)
            )
        } else {
            AdViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_classified_ad_list, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Section -> (holder as SectionViewHolder).bind(row)
            is Row.Ad -> (holder as AdViewHolder).bind(row)
        }
    }

    private class SectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvSectionTitle)
        private val tvCount: TextView = itemView.findViewById(R.id.tvSectionCount)

        fun bind(row: Row.Section) {
            tvTitle.text = row.title
            tvTitle.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    itemView.context,
                    if (row.own) R.color.yuz_gold else R.color.yuz_text_secondary
                )
            )
            tvCount.text = row.count.toString()
        }
    }

    private inner class AdViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: MaterialCardView = itemView.findViewById(R.id.adItemRoot)
        private val ivAdImage: ImageView = itemView.findViewById(R.id.ivAdImage)
        private val tvAdBadge: TextView = itemView.findViewById(R.id.tvAdBadge)
        private val tvAdOwn: TextView = itemView.findViewById(R.id.tvAdOwn)
        private val tvAdImageCount: TextView = itemView.findViewById(R.id.tvAdImageCount)
        private val tvAdMeta: TextView = itemView.findViewById(R.id.tvAdMeta)
        private val tvAdTitle: TextView = itemView.findViewById(R.id.tvAdTitle)
        private val tvAdLocation: TextView = itemView.findViewById(R.id.tvAdLocation)
        private val locationRow: View = itemView.findViewById(R.id.adLocationRow)
        private val tvAdPrice: TextView = itemView.findViewById(R.id.tvAdPrice)
        private val tvAdAuthor: TextView = itemView.findViewById(R.id.tvAdAuthor)
        private val actionsRow: View = itemView.findViewById(R.id.adActionsRow)
        private val btnEdit: ImageButton = itemView.findViewById(R.id.btnAdEdit)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnAdDelete)

        fun bind(row: Row.Ad) {
            ClassifiedAdCardBinder.bindList(
                ad = row.ad,
                ivAdImage = ivAdImage,
                tvAdBadge = tvAdBadge,
                tvAdOwn = tvAdOwn,
                tvAdImageCount = tvAdImageCount,
                tvAdMeta = tvAdMeta,
                tvAdTitle = tvAdTitle,
                tvAdLocation = tvAdLocation,
                locationRow = locationRow,
                tvAdPrice = tvAdPrice,
                tvAdAuthor = tvAdAuthor,
                isOwn = row.own,
                sizePx = thumbSizePx
            )
            cardRoot.setOnClickListener { onAdClick(row.ad) }
            // Tahrirlash/o'chirish faqat o'z e'lonida ko'rinadi.
            actionsRow.visibility = if (row.own) View.VISIBLE else View.GONE
            btnEdit.setOnClickListener { onEditClick?.invoke(row.ad) }
            btnDelete.setOnClickListener { onDeleteClick?.invoke(row.ad) }
        }
    }

    private class DiffCallback(
        private val oldRows: List<Row>,
        private val newRows: List<Row>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldRows.size

        override fun getNewListSize(): Int = newRows.size

        override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean {
            val a = oldRows[oldPosition]
            val b = newRows[newPosition]
            return when {
                a is Row.Section && b is Row.Section -> a.own == b.own
                a is Row.Ad && b is Row.Ad -> a.ad.id == b.ad.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean =
            oldRows[oldPosition] == newRows[newPosition]
    }

    private companion object {
        const val TYPE_SECTION = 0
        const val TYPE_AD = 1
        const val THUMB_DP = 104
        const val PREFETCH_COUNT = 8
    }
}
