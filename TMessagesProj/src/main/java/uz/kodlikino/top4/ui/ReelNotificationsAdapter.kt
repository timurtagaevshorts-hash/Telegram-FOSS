package uz.kodlikino.top4.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.RequestOptions
import org.telegram.messenger.R
import uz.kodlikino.top4.models.ReelNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reels bildirishnomalari ro'yxati — donor `ProfileNotificationsAdapter`
 * varianti. Oddiy ArrayList + notifyDataSetChanged: Telegram vendrlangan
 * eski RecyclerView AAR'ida ListAdapter/DiffUtil ishlamaydi.
 */
class ReelNotificationsAdapter(
    private val appContext: Context,
    private val onClick: (ReelNotification) -> Unit,
    private val onDelete: (ReelNotification) -> Unit
) : RecyclerView.Adapter<ReelNotificationsAdapter.VH>() {

    private val items = ArrayList<ReelNotification>()

    fun submit(list: List<ReelNotification>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun snapshot(): List<ReelNotification> = ArrayList(items)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reel_notification, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        items.getOrNull(position)?.let(holder::bind)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvInitial: TextView = itemView.findViewById(R.id.tvActorInitial)
        private val ivPhoto: ImageView = itemView.findViewById(R.id.ivActorPhoto)
        private val ivReelThumb: ImageView = itemView.findViewById(R.id.ivReelThumb)
        private val ivType: ImageView = itemView.findViewById(R.id.ivNotifType)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvNotificationTitle)
        private val tvReel: TextView = itemView.findViewById(R.id.tvNotificationReel)
        private val tvBody: TextView = itemView.findViewById(R.id.tvNotificationBody)
        private val tvTime: TextView = itemView.findViewById(R.id.tvNotificationTime)
        private val unreadDot: View = itemView.findViewById(R.id.viewUnreadDot)
        private val btnDelete: View = itemView.findViewById(R.id.btnDeleteNotification)

        fun bind(item: ReelNotification) {
            val name = item.actorName.ifBlank { "Foydalanuvchi" }
            tvTitle.text = when (item.type) {
                ReelNotification.TYPE_REPLY ->
                    appContext.getString(R.string.yuz_notif_replied, name)
                ReelNotification.TYPE_COMMENT ->
                    appContext.getString(R.string.yuz_notif_commented, name)
                else -> appContext.getString(R.string.yuz_notif_liked, name)
            }
            val reelName = item.reelTitle.ifBlank {
                appContext.getString(R.string.yuz_notif_untitled_reel)
            }
            tvReel.text = appContext.getString(R.string.yuz_notif_reel_label, reelName)
            tvReel.visibility = if (item.reelTitle.isBlank() && item.reelId.isBlank()) View.GONE else View.VISIBLE

            val comment = item.commentText.trim()
            if (item.type != ReelNotification.TYPE_LIKE && comment.isNotBlank()) {
                tvBody.visibility = View.VISIBLE
                tvBody.text = "\u201C$comment\u201D"
            } else {
                tvBody.visibility = View.GONE
            }
            tvTime.text = TIME_FMT.format(Date(item.createdAt))
            unreadDot.visibility = if (item.read) View.INVISIBLE else View.VISIBLE
            ivType.setImageResource(
                if (item.type == ReelNotification.TYPE_LIKE) R.drawable.ic_yuz_heart
                else R.drawable.ic_yuz_comment
            )

            tvInitial.text = name.take(1).uppercase(Locale.getDefault())
            if (item.actorPhotoUrl.isNotBlank()) {
                ivPhoto.visibility = View.VISIBLE
                Glide.with(ivPhoto)
                    .load(item.actorPhotoUrl)
                    .apply(RequestOptions.circleCropTransform())
                    .into(ivPhoto)
            } else {
                ivPhoto.visibility = View.GONE
                ivPhoto.setImageDrawable(null)
            }

            if (item.reelThumbUrl.isNotBlank()) {
                Glide.with(ivReelThumb)
                    .load(item.reelThumbUrl)
                    .centerCrop()
                    .into(ivReelThumb)
            } else {
                ivReelThumb.setImageDrawable(null)
            }

            itemView.setOnClickListener { onClick(item) }
            btnDelete.setOnClickListener { onDelete(item) }
        }
    }

    companion object {
        private val TIME_FMT = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    }
}
