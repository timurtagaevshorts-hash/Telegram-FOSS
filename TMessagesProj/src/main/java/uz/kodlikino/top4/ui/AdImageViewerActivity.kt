package uz.kodlikino.top4.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment

/**
 * E'lon rasmlari uchun to'liq ekranli ko'ruvchi — KinoTOP4'dagi
 * `ChatImageViewerDialog` pariteti.
 *
 * Nima uchun dialog emas, [BaseFragment]: YuzGram Telegram ichida ishlaydi,
 * shuning uchun orqaga qaytish, status bar va pastki bar yashirilishi
 * Telegram'ning o'z fragment stack'i orqali boshqariladi (dialog'da buni
 * qo'lda qilish kerak edi).
 *
 * Xatti kamaytirish: rasm ro'yxati bo'sh bo'lsa ekran darhol yopiladi,
 * indeks ise [urls] doirasiga qisiladi (`coerceIn`) — feed'dan noto'g'ri
 * index kelib qolsa IndexOutOfBounds o'rniga xavfsiz.
 */
class AdImageViewerActivity(
    urls: List<String>,
    startIndex: Int,
) : BaseFragment() {

    private val images: List<String> = urls.filter { it.isNotBlank() }

    private var themedContext: Context? = null
    private var vpViewer: ViewPager2? = null
    private var tvCounter: TextView? = null
    private var btnBack: ImageView? = null
    private var tvHint: TextView? = null

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_ad_image_viewer, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE

        vpViewer = root.findViewById(R.id.vpViewer)
        tvCounter = root.findViewById(R.id.tvViewerCounter)
        btnBack = root.findViewById(R.id.btnViewerBack)
        tvHint = root.findViewById(R.id.tvViewerHint)

        // Qora fonda ustki qatorlar status bar ostiga tushmasin.
        root.setPadding(
            root.paddingLeft,
            AndroidUtilities.statusBarHeight,
            root.paddingRight,
            root.paddingBottom
        )

        btnBack?.setOnClickListener { removeSelfFromStack() }

        if (images.isEmpty()) {
            // createView ichida stack hali to'lmagan bo'lishi mumkin — yopishni
            // keyingi message'ga suramiz (batch'da removeSelfFromStack xavfsiz).
            AndroidUtilities.runOnUIThread { removeSelfFromStack() }
            return root
        }

        val adapter = AdFullImageAdapter(images)
        vpViewer?.adapter = adapter
        val start = startIndex.coerceIn(0, images.size - 1)
        vpViewer?.setCurrentItem(start, false)
        updateCounter(start)
        if (images.size < 2) tvHint?.visibility = View.GONE

        vpViewer?.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateCounter(position)
            }
        })

        return root
    }

    private fun updateCounter(position: Int) {
        if (images.size < 2) {
            tvCounter?.text = ""
            return
        }
        tvCounter?.text = getString(R.string.classified_viewer_counter, position + 1, images.size)
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
    }

    override fun onFragmentDestroy() {
        // Glide sayohatini to'xtatamiz: fragment bilan birga view'lar ham
        // o'layotgani uchun holder'larga rasm "qaytishi" xatosini oldini oladi.
        vpViewer?.adapter = null
        super.onFragmentDestroy()
    }

    // View'da getString() yo'q (faqat Context'da bor) — shuning uchun o'z helperimiz.
    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }

    private fun getString(resId: Int, vararg args: Any): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId, *args)
    }
}

/**
 * Ko'ruvchi saifalari: har birida [ZoomableImageView] + yuklanish indikatori.
 * Rasm view o'lchamigacha downsample bo'ladi (Glide standart xatti-harakati) —
 * shuning uchun masshtab tepasi [ZoomableImageView]da 3x bilan chegaralangan:
 * undan ko'pi bilan "piksellanish" ko'rinardi, xotira esa ortib ketardi.
 */
class AdFullImageAdapter(
    private val urls: List<String>,
) : RecyclerView.Adapter<AdFullImageAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val iv: ZoomableImageView = view.findViewById(R.id.ivFullImage)
        val pb: ProgressBar? = view.findViewById(R.id.pbFullImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ad_full_image, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val url = urls[position]
        holder.pb?.visibility = View.VISIBLE
        holder.iv.setImageDrawable(null)
        Glide.with(holder.iv)
            .load(url)
            .transition(DrawableTransitionOptions.withCrossFade())
            .error(R.color.modern_surface)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>?,
                    isFirstResource: Boolean,
                ): Boolean {
                    holder.pb?.visibility = View.GONE
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable?,
                    model: Any?,
                    target: Target<Drawable>?,
                    dataSource: DataSource?,
                    isFirstResource: Boolean,
                ): Boolean {
                    holder.pb?.visibility = View.GONE
                    return false
                }
            })
            .into(holder.iv)
    }

    override fun getItemCount(): Int = urls.size
}
