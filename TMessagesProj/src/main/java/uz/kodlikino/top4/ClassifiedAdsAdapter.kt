package uz.kodlikino.top4

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.firebase.auth.FirebaseAuth
import org.telegram.messenger.R
import uz.kodlikino.top4.models.ClassifiedAd

class ClassifiedAdsAdapter(
    private val onAdClick: (ClassifiedAd) -> Unit,
    private val onEditClick: ((ClassifiedAd) -> Unit)? = null,
    private val onDeleteClick: ((ClassifiedAd) -> Unit)? = null
) : RecyclerView.Adapter<ClassifiedAdsAdapter.AdViewHolder>() {

    private val items = mutableListOf<ClassifiedAd>()
    private var myUid: String = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    fun setMyUid(uid: String) {
        myUid = uid.trim()
    }

    fun submit(list: List<ClassifiedAd>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AdViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_classified_ad_list, parent, false)
        return AdViewHolder(view)
    }

    override fun onBindViewHolder(holder: AdViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class AdViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: MaterialCardView = itemView.findViewById(R.id.adItemRoot)
        private val ivAdImage: ImageView = itemView.findViewById(R.id.ivAdImage)
        private val tvAdBadge: TextView = itemView.findViewById(R.id.tvAdBadge)
        private val tvAdTitle: TextView = itemView.findViewById(R.id.tvAdTitle)
        private val tvAdSubtitle: TextView = itemView.findViewById(R.id.tvAdSubtitle)
        private val tvAdPrice: TextView = itemView.findViewById(R.id.tvAdPrice)
        private val tvAdAuthor: TextView = itemView.findViewById(R.id.tvAdAuthor)

        fun bind(ad: ClassifiedAd) {
            ClassifiedAdCardBinder.bindList(
                ad = ad,
                ivAdImage = ivAdImage,
                tvAdBadge = tvAdBadge,
                tvAdTitle = tvAdTitle,
                tvAdSubtitle = tvAdSubtitle,
                tvAdPrice = tvAdPrice,
                tvAdAuthor = tvAdAuthor
            )
            cardRoot.setOnClickListener { onAdClick(ad) }
        }
    }
}
