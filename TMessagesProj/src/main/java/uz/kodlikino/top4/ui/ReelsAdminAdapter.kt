package uz.kodlikino.top4.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.telegram.messenger.R
import uz.kodlikino.top4.models.Reel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Admin "Reels" tab qatori — har bir joylangan reels: sarlavha, ko'rish/layk/izoh
 * soni, sana va yuklovchi; o'ngda o'chirish tugmasi (isAdminUser() rule orqali).
 */
class ReelsAdminAdapter(
    private val appContext: Context,
    private var items: List<Reel>,
    private val onDelete: (Reel) -> Unit
) : RecyclerView.Adapter<ReelsAdminAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvAdminReelTitle)
        val meta: TextView = view.findViewById(R.id.tvAdminReelMeta)
        val delete: ImageButton = view.findViewById(R.id.btnAdminReelDelete)
    }

    fun submit(list: List<Reel>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_admin_reel, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.title.text = r.title.ifBlank { appContext.getString(R.string.yuz_admin_reel_untitled) }
        val parts = ArrayList<String>()
        parts.add("${fmt(r.views)} ${appContext.getString(R.string.yuz_stat_short_views)}")
        parts.add("${fmt(r.likeCount)} ${appContext.getString(R.string.yuz_stat_short_likes)}")
        parts.add("${fmt(r.commentCount)} ${appContext.getString(R.string.yuz_stat_short_comments)}")
        if (r.createdAt > 0L) parts.add(dateFormat.format(Date(r.createdAt)))
        val uploader = r.uploadedByName.trim()
        if (uploader.isNotBlank()) parts.add(uploader)
        holder.meta.text = parts.joinToString(" · ")
        holder.delete.setOnClickListener { onDelete(r) }
    }

    private fun fmt(n: Int): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }
}
