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
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.ListenerRegistration
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.ClassifiedAdsAdapter
import uz.kodlikino.top4.models.ClassifiedAd
import uz.kodlikino.top4.models.ClassifiedAdCategories
import uz.kodlikino.top4.utils.ClassifiedAdRepository

/**
 * "Elonlar" (classifieds) bo'limi — YuzGram pastki navigatsiyasining 2-tab'i.
 * Eski KinoTOP4 elonlar feed'i (RecyclerView + kategoriyalar + qidiruv + filtr) shu
 * Telegram BaseFragment ichiga Material tema (ContextThemeWrapper) ostida joylashtirilgan.
 * Pastki bar LaunchActivity darajasida (doimiy) — bu ekran o'z barini qo'shmaydi.
 *
 * Faza C (bu bosqich): feed + dizayn to'liq ishlaydi. Detail/Create ekranlari
 * Faza D'da qo'shiladi — hozircha bosilganda "tez orada" ko'rsatiladi.
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
    private var etSearchKeyword: EditText? = null
    private var etFilterMinPrice: TextInputEditText? = null
    private var etFilterMaxPrice: TextInputEditText? = null
    private var etFilterLocation: TextInputEditText? = null

    private val adapter = ClassifiedAdsAdapter(
        onAdClick = { openSoon() },
        onEditClick = { openSoon() },
        onDeleteClick = { }
    )

    private var adsListener: ListenerRegistration? = null
    private var themedContext: Context? = null
    private var allAds: List<ClassifiedAd> = emptyList()
    private var selectedCategory: String? = null
    private var searchQuery = ""
    private var filterMinPrice: Long? = null
    private var filterMaxPrice: Long? = null
    private var filterLocation = ""
    private val feedShuffleSeed = System.currentTimeMillis()

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

        adsAppBar = root.findViewById(R.id.adsAppBar)
        rvAds = root.findViewById(R.id.rvClassifiedAds)
        swipeRefresh = root.findViewById(R.id.swipeRefreshAds)
        progressBar = root.findViewById(R.id.progressBar)
        emptyState = root.findViewById(R.id.emptyState)
        chipGroup = root.findViewById(R.id.chipGroupCategories)
        searchContainer = root.findViewById(R.id.adsSearchContainer)
        filterPanel = root.findViewById(R.id.filterPanel)
        etSearchKeyword = root.findViewById(R.id.etSearchKeyword)
        etFilterMinPrice = root.findViewById(R.id.etFilterMinPrice)
        etFilterMaxPrice = root.findViewById(R.id.etFilterMaxPrice)
        etFilterLocation = root.findViewById(R.id.etFilterLocation)

        // Status bari ostiga tushmasligi uchun header'ga tepa bo'shliq.
        adsAppBar?.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0)

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
        etSearchKeyword?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })
        root.findViewById<MaterialButton>(R.id.btnFilterApply)?.setOnClickListener {
            filterMinPrice = etFilterMinPrice?.text?.toString()?.trim()?.toLongOrNull()
            filterMaxPrice = etFilterMaxPrice?.text?.toString()?.trim()?.toLongOrNull()
            filterLocation = etFilterLocation?.text?.toString()?.trim().orEmpty()
            filterPanel?.visibility = View.GONE
            applyFilter()
        }
        root.findViewById<MaterialButton>(R.id.btnFilterReset)?.setOnClickListener {
            etFilterMinPrice?.text?.clear()
            etFilterMaxPrice?.text?.clear()
            etFilterLocation?.text?.clear()
            filterMinPrice = null
            filterMaxPrice = null
            filterLocation = ""
            applyFilter()
        }

        root.findViewById<ExtendedFloatingActionButton>(R.id.fabPostAd)?.setOnClickListener { openSoon() }

        swipeRefresh?.setOnRefreshListener { startAdsListener() }

        return root
    }

    private fun openSoon() {
        val ctx = parentActivity ?: context ?: return
        Toast.makeText(ctx, "Bu bo'lim tez orada qo'shiladi", Toast.LENGTH_SHORT).show()
    }

    private fun setupCategoryChips(context: Context) {
        val group = chipGroup ?: return
        group.removeAllViews()
        fun addPill(label: String, id: String?, checked: Boolean) {
            val chip = Chip(context).apply {
                text = label
                isCheckable = true
                isChecked = checked
                setTextColor(
                    android.content.res.ColorStateList.valueOf(
                        if (checked) android.graphics.Color.WHITE else 0xFF8B92A8.toInt()
                    )
                )
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    if (checked) 0xFF4F8CFF.toInt() else 0xFF0D121D.toInt()
                )
                chipStrokeWidth = 1f * resources.displayMetrics.density
                chipStrokeColor = android.content.res.ColorStateList.valueOf(
                    if (checked) 0x00FFFFFF else 0x1AFFFFFF
                )
                chipCornerRadius = 20f * resources.displayMetrics.density
                setOnClickListener {
                    selectedCategory = id
                    setupCategoryChips(context)
                    applyFilter()
                }
            }
            group.addView(chip)
        }
        addPill("Barchasi", null, selectedCategory == null)
        ClassifiedAdCategories.all.forEach { addPill(it.label, it.id, selectedCategory == it.id) }
    }

    private fun startAdsListener() {
        adsListener?.remove()
        progressBar?.visibility = View.VISIBLE
        adapter.setMyUid(currentUid())
        // Firestore snapshot listener Android'da default main-thread'da yetkazadi.
        adsListener = ClassifiedAdRepository.listenActiveAds(shuffleForFeed = false) { ads ->
            if (fragmentView == null) return@listenActiveAds
            progressBar?.visibility = View.GONE
            swipeRefresh?.isRefreshing = false
            allAds = ads
            applyFilter()
        }
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
            filtered = filtered.filter { it.address.contains(filterLocation, true) }
        }
        filterMinPrice?.let { min -> filtered = filtered.filter { it.price <= 0L || it.price >= min } }
        filterMaxPrice?.let { max -> filtered = filtered.filter { it.price <= 0L || it.price <= max } }

        filtered = ClassifiedAdRepository.orderForFeed(
            filtered, uid, feedShuffleSeed xor ClassifiedAdRepository.feedOrderSeed(filtered)
        )
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
}
