package uz.kodlikino.top4.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.ClassifiedAdsAdapter
import uz.kodlikino.top4.models.ClassifiedAd
import uz.kodlikino.top4.models.ClassifiedAdCategories
import uz.kodlikino.top4.utils.ClassifiedAdRepository
import uz.kodlikino.top4.utils.ClassifiedLocationHelper
import uz.kodlikino.top4.utils.YuzGramAdsAuth

/**
 * "Elonlar" (classifieds) bo'limi — YuzGram pastki navigatsiyasining 2-tab'i.
 * Eski KinoTOP4 elonlar feed'i (RecyclerView + kategoriyalar + qidiruv + filtr) shu
 * Telegram BaseFragment ichiga Material tema (ContextThemeWrapper) ostida joylashtirilgan.
 * Pastki bar LaunchActivity darajasida (doimiy) — bu ekran o'z barini qo'shmaydi.
 *
 * Faza C: feed + dizayn. Faza D: detail + "E'lon berish" (yaratish/tahrirlash).
 * Faza E: "Mening e'lonlarim" rejimi + kartochkadan tahrirlash/o'chirish.
 */
class ElonlarActivity : BaseFragment() {

    private var rvAds: RecyclerView? = null
    private var swipeRefresh: SwipeRefreshLayout? = null
    private var progressBar: ProgressBar? = null
    private var emptyState: LinearLayout? = null
    private var chipGroup: ChipGroup? = null
    private var adsAppBar: View? = null
    private var searchContainer: View? = null
    private var filterPanel: LinearLayout? = null
    private var btnMyAds: TextView? = null
    private var etSearchKeyword: EditText? = null
    private var etSearchAddress: EditText? = null
    private var etFilterMinPrice: TextInputEditText? = null
    private var etFilterMaxPrice: TextInputEditText? = null
    private var etFilterLocation: TextInputEditText? = null
    private var btnRegionPicker: MaterialButton? = null
    private var chipGroupDate: ChipGroup? = null
    private var chipGroupSort: ChipGroup? = null

    private val adapter = ClassifiedAdsAdapter(
        onAdClick = { openDetail(it) },
        onEditClick = { openEdit(it) },
        onDeleteClick = { confirmDelete(it) }
    )

    private var adsListener: ListenerRegistration? = null
    private var themedContext: Context? = null
    private var allAds: List<ClassifiedAd> = emptyList()
    private var selectedCategory: String? = null
    private var mineOnly = false
    private var searchQuery = ""
    private var filterMinPrice: Long? = null
    private var filterMaxPrice: Long? = null
    private var filterLocation = ""
    private var filterDays = 0
    private var sortMode = 0
    private val feedShuffleSeed = System.currentTimeMillis()
    private var scope: CoroutineScope? = null

    private fun currentUid(): String {
        val id = UserConfig.getInstance(currentAccount).clientUserId
        return if (id > 0L) "tg_$id" else ""
    }

    override fun createView(context: Context): View {
        // Material komponentlari Telegram temasida emas, alohida Material temasi ostida inflate qilinadi.
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_classified_ads_tab, null) as ViewGroup
        fragmentView = root

        // Telegram'ning o'z action bari kerak emas — feed'ning o'z header'i bor.
        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        adsAppBar = root.findViewById(R.id.adsAppBar)
        rvAds = root.findViewById(R.id.rvClassifiedAds)
        swipeRefresh = root.findViewById(R.id.swipeRefreshAds)
        progressBar = root.findViewById(R.id.progressBar)
        emptyState = root.findViewById(R.id.emptyState)
        chipGroup = root.findViewById(R.id.chipGroupCategories)
        searchContainer = root.findViewById(R.id.adsSearchContainer)
        filterPanel = root.findViewById(R.id.filterPanel)
        btnMyAds = root.findViewById(R.id.btnMyAds)
        etSearchKeyword = root.findViewById(R.id.etSearchKeyword)
        etSearchAddress = root.findViewById(R.id.etSearchAddress)
        etFilterMinPrice = root.findViewById(R.id.etFilterMinPrice)
        etFilterMaxPrice = root.findViewById(R.id.etFilterMaxPrice)
        etFilterLocation = root.findViewById(R.id.etFilterLocation)
        btnRegionPicker = root.findViewById(R.id.btnFilterRegionPicker)
        chipGroupDate = root.findViewById(R.id.chipGroupAdsDate)
        chipGroupSort = root.findViewById(R.id.chipGroupAdsSort)

        // Status bari bilan tepada bo'sh qora joy qolmasin — haqiqiy inset.
        YuzTopInset.applyPadding(adsAppBar)

        rvAds?.apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@ElonlarActivity.adapter
            clipToPadding = false
            setPadding(0, 0, 0, YuzGramBottomBar.height())
        }

        setupCategoryChips(themed)

        root.findViewById<View>(R.id.btnElonlarSearch)?.setOnClickListener {
            searchContainer?.visibility =
                if (searchContainer?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        root.findViewById<ImageButton>(R.id.btnToggleFilter)?.setOnClickListener {
            filterPanel?.visibility =
                if (filterPanel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        // #75: viloyat/tuman tanlagichi + sana/saralash chiplari.
        btnRegionPicker?.setOnClickListener {
            val ctx = themedContext ?: return@setOnClickListener
            ClassifiedLocationHelper.showLocationPicker(ctx) { loc ->
                filterLocation = loc
                etFilterLocation?.setText(loc)
                etSearchAddress?.setText(loc)
                btnRegionPicker?.text = ClassifiedLocationHelper.pickerLabel(loc)
                applyFilter()
            }
        }
        setupDateChips()
        setupSortChips()
        etSearchKeyword?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })
        // Manzil bo'yicha qidiruv — avval bu maydon focusable="false" edi, ya'ni
        // bosilganda hech narsa bo'lmaydi (klaviatura chiqmasdi). Endi u haqiqiy
        // filtr: har bir belgida `filterLocation` yangilanadi va feed qisqaradi.
        etSearchAddress?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                filterLocation = s?.toString()?.trim().orEmpty()
                // Filtrlar panelidagi manzil maydoni bilan bir xil ko'rinsin.
                if (etFilterLocation?.text?.toString()?.trim() != filterLocation) {
                    etFilterLocation?.setText(filterLocation)
                }
                applyFilter()
            }
        })
        root.findViewById<MaterialButton>(R.id.btnFilterApply)?.setOnClickListener {
            filterMinPrice = etFilterMinPrice?.text?.toString()?.trim()?.toLongOrNull()
            filterMaxPrice = etFilterMaxPrice?.text?.toString()?.trim()?.toLongOrNull()
            filterLocation = etFilterLocation?.text?.toString()?.trim().orEmpty()
            // Paneldagi manzil qidiruv qatoriga ham qaytarilsin (ikki maydon chalkashmasin).
            if (etSearchAddress?.text?.toString()?.trim() != filterLocation) {
                etSearchAddress?.setText(filterLocation)
            }
            btnRegionPicker?.text = ClassifiedLocationHelper.pickerLabel(filterLocation)
            filterPanel?.visibility = View.GONE
            applyFilter()
        }
        root.findViewById<MaterialButton>(R.id.btnFilterReset)?.setOnClickListener {
            etFilterMinPrice?.text?.clear()
            etFilterMaxPrice?.text?.clear()
            etFilterLocation?.text?.clear()
            etSearchAddress?.text?.clear()
            filterMinPrice = null
            filterMaxPrice = null
            filterLocation = ""
            filterDays = 0
            sortMode = 0
            btnRegionPicker?.text = ClassifiedLocationHelper.pickerLabel("")
            setupDateChips()
            setupSortChips()
            applyFilter()
        }

        root.findViewById<ExtendedFloatingActionButton>(R.id.fabPostAd)?.setOnClickListener { openCreate() }

        btnMyAds?.setOnClickListener {
            mineOnly = !mineOnly
            updateMyAdsButton()
            startAdsListener()
        }
        updateMyAdsButton()

        swipeRefresh?.setOnRefreshListener { startAdsListener() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        return root
    }

    /** "Mening e'lonlarim" tugmasining holati — yoqilganda to'ldirilgan ko'k pill. */
    private fun updateMyAdsButton() {
        val btn = btnMyAds ?: return
        val ctx = themedContext ?: context ?: return
        btn.setBackgroundResource(
            if (mineOnly) R.drawable.bg_yuz_pill_active else R.drawable.bg_yuz_pill
        )
        btn.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                ctx,
                if (mineOnly) R.color.yuz_text_on_accent else R.color.yuz_text_secondary
            )
        )
    }

    private fun openDetail(ad: ClassifiedAd) {
        presentFragment(ClassifiedAdDetailActivity(ad.id), false)
    }

    /** "Elon joylash" — yangi e'lon yaratish ekrani (Faza D). */
    private fun openCreate() {
        presentFragment(CreateClassifiedAdActivity(), false)
    }

    /** Faza E: kartochkadagi qalam — e'lonni tahrirlash (CreateClassifiedAd edit rejimi). */
    private fun openEdit(ad: ClassifiedAd) {
        presentFragment(CreateClassifiedAdActivity(ad.id), false)
    }

    /** Faza E: kartochkadagi chiqindiy qutisi — tasdiqlab o'chirish. */
    private fun confirmDelete(ad: ClassifiedAd) {
        val themed = themedContext ?: context ?: return
        val title = if (ad.title.isBlank()) ad.model else ad.title
        AlertDialog.Builder(themed)
            .setTitle(R.string.classified_delete_confirm_title)
            .setMessage(themed.getString(R.string.classified_delete_confirm_message, title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.classified_delete) { _, _ -> deleteAd(ad) }
            .show()
    }

    private fun deleteAd(ad: ClassifiedAd) {
        val s = scope ?: return
        val account = currentAccount
        s.launch {
            try {
                // Firestore qoidasi: o'chirish faqat tashabbus egasiga ruxsat beradi,
                // shuning uchun avval Telegram identiteti Firebase sessiyasiga almashtiriladi.
                YuzGramAdsAuth.ensureSignedIn(account)
                ClassifiedAdRepository.deleteAd(ad.id)
                Toast.makeText(
                    parentActivity ?: context ?: return@launch,
                    "E'lon o'chirildi", Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    parentActivity ?: context ?: return@launch,
                    "O'chirib bo'lmadi: ${e.message}", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupCategoryChips(context: Context) {
        val group = chipGroup ?: return
        // Chiplar yagona uslubdan (YuzChips) o'tadi: kategoriya chiplari va filtr
        // qatorlari bir xil ko'rinishi shart.
        val items = ArrayList<YuzChips.Item>()
        items.add(
            YuzChips.Item("", context.getString(R.string.classified_all_categories))
        )
        ClassifiedAdCategories.all.forEach { items.add(YuzChips.Item(it.id, it.label, it.icon)) }
        val selected = items.indexOfFirst { it.id == (selectedCategory ?: "") }.coerceAtLeast(0)
        YuzChips.fill(context, group, items, selected) { i ->
            selectedCategory = items[i].id.ifBlank { null }
            setupCategoryChips(context)
            applyFilter()
        }
    }

    /** #75: filtr panelidagi chip qatorlari — kategoriya chiplari bilan bir xil uslub. */
    private fun buildFilterChips(
        group: ChipGroup?,
        labels: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        val ctx = themedContext ?: return
        YuzChips.fill(ctx, group, labels.map { YuzChips.Item(it, it) }, selectedIndex, onSelect)
    }

    private fun setupDateChips() {
        val ctx = themedContext ?: return
        val values = listOf(1, 7, 30, 0)
        val selected = values.indexOf(filterDays).coerceAtLeast(0)
        val labels = listOf(
            ctx.getString(R.string.classified_filter_today),
            ctx.getString(R.string.classified_filter_7days),
            ctx.getString(R.string.classified_filter_30days),
            ctx.getString(R.string.classified_all_categories)
        )
        buildFilterChips(chipGroupDate, labels, selected) { i ->
            filterDays = values.getOrElse(i) { 0 }
            setupDateChips()
            applyFilter()
        }
    }

    private fun setupSortChips() {
        val ctx = themedContext ?: return
        val labels = listOf(
            ctx.getString(R.string.classified_sort_default),
            ctx.getString(R.string.classified_sort_new),
            ctx.getString(R.string.classified_sort_cheap),
            ctx.getString(R.string.classified_sort_expensive)
        )
        buildFilterChips(chipGroupSort, labels, sortMode) { i ->
            sortMode = i
            setupSortChips()
            applyFilter()
        }
    }

    private fun startAdsListener() {
        adsListener?.remove()
        progressBar?.visibility = View.VISIBLE
        adapter.setMyUid(currentUid())
        // Firestore snapshot listener Android'da default main-thread'da yetkazadi.
        if (mineOnly) {
            val uid = currentUid()
            if (uid.isBlank()) {
                progressBar?.visibility = View.GONE
                swipeRefresh?.isRefreshing = false
                allAds = emptyList()
                applyFilter()
                return
            }
            adsListener = ClassifiedAdRepository.listenUserAds(uid) { ads ->
                if (fragmentView == null) return@listenUserAds
                onAdsReceived(ads)
            }
            return
        }
        adsListener = ClassifiedAdRepository.listenActiveAds(shuffleForFeed = false) { ads ->
            if (fragmentView == null) return@listenActiveAds
            onAdsReceived(ads)
        }
    }

    private fun onAdsReceived(ads: List<ClassifiedAd>) {
        progressBar?.visibility = View.GONE
        swipeRefresh?.isRefreshing = false
        allAds = ads
        applyFilter()
    }

    private fun applyFilter() {
        val uid = currentUid()
        var filtered = allAds
        if (!selectedCategory.isNullOrBlank()) {
            filtered = filtered.filter { it.category == selectedCategory }
        }
        if (searchQuery.isNotBlank()) {
            filtered = filtered.filter { ad ->
                ad.title.contains(searchQuery, true) ||
                    ad.model.contains(searchQuery, true) ||
                    ad.description.contains(searchQuery, true) ||
                    ad.address.contains(searchQuery, true)
            }
        }
        if (filterLocation.isNotBlank()) {
            // #75: "Viloyat, Tuman" satrlari ham, erkin yozilgan shahar nomlari ham
            // tushishi uchun region+address maydonlariga bo'sh (loose) moslik.
            filtered = filtered.filter {
                ClassifiedLocationHelper.matches(filterLocation, it.region, it.address)
            }
        }
        filterMinPrice?.let { min -> filtered = filtered.filter { it.price <= 0L || it.price >= min } }
        filterMaxPrice?.let { max -> filtered = filtered.filter { it.price <= 0L || it.price <= max } }
        if (filterDays > 0) {
            val cutoff = System.currentTimeMillis() - filterDays * 86_400_000L
            filtered = filtered.filter { it.createdAt >= cutoff }
        }

        filtered = ClassifiedAdRepository.orderForFeed(
            filtered, uid, feedShuffleSeed xor ClassifiedAdRepository.feedOrderSeed(filtered)
        )
        filtered = when (sortMode) {
            1 -> filtered.sortedByDescending { it.createdAt }
            // Narx bo'yicha saralashda narxi noma'lum (0) e'lonlar oxirga qolsin.
            2 -> filtered.sortedWith(compareBy({ it.price <= 0L }, { it.price }))
            3 -> filtered.sortedByDescending { it.price }
            else -> filtered
        }
        adapter.setMyUid(uid)
        adapter.submit(filtered)
        emptyState?.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        rvAds?.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        if (fragmentView != null) startAdsListener()
    }

    override fun onPause() {
        adsListener?.remove()
        adsListener = null
        super.onPause()
    }

    override fun onFragmentDestroy() {
        adsListener?.remove()
        adsListener = null
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }
}
