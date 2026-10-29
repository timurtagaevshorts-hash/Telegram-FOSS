package uz.kodlikino.top4.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import uz.kodlikino.top4.games.GamesCore

/**
 * Doska ustida 2 soniya ko‘rinadigan reaksiya kartochkasi.
 *
 * Chaqiruvchi faqat [show] ni chaqiradi — ko‘rinish, animatsiya va yashirish
 * vaqti shu yerda boshqariladi, shuning uchun shashka va mafiya bir xil
 * hilpillaydi.
 */
class GameReactionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val emojiView: TextView
    private val nameView: TextView
    private var token = 0L

    private val hide = Runnable { fadeOut(token) }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.yuz_surface_raised))
            cornerRadius = dp(22f).toFloat()
            setStroke(
                dp(1.4f),
                ColorUtils.setAlphaComponent(
                    ContextCompat.getColor(context, R.color.yuz_action),
                    90
                )
            )
        }
        elevation = dp(14f).toFloat()
        setPadding(dp(22f), dp(14f), dp(22f), dp(14f))
        visibility = View.INVISIBLE
        isClickable = false
        isFocusable = false

        emojiView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        addView(emojiView)

        nameView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(ContextCompat.getColor(context, R.color.yuz_text_bright))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        addView(
            nameView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            }
        )
    }

    /** Reaksiyani ko‘rsatadi: 220 ms kirish, ~1,75 s ushlash, 250 ms chiqish. */
    fun show(emoji: String, owner: String) {
        if (emoji.isBlank()) return
        token++
        emojiView.text = emoji
        val name = owner.trim()
        nameView.text = name
        nameView.visibility = if (name.isEmpty()) View.GONE else View.VISIBLE

        animate().cancel()
        removeCallbacks(hide)
        visibility = View.VISIBLE
        alpha = 0f
        scaleX = 0.72f
        scaleY = 0.72f
        translationY = dp(12f).toFloat()
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(OvershootInterpolator(1.4f))
            .start()
        postDelayed(hide, 1750L)
    }

    fun hideNow() {
        token++
        removeCallbacks(hide)
        animate().cancel()
        visibility = View.INVISIBLE
    }

    private fun fadeOut(forToken: Long) {
        if (forToken != token) return
        animate()
            .alpha(0f)
            .scaleX(1.1f)
            .scaleY(1.1f)
            .translationY(-dp(14f).toFloat())
            .setDuration(250L)
            .withEndAction {
                if (forToken == token) visibility = View.INVISIBLE
            }
            .start()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hide)
        animate().cancel()
        super.onDetachedFromWindow()
    }

    companion object {

        /** Reaksiya chiplarini [row] ichiga quradi. */
        fun buildChips(row: LinearLayout, onPick: (String) -> Unit) {
            val context = row.context
            row.removeAllViews()
            for (emoji in GamesCore.REACTIONS) {
                val chip = TextView(context)
                chip.text = emoji
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                chip.includeFontPadding = false
                chip.gravity = Gravity.CENTER
                chip.background =
                    ContextCompat.getDrawable(context, R.drawable.bg_yuz_games_reaction_chip)
                chip.isClickable = true
                chip.isFocusable = true
                chip.contentDescription = emoji
                chip.setOnClickListener { onPick(emoji) }
                val lp = LinearLayout.LayoutParams(dp(42f), dp(42f))
                lp.marginEnd = dp(6f)
                row.addView(chip, lp)
            }
        }

        private fun dp(value: Float): Int = AndroidUtilities.dp(value)
    }
}
