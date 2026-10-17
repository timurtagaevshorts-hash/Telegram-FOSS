package uz.kodlikino.top4.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.ClassifiedAdDetailImageAdapter
import uz.kodlikino.top4.models.ClassifiedAd
import uz.kodlikino.top4.models.ClassifiedAdCategories
import uz.kodlikino.top4.utils.ClassifiedAdRepository

/**
 * "Elonlar" — bitta e'lon detail ekrani (Faza C slice 2).
 *
 * KinoTOP4'dagi [ClassifiedAdDetailActivity] (AppCompatActivity) Telegram
 * arxitekturasiga moslab [BaseFragment] ko'rinishida qayta yozildi:
 *  - feed'dan `presentFragment(ClassifiedAdDetailActivity(adId))` orqali ochiladi;
 *  - Material komponentlari [ContextThemeWrapper] (Theme.YuzGramAds) ostida;
 *  - Telegram'ning o'z action bari yashiriladi, layout'ning o'z Toolbar'i ishlatiladi;
 *  - pastki navigatsiya bari LaunchActivity tomonidan avtomatik yashiriladi
 *    (detail root ekran emas).
 *
 * Slice 2 doirasi (read-only): rasm pager, kategoriya/model/narx/manzil/tavsif,
 * qo'ng'iroq va ulashish. Fullscreen rasm ko'ruvchi va "do'stlarga yuborish"
 * (Telegram forward) + tahrirlash/o'chirish Faza D'da qo'shiladi.
 */
class ClassifiedAdDetailActivity(
    private val adId: String,
) : BaseFragment() {

    private var themedContext: Context? = null
    private var toolbar: Toolbar? = null
    private var vpImages: ViewPager2? = null
    private var tabImageIndicator: TabLayout? = null
    private var tvCategory: TextView? = null
    private var tvModel: TextView? = null
    private var tvTitle: TextView? = null
    private var tvPrice: TextView? = null
    private var tvAddress: TextView? = null
    private var tvDescription: TextView? = null
    private var btnCall: MaterialButton? = null
    private var btnShareFriends: MaterialButton? = null

    private val imageAdapter = ClassifiedAdDetailImageAdapter { urls, index ->
        // Rasmga bosiladi — to'liq ekranli ko'ruvchi (KinoTOP4'dagi
        // ChatImageViewerDialog pariteti). Yangi saifa ochiladi, dialog emas:
        // shunda orqaga bosish Telegram'ning o'z stack'ini yopadi.
        if (urls.isEmpty()) return@ClassifiedAdDetailImageAdapter
        presentFragment(AdImageViewerActivity(urls, index), false)
    }

    private var scope: CoroutineScope? = null
    private var loadedAd: ClassifiedAd? = null
    private var phoneNumber: String = ""

    private fun currentUid(): String {
        val id = UserConfig.getInstance(currentAccount).clientUserId
        return if (id > 0L) "tg_$id" else ""
    }

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.activity_classified_ad_detail, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE

        toolbar = root.findViewById(R.id.toolbar)
        vpImages = root.findViewById(R.id.vpImages)
        tabImageIndicator = root.findViewById(R.id.tabImageIndicator)
        tvCategory = root.findViewById(R.id.tvCategory)
        tvModel = root.findViewById(R.id.tvModel)
        tvTitle = root.findViewById(R.id.tvTitle)
        tvPrice = root.findViewById(R.id.tvPrice)
        tvAddress = root.findViewById(R.id.tvAddress)
        tvDescription = root.findViewById(R.id.tvDescription)
        btnCall = root.findViewById(R.id.btnCall)
        btnShareFriends = root.findViewById(R.id.btnShareFriends)

        // Telegram action bar yashirilgan — Toolbar status bar ostiga tushmasin.
        toolbar?.apply {
            YuzTopInset.applyPadding(this)
            setNavigationOnClickListener { removeSelfFromStack() }
        }

        vpImages?.adapter = imageAdapter

        btnShareFriends?.setOnClickListener { shareAd() }
        btnCall?.setOnClickListener { callSeller() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        loadAd()

        return root
    }

    private fun loadAd() {
        val s = scope ?: return
        s.launch {
            // launch Dispatchers.Main (immediate emas) bilan dispatch qilinadi —
            // bu paytga fragment allaqachon stack'da bo'ladi, shuning uchun
            // removeSelfFromStack() xavfsiz.
            if (adId.isBlank()) {
                toast(R.string.classified_ad_not_found)
                removeSelfFromStack()
                return@launch
            }
            val ad = ClassifiedAdRepository.getAdById(adId)
            if (fragmentView == null) return@launch
            val myUid = currentUid()
            if (ad == null || (!ad.isPubliclyVisible() && ad.ownerUid != myUid)) {
                toast(R.string.classified_ad_not_found)
                removeSelfFromStack()
                return@launch
            }
            loadedAd = ad
            bindAd(ad)
        }
    }

    private fun bindAd(ad: ClassifiedAd) {
        val title = if (ad.title.isNotBlank()) ad.title else getString(R.string.classified_ad_detail)
        toolbar?.title = title

        val emoji = ClassifiedAdCategories.emoji(ad.category)
        tvCategory?.text = "$emoji ${ClassifiedAdCategories.label(ad.category)}"

        if (ad.model.isNotBlank()) {
            tvModel?.visibility = View.VISIBLE
            tvModel?.text = ad.model
        } else {
            tvModel?.visibility = View.GONE
        }

        tvTitle?.text = title
        tvPrice?.text = ad.formattedPrice()
        tvAddress?.text =
            if (ad.address.isNotBlank()) ad.address else getString(R.string.classified_address_missing)
        tvDescription?.text =
            if (ad.description.isNotBlank()) ad.description else getString(R.string.classified_description_missing)
        phoneNumber = ad.phone

        val images = ad.imageUrls.filter { it.isNotBlank() }
        if (images.isEmpty()) {
            vpImages?.visibility = View.GONE
            tabImageIndicator?.visibility = View.GONE
        } else {
            vpImages?.visibility = View.VISIBLE
            tabImageIndicator?.visibility = if (images.size > 1) View.VISIBLE else View.GONE
            imageAdapter.submit(images)
            TabLayoutMediator(tabImageIndicator!!, vpImages!!) { _, _ -> }.attach()
        }
    }

    private fun callSeller() {
        val phone = phoneNumber.trim()
        if (phone.isEmpty()) {
            toast(R.string.classified_phone_missing)
            return
        }
        try {
            startActivitySafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.classified_phone_missing)
        }
    }

    private fun shareAd() {
        val ad = loadedAd ?: return
        val text = buildString {
            append(ad.title)
            if (ad.formattedPrice().isNotBlank()) {
                append("\n"); append(ad.formattedPrice())
            }
            if (ad.address.isNotBlank()) {
                append("\n"); append(ad.address)
            }
            if (ad.description.isNotBlank()) {
                append("\n\n"); append(ad.description)
            }
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(share, ad.title)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivitySafely(chooser)
    }

    private fun startActivitySafely(intent: Intent) {
        val activity = parentActivity ?: context ?: return
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // ekranda mos ilova yo'q — jim o'tamiz.
        }
    }

    private fun toast(resId: Int) {
        val ctx = context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }

    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
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
}
