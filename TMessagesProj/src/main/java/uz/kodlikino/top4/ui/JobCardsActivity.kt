package uz.kodlikino.top4.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.firebase.firestore.ListenerRegistration
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.JobCardsAdapter
import uz.kodlikino.top4.models.JobCard
import uz.kodlikino.top4.models.JobCardListingTypes
import uz.kodlikino.top4.utils.JobCardRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth

/**
 * "Ish kartasi" (job board) bo'limi — Funksiyalar hub'ichidan ochiladi.
 * KinoTOP4'dagi [androidx.appcompat.app.AppCompatActivity] `JobCardsActivity`
 * Telegram arxitekturasiga moslab [BaseFragment] ko'rinishida qayta yozildi.
 *
 * Ikki tab: "Ishlar" (ishchi kerak) va "Ishchilar" (ishlayman). Hudud filtri
 * (ClassifiedLocationHelper) bu portda yo'q — feed'lar `classified_ads` ichidagi
 * `job_board_*` kategoriyalaridan o'qiydi.
 */
class JobCardsActivity : BaseFragment() {

    private var tabSections: TabLayout? = null
    private var rvJobCards: RecyclerView? = null
    private var fabPost: ExtendedFloatingActionButton? = null
    private var progressBar: ProgressBar? = null
    private var emptyState: LinearLayout? = null
    private var tvEmpty: TextView? = null

    private var themedContext: Context? = null
    private val adapter = JobCardsAdapter { openDetail(it) }
    private var cardsListener: ListenerRegistration? = null
    private var currentType: String = JobCardListingTypes.HIRING

    private fun currentUid(): String = YuzGramAdsAuth.expectedUid(currentAccount)

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.activity_job_cards, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE

        val header = root.findViewById<View>(R.id.jobCardsAppBar)
        header?.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0)

        tabSections = root.findViewById(R.id.tabJobSections)
        rvJobCards = root.findViewById(R.id.rvJobCards)
        fabPost = root.findViewById(R.id.fabPostJob)
        progressBar = root.findViewById(R.id.progressBar)
        emptyState = root.findViewById(R.id.emptyState)
        tvEmpty = root.findViewById(R.id.tvEmpty)

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
            adapter.submit(list)
            emptyState?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            rvJobCards?.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            tvEmpty?.text = if (currentType == JobCardListingTypes.HIRING) {
                getString(R.string.job_board_empty_jobs)
            } else {
                getString(R.string.job_board_empty_workers)
            }
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
