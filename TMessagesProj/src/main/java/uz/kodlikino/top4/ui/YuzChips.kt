package uz.kodlikino.top4.ui

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.telegram.messenger.R

/**
 * Ilova bo'ylab yagona "pill"/chip uslubı. Avvallari Elonlar, AI, Musiqa va Admin
 * ekranlarida uch marta qayta yozilgan edi (har birida radius, rang, balandlik
 * biroz boshqacha) — endi hammasi shu yerdan o'tadi.
 *
 * Holat ranglari `res/color/yuz_chip_*.xml` selektorlarida yashaydi: bitta joyni
 * o'zgartirish butun ilovaga yoyiladi.
 */
object YuzChips {

    data class Item(val id: String, val label: String, val iconRes: Int = 0)

    /**
     * @param context Material tema ostidagi kontekst (Chip MaterialComponents
     *                talab qiladi) — chaqiruvchi `themedContext` beradi.
     */
    fun create(context: Context, label: String, checked: Boolean, iconRes: Int = 0): Chip {
        val d = context.resources.displayMetrics.density
        return Chip(context).apply {
            isCheckable = true
            isChecked = checked
            text = label
            textSize = 13f
            setTypeface(typeface, if (checked) Typeface.BOLD else Typeface.NORMAL)
            isSingleLine = true
            chipStartPadding = 12f * d
            chipEndPadding = 12f * d
            textStartPadding = 6f * d
            textEndPadding = 6f * d
            chipMinHeight = 34f * d
            chipCornerRadius = 10f * d
            checkedIcon = null
            closeIcon = null
            setEnsureMinTouchTargetSize(false)
            chipBackgroundColor =
                ContextCompat.getColorStateList(context, R.color.yuz_chip_bg)
            setTextColor(
                ContextCompat.getColorStateList(context, R.color.yuz_chip_fg)
            )
            chipStrokeColor =
                ContextCompat.getColorStateList(context, R.color.yuz_chip_stroke)
            chipStrokeWidth = 1f * d
            if (iconRes != 0) {
                chipIcon = ContextCompat.getDrawable(context, iconRes)
                chipIconSize = 18f * d
                chipIconTint =
                    ContextCompat.getColorStateList(context, R.color.yuz_chip_fg)
                setChipIconVisible(true)
            } else {
                setChipIconVisible(false)
            }
        }
    }

    /** Bir qator tanlov chipi: `onSelect(index)` tanlangan element indeksini oladi. */
    fun fill(
        context: Context,
        group: ChipGroup?,
        items: List<Item>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        val g = group ?: return
        g.removeAllViews()
        items.forEachIndexed { index, item ->
            val chip = create(context, item.label, index == selectedIndex, item.iconRes)
            chip.setOnClickListener { onSelect(index) }
            g.addView(chip)
        }
    }

    /**
     * Material talab qilmaydigan versiya: Telegram'ning o'z konteynerlarida
     * (reels, mini-pleyer) ishlatiladigan oddiy bosiladigan matn.
     */
    fun pillText(context: Context, label: String, checked: Boolean): android.widget.TextView {
        val d = context.resources.displayMetrics.density
        return android.widget.TextView(context).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, if (checked) Typeface.BOLD else Typeface.NORMAL)
            setPadding((12f * d).toInt(), (7f * d).toInt(), (12f * d).toInt(), (7f * d).toInt())
            setBackgroundResource(
                if (checked) R.drawable.bg_yuz_pill_active else R.drawable.bg_yuz_pill
            )
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (checked) R.color.yuz_text_on_accent else R.color.yuz_text_secondary
                )
            )
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8f * d).toInt() }
            isSingleLine = true
        }
    }
}
