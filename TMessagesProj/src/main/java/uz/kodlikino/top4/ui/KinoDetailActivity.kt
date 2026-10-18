package uz.kodlikino.top4.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.Movie
import uz.kodlikino.top4.utils.MoviesRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzPremiumManager

/** Kino sahifasi — banner, nom, meta, tavsif va "Ko'rish" tugmasi (→ pleyer). */
class KinoDetailActivity(private val movie: Movie) : BaseFragment() {

    private var themedContext: Context? = null
    private var scope: CoroutineScope? = null
    private val repo = MoviesRepository()
    private var current = movie

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_kino_detail, null) as ViewGroup
        fragmentView = root
        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        val topBar = root.findViewById<View>(R.id.kinoDetailTopBar)
        YuzTopInset.applyPadding(topBar)
        root.findViewById<View>(R.id.btnKinoDetailBack)?.setOnClickListener { removeSelfFromStack() }

        val banner = root.findViewById<ImageView>(R.id.ivKinoBanner)
        val title = root.findViewById<TextView>(R.id.tvKinoDetailTitle)
        val meta = root.findViewById<TextView>(R.id.tvKinoDetailMeta)
        val views = root.findViewById<TextView>(R.id.tvKinoDetailViews)
        val desc = root.findViewById<TextView>(R.id.tvKinoDetailDesc)
        val watch = root.findViewById<View>(R.id.btnKinoWatch)

        bind(banner, title, meta, views, desc)

        watch.setOnClickListener { openPlayer() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        bumpViews()
        return root
    }

    private fun bind(
        banner: ImageView,
        title: TextView,
        meta: TextView,
        views: TextView,
        desc: TextView
    ) {
        title.text = current.title
        val parts = listOfNotNull(
            current.year.takeIf { it.isNotBlank() },
            current.genre.takeIf { it.isNotBlank() },
            current.duration.takeIf { it.isNotBlank() },
            if (current.rating > 0.0)
                themedContext?.getString(R.string.kino_rating, formatRating(current.rating))
                ?: "" else null
        ).filter { it.isNotBlank() }
        meta.text = parts.joinToString("  •  ")
        meta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        views.text = themedContext?.getString(R.string.kino_views, current.views.toString())
            ?: "${current.views}"
        desc.text = current.description
        desc.visibility = if (current.description.isNotBlank()) View.VISIBLE else View.GONE
        val url = current.bannerUrl.ifBlank { current.imageUrl }
        if (url.isNotBlank()) {
            Glide.with(banner)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.drawable.bg_classified_image_placeholder)
                .into(banner)
        } else {
            banner.setImageDrawable(null)
        }
    }

    private fun openPlayer() {
        if (current.videoUrl.isBlank()) {
            toast(R.string.kino_no_url)
            return
        }
        // Premium kino — faqat Premium Kino/To'liq Premium obunachilarga
        // (donor `PremiumManager.hasPremiumMovies` gating'ining fork moslamasi).
        if (current.isPremium) {
            val themed = themedContext ?: context
            val unlocked = themed != null &&
                YuzPremiumManager.from(themed).hasPremiumMovies(currentAccount)
            if (!unlocked) {
                toast(R.string.kino_premium_required)
                presentFragment(YuzPremiumActivity(), false)
                return
            }
        }
        // #72: ko'rish avvalgi kabi ALOHIDA fullscreen Activity'da ochiladi —
        // LaunchActivity ichidagi fragment WebView landscape/immersive holatini
        // to'g'ri ololmas edi.
        val act = parentActivity ?: return
        android.content.Intent(act, YuzKinoPlayerActivity::class.java).apply {
            putExtra(YuzKinoPlayerActivity.EXTRA_URL, current.videoUrl)
            putExtra(YuzKinoPlayerActivity.EXTRA_TITLE, current.title)
        }.let { act.startActivity(it) }
    }

    private fun bumpViews() {
        val s = scope ?: return
        val id = current.id
        if (id.isBlank()) return
        val account = currentAccount
        s.launch {
            try {
                YuzGramAdsAuth.ensureSignedIn(account)
                repo.incrementViews(id)
            } catch (_: Exception) {
            }
            current = current.copy(views = current.views + 1)
            if (fragmentView != null) {
                fragmentView?.findViewById<TextView>(R.id.tvKinoDetailViews)?.text =
                    themedContext?.getString(R.string.kino_views, current.views.toString())
            }
        }
    }

    private fun formatRating(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", value)
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
    }

    override fun onFragmentDestroy() {
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }

    private fun toast(resId: Int) {
        val ctx = themedContext ?: context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }
}
