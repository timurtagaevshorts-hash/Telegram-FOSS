package uz.kodlikino.top4.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.Movie
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.utils.KinoAdminAuth
import uz.kodlikino.top4.utils.MoviesRepository
import uz.kodlikino.top4.utils.ReelsRepository
import uz.kodlikino.top4.utils.YuzAdminStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * YuzGram admin paneli (#77) — eski KinoTOP4 login/paroli (claimAdminSession) bilan
 * kiriladi; muvaffaqiyatli kirish `admins/{uid}` hujjatini yozadi, shuning uchun
 * Firestore rule'lari (isAdminUser()) bo'yicha real admin huquqi paydo bo'ladi.
 *
 * Uch tab:
 *  - Dashboard — haqiqiy statistika (comment_profiles.lastActiveAt/createdAt/isPremium,
 *    reels yig'indilari, movies soni) — [YuzAdminStats];
 *  - Kinolar — movies CRUD;
 *  - Reels — barcha joylangan reels ro'yxati + admin o'chirishi.
 *
 * Kirish nuqtasi: Sozlamalar (ProfileActivity) ro'yxatidagi "Admin panel" qatori.
 */
class KinoAdminActivity : BaseFragment() {

    private var themedContext: Context? = null
    private var loginPanel: View? = null
    private var managePanel: View? = null
    private var etUser: TextInputEditText? = null
    private var etPass: TextInputEditText? = null
    private var loginProgress: ProgressBar? = null
    private var btnLogin: MaterialButton? = null

    private var btnTabDash: TextView? = null
    private var btnTabMovies: TextView? = null
    private var btnTabReels: TextView? = null
    private var dashPanel: View? = null
    private var moviesPanel: View? = null
    private var reelsPanel: View? = null

    private var progressDash: ProgressBar? = null
    private var tvStatsUpdated: TextView? = null
    private var gridUsersStats: GridLayout? = null
    private var gridContentStats: GridLayout? = null
    private var tvRecentTitle: TextView? = null
    private var llRecentUsers: ViewGroup? = null
    private var btnRefreshStats: View? = null
    private var dashBusy = false

    private var rv: RecyclerView? = null
    private var adapter: KinoAdminAdapter? = null
    private var empty: TextView? = null
    private var progress: ProgressBar? = null

    private var rvReels: RecyclerView? = null
    private var reelsAdapter: ReelsAdminAdapter? = null
    private var reelsEmpty: TextView? = null
    private var reelsProgress: ProgressBar? = null
    private var reelsLoadedOnce = false

    private var scope: CoroutineScope? = null
    private val repo = MoviesRepository()
    private var movies: List<Movie> = emptyList()
    private var loginBusy = false

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_kino_admin, null) as ViewGroup
        fragmentView = root
        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        val topBar = root.findViewById<View>(R.id.kinoAdminTopBar)
        YuzTopInset.applyPadding(topBar)
        root.findViewById<View>(R.id.btnKinoAdminBack)?.setOnClickListener { removeSelfFromStack() }

        loginPanel = root.findViewById(R.id.loginPanel)
        managePanel = root.findViewById(R.id.managePanel)
        etUser = root.findViewById(R.id.etKinoAdminUser)
        etPass = root.findViewById(R.id.etKinoAdminPass)
        loginProgress = root.findViewById(R.id.kinoLoginProgress)
        btnLogin = root.findViewById(R.id.btnKinoAdminLogin)

        btnTabDash = root.findViewById(R.id.btnTabDash)
        btnTabMovies = root.findViewById(R.id.btnTabMovies)
        btnTabReels = root.findViewById(R.id.btnTabReels)
        dashPanel = root.findViewById(R.id.dashPanel)
        moviesPanel = root.findViewById(R.id.moviesPanel)
        reelsPanel = root.findViewById(R.id.reelsPanel)

        progressDash = root.findViewById(R.id.progressDash)
        tvStatsUpdated = root.findViewById(R.id.tvStatsUpdated)
        gridUsersStats = root.findViewById(R.id.gridUsersStats)
        gridContentStats = root.findViewById(R.id.gridContentStats)
        tvRecentTitle = root.findViewById(R.id.tvRecentTitle)
        llRecentUsers = root.findViewById(R.id.llRecentUsers)
        btnRefreshStats = root.findViewById(R.id.btnRefreshStats)

        rv = root.findViewById(R.id.rvAdminMovies)
        empty = root.findViewById(R.id.tvAdminEmpty)
        progress = root.findViewById(R.id.progressAdminMovies)

        rvReels = root.findViewById(R.id.rvAdminReels)
        reelsEmpty = root.findViewById(R.id.tvReelsEmpty)
        reelsProgress = root.findViewById(R.id.progressAdminReels)

        adapter = KinoAdminAdapter(
            themed, emptyList(),
            onEdit = { showMovieDialog(it) },
            onDelete = { confirmDelete(it) }
        )
        rv?.layoutManager = LinearLayoutManager(themed)
        rv?.adapter = adapter

        reelsAdapter = ReelsAdminAdapter(themed, emptyList()) { confirmDeleteReel(it) }
        rvReels?.layoutManager = LinearLayoutManager(themed)
        rvReels?.adapter = reelsAdapter

        btnLogin?.setOnClickListener { doLogin() }
        root.findViewById<FloatingActionButton>(R.id.btnAddKino)?.setOnClickListener {
            showMovieDialog(null)
        }
        btnTabDash?.setOnClickListener { showTab(TAB_DASH) }
        btnTabMovies?.setOnClickListener { showTab(TAB_MOVIES) }
        btnTabReels?.setOnClickListener { showTab(TAB_REELS) }
        btnRefreshStats?.setOnClickListener { loadDashboard() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        return root
    }

    // ── Login ────────────────────────────────────────────────────────────

    private fun doLogin() {
        if (loginBusy) return
        val user = etUser?.text?.toString()?.trim().orEmpty()
        val pass = etPass?.text?.toString().orEmpty()
        if (user.isBlank() || pass.isBlank()) {
            toast(R.string.kino_admin_need_fields)
            return
        }
        val s = scope ?: return
        loginBusy = true
        loginProgress?.visibility = View.VISIBLE
        btnLogin?.isEnabled = false
        val account = currentAccount
        s.launch {
            val result = KinoAdminAuth.claimSession(account, user, pass)
            loginBusy = false
            if (fragmentView == null) return@launch
            loginProgress?.visibility = View.GONE
            btnLogin?.isEnabled = true
            if (result.isSuccess) {
                enterManageMode()
            } else {
                val msg = result.exceptionOrNull()?.message ?: getString(R.string.kino_admin_failed)
                toast(msg)
            }
        }
    }

    private fun enterManageMode() {
        loginPanel?.visibility = View.GONE
        managePanel?.visibility = View.VISIBLE
        showTab(TAB_DASH)
        loadDashboard()
    }

    // ── Tabs ─────────────────────────────────────────────────────────────

    private fun showTab(tab: Int) {
        val themed = themedContext ?: return
        dashPanel?.visibility = if (tab == TAB_DASH) View.VISIBLE else View.GONE
        moviesPanel?.visibility = if (tab == TAB_MOVIES) View.VISIBLE else View.GONE
        reelsPanel?.visibility = if (tab == TAB_REELS) View.VISIBLE else View.GONE
        styleTab(btnTabDash, tab == TAB_DASH)
        styleTab(btnTabMovies, tab == TAB_MOVIES)
        styleTab(btnTabReels, tab == TAB_REELS)
        if (tab == TAB_MOVIES && adapter?.itemCount == 0) loadMovies()
        if (tab == TAB_REELS && !reelsLoadedOnce) loadReels()
    }

    private fun styleTab(chip: TextView?, active: Boolean) {
        val themed = themedContext ?: return
        chip ?: return
        chip.setBackgroundResource(
            if (active) R.drawable.bg_classified_category_badge else R.drawable.bg_yuz_chip
        )
        @Suppress("DEPRECATION")
        chip.setTextColor(themed.resources.getColor(if (active) R.color.home_accent else R.color.text_secondary))
        chip.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
    }

    // ── Dashboard ────────────────────────────────────────────────────────

    private fun loadDashboard() {
        if (dashBusy) return
        val s = scope ?: return
        dashBusy = true
        progressDash?.visibility = View.VISIBLE
        s.launch {
            val result = runCatching { YuzAdminStats.load() }
            dashBusy = false
            if (fragmentView == null) return@launch
            progressDash?.visibility = View.GONE
            val dash = result.getOrNull()
            if (dash == null) {
                toast(R.string.yuz_admin_stats_failed)
                return@launch
            }
            tvStatsUpdated?.text =
                getString(R.string.yuz_admin_updated, timeFormat.format(Date()))
            fillUsersGrid(dash)
            fillContentGrid(dash)
            fillRecent(dash.recentActive)
        }
    }

    private fun fillUsersGrid(dash: YuzAdminStats.Dashboard) {
        val grid = gridUsersStats ?: return
        val themed = themedContext ?: return
        grid.removeAllViews()
        addTile(themed, grid, 0, dash.todayActive.toLong(), R.string.yuz_stat_today_active)
        addTile(themed, grid, 1, dash.weekActive.toLong(), R.string.yuz_stat_week_active)
        addTile(themed, grid, 2, dash.monthActive.toLong(), R.string.yuz_stat_month_active)
        addTile(themed, grid, 3, dash.totalUsers.toLong(), R.string.yuz_stat_total_users)
        addTile(themed, grid, 4, dash.newUsersMonth.toLong(), R.string.yuz_stat_new_month)
        addTile(themed, grid, 5, dash.premiumUsers.toLong(), R.string.yuz_stat_premium)
        grid.rowCount = 3
    }

    private fun fillContentGrid(dash: YuzAdminStats.Dashboard) {
        val grid = gridContentStats ?: return
        val themed = themedContext ?: return
        grid.removeAllViews()
        addTile(themed, grid, 0, dash.reelsCount.toLong(), R.string.yuz_stat_reels)
        addTile(themed, grid, 1, dash.moviesCount.toLong(), R.string.yuz_stat_movies)
        addTile(themed, grid, 2, dash.totalViews, R.string.yuz_stat_views)
        addTile(themed, grid, 3, dash.totalLikes, R.string.yuz_stat_likes)
        addTile(themed, grid, 4, dash.totalComments, R.string.yuz_stat_comments)
        addTile(themed, grid, 5, dash.uploadsToday.toLong(), R.string.yuz_stat_uploads_today)
        addTile(themed, grid, 6, dash.uploadsWeek.toLong(), R.string.yuz_stat_uploads_week)
        grid.rowCount = 4
    }

    private fun addTile(themed: Context, grid: GridLayout, index: Int, value: Long, labelRes: Int) {
        val tile = LayoutInflater.from(themed)
            .inflate(R.layout.item_admin_stat_tile, grid, false)
        val m = AndroidUtilities.dp(5f)
        tile.layoutParams = GridLayout.LayoutParams().apply {
            width = 0
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            rowSpec = GridLayout.spec(index / 2)
            columnSpec = GridLayout.spec(index % 2, 1f)
            setMargins(m, m, m, m)
        }
        tile.findViewById<TextView>(R.id.tvStatValue).text = formatCount(value)
        tile.findViewById<TextView>(R.id.tvStatLabel).text = getString(labelRes)
        grid.addView(tile)
    }

    private fun fillRecent(recent: List<Pair<String, Long>>) {
        val themed = themedContext ?: return
        val ll = llRecentUsers ?: return
        ll.removeAllViews()
        tvRecentTitle?.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        if (recent.isEmpty()) return
        val now = System.currentTimeMillis()
        val m = AndroidUtilities.dp(4f)
        for ((name, ts) in recent) {
            val row = TextView(themed).apply {
                text = "$name · ${agoLabel(ts, now)}"
                setTextColor(themed.resources.getColor(R.color.text_secondary))
                textSize = 13f
                setBackgroundResource(R.drawable.bg_profile_modern_card)
                setPadding(
                    AndroidUtilities.dp(12f), AndroidUtilities.dp(9f),
                    AndroidUtilities.dp(12f), AndroidUtilities.dp(9f)
                )
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, m, 0, m)
            ll.addView(row, lp)
        }
    }

    private fun agoLabel(ts: Long, now: Long): String {
        val ctx = themedContext ?: return ""
        val diff = (now - ts).coerceAtLeast(0)
        val mins = diff / 60_000L
        val hours = diff / 3_600_000L
        val days = diff / 86_400_000L
        return when {
            mins < 1 -> ctx.getString(R.string.yuz_admin_ago_now)
            mins < 60 -> ctx.getString(R.string.yuz_admin_ago_min, mins.toInt())
            hours < 24 -> ctx.getString(R.string.yuz_admin_ago_hour, hours.toInt())
            else -> ctx.getString(R.string.yuz_admin_ago_day, days.toInt())
        }
    }

    private fun formatCount(v: Long): String = when {
        v >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
        v >= 10_000 -> String.format(Locale.US, "%.1fK", v / 1_000.0)
        else -> v.toString()
    }

    // ── Kinolar CRUD ─────────────────────────────────────────────────────

    private fun loadMovies() {
        val s = scope ?: return
        progress?.visibility = View.VISIBLE
        empty?.visibility = View.GONE
        s.launch {
            val list = repo.listMovies()
            progress?.visibility = View.GONE
            if (fragmentView == null) return@launch
            movies = list
            adapter?.submit(list)
            empty?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showMovieDialog(existing: Movie?) {
        val themed = themedContext ?: return
        val view = LayoutInflater.from(themed).inflate(R.layout.dialog_kino_add, null)

        fun field(id: Int): TextInputEditText = view.findViewById(id)
        fun set(id: Int, value: String) { field(id).setText(value) }
        fun get(id: Int): String = field(id).text?.toString()?.trim().orEmpty()

        if (existing != null) {
            set(R.id.etMovieTitle, existing.title)
            set(R.id.etMovieUrl, existing.videoUrl)
            set(R.id.etMovieGenre, existing.genre)
            set(R.id.etMovieCategory, existing.category)
            set(R.id.etMovieYear, existing.year)
            set(R.id.etMovieDuration, existing.duration)
            set(R.id.etMovieRating, if (existing.rating > 0) existing.rating.toString() else "")
            set(R.id.etMovieImage, existing.imageUrl)
            set(R.id.etMovieBanner, existing.bannerUrl)
            set(R.id.etMovieDesc, existing.description)
        }
        view.findViewById<CheckBox>(R.id.cbAdult).isChecked = existing?.adult ?: true
        view.findViewById<CheckBox>(R.id.cbMultfilm).isChecked = existing?.isMultfilm ?: false
        view.findViewById<CheckBox>(R.id.cbPremium).isChecked = existing?.isPremium ?: false

        val titleRes = if (existing == null) R.string.kino_dlg_title_add else R.string.kino_dlg_title_edit
        val dialog = AlertDialog.Builder(themed)
            .setTitle(titleRes)
            .setView(view)
            .setNegativeButton(R.string.kino_f_cancel, null)
            .setPositiveButton(R.string.kino_f_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = get(R.id.etMovieTitle)
                val video = get(R.id.etMovieUrl)
                if (title.isBlank() || video.isBlank()) {
                    toast(R.string.kino_f_required)
                    return@setOnClickListener
                }
                val movie = Movie(
                    id = existing?.id ?: System.currentTimeMillis().toString(),
                    title = title,
                    description = get(R.id.etMovieDesc),
                    genre = get(R.id.etMovieGenre),
                    category = get(R.id.etMovieCategory),
                    imageUrl = get(R.id.etMovieImage),
                    bannerUrl = get(R.id.etMovieBanner),
                    videoUrl = video,
                    year = get(R.id.etMovieYear),
                    duration = get(R.id.etMovieDuration),
                    rating = get(R.id.etMovieRating).toDoubleOrNull() ?: 0.0,
                    views = existing?.views ?: 0,
                    likes = existing?.likes ?: 0,
                    searchCount = existing?.searchCount ?: 0,
                    adult = view.findViewById<CheckBox>(R.id.cbAdult).isChecked,
                    isMultfilm = view.findViewById<CheckBox>(R.id.cbMultfilm).isChecked,
                    isPremium = view.findViewById<CheckBox>(R.id.cbPremium).isChecked,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    adNotificationText = existing?.adNotificationText.orEmpty(),
                    adNotificationSent = existing?.adNotificationSent ?: false
                )
                saveMovie(movie, dialog)
            }
        }
        dialog.show()
    }

    private fun saveMovie(movie: Movie, dialog: AlertDialog) {
        val s = scope ?: return
        s.launch {
            val result = repo.saveMovie(movie)
            if (fragmentView == null) return@launch
            if (result.isSuccess) {
                dialog.dismiss()
                toast(R.string.kino_admin_saved)
                loadMovies()
            } else {
                val msg = result.exceptionOrNull()?.message ?: "xato"
                toast(getString(R.string.kino_admin_save_failed, msg))
            }
        }
    }

    private fun confirmDelete(movie: Movie) {
        val themed = themedContext ?: return
        try {
            AlertDialog.Builder(themed)
                .setTitle(R.string.kino_admin_delete)
                .setMessage(getString(R.string.kino_admin_delete_confirm, movie.title))
                .setNegativeButton(R.string.kino_f_cancel, null)
                .setPositiveButton(R.string.kino_admin_delete) { _, _ -> deleteMovie(movie) }
                .show()
        } catch (_: Exception) {
            deleteMovie(movie)
        }
    }

    private fun deleteMovie(movie: Movie) {
        val s = scope ?: return
        s.launch {
            val result = repo.deleteMovie(movie.id)
            if (fragmentView == null) return@launch
            if (result.isSuccess) {
                toast(R.string.kino_admin_deleted)
                loadMovies()
            } else {
                toast(getString(R.string.kino_admin_save_failed, result.exceptionOrNull()?.message ?: "xato"))
            }
        }
    }

    // ── Reels admin ──────────────────────────────────────────────────────

    private fun loadReels() {
        val s = scope ?: return
        reelsLoadedOnce = true
        reelsProgress?.visibility = View.VISIBLE
        reelsEmpty?.visibility = View.GONE
        s.launch {
            val list = runCatching { ReelsRepository.getReels() }.getOrDefault(emptyList())
            reelsProgress?.visibility = View.GONE
            if (fragmentView == null) return@launch
            reelsAdapter?.submit(list)
            reelsEmpty?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDeleteReel(reel: Reel) {
        val themed = themedContext ?: return
        val title = reel.title.ifBlank { getString(R.string.yuz_admin_reel_untitled) }
        try {
            AlertDialog.Builder(themed)
                .setTitle(R.string.kino_admin_delete)
                .setMessage(getString(R.string.yuz_admin_reel_delete_confirm, title))
                .setNegativeButton(R.string.kino_f_cancel, null)
                .setPositiveButton(R.string.kino_admin_delete) { _, _ -> deleteReel(reel) }
                .show()
        } catch (_: Exception) {
            deleteReel(reel)
        }
    }

    private fun deleteReel(reel: Reel) {
        val s = scope ?: return
        s.launch {
            val result = runCatching { ReelsRepository.deleteReel(reel.id) }
            if (fragmentView == null) return@launch
            if (result.isSuccess) {
                toast(R.string.kino_admin_deleted)
                loadReels()
            } else {
                toast(getString(R.string.kino_admin_save_failed, result.exceptionOrNull()?.message ?: "xato"))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
    }

    override fun onFragmentDestroy() {
        rv?.adapter = null
        adapter = null
        rvReels?.adapter = null
        reelsAdapter = null
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }

    private fun toast(resId: Int) {
        val ctx = themedContext ?: context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        val ctx = parentActivity ?: context ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }

    private fun getString(resId: Int, vararg args: Any): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId, *args)
    }

    companion object {
        private const val TAB_DASH = 0
        private const val TAB_MOVIES = 1
        private const val TAB_REELS = 2
    }
}
