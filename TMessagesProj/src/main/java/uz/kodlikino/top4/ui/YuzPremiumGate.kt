package uz.kodlikino.top4.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.utils.YuzPremiumManager

/**
 * Reels joylash uchun YuzGram Premium darvozasi.
 *
 * Reels joylash endi faqat premium obunachilar uchun: tugma bosilganda
 * [YuzPremiumManager.isPremium] tekshiriladi va obuna bo'lmasa chiroyli
 * "Premium kerak" kartasi ko'rsatiladi. Karta — qora shisha fon, oltin
 * nishon, imtiyozlar ro'yxati va brend gradientli CTA (yuz_action tokeni).
 */
object YuzPremiumGate {

    fun isPremium(account: Int, context: android.content.Context): Boolean =
        YuzPremiumManager.from(context).isPremium(account)

    fun canPostReels(account: Int, context: android.content.Context): Boolean =
        isPremium(account, context)

    fun showReelsRequired(fragment: BaseFragment) {
        val raw = fragment.parentActivity ?: return
        val context = YuzTheme.wrap(raw)
        showCard(
            fragment,
            context,
            title = context.getString(R.string.YuzPremiumReelTitle),
            body = context.getString(R.string.YuzPremiumReelBody),
            perks = listOf(
                context.getString(R.string.YuzPremiumReelPerkUnlimited),
                context.getString(R.string.YuzPremiumReelPerkMovies),
                context.getString(R.string.YuzPremiumReelPerkBadge)
            ),
            cta = context.getString(R.string.YuzPremiumReelCta)
        )
    }

    /** Ishonch: "doimo ruxsatsiz kuzatish" faqat premium uchun. */
    fun showWatchFreeRequired(fragment: BaseFragment) {
        val raw = fragment.parentActivity ?: return
        val context = YuzTheme.wrap(raw)
        showCard(
            fragment,
            context,
            title = context.getString(R.string.yuz_trust_premium_badge),
            body = context.getString(R.string.yuz_trust_premium_required),
            perks = listOf(context.getString(R.string.yuz_trust_premium_hint)),
            cta = context.getString(R.string.yuz_trust_premium_more)
        )
    }

    /** Ishonch: mustahkamlash (qulf) faqat premium uchun. */
    fun showLockRequired(fragment: BaseFragment) {
        val raw = fragment.parentActivity ?: return
        val context = YuzTheme.wrap(raw)
        showCard(
            fragment,
            context,
            title = context.getString(R.string.yuz_trust_lock_premium_title),
            body = context.getString(R.string.yuz_trust_lock_premium_body),
            perks = listOf(context.getString(R.string.yuz_trust_lock_premium_perk)),
            cta = context.getString(R.string.yuz_trust_premium_more)
        )
    }

    private fun showCard(
        fragment: BaseFragment,
        context: android.content.Context,
        title: String,
        body: String,
        perks: List<String>,
        cta: String
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = AndroidUtilities.dp(26f).toFloat()
                colors = intArrayOf(0xFF25162F.toInt(), 0xFF130A19.toInt())
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(AndroidUtilities.dp(1f), 0x33FFD84D)
            }
            setPadding(dp(22f), dp(24f), dp(22f), dp(18f))
        }

        card.addView(TextView(context).apply {
            text = "★"
            setTextColor(0xFF1A1405.toInt())
            textSize = 26f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(0xFFFFE07A.toInt(), 0xFFF0A93B.toInt())
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
        }, LinearLayout.LayoutParams(dp(66f), dp(66f)))

        card.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14f) })

        card.addView(TextView(context).apply {
            text = body
            setTextColor(0xFFA9B6CC.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setLineSpacing(dp(3f).toFloat(), 1f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8f) })

        val perksView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (perk in perks) {
            addPerk(context, perksView, perk)
        }
        card.addView(perksView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16f) })

        val ctaView = TextView(context).apply {
            text = cta
            setTextColor(0xFF11061C.toInt())
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = AndroidUtilities.dp(18f).toFloat()
                colors = intArrayOf(0xFFE0245E.toInt(), 0xFFFF5C93.toInt())
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            setOnClickListener {
                dialog.dismiss()
                fragment.presentFragment(YuzPremiumActivity(), false)
            }
        }
        card.addView(ctaView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)
        ).apply { topMargin = dp(20f) })

        card.addView(TextView(context).apply {
            text = context.getString(R.string.YuzPremiumNotNow)
            setTextColor(0xFF8C9BB2.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, dp(4f))
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(18f), 0, dp(18f), dp(24f))
            addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setDimAmount(0.76f)
            setGravity(Gravity.BOTTOM)
        }
        root.setOnClickListener { dialog.dismiss() }
        card.setOnClickListener { }

        // Yumshoq kirish: karta pastdan ko'tarilib paydo bo'ladi.
        card.alpha = 0f
        card.translationY = dp(28f).toFloat()
        card.postDelayed({
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }, 40)

        fragment.showDialog(dialog)
    }

    private fun addPerk(context: android.content.Context, parent: LinearLayout, text: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5f), 0, dp(5f))
        }
        row.addView(TextView(context).apply {
            this.text = "✦"
            setTextColor(0xFFFFD84D.toInt())
            textSize = 13f
        }, LinearLayout.LayoutParams(dp(20f), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(context).apply {
            this.text = text
            setTextColor(0xFFF4EDF8.toInt())
            textSize = 14f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        parent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun dp(value: Float): Int = AndroidUtilities.dp(value)
}
