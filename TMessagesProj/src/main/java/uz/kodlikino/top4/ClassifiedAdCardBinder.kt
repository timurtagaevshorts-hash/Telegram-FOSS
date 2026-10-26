package uz.kodlikino.top4

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.card.MaterialCardView
import org.telegram.messenger.R
import uz.kodlikino.top4.models.AdModeration
import uz.kodlikino.top4.models.ClassifiedAd
import uz.kodlikino.top4.models.ClassifiedAdCardTheme
import uz.kodlikino.top4.models.ClassifiedAdCategories

object ClassifiedAdCardBinder {

    /** "Yangi" nishoni faqat birinchi sutkadagi e'lonlarga. */
    private const val NEW_BADGE_WINDOW_MS = 24L * 60L * 60L * 1000L

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
        tvAdOwn: TextView,
        tvAdImageCount: TextView,
        tvAdMeta: TextView,
        tvAdTitle: TextView,
        tvAdLocation: TextView,
        locationRow: View,
        tvAdPrice: TextView,
        tvAdAuthor: TextView,
        isOwn: Boolean,
        sizePx: Int
    ) {
        val ctx = ivAdImage.context
        tvAdTitle.text = ad.title.ifBlank { ad.description.take(80) }

        val meta = ad.model.ifBlank {
            if (ad.category.isNotBlank() && ad.category != ClassifiedAdCategories.OTHER) {
                ClassifiedAdCategories.label(ad.category)
            } else ""
        }
        tvAdMeta.text = meta
        tvAdMeta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

        val location = ad.region.ifBlank { ad.address }
        if (location.isBlank()) {
            locationRow.visibility = View.GONE
        } else {
            locationRow.visibility = View.VISIBLE
            tvAdLocation.text = location
        }

        tvAdPrice.text = ad.formattedPrice()
        tvAdAuthor.text = ad.ownerName.ifBlank { "Top4 Foydalanuvchi" }

        if (isOwn) {
            // O'z e'lonida "Sizning" o'rniga moderatsiya holati ko'rinadi:
            // foydalanuvchi e'loni tekshiruvda yoki rad etilganini darhol biladi.
            tvAdOwn.visibility = View.VISIBLE
            tvAdOwn.text = ctx.getString(AdModeration.labelRes(ad.status))
            tvAdOwn.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    ctx, AdModeration.colorRes(ad.status)
                )
            )
            tvAdBadge.visibility = View.GONE
        } else {
            tvAdOwn.visibility = View.GONE
            val isNew = System.currentTimeMillis() - ad.createdAt < NEW_BADGE_WINDOW_MS
            if (isNew) {
                tvAdBadge.text = ctx.getString(R.string.classified_badge_new)
                tvAdBadge.visibility = View.VISIBLE
            } else {
                tvAdBadge.visibility = View.GONE
            }
        }

        val images = ad.imageUrls.filter { it.isNotBlank() }
        if (images.size > 1) {
            tvAdImageCount.text =
                ctx.getString(R.string.classified_images_count_badge, images.size - 1)
            tvAdImageCount.visibility = View.VISIBLE
        } else {
            tvAdImageCount.visibility = View.GONE
        }

        val primary = images.firstOrNull().orEmpty()
        if (primary.isNotBlank()) {
            ivAdImage.clearColorFilter()
            ivAdImage.scaleType = ImageView.ScaleType.CENTER_CROP
            ivAdImage.setPadding(0, 0, 0, 0)
            val request = Glide.with(ivAdImage)
                .load(primary)
                .transform(CenterCrop())
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .priority(Priority.HIGH)
                .transition(DrawableTransitionOptions.withCrossFade(120))
                .placeholder(R.drawable.bg_classified_image_placeholder)
                .error(R.drawable.bg_classified_image_placeholder)
            if (sizePx > 0) request.override(sizePx)
            request.into(ivAdImage)
        } else {
            Glide.with(ivAdImage).clear(ivAdImage)
            val pad = (28 * ctx.resources.displayMetrics.density).toInt()
            ivAdImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            ivAdImage.setPadding(pad, pad, pad, pad)
            ivAdImage.setColorFilter(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.yuz_text_muted)
            )
            ivAdImage.setImageResource(ClassifiedAdCategories.icon(ad.category))
        }
    }
}
