package uz.kodlikino.top4

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import org.telegram.messenger.R

/**
 * Create/Tahrirlash ekranidagi tanlangan rasmlarning kichik ko'rinishlari (thumb).
 * KinoTOP4'dan ko'chirildi; R manbasi `org.telegram.messenger.R` ga o'zgartirildi.
 */
class ClassifiedAdImageThumbAdapter(
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<ClassifiedAdImageThumbAdapter.ThumbViewHolder>() {

    sealed class ImageItem {
        data class Remote(val url: String) : ImageItem()
        data class Local(val uri: Uri) : ImageItem()
    }

    private val items = mutableListOf<ImageItem>()

    fun submit(list: List<ImageItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_classified_ad_image_thumb, parent, false)
        return ThumbViewHolder(view)
    }

    override fun onBindViewHolder(holder: ThumbViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class ThumbViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivImage: ImageView = itemView.findViewById(R.id.ivImage)
        private val btnRemove: ImageButton = itemView.findViewById(R.id.btnRemove)

        fun bind(item: ImageItem, position: Int) {
            val cornerPx = (12 * itemView.resources.displayMetrics.density).toInt()
            val options = RequestOptions()
                .transform(CenterCrop(), RoundedCorners(cornerPx))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.drawable.bg_classified_image_placeholder)
                .error(R.drawable.bg_classified_image_placeholder)
            when (item) {
                is ImageItem.Remote -> Glide.with(ivImage).load(item.url).apply(options).into(ivImage)
                is ImageItem.Local -> Glide.with(ivImage).load(item.uri).apply(options).into(ivImage)
            }
            btnRemove.setOnClickListener { onRemove(position) }
        }
    }
}
