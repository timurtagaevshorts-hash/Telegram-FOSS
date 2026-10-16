package uz.kodlikino.top4

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.card.MaterialCardView
import org.telegram.messenger.R
import uz.kodlikino.top4.models.ClassifiedAd
import uz.kodlikino.top4.models.ClassifiedAdCardTheme
import uz.kodlikino.top4.models.JobCard
import uz.kodlikino.top4.models.JobCardListingTypes

object JobCardCardBinder {

    fun bind(
        card: JobCard,
        cardRoot: MaterialCardView,
        tvType: TextView,
        tvTitle: TextView,
        tvSubtitle: TextView,
        tvSalary: TextView,
        tvLocation: TextView,
        locationRow: View,
        ivLocationIcon: ImageView,
        ivThumb: ImageView,
        thumbFrame: View,
        isOwn: Boolean
    ) {
        tvType.text = "${JobCardListingTypes.emoji(card.listingType)} ${JobCardListingTypes.label(card.listingType)}"
        tvTitle.text = card.title.ifBlank { card.description.take(60) }
        tvSubtitle.text = card.description.take(120).ifBlank { card.location }
        tvSubtitle.visibility = if (tvSubtitle.text.isNullOrBlank()) View.GONE else View.VISIBLE

        if (card.listingType == JobCardListingTypes.HIRING) {
            tvSalary.visibility = View.VISIBLE
            tvSalary.text = card.formattedSalary()
        } else {
            tvSalary.visibility = View.GONE
        }

        if (card.location.isNotBlank()) {
            locationRow.visibility = View.VISIBLE
            tvLocation.text = card.location
        } else {
            locationRow.visibility = View.GONE
        }

        val themeAd = ClassifiedAd(
            id = card.id,
            title = card.title,
            description = card.description,
            address = card.location,
            price = card.salaryAmount,
            cardTheme = card.cardTheme
        )
        ClassifiedAdCardTheme.applyToCard(
            ad = themeAd,
            card = cardRoot,
            tvTitle = tvTitle,
            tvPrice = tvSalary,
            tvAddress = tvLocation,
            tvCategory = tvType,
            addressRow = locationRow,
            addressIcon = ivLocationIcon,
            imageCard = null,
            tvModel = tvSubtitle
        )

        if (isOwn) {
            cardRoot.strokeWidth = (2f * cardRoot.resources.displayMetrics.density).toInt()
        }

        val url = card.displayImageUrl()
        thumbFrame.visibility = View.VISIBLE
        ivThumb.visibility = View.VISIBLE
        if (url.isNotBlank()) {
            Glide.with(ivThumb)
                .load(url)
                .transform(CenterCrop())
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.bg_classified_image_placeholder)
                .error(R.drawable.bg_classified_image_placeholder)
                .into(ivThumb)
        } else {
            ivThumb.setImageResource(R.drawable.bg_classified_image_placeholder)
        }
    }
}
