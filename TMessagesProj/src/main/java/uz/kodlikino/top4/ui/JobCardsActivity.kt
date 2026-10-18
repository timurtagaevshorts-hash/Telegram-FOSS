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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.ListenerRegistration
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.JobCardsAdapter
import uz.kodlikino.top4.models.JobCard
import uz.kodlikino.top4.models.JobCardListingTypes
import uz.kodlikino.top4.utils.ClassifiedLocationHelper
import uz.kodlikino.top4.utils.JobCardRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth

/**
 * "Ish kartasi" (job board) bo'limi — Funksiyalar hub'ichidan ochiladi.
 * KinoTOP4'dagi [androidx.appcompat.app.AppCompatActivity] `JobCardsActivity`
 * Telegram arxitekturasiga moslab [BaseFragment] ko'rinishida qayta yozildi.
 *
 * Ikki tab: "Ishlar" (ishchi kerak) va "Ishchilar" (ishlayman).
 * #75: qidiruv + filtrlar (hudud tanlagichi, maosh oralig'i, sana, saralash) —
 * Elonlar bo'limi bilan bir xil uslub va mantik.
 */
class JobCardsActivity : BaseFragment() {

    private var tabSections: TabLayout? = null
    private var rvJobCards: RecyclerView? = null
    private var fabPost: ExtendedFloatingActionButton? = null
    private var progressBar: ProgressBar? = null
    private var emptyState: LinearLayout? = null
    private var tvEmpty: TextView? = null
    private var filterPanel: LinearLayout? = null
    private var etJobSearch: EditText? = null
    private var etJobMinSalary: TextInputEditText? = null
    private var etJobMaxSalary: TextInputEditText? = null
    private var etJobLocation: TextInputEditText? = null
    private var btnRegionPicker: MaterialButton? = null
    private var chipGroupDate: ChipGroup? = null
    private var chipGroupSort: ChipGroup? = null

    private var themedContext: Context? = null
    private val adapter = JobCardsAdapter { openDetail(it) }
    private var cardsListener: ListenerRegistration? = null
    private var currentType: String = JobCardListingTypes.HIRING

    private var allCards: List<JobCard> = emptyList()
    private var searchQuery = ""
    private var filterLocation = ""
    private var filterMinSalary: Long? = null
    private var filterMaxSalary: Long? = null
    private var filterDays = 0
    private var sortMode = 0

    private fun currentUid(): String = YuzGramAdsAuth.expectedUid(currentAccount)

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.activity_job_cards, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        val header = root.findViewById<View>(R.id.jobCardsAppBar)
        YuzTopInset.applyPadding(header)

        tabSections = root.findViewById(R.id.tabJobSections)
        rvJobCards = root.findViewById(R.id.rvJobCards)
        fabPost = root.findViewById(R.id.fabPostJob)
        progressBar = root.findViewById(R.id.progressBar)
        emptyState = root.findViewById(R.id.emptyState)
        tvEmpty = root.findViewById(R.id.tvEmpty)
        filterPanel = root.findViewById(R.id.jobFilterPanel)
        etJobSearch = root.findViewById(R.id.etJobSearch)
        etJobMinSalary = root.findViewById(R.id.etJobMinSalary)
        etJobMaxSalary = root.findViewById(R.id.etJobMaxSalary)
        etJobLocation = root.findViewById(R.id.etJobLocation)
        btnRegionPicker = root.findViewById(R.id.btnJobRegionPicker)
        chipGroupDate = root.findViewById(R.id.chipGroupJobDate)
        chipGroupSort = root.findViewById(R.id.chipGroupJobSort)

        rvJobCards?.apply {
            layoutManager = LinearLayoutManager(themed)
            adapter = this@JobCardsActivity.adapter
            clipToPadding = false
            val bottomPad = YuzGramBottomBar.height() + AndroidUtilities.dp(12f)
            setPadding(AndroidUtilities.dp(20f), AndroidUtilities.dp(8f), AndroidUtilities.dp(20f), bottomPad)
        }

        tabSections?.apply {
            addTab(newTab().setText(R.string.job_board_tab_jobs))
            addTab(newTab().setText(R.string.job_board_tab_workers))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    currentType = when (tab?.position) {
                        1 -> JobCardListingTypes.SEEKING_WORK
                        else -> JobCardListingTypes.HIRING
                    }
                    attachListener()
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) = Unit
                override fun onTabReselected(tab: TabLayout.Tab?) = Unit
            })
        }

        // #75: qidiruv + filtr paneli.
        etJobSearch?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
        })
        root.findViewById<ImageButton>(R.id.btnJobFilterToggle)?.setOnClickListener {
            filterPanel?.visibility =
                if (filterPanel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        btnRegionPicker?.setOnClickListener {
            val ctx = themedContext ?: return@setOnClickListener
            ClassifiedLocationHelper.showLocationPicker(ctx) { loc ->
                filterLocation = loc
                etJobLocation?.setText(loc)
                btnRegionPicker?.text = ClassifiedLocationHelper.pickerLabel(loc)
                applyFilter()
            }
        }
        setupDateChips()
        setupSortChips()
        root.findViewById<MaterialButton>(R.id.btnJobFilterApply)?.setOnClickListener {
            filterMinSalary = etJobMinSalary?.text?.toString()?.trim()?.toLongOrNull()
            filterMaxSalary = etJobMaxSalary?.text?.toString()?.trim()?.toLongOrNull()
            filterLocation = etJobLocation?.text?.toString()?.trim().orEmpty()
            btnRegionPicker?.text = ClassifiedLocationHelper.pickerLabel(filterLocation)
            filterPanel?.visibility = View.GONE
            applyFilter()
        }
        root.findViewById<MaterialButton>(R.id.btnJobFilterReset)?.setOnClickListener {
            etJobSearch?.text?.clear()
            etJobMinSalary?.text?.clear()
            etJobMaxSalary?.text?.clear()
            etJobLocation?.text?.clear()
            searchQuery = ""
            filterMinSalary = null
            filterMaxSalary = null
            filterLocation = ""
            filterDays = 0
            sortMode = 0
            btnRegionPicker?.text = ClassifiedLocationHelper.pickerLabel("")
            setupDateChips()
            setupSortChips()
            applyFilter()
        }

        fabPost?.setOnClickListener {
            if (currentUid().isBlank()) {
                toast(R.string.classified_sign_in_required)
                return@setOnClickListener
            }
            presentFragment(CreateJobCardActivity(currentType), false)
        }

        return root
    }

    private fun attachListener() {
        cardsListener?.remove()
        progressBar?.visibility = View.VISIBLE
        emptyState?.visibility = View.GONE
        val uid = currentUid()
        adapter.setMyUid(uid)
        cardsListener = JobCardRepository.listenActiveCards(
            listingType = currentType,
            myUid = uid
        ) { list ->
            if (fragmentView == null) return@listenActiveCards
            progressBar?.visibility = View.GONE
            allCards = list
            applyFilter()
        }
    }

    private fun applyFilter() {
        if (fragmentView == null) return
        var list = allCards
        if (searchQuery.isNotBlank()) {
            list = list.filter { card ->
                card.title.contains(searchQuery, true) ||
                    card.description.contains(searchQuery, true) ||
                    card.location.contains(searchQuery, true)
            }
        }
        if (filterLocation.isNotBlank()) {
            list = list.filter {
                ClassifiedLocationHelper.matches(filterLocation, it.region, it.location)
            }
        }
        if (filterDays > 0) {
            val cutoff = System.currentTimeMillis() - filterDays * 86_400_000L
            list = list.filter { it.createdAt >= cutoff }
        }
        // "Kelishilgan holda" (maosh 0) e'lonlar narx filtrida saqlanib qoladi.
        filterMinSalary?.let { min ->
            list = list.filter { it.salaryAmount <= 0L || it.salaryAmount >= min }
        }
        filterMaxSalary?.let { max ->
            list = list.filter { it.salaryAmount <= 0L || it.salaryAmount <= max }
        }
        // Sort 0 — repository allaqachon "o'niki tepada + yangi" tartibida yetkazadi.
        list = when (sortMode) {
            1 -> list.sortedByDescending { it.createdAt }
            2 -> list.sortedWith(compareBy({ it.salaryAmount <= 0L }, { it.salaryAmount }))
            3 -> list.sortedByDescending { it.salaryAmount }
            else -> list
        }
        adapter.submit(list)
        emptyState?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        rvJobCards?.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        tvEmpty?.text = if (currentType == JobCardListingTypes.HIRING) {
            getString(R.string.job_board_empty_jobs)
        } else {
            getString(R.string.job_board_empty_workers)
        }
    }

    /** #75: filtr chip'lari — Elonlar panelidagi bilan bir xil uslub. */
    private fun buildFilterChips(
        group: ChipGroup?,
        labels: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        val g = group ?: return
        val ctx = themedContext ?: return
        g.removeAllViews()
        val accent = androidx.core.content.ContextCompat.getColor(ctx, R.color.primary)
        val surface = androidx.core.content.ContextCompat.getColor(ctx, R.color.home_card)
        val muted = androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary)
        val density = ctx.resources.displayMetrics.density
        labels.forEachIndexed { index, label ->
            val checked = index == selectedIndex
            val chip = Chip(ctx).apply {
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
                chipCornerRadius = 18f * density
                setOnClickListener { onSelect(index) }
            }
            g.addView(chip)
        }
    }

    private fun setupDateChips() {
        val values = listOf(1, 7, 30, 0)
        val selected = values.indexOf(filterDays).coerceAtLeast(0)
        buildFilterChips(chipGroupDate, listOf("Bugun", "7 kun", "30 kun", "Barchasi"), selected) { i ->
            filterDays = values.getOrElse(i) { 0 }
            setupDateChips()
            applyFilter()
        }
    }

    private fun setupSortChips() {
        buildFilterChips(chipGroupSort, listOf("Standart", "Yangi", "Arzon", "Qimmat"), sortMode) { i ->
            sortMode = i
            setupSortChips()
            applyFilter()
        }
    }

    private fun openDetail(card: JobCard) {
        presentFragment(JobCardDetailActivity(card.id), false)
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        if (fragmentView != null) attachListener()
    }

    override fun onPause() {
        cardsListener?.remove()
        cardsListener = null
        super.onPause()
    }

    override fun onFragmentDestroy() {
        cardsListener?.remove()
        cardsListener = null
        super.onFragmentDestroy()
    }

    private fun toast(resId: Int) {
        val ctx = themedContext ?: context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }

    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }
}
