package uz.kodlikino.top4.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.telegram.messenger.R
import uz.kodlikino.top4.models.AdBanner
import uz.kodlikino.top4.utils.YuzAdStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Admin "Reklama" tab qatori — rasm (bo'lmasa rang uslubi), sarlavha,
 * joylashuvlar, faollik holati va o'chirish tugmasi. Qatorga bosish — tahrirlash.
 */
class AdsAdminAdapter(
    private val appContext: Context,
    private var items: List<AdBanner>,
    private val onEdit: (AdBanner) -> Unit,
    private val onDelete: (AdBanner) -> Unit
) : RecyclerView.Adapter<AdsAdminAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.ivAdminAdThumb)
        val title: TextView = view.findViewById(R.id.tvAdminAdTitle)
        val meta: TextView = view.findViewById(R.id.tvAdminAdMeta)
        val status: TextView = view.findViewById(R.id.tvAdminAdStatus)
        val delete: ImageButton = view.findViewById(R.id.btnAdminAdDelete)
    }

    fun submit(list: List<AdBanner>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_admin_ad, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ad = items[position]
        val theme = YuzAdStore.theme(ad.theme)

        holder.title.text = ad.title.ifBlank { appContext.getString(R.string.yuz_admin_ad_untitled) }

        val parts = ArrayList<String>()
        if (ad.showInChats) parts.add(appContext.getString(R.string.yuz_admin_ad_place_chats))
        if (ad.showInFunctions) parts.add(appContext.getString(R.string.yuz_admin_ad_place_functions))
        if (parts.isEmpty()) parts.add(appContext.getString(R.string.yuz_admin_ad_place_none))
        if (ad.createdAt > 0L) parts.add(dateFormat.format(Date(ad.createdAt)))
        holder.meta.text = parts.joinToString(" · ")

        holder.status.text = appContext.getString(
            if (ad.isActive) R.string.yuz_admin_ad_active else R.string.yuz_admin_ad_inactive
        )
        holder.status.setTextColor(
            ContextCompat.getColor(
                appContext,
                if (ad.isActive) R.color.yuz_action else R.color.yuz_text_muted
            )
        )

        holder.thumb.setBackgroundColor(theme.start)
        if (ad.imageUrl.isNotBlank()) {
            Glide.with(appContext)
                .load(ad.imageUrl)
                .centerCrop()
                .into(holder.thumb)
        } else {
            Glide.with(appContext).clear(holder.thumb)
            holder.thumb.setImageDrawable(null)
        }

        holder.itemView.setOnClickListener { onEdit(ad) }
        holder.delete.setOnClickListener { onDelete(ad) }
    }
}
