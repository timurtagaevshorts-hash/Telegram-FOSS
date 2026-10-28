package uz.kodlikino.top4.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R

/**
 * O‘yin qoidalari — o‘zbek tilida, bo‘limlarga ajratilgan holda.
 *
 * Ikkala o‘yin uchun ham bitta dialog: sarlavha, qisqa maqsad, so‘ng
 * raqamlangan qadamlar. Matnlar resurslarda — tarjima qilish oson.
 */
object GameRulesDialog {

    const val CHECKERS = "checkers"
    const val MAFIA = "mafia"

    fun show(context: Context, kind: String) {
        val dialog = Dialog(context, R.style.TransparentDialog)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, R.color.yuz_surface_raised, 20f)
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }

        val frame = FrameLayout(context).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            )
        }

        root.addView(title(context, if (kind == MAFIA) R.string.maf_title else R.string.chk_title))
        root.addView(
            subtitle(
                context,
                if (kind == MAFIA) R.string.rules_mafia_lead else R.string.rules_checkers_lead
            )
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(8) }
        }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll)

        val sections: List<Int> =
            if (kind == MAFIA) MAFIA_SECTIONS else CHECKERS_SECTIONS
        for (resId in sections) {
            body.addView(section(context, resId))
        }

        val close = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        close.setText(R.string.rules_close)
        close.setTextColor(ContextCompat.getColor(context, R.color.yuz_action))
        close.strokeColor = ContextCompat.getColorStateList(context, R.color.yuz_action)
        close.strokeWidth = dp(1.2f)
        close.cornerRadius = dp(14)
        close.setTextSize(15f)
        close.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(46)
        ).apply { topMargin = dp(12) }
        close.setOnClickListener { dialog.dismiss() }
        root.addView(close)

        dialog.setContentView(frame)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private val CHECKERS_SECTIONS = listOf(
        R.string.rules_chk_goal,
        R.string.rules_chk_board,
        R.string.rules_chk_move,
        R.string.rules_chk_kings,
        R.string.rules_chk_capture,
        R.string.rules_chk_max_capture,
        R.string.rules_chk_chain,
        R.string.rules_chk_time,
        R.string.rules_chk_draw,
        R.string.rules_chk_voice
    )

    private val MAFIA_SECTIONS = listOf(
        R.string.rules_maf_goal,
        R.string.rules_maf_roles,
        R.string.rules_maf_night,
        R.string.rules_maf_day,
        R.string.rules_maf_vote,
        R.string.rules_maf_host,
        R.string.rules_maf_voice
    )

    private fun title(context: Context, resId: Int): TextView = TextView(context).apply {
        text = context.getString(resId)
        setTextColor(ContextCompat.getColor(context, R.color.yuz_text_bright))
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun subtitle(context: Context, resId: Int): TextView = TextView(context).apply {
        text = context.getString(resId)
        setTextColor(ContextCompat.getColor(context, R.color.yuz_text_secondary))
        textSize = 13.5f
        setLineSpacing(dp(2f).toFloat(), 1f)
        setPadding(0, dp(4), 0, 0)
    }

    private fun section(context: Context, resId: Int): TextView {
        val raw = context.getString(resId)
        val lines = raw.lines()
        val heading = lines.firstOrNull().orEmpty()
        val bodyText = lines.drop(1).joinToString("\n").trim()

        val view = TextView(context)
        val sb = StringBuilder()
        if (heading.isNotBlank()) {
            sb.append(heading)
            if (bodyText.isNotBlank()) sb.append('\n')
        }
        sb.append(bodyText)
        view.text = sb.toString()
        view.setTextColor(ContextCompat.getColor(context, R.color.yuz_text))
        view.textSize = 13.5f
        view.setLineSpacing(dp(4f).toFloat(), 1f)
        view.setPadding(0, dp(10), 0, 0)
        return view
    }

    private fun rounded(context: Context, colorRes: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, colorRes))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1.2f), ContextCompat.getColor(context, R.color.yuz_stroke))
        }

    private fun dp(value: Float): Int = AndroidUtilities.dp(value)

    private fun dp(value: Int): Int = AndroidUtilities.dp(value.toFloat())
}
