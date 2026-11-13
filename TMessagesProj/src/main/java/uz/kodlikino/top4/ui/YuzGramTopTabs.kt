package uz.kodlikino.top4.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R

/**
 * YuzGram r61: chats sahifasidagi filtrlar uchun YUQORI tab qatori
 * (Telegram uslubi). Faol chip to'q pushti gradientda oq yozuv bilan,
 * qolganlari qora fonda shisha chip — qidiruv pill'i bilan bir tilda.
 */
class YuzGramTopTabs(context: Context) : HorizontalScrollView(context) {

    companion object {
        private const val HEIGHT_DP = 46f
        private const val CHIP_H_DP = 34f
        private const val CHIP_R_DP = 17f

        @JvmStatic
        fun height(): Int = AndroidUtilities.dp(HEIGHT_DP)

        private fun dp(value: Float): Int = AndroidUtilities.dp(value)

        private fun dpf(value: Float): Float = AndroidUtilities.dp(value).toFloat()
    }

    interface Listener {
        fun onSelected(index: Int)
    }

    interface ReselectListener {
        fun onReselected()
    }

    interface CounterProvider {
        fun count(filter: MessagesController.DialogFilter): Int
    }

    var listener: Listener? = null
    var reselectListener: ReselectListener? = null

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    private val chips = ArrayList<Chip>()
    private var selected = 0

    private val accent = ContextCompat.getColor(context, R.color.yuz_action)

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(dp(12f), 0, dp(50f), 0)
        addView(row)
        visibility = View.GONE
        isClickable = false
    }

    fun bind(
        filters: List<MessagesController.DialogFilter>,
        selectedIndex: Int,
        counterProvider: CounterProvider,
        animated: Boolean
    ) {
        val rebuild = chips.size != filters.size ||
            filters.indices.any { chips[it].title.text.toString() != label(filters[it]) }
        if (rebuild) {
            row.removeAllViews()
            chips.clear()
            filters.forEachIndexed { index, filter ->
                val chip = Chip(context, label(filter))
                chip.setOnClickListener {
                    if (selected == index) reselectListener?.onReselected()
                    else listener?.onSelected(index)
                }
                chips.add(chip)
                row.addView(chip)
            }
        }
        filters.forEachIndexed { index, filter ->
            val chip = chips[index]
            chip.title.text = label(filter)
            chip.setCounter(counterProvider.count(filter))
        }
        setSelected(selectedIndex, animated && !rebuild)
        if (rebuild && animated) animateEnter()
    }

    fun setSelected(index: Int, animated: Boolean) {
        if (index < 0 || index >= chips.size) return
        selected = index
        chips.forEachIndexed { i, chip -> chip.setActive(i == index, animated) }
        val chip = chips[index]
        post {
            if (width == 0) return@post
            val left = chip.left - dp(12f)
            val right = chip.right - width + paddingRight
            when {
                left < scrollX -> smoothScrollTo(left.coerceAtLeast(0), 0)
                chip.right > scrollX + width - paddingRight ->
                    smoothScrollTo(right.coerceAtLeast(0), 0)
            }
        }
    }

    private fun animateEnter() {
        chips.forEachIndexed { index, chip ->
            chip.alpha = 0f
            chip.scaleX = 0.94f
            chip.scaleY = 0.94f
            chip.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay((index * 18).toLong())
                .setDuration(200)
                .setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT)
                .start()
        }
    }

    private fun label(filter: MessagesController.DialogFilter): String {
        return if (filter.isDefault) {
            LocaleController.getString("FilterAllChats", R.string.FilterAllChats)
        } else {
            filter.name ?: ""
        }
    }

    private inner class Chip(context: Context, titleText: String) :
        LinearLayout(context) {

        val title = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            includeFontPadding = false
            maxLines = 1
            text = titleText
        }
        private val badge = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        private var active = false
        private var count = 0

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(13f), 0, dp(13f), 0)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(CHIP_H_DP)).apply {
                rightMargin = dp(6f)
            }
            addView(title)
            addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, dp(18f)).apply {
                leftMargin = dp(6f)
                gravity = Gravity.CENTER
            })
            setActive(false, false)
        }

        fun setCounter(value: Int) {
            count = value
            applyBadge()
        }

        /** Badge ranglari chip holatiga bog'liq — faol chip almashganda ham yangilanadi. */
        private fun applyBadge() {
            if (count <= 0) {
                badge.visibility = View.GONE
                return
            }
            badge.visibility = View.VISIBLE
            badge.text = if (count > 999) "999+" else count.toString()
            badge.setPadding(dp(5f), 0, dp(5f), 0)
            badge.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpf(9f)
                setColor(if (active) 0xFF6E0E33.toInt() else 0x33C2185B)
            }
            badge.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFFE38CA9.toInt())
        }

        fun setActive(value: Boolean, animate: Boolean) {
            if (value == active && background != null) {
                applyColors(value)
                return
            }
            active = value
            applyColors(value)
            if (animate) {
                animate().cancel()
                animate().scaleX(1.06f).scaleY(1.06f).setDuration(90)
                    .setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.DEFAULT)
                    .withEndAction {
                        animate().scaleX(1f).scaleY(1f).setDuration(120)
                            .setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT)
                            .start()
                    }
                    .start()
            }
        }

        private fun applyColors(value: Boolean) {
            // r61: ilova foni doim qora — chip ham doim qorong'i shishada.
            background = if (value) {
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                    accent, 0xFF8E0F45.toInt()
                )).apply { cornerRadius = dpf(CHIP_R_DP) }
            } else {
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                    0xCC3A1220.toInt(), 0xCC24080F.toInt()
                )).apply {
                    cornerRadius = dpf(CHIP_R_DP)
                    setStroke(dp(1f), 0x4DCC4E7F)
                }
            }
            title.setTextColor(
                if (value) 0xFFFFFFFF.toInt() else 0xFFD3B7C3.toInt()
            )
            title.typeface = if (value) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            applyBadge()
        }
    }
}
