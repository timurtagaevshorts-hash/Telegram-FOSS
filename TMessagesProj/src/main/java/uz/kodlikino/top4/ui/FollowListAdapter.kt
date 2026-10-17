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
import com.bumptech.glide.request.RequestOptions
import org.telegram.messenger.R
import uz.kodlikino.top4.models.FollowUserItem
import java.util.Locale

/**
 * Obunachilar / obunalar ro'yxati adapter'i ([FollowListActivity] uchun).
 * Har element — avatar + nom; bosilganda [onOpen] orqali o'sha foydalanuvchining
 * YuzGram profili ochiladi.
 */
class FollowListAdapter(
    private val appContext: Context,
    private val onOpen: (FollowUserItem) -> Unit
) : RecyclerView.Adapter<FollowListAdapter.Row>() {

    private val items = ArrayList<FollowUserItem>()

    fun submit(list: List<FollowUserItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
        val view = LayoutInflater.from(appContext)
            .inflate(R.layout.item_follow, parent, false)
        return Row(view)
    }

    override fun onBindViewHolder(holder: Row, position: Int) {
        items.getOrNull(position)?.let(holder::bind)
    }

    inner class Row(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val initial: TextView = itemView.findViewById(R.id.tvFollowAvatarInitial)
        private val avatar: ImageView = itemView.findViewById(R.id.ivFollowAvatar)
        private val name: TextView = itemView.findViewById(R.id.tvFollowName)

        fun bind(item: FollowUserItem) {
            val label = item.name.ifBlank { "YuzGram" }
            name.text = label
            initial.text = label.take(1).uppercase(Locale.getDefault())
            if (item.photoUrl.isNotBlank()) {
                avatar.visibility = View.VISIBLE
                Glide.with(avatar)
                    .load(item.photoUrl)
                    .apply(RequestOptions.circleCropTransform())
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .error(R.drawable.bg_classified_image_placeholder)
                    .into(avatar)
            } else {
                avatar.visibility = View.GONE
                avatar.setImageDrawable(null)
            }
            itemView.setOnClickListener { onOpen(item) }
        }
    }
}
