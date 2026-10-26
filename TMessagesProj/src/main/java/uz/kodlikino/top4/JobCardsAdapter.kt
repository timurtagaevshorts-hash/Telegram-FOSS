package uz.kodlikino.top4

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import org.telegram.messenger.R
import uz.kodlikino.top4.models.JobCard

class JobCardsAdapter(
    private val onCardClick: (JobCard) -> Unit
) : RecyclerView.Adapter<JobCardsAdapter.VH>() {

    private val items = mutableListOf<JobCard>()
    private var myUid: String = ""

    fun setMyUid(uid: String) {
        myUid = uid.trim()
    }

    fun submit(list: List<JobCard>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size

            override fun getNewListSize(): Int = list.size

            override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                items[oldPosition].id == list[newPosition].id

            override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                items[oldPosition] == list[newPosition]
        })
        items.clear()
        items.addAll(list)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_job_card, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardRoot: MaterialCardView = itemView.findViewById(R.id.jobCardRoot)
        private val tvType: TextView = itemView.findViewById(R.id.tvJobType)
        private val tvOwn: TextView = itemView.findViewById(R.id.tvJobOwn)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvJobTitle)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvJobSubtitle)
        private val tvSalary: TextView = itemView.findViewById(R.id.tvJobSalary)
        private val tvLocation: TextView = itemView.findViewById(R.id.tvJobLocation)
        private val locationRow: View = itemView.findViewById(R.id.jobLocationRow)
        private val ivLocation: ImageView = itemView.findViewById(R.id.ivJobLocationIcon)
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivJobThumb)
        private val thumbFrame: View = itemView.findViewById(R.id.jobThumbFrame)

        fun bind(card: JobCard) {
            val isOwn = card.ownerUid.isNotBlank() && card.ownerUid == myUid
            JobCardCardBinder.bind(
                card = card,
                cardRoot = cardRoot,
                tvType = tvType,
                tvOwn = tvOwn,
                tvTitle = tvTitle,
                tvSubtitle = tvSubtitle,
                tvSalary = tvSalary,
                tvLocation = tvLocation,
                locationRow = locationRow,
                ivLocationIcon = ivLocation,
                ivThumb = ivThumb,
                thumbFrame = thumbFrame,
                isOwn = isOwn
            )
            cardRoot.setOnClickListener { onCardClick(card) }
        }
    }
}
