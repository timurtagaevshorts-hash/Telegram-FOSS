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

object ClassifiedAdCardBinder {

    fun bind(
        ad: ClassifiedAd,
        cardRoot: MaterialCardView,
        tvCategory: TextView,
        tvModel: TextView,
        tvTitle: TextView,
        tvPrice: TextView,
        tvAddress: TextView,
        addressRow: View,
        ivAddressIcon: ImageView,
        ivAdImage: ImageView,
        cardAdImage: View,
        tvImageCount: TextView,
        onImageClick: (() -> Unit)? = null,
        isOwn: Boolean = false
    ) {
        // Kartada kategoriya ko'rsatilmaydi: badge joyi narx va model uchun kerak,
        // tur filter chiplarida allaqachon tanlangan.
        tvCategory.visibility = View.GONE

        if (ad.model.isNotBlank()) {
            tvModel.visibility = View.VISIBLE
            tvModel.text = ad.model
        } else {
            tvModel.visibility = View.GONE
        }

        tvTitle.text = ad.title.ifBlank { ad.description.take(80) }
        tvPrice.text = ad.formattedPrice()

        ClassifiedAdCardTheme.applyToCard(
            ad = ad,
            card = cardRoot,
            tvTitle = tvTitle,
            tvModel = tvModel,
            tvPrice = tvPrice,
            tvAddress = tvAddress,
            tvCategory = tvCategory,
            addressRow = addressRow,
            addressIcon = ivAddressIcon,
            imageCard = null
        )

        if (isOwn) {
            cardRoot.strokeWidth = (2f * cardRoot.resources.displayMetrics.density).toInt()
        } else {
            cardRoot.strokeWidth = (1f * cardRoot.resources.displayMetrics.density).toInt()
        }

        val images = ad.imageUrls.filter { it.isNotBlank() }
        val primary = images.firstOrNull().orEmpty()
        if (primary.isNotBlank()) {
            ivAdImage.visibility = View.VISIBLE
            Glide.with(ivAdImage)
                .load(primary)
                .transform(CenterCrop())
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.color.yuz_surface)
                .error(R.color.yuz_surface)
                .into(ivAdImage)
            if (images.size > 1) {
                tvImageCount.visibility = View.VISIBLE
                tvImageCount.text = images.size.toString()
            } else {
                tvImageCount.visibility = View.GONE
            }
            val click = View.OnClickListener { onImageClick?.invoke() }
            ivAdImage.isClickable = onImageClick != null
            cardAdImage.isClickable = onImageClick != null
            ivAdImage.setOnClickListener(click)
            cardAdImage.setOnClickListener(click)
        } else {
            Glide.with(ivAdImage).clear(ivAdImage)
            ivAdImage.setImageDrawable(null)
            ivAdImage.setBackgroundColor(
                ClassifiedAdCardTheme.styleFor(ad.cardTheme, cardRoot.context).cardBackground
            )
            tvImageCount.visibility = View.GONE
            ivAdImage.setOnClickListener(null)
            cardAdImage.setOnClickListener(null)
        }
    }

    fun bindList(
        ad: ClassifiedAd,
        ivAdImage: ImageView,
        tvAdBadge: TextView,
        tvAdTitle: TextView,
        tvAdSubtitle: TextView,
        tvAdPrice: TextView,
        tvAdAuthor: TextView
    ) {
        tvAdTitle.text = ad.title.ifBlank { ad.description.take(50) }
        tvAdSubtitle.text = ad.description.take(80)
        tvAdPrice.text = ad.formattedPrice()
        tvAdAuthor.text = ad.ownerName.ifBlank { "Top4 Foydalanuvchi" }

        val images = ad.imageUrls.filter { it.isNotBlank() }
        val primary = images.firstOrNull().orEmpty()

        if (primary.isNotBlank()) {
            Glide.with(ivAdImage)
                .load(primary)
                .transform(CenterCrop())
                .transition(DrawableTransitionOptions.withCrossFade())
                .placeholder(R.drawable.bg_shimmer_placeholder)
                .into(ivAdImage)
        } else {
            ivAdImage.setImageResource(R.drawable.ic_logo)
        }

        val isNew = System.currentTimeMillis() - ad.createdAt < 24 * 60 * 60 * 1000L
        tvAdBadge.visibility = if (isNew) View.VISIBLE else View.GONE
    }
}
