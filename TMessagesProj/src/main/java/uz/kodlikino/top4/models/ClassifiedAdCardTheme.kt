package uz.kodlikino.top4.models

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import org.telegram.messenger.R

object ClassifiedAdCardThemes {
    const val BLACK = "black"

    fun normalize(raw: String, isPremiumUser: Boolean): String {
        return BLACK
    }
}

data class ClassifiedCardStyle(
    val cardBackground: Int,
    val titleColor: Int,
    val secondaryColor: Int,
    val priceColor: Int,
    val strokeColor: Int,
    val categoryBgRes: Int,
    val categoryTextColor: Int,
    val priceBadgeBgRes: Int,
    val addressIconTint: Int,
    val imageStrokeColor: Int,
    val premiumAccent: Boolean
)

object ClassifiedAdCardTheme {

    fun styleFor(themeRaw: String, context: Context, isPremiumUser: Boolean = false): ClassifiedCardStyle {
        // Neon palitra (yuz_classified_colors.xml) bilan bir xil: kartalar ham to'q
        // ko'k-keymish sirt ustida, brend ko'ki va oltin narx bilan chiqadi — shunda
        // Elonlar feed'i chat/pastki bar bilan bitta uslubda ko'rinadi.
        return ClassifiedCardStyle(
            cardBackground = Color.parseColor("#0D121D"),
            titleColor = Color.parseColor("#FFFFFF"),
            secondaryColor = Color.parseColor("#95A1B4"),
            priceColor = Color.parseColor("#FFD84D"),
            strokeColor = Color.parseColor("#202B3C"),
            categoryBgRes = R.drawable.bg_classified_category_badge,
            categoryTextColor = Color.parseColor("#FFFFFF"),
            priceBadgeBgRes = R.drawable.bg_classified_price_badge,
            addressIconTint = Color.parseColor("#2AABEE"),
            imageStrokeColor = Color.parseColor("#202B3C"),
            premiumAccent = false
        )
    }

    fun applyToCard(
        ad: ClassifiedAd,
        card: MaterialCardView,
        tvTitle: TextView,
        tvPrice: TextView,
        tvCategory: TextView,
        tvAddress: TextView? = null,
        addressRow: View? = null,
        addressIcon: ImageView? = null,
        imageCard: MaterialCardView? = null,
        tvModel: TextView? = null
    ) {
        val style = styleFor(ad.cardTheme, card.context)
        card.setCardBackgroundColor(style.cardBackground)
        card.strokeColor = style.strokeColor
        card.strokeWidth = if (style.premiumAccent) dp(card, 1.5f) else dp(card, 1f)
        card.cardElevation = if (style.premiumAccent) 10f else 4f

        tvTitle.setTextColor(style.titleColor)
        tvPrice.setTextColor(style.priceColor)
        tvPrice.setBackgroundResource(style.priceBadgeBgRes)
        tvCategory.setBackgroundResource(style.categoryBgRes)
        tvCategory.setTextColor(style.categoryTextColor)
        tvModel?.setTextColor(if (style.premiumAccent) style.priceColor else style.addressIconTint)
        addressIcon?.setColorFilter(style.addressIconTint)

        if (tvAddress != null && addressRow != null) {
            tvAddress.setTextColor(style.secondaryColor)
            if (ad.address.isNotBlank()) {
                addressRow.visibility = View.VISIBLE
                tvAddress.text = ad.address
            } else {
                addressRow.visibility = View.GONE
            }
        }

        imageCard?.let {
            it.strokeColor = style.imageStrokeColor
            it.strokeWidth = dp(card, 1f)
        }
    }

    fun applyMyAdActions(
        style: ClassifiedCardStyle,
        btnEdit: com.google.android.material.button.MaterialButton,
        btnDelete: com.google.android.material.button.MaterialButton
    ) {
        val onDark = isDarkBackground(style.cardBackground)
        val editColor = if (onDark) style.titleColor else style.addressIconTint
        val deleteColor = Color.parseColor("#FF5252")
        btnEdit.setTextColor(editColor)
        btnEdit.iconTint = android.content.res.ColorStateList.valueOf(editColor)
        btnEdit.strokeColor = android.content.res.ColorStateList.valueOf(
            if (onDark) Color.argb(120, Color.red(editColor), Color.green(editColor), Color.blue(editColor))
            else editColor
        )
        btnDelete.setTextColor(deleteColor)
        btnDelete.iconTint = android.content.res.ColorStateList.valueOf(deleteColor)
        btnDelete.strokeColor = android.content.res.ColorStateList.valueOf(
            Color.argb(if (onDark) 100 else 85, 255, 82, 82)
        )
    }

    private fun isDarkBackground(color: Int): Boolean {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return (0.299 * r + 0.587 * g + 0.114 * b) < 0.5
    }

    private fun dp(view: View, value: Float): Int =
        (value * view.resources.displayMetrics.density).toInt()
}
