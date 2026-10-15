package uz.kodlikino.top4

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import org.telegram.messenger.R

/**
 * Elon detail'idagi rasmlarni g'alvir (swipe) ko'rsatuvchi ViewPager2 adapteri.
 * KinoTOP4'dan ko'chirildi; faqat R manbasi org.telegram.messenger.R ga o'zgartirildi.
 */
class ClassifiedAdDetailImageAdapter(
    private val onImageClick: ((List<String>, Int) -> Unit)? = null
) : RecyclerView.Adapter<ClassifiedAdDetailImageAdapter.ImageViewHolder>() {

    private val urls = mutableListOf<String>()

    fun submit(list: List<String>) {
        urls.clear()
        urls.addAll(list.filter { it.isNotBlank() })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_classified_ad_detail_image, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(urls[position], position)
    }

    override fun getItemCount(): Int = urls.size

    inner class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivImage: ImageView = itemView.findViewById(R.id.ivDetailImage)

        fun bind(url: String, index: Int) {
            Glide.with(ivImage)
                .load(url)
                .fitCenter()
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.color.home_card)
                .error(R.color.home_card)
                .into(ivImage)

            itemView.setOnClickListener {
                onImageClick?.invoke(urls, index)
            }
        }
    }
}
