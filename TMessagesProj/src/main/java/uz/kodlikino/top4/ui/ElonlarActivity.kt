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
import com.google.android.material.chip.Chip
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

    /** "Mening e'lonlarim" tugmasining holati — yoqilganda neon ko'k, o'chganda kulrang. */
    private fun updateMyAdsButton() {
        val btn = btnMyAds ?: return
        if (mineOnly) {
            btn.setBackgroundResource(R.drawable.bg_classified_my_ads_active)
            btn.setTextColor(android.graphics.Color.WHITE)
        } else {
            btn.setBackgroundResource(R.drawable.bg_classified_search_bar)
            val ctx = themedContext ?: context ?: return
            btn.setTextColor(
                androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary)
            )
        }
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
            .setTitle("E'lonni o'chirish")
            .setMessage("\"$title\" o'chirilsinmi? Bu amalni qaytarib bo'lmaydi.")
            .setNegativeButton("Bekor qilish", null)
            .setPositiveButton("O'chirish") { _, _ -> deleteAd(ad) }
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
        group.removeAllViews()
        // Palitra resurslardan olinadi — chat/Elonlar/Reels bir xil aksentni ishlatadi.
        val accent = androidx.core.content.ContextCompat.getColor(context, R.color.primary)
        val surface = androidx.core.content.ContextCompat.getColor(context, R.color.home_card)
        val muted = androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary)
        // BaseFragment — View emas (oddiy abstract klass), shuning uchun `resources`
        // yoki `requireContext()` bu yerda yo'q: parametr kelgan Context ishlatiladi.
        val density = context.resources.displayMetrics.density
        fun addPill(label: String, id: String?, checked: Boolean) {
            val chip = Chip(context).apply {
                text = label
                isCheckable = true
                isChecked = checked
                setTextColor(
                    android.content.res.ColorStateList.valueOf(
                        if (checked) android.graphics.Color.WHITE else muted
                    )
                )
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                    if (checked) accent else surface
                )
                chipStrokeWidth = 1f * density
                chipStrokeColor = android.content.res.ColorStateList.valueOf(
                    if (checked) 0x00FFFFFF else 0x1AFFFFFF
                )
                chipCornerRadius = 20f * density
                setOnClickListener {
                    selectedCategory = id
                    setupCategoryChips(context)
                    applyFilter()
                }
            }
            group.addView(chip)
        }
        addPill("Barchasi", null, selectedCategory == null)
        // Emoji bilan: qator endi to'liq kenglikda — turkumni bir qarashda ajratish oson.
        ClassifiedAdCategories.all.forEach { addPill("${it.emoji} ${it.label}", it.id, selectedCategory == it.id) }
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

    override fun onFragmentDestroy() {
        adsListener?.remove()
        adsListener = null
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }
}
