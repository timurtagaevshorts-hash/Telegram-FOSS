package uz.kodlikino.top4.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.button.MaterialButton
import org.telegram.messenger.R
import uz.kodlikino.top4.models.AdModeration
import uz.kodlikino.top4.models.ClassifiedAd
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Admin "E'lonlar" tab qatori — e'lon/ish e'loni kartasi: rasm, turi, sarlavha,
 * kim joylagani + sana, joriy moderatsiya holati va tasdiqlash/rad etish
 * tugmalari. Amallar [ClassifiedAdRepository.moderate] orqali bajariladi.
 */
class ModerationAdminAdapter(
    private val appContext: Context,
    private val onApprove: (ClassifiedAd) -> Unit,
    private val onReject: (ClassifiedAd) -> Unit
) : RecyclerView.Adapter<ModerationAdminAdapter.VH>() {

    private var items: List<ClassifiedAd> = emptyList()
    private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.ivModerationThumb)
        val kind: TextView = view.findViewById(R.id.tvModerationKind)
        val title: TextView = view.findViewById(R.id.tvModerationTitle)
        val meta: TextView = view.findViewById(R.id.tvModerationMeta)
        val status: TextView = view.findViewById(R.id.tvModerationStatus)
        val approve: MaterialButton = view.findViewById(R.id.btnModerationApprove)
        val reject: MaterialButton = view.findViewById(R.id.btnModerationReject)
    }

    fun submit(list: List<ClassifiedAd>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = list.size
            override fun areItemsTheSame(o: Int, n: Int): Boolean = old[o].id == list[n].id
            override fun areContentsTheSame(o: Int, n: Int): Boolean = old[o] == list[n]
        })
        items = list
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_admin_moderation, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ad = items[position]
        val isJob = ad.listingType.isNotBlank()
        holder.kind.text = appContext.getString(
            if (isJob) R.string.yuz_admin_moderation_kind_job else R.string.yuz_admin_moderation_kind_ad
        )
        holder.title.text = when {
            ad.title.isNotBlank() -> ad.title
            ad.model.isNotBlank() -> ad.model
            else -> ad.description.take(60)
        }
        holder.meta.text = buildMeta(ad, isJob)

        holder.status.text = appContext.getString(AdModeration.labelRes(ad.status))
        holder.status.setTextColor(ContextCompat.getColor(appContext, AdModeration.colorRes(ad.status)))

        val image = ad.primaryImage()
        if (image.isBlank()) {
            Glide.with(appContext).clear(holder.thumb)
        } else {
            Glide.with(appContext)
                .load(image)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(holder.thumb)
        }

        val approved = AdModeration.normalize(ad.status) == AdModeration.APPROVED
        val rejected = AdModeration.normalize(ad.status) == AdModeration.REJECTED
        holder.approve.visibility = if (approved) View.GONE else View.VISIBLE
        holder.reject.visibility = if (rejected) View.GONE else View.VISIBLE
        holder.approve.setOnClickListener { onApprove(ad) }
        holder.reject.setOnClickListener { onReject(ad) }
    }

    private fun buildMeta(ad: ClassifiedAd, isJob: Boolean): String {
        val parts = ArrayList<String>()
        if (isJob) {
            if (ad.price > 0L) parts.add(ad.formattedPrice())
        } else if (ad.price > 0L) {
            parts.add(ad.formattedPrice())
        }
        val owner = ad.ownerName.trim()
        if (owner.isNotBlank()) parts.add(owner)
        val place = ad.region.trim().ifBlank { ad.address.trim() }
        if (place.isNotBlank()) parts.add(place)
        if (ad.createdAt > 0L) parts.add(dateFormat.format(Date(ad.createdAt)))
        return parts.joinToString(" · ")
    }
}
