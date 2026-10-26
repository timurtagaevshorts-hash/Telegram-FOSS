package uz.kodlikino.top4.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import uz.kodlikino.top4.models.AdBanner
import uz.kodlikino.top4.utils.YuzAdStore

/**
 * Reklama banneri — chat ro'yxati tepasida va Funksiyalar sahifasida.
 *
 * To'liq kenglikdagi karta: rasm (bo'lmasa gradient naqsh), pastdan qorayuvchi
 * scrim, "REKLAMA" yorlig'i, sarlavha/matn va CTA. Balandlik ekran kengligiga
 * qarab [heightForWidthPx] bilan hisoblanadi — kichik va katta ekranlarda ham
 * nisbat buzilmaydi. Rang admin tanlagan [YuzAdStore.AdTheme] gradientidan.
 */
class YuzAdBannerView @JvmOverloads constructor(
    context: Context,
    private val openListener: OpenListener? = null
) : FrameLayout(context) {

    fun interface OpenListener {
        fun onOpen(url: String)
    }

    private val image = ImageView(context)
    private val scrim = View(context)
    private val label = TextView(context)
    private val titleView = TextView(context)
    private val subtitleView = TextView(context)
    private val cta = TextView(context)
    private val hostView = TextView(context)
    private val subtitleLp = LinearLayout.LayoutParams(
        LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
    )

    private val radius = AndroidUtilities.dp(20f).toFloat()
    private var linkUrl = ""
    private var localImageUri: Uri? = null

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }

        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.visibility = GONE
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val decorTop = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x1FFFFFFF)
            }
        }
        val topSize = AndroidUtilities.dp(168f)
        val topLp = LayoutParams(topSize, topSize, Gravity.TOP or Gravity.END)
        topLp.topMargin = -AndroidUtilities.dp(58f)
        topLp.rightMargin = -AndroidUtilities.dp(46f)
        addView(decorTop, topLp)

        val decorBottom = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x14FFFFFF)
            }
        }
        val bottomSize = AndroidUtilities.dp(118f)
        val bottomLp = LayoutParams(bottomSize, bottomSize, Gravity.BOTTOM or Gravity.START)
        bottomLp.bottomMargin = -AndroidUtilities.dp(52f)
        bottomLp.leftMargin = -AndroidUtilities.dp(36f)
        addView(decorBottom, bottomLp)

        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xF2000000.toInt(), 0x9E000000.toInt(), 0x00000000)
        )
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        label.apply {
            setTextColor(Color.WHITE)
            textSize = 9f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.14f
            setPadding(
                AndroidUtilities.dp(8f), AndroidUtilities.dp(3f),
                AndroidUtilities.dp(8f), AndroidUtilities.dp(3f)
            )
            background = rounded(0x66000000, AndroidUtilities.dp(7f).toFloat())
        }
        val labelLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        labelLp.topMargin = AndroidUtilities.dp(12f)
        labelLp.leftMargin = AndroidUtilities.dp(12f)
        addView(label, labelLp)

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                AndroidUtilities.dp(16f), 0,
                AndroidUtilities.dp(16f), AndroidUtilities.dp(14f)
            )
        }
        titleView.apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.02f)
        }
        bottom.addView(titleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        subtitleView.apply {
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.08f)
        }
        subtitleLp.topMargin = AndroidUtilities.dp(3f)
        bottom.addView(subtitleView, subtitleLp)

        val ctaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cta.apply {
            setTextColor(0xFF06121C.toInt())
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(
                AndroidUtilities.dp(14f), AndroidUtilities.dp(7f),
                AndroidUtilities.dp(14f), AndroidUtilities.dp(7f)
            )
            background = rounded(0xFF2AABEE.toInt(), AndroidUtilities.dp(15f).toFloat())
        }
        ctaRow.addView(cta, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        hostView.apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val hostLp = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        hostLp.leftMargin = AndroidUtilities.dp(9f)
        ctaRow.addView(hostView, hostLp)

        val ctaLp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        ctaLp.topMargin = AndroidUtilities.dp(9f)
        bottom.addView(ctaRow, ctaLp)

        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        if (Build.VERSION.SDK_INT >= 23) {
            foreground = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                null,
                GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = radius
                }
            )
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val targetHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(
                heightForWidthPx(MeasureSpec.getSize(widthMeasureSpec)),
                MeasureSpec.EXACTLY
            )
        }
        super.onMeasure(widthMeasureSpec, targetHeight)
    }

    fun bind(banner: AdBanner) {
        val theme = YuzAdStore.theme(banner.theme)
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(theme.start, theme.end)
        ).apply { cornerRadius = radius }
        (cta.background as? GradientDrawable)?.setColor(theme.accent)

        label.text = context.getString(R.string.yuz_ad_label)
        cta.text = context.getString(R.string.yuz_ad_cta)

        titleView.text = banner.title
        titleView.visibility = if (banner.title.isBlank()) GONE else VISIBLE
        subtitleView.text = banner.subtitle
        subtitleView.visibility = if (banner.subtitle.isBlank()) GONE else VISIBLE
        subtitleLp.topMargin = if (banner.subtitle.isBlank()) 0 else AndroidUtilities.dp(3f)
        subtitleView.requestLayout()

        linkUrl = banner.linkUrl.trim()
        val host = runCatching { Uri.parse(linkUrl).host }.getOrNull()
            ?.removePrefix("www.")
            ?.trim()
            .orEmpty()
        hostView.text = host
        hostView.visibility = if (host.isNotBlank()) VISIBLE else GONE
        cta.visibility = if (linkUrl.isNotBlank()) VISIBLE else GONE
        isClickable = linkUrl.isNotBlank()
        setOnClickListener {
            val url = linkUrl
            if (url.isNotBlank()) openListener?.onOpen(url)
        }

        val local = localImageUri
        val imageUrl = banner.imageUrl.trim()
        if (local == null && imageUrl.isBlank()) {
            image.visibility = GONE
            try {
                Glide.with(this).clear(image)
            } catch (_: Exception) {
            }
        } else {
            image.visibility = VISIBLE
            try {
                // Rasm aynan karta o'lchamida decode qilinadi — admin yuklagan
                // katta JPEG telefon xotirasini bekorga egallamaydi.
                val width = AndroidUtilities.displaySize.x
                Glide.with(this)
                    .load(local ?: imageUrl)
                    .override(width, heightForWidthPx(width))
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(image)
            } catch (_: Exception) {
                image.visibility = GONE
            }
        }
        contentDescription = buildString {
            append(context.getString(R.string.yuz_ad_label))
            if (banner.title.isNotBlank()) append(". ").append(banner.title)
            if (banner.subtitle.isNotBlank()) append(". ").append(banner.subtitle)
        }
    }

    /**
     * Admin dialogida tanlangan lokal rasmni ko'rsatadi — yuklash hali
     * boshlanmagan, lekin admin natijani darhol ko'rishi kerak. `null`
     * qaytarilsa keyingi [bind] yana banner URL'idan chizadi.
     */
    fun showLocalImage(uri: Uri?) {
        localImageUri = uri
        if (uri == null) return
        image.visibility = VISIBLE
        try {
            Glide.with(this).load(uri).centerCrop().into(image)
        } catch (_: Exception) {
            image.visibility = GONE
        }
    }

    private fun rounded(color: Int, corner: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = corner
    }

    companion object {
        /**
         * Karta balandligi: kenglikning ~34% i, 118–168 dp oralig'ida. Chat
         * ro'yxati padding'i ham shu funksiyadan foydalanadi — ular doim mos.
         */
        @JvmStatic
        fun heightForWidthPx(widthPx: Int): Int {
            val raw = widthPx * 0.34f
            val min = AndroidUtilities.dp(118f).toFloat()
            val max = AndroidUtilities.dp(168f).toFloat()
            return raw.coerceIn(min, max).toInt()
        }
    }
}
