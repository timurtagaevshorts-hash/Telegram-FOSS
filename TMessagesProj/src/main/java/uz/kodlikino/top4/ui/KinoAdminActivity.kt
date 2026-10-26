package uz.kodlikino.top4.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.AdBanner
import uz.kodlikino.top4.models.AdModeration
import uz.kodlikino.top4.models.ClassifiedAd
import uz.kodlikino.top4.models.Movie
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.utils.AdBannerStorageHelper
import uz.kodlikino.top4.utils.ClassifiedAdRepository
import uz.kodlikino.top4.utils.KinoAdminAuth
import uz.kodlikino.top4.utils.MoviesRepository
import uz.kodlikino.top4.utils.ReelsRepository
import uz.kodlikino.top4.utils.YuzAdminStats
import uz.kodlikino.top4.utils.YuzAdModeration
import uz.kodlikino.top4.utils.YuzAdStore
import uz.kodlikino.top4.utils.YuzReelQuota
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * YuzGram admin paneli (#77) — eski KinoTOP4 login/paroli (claimAdminSession) bilan
 * kiriladi; muvaffaqiyatli kirish `admins/{uid}` hujjatini yozadi, shuning uchun
 * Firestore rule'lari (isAdminUser()) bo'yicha real admin huquqi paydo bo'ladi.
 *
 * Besh tab:
 *  - Dashboard — haqiqiy statistika (comment_profiles.lastActiveAt/createdAt/isPremium,
 *    reels yig'indilari, movies soni) — [YuzAdminStats];
 *  - Kinolar — movies CRUD;
 *  - Reels — barcha joylangan reels ro'yxati + admin o'chirishi;
 *  - Limitlar — oddiy/premium uchun kunlik reel limiti va video hajmi
 *    (`settings/app`, [YuzReelQuota]);
 *  - Reklama — `ad_banners` CRUD: chatlar va Funksiyalar tepasidagi bannerlar
 *    ([YuzAdStore]).
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
    private var btnTabLimits: TextView? = null
    private var btnTabAds: TextView? = null
    private var btnTabModeration: TextView? = null
    private var dashPanel: View? = null
    private var moviesPanel: View? = null
    private var reelsPanel: View? = null
    private var limitsPanel: View? = null
    private var adsPanel: View? = null
    private var moderationPanel: View? = null

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

    private var etLimitRegularDaily: TextInputEditText? = null
    private var etLimitPremiumDaily: TextInputEditText? = null
    private var etLimitRegularMb: TextInputEditText? = null
    private var etLimitPremiumMb: TextInputEditText? = null
    private var progressLimits: ProgressBar? = null
    private var tvLimitsUpdated: TextView? = null
    private var btnSaveLimits: MaterialButton? = null
    private var limitsLoadedOnce = false
    private var limitsBusy = false

    private var rvAds: RecyclerView? = null
    private var adsAdapter: AdsAdminAdapter? = null
    private var adsEmpty: TextView? = null
    private var adsProgress: ProgressBar? = null
    private var adsLoadedOnce = false
    private var ads: List<AdBanner> = emptyList()

    private var rvModeration: RecyclerView? = null
    private var moderationAdapter: ModerationAdminAdapter? = null
    private var moderationEmpty: TextView? = null
    private var moderationProgress: ProgressBar? = null
    private var tvModerationPending: TextView? = null
    private var tvModerationUpdated: TextView? = null
    private var swModerationAuto: SwitchMaterial? = null
    private var moderationLoadedOnce = false
    private var moderationBusy = false
    private var moderationAutoBusy = false
    private var suppressModerationSwitch = false
    private var moderationItems: List<ClassifiedAd> = emptyList()

    private var adPendingImage: Uri? = null
    private var adDialogPreview: YuzAdBannerView? = null
    private var adDialogStatus: TextView? = null
    private var adDialogPick: MaterialButton? = null
    private var adDialogRefreshImageUi: ((Boolean) -> Unit)? = null
    private var adDialogBusy = false
    private var adUploading = false
    private var adSaveJob: Job? = null

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
        btnTabLimits = root.findViewById(R.id.btnTabLimits)
        btnTabAds = root.findViewById(R.id.btnTabAds)
        btnTabModeration = root.findViewById(R.id.btnTabModeration)
        dashPanel = root.findViewById(R.id.dashPanel)
        moviesPanel = root.findViewById(R.id.moviesPanel)
        reelsPanel = root.findViewById(R.id.reelsPanel)
        limitsPanel = root.findViewById(R.id.limitsPanel)
        adsPanel = root.findViewById(R.id.adsPanel)
        moderationPanel = root.findViewById(R.id.moderationPanel)

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

        etLimitRegularDaily = root.findViewById(R.id.etAdminLimitRegularDaily)
        etLimitPremiumDaily = root.findViewById(R.id.etAdminLimitPremiumDaily)
        etLimitRegularMb = root.findViewById(R.id.etAdminLimitRegularMb)
        etLimitPremiumMb = root.findViewById(R.id.etAdminLimitPremiumMb)
        progressLimits = root.findViewById(R.id.progressLimits)
        tvLimitsUpdated = root.findViewById(R.id.tvLimitsUpdated)
        btnSaveLimits = root.findViewById(R.id.btnSaveLimits)

        rvAds = root.findViewById(R.id.rvAdminAds)
        adsEmpty = root.findViewById(R.id.tvAdminAdsEmpty)
        adsProgress = root.findViewById(R.id.progressAdminAds)

        rvModeration = root.findViewById(R.id.rvAdminModeration)
        moderationEmpty = root.findViewById(R.id.tvAdminModerationEmpty)
        moderationProgress = root.findViewById(R.id.progressAdminModeration)
        tvModerationPending = root.findViewById(R.id.tvModerationPending)
        tvModerationUpdated = root.findViewById(R.id.tvModerationUpdated)
        swModerationAuto = root.findViewById(R.id.swAdminModerationAuto)

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

        adsAdapter = AdsAdminAdapter(
            themed, emptyList(),
            onEdit = { showAdDialog(it) },
            onDelete = { confirmDeleteAd(it) }
        )
        rvAds?.layoutManager = LinearLayoutManager(themed)
        rvAds?.adapter = adsAdapter

        moderationAdapter = ModerationAdminAdapter(
            themed,
            onApprove = { moderateAd(it, true) },
            onReject = { moderateAd(it, false) }
        )
        rvModeration?.layoutManager = LinearLayoutManager(themed)
        rvModeration?.adapter = moderationAdapter

        btnLogin?.setOnClickListener { doLogin() }
        root.findViewById<FloatingActionButton>(R.id.btnAddKino)?.setOnClickListener {
            showMovieDialog(null)
        }
        root.findViewById<FloatingActionButton>(R.id.btnAddAd)?.setOnClickListener {
            showAdDialog(null)
        }
        btnSaveLimits?.setOnClickListener { saveLimits() }
        btnTabDash?.setOnClickListener { showTab(TAB_DASH) }
        btnTabMovies?.setOnClickListener { showTab(TAB_MOVIES) }
        btnTabReels?.setOnClickListener { showTab(TAB_REELS) }
        btnTabLimits?.setOnClickListener { showTab(TAB_LIMITS) }
        btnTabAds?.setOnClickListener { showTab(TAB_ADS) }
        btnTabModeration?.setOnClickListener { showTab(TAB_MODERATION) }
        btnRefreshStats?.setOnClickListener { loadDashboard() }

        swModerationAuto?.setOnCheckedChangeListener { _, checked ->
            if (suppressModerationSwitch) return@setOnCheckedChangeListener
            saveModerationAuto(!checked)
        }

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
        dashPanel?.visibility = if (tab == TAB_DASH) View.VISIBLE else View.GONE
        moviesPanel?.visibility = if (tab == TAB_MOVIES) View.VISIBLE else View.GONE
        reelsPanel?.visibility = if (tab == TAB_REELS) View.VISIBLE else View.GONE
        limitsPanel?.visibility = if (tab == TAB_LIMITS) View.VISIBLE else View.GONE
        adsPanel?.visibility = if (tab == TAB_ADS) View.VISIBLE else View.GONE
        moderationPanel?.visibility = if (tab == TAB_MODERATION) View.VISIBLE else View.GONE
        styleTab(btnTabDash, tab == TAB_DASH)
        styleTab(btnTabMovies, tab == TAB_MOVIES)
        styleTab(btnTabReels, tab == TAB_REELS)
        styleTab(btnTabLimits, tab == TAB_LIMITS)
        styleTab(btnTabAds, tab == TAB_ADS)
        styleTab(btnTabModeration, tab == TAB_MODERATION)
        if (tab == TAB_MOVIES && adapter?.itemCount == 0) loadMovies()
        if (tab == TAB_REELS && !reelsLoadedOnce) loadReels()
        if (tab == TAB_LIMITS && !limitsLoadedOnce) loadLimits()
        if (tab == TAB_ADS && !adsLoadedOnce) loadAds()
        if (tab == TAB_MODERATION && !moderationLoadedOnce) loadModeration()
    }

    private fun styleTab(chip: TextView?, active: Boolean) {
        val themed = themedContext ?: return
        chip ?: return
        chip.setBackgroundResource(
            if (active) R.drawable.bg_yuz_pill_active else R.drawable.bg_yuz_pill
        )
        chip.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                themed,
                if (active) R.color.yuz_text_on_accent else R.color.yuz_text_secondary
            )
        )
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
                setTextColor(ContextCompat.getColor(themed, R.color.yuz_text_secondary))
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
            // #120: dialog ko'rsatib bo'lmasa, tasdiqsiz O'CHIRISH xavfli —
            // beep va fail toast, delete emas.
            toast(R.string.kino_admin_delete_dlg_failed)
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
            // #120: tasdiq dialogi ochilmasa — reel o'chirilmaydi (yuqoridagi kabi)
            toast(R.string.kino_admin_delete_dlg_failed)
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

    // ── Reels limitlari ──────────────────────────────────────────────────

    private fun loadLimits() {
        val s = scope ?: return
        limitsBusy = true
        progressLimits?.visibility = View.VISIBLE
        tvLimitsUpdated?.text = ""
        btnSaveLimits?.isEnabled = false
        s.launch {
            val result = YuzReelQuota.adminLoadLimits()
            if (fragmentView == null) return@launch
            limitsBusy = false
            progressLimits?.visibility = View.GONE
            btnSaveLimits?.isEnabled = true
            val limits = result.getOrNull()
            if (limits == null) {
                // Xato bo'lsa tab qayta ochilganda yana urinamiz.
                limitsLoadedOnce = false
                fillLimits(YuzReelQuota.cachedLimits())
                toast(R.string.yuz_admin_limits_load_failed)
            } else {
                limitsLoadedOnce = true
                fillLimits(limits)
                tvLimitsUpdated?.text = getString(
                    R.string.yuz_admin_limits_updated, timeFormat.format(Date())
                )
            }
        }
    }

    private fun fillLimits(limits: YuzReelQuota.ReelLimits) {
        etLimitRegularDaily?.setText(limits.regularDaily.toString())
        etLimitPremiumDaily?.setText(limits.premiumDaily.toString())
        etLimitRegularMb?.setText(limits.regularMaxMb.toString())
        etLimitPremiumMb?.setText(limits.premiumMaxMb.toString())
    }

    private fun saveLimits() {
        if (limitsBusy) return
        val regularDaily = etLimitRegularDaily?.text?.toString()?.trim()?.toIntOrNull()
        val premiumDaily = etLimitPremiumDaily?.text?.toString()?.trim()?.toIntOrNull()
        val regularMb = etLimitRegularMb?.text?.toString()?.trim()?.toIntOrNull()
        val premiumMb = etLimitPremiumMb?.text?.toString()?.trim()?.toIntOrNull()
        if (regularDaily == null || premiumDaily == null ||
            regularMb == null || premiumMb == null ||
            regularDaily < 0 || premiumDaily < 0 ||
            regularMb !in 1..1024 || premiumMb !in 1..1024
        ) {
            toast(R.string.yuz_admin_limits_invalid)
            return
        }
        val limits = YuzReelQuota.ReelLimits(
            regularDaily = regularDaily,
            premiumDaily = premiumDaily,
            regularMaxMb = regularMb,
            premiumMaxMb = premiumMb
        )
        val s = scope ?: return
        limitsBusy = true
        progressLimits?.visibility = View.VISIBLE
        btnSaveLimits?.isEnabled = false
        s.launch {
            val result = YuzReelQuota.saveLimits(limits)
            if (fragmentView == null) return@launch
            limitsBusy = false
            progressLimits?.visibility = View.GONE
            btnSaveLimits?.isEnabled = true
            if (result.isSuccess) {
                // Serverda saqlangan (sanitize qilingan) qiymatlarni qayta ko'rsatamiz —
                // maydonlar bilan haqiqiy holat ajralib qolmasin.
                result.getOrNull()?.let { fillLimits(it) }
                toast(R.string.yuz_admin_limits_saved)
                tvLimitsUpdated?.text = getString(
                    R.string.yuz_admin_limits_updated, timeFormat.format(Date())
                )
            } else {
                toast(
                    getString(
                        R.string.yuz_admin_limits_save_failed,
                        result.exceptionOrNull()?.message ?: "xato"
                    )
                )
            }
        }
    }

    // ── Reklama CRUD ─────────────────────────────────────────────────────

    private fun loadAds() {
        val s = scope ?: return
        adsLoadedOnce = true
        adsProgress?.visibility = View.VISIBLE
        adsEmpty?.visibility = View.GONE
        s.launch {
            val result = YuzAdStore.loadAll()
            if (fragmentView == null) return@launch
            adsProgress?.visibility = View.GONE
            if (result.isFailure) {
                toast(R.string.yuz_admin_ad_load_failed)
                adsEmpty?.visibility = if (ads.isEmpty()) View.VISIBLE else View.GONE
                return@launch
            }
            ads = result.getOrDefault(emptyList())
            adsAdapter?.submit(ads)
            adsEmpty?.visibility = if (ads.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ── E'lonlar moderatsiyasi ───────────────────────────────────────────

    private fun moderationRank(ad: ClassifiedAd): Int =
        if (AdModeration.normalize(ad.status) == AdModeration.PENDING) 0 else 1

    private fun loadModeration() {
        if (moderationBusy) return
        val s = scope ?: return
        moderationBusy = true
        moderationLoadedOnce = true
        moderationProgress?.visibility = View.VISIBLE
        moderationEmpty?.visibility = View.GONE
        s.launch {
            val settings = YuzAdModeration.adminLoad()
            val listResult = ClassifiedAdRepository.adminLoadAll()
            moderationBusy = false
            if (fragmentView == null) return@launch
            moderationProgress?.visibility = View.GONE
            settings.onSuccess { applyModerationAuto(!it) }
            if (listResult.isFailure) {
                toast(R.string.yuz_admin_moderation_load_failed)
            }
            moderationItems = listResult.getOrDefault(emptyList()).sortedWith(
                compareBy({ moderationRank(it) }, { -it.createdAt })
            )
            moderationAdapter?.submit(moderationItems)
            moderationEmpty?.visibility =
                if (moderationItems.isEmpty()) View.VISIBLE else View.GONE
            updateModerationHeader()
            tvModerationUpdated?.text =
                getString(R.string.yuz_admin_moderation_updated, timeFormat.format(Date()))
            tvModerationUpdated?.visibility = View.VISIBLE
        }
    }

    /** Switch — "tekshiruvsiz joylash"; saqlanadigan bayroq uning teskarisi. */
    private fun applyModerationAuto(auto: Boolean) {
        suppressModerationSwitch = true
        swModerationAuto?.isChecked = auto
        suppressModerationSwitch = false
    }

    private fun saveModerationAuto(auto: Boolean) {
        if (moderationAutoBusy) return
        val s = scope ?: return
        moderationAutoBusy = true
        swModerationAuto?.isEnabled = false
        s.launch {
            val result = YuzAdModeration.adminSave(!auto)
            moderationAutoBusy = false
            if (fragmentView == null) return@launch
            swModerationAuto?.isEnabled = true
            if (result.isSuccess) {
                toast(R.string.yuz_admin_moderation_auto_saved)
            } else {
                val reason = result.exceptionOrNull()?.message ?: "xato"
                toast(getString(R.string.yuz_admin_moderation_auto_failed, reason))
                applyModerationAuto(!auto)
            }
        }
    }

    private fun updateModerationHeader() {
        val pending = moderationItems.count {
            AdModeration.normalize(it.status) == AdModeration.PENDING
        }
        tvModerationPending?.text =
            getString(R.string.yuz_admin_moderation_pending_count, pending)
    }

    private fun moderateAd(ad: ClassifiedAd, approved: Boolean) {
        val s = scope ?: return
        s.launch {
            val result = ClassifiedAdRepository.moderate(ad.id, approved)
            if (fragmentView == null) return@launch
            if (result.isSuccess) {
                toast(
                    if (approved) {
                        R.string.yuz_admin_moderation_approved
                    } else {
                        R.string.yuz_admin_moderation_rejected
                    }
                )
                updateModerationItem(ad.id, approved)
            } else {
                val reason = result.exceptionOrNull()?.message ?: "xato"
                toast(getString(R.string.yuz_admin_moderation_failed, reason))
            }
        }
    }

    /** Ro'yxatni qayta yuklamasdan holatni joyida yangilaydi (teskari almashinuv). */
    private fun updateModerationItem(id: String, approved: Boolean) {
        moderationItems = moderationItems.map { ad ->
            if (ad.id != id) {
                ad
            } else {
                ad.copy(
                    status = if (approved) AdModeration.APPROVED else AdModeration.REJECTED,
                    isActive = approved,
                    moderatedAt = System.currentTimeMillis()
                )
            }
        }.sortedWith(compareBy({ moderationRank(it) }, { -it.createdAt }))
        moderationAdapter?.submit(moderationItems)
        updateModerationHeader()
        moderationEmpty?.visibility =
            if (moderationItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAdDialog(existing: AdBanner?) {
        val themed = themedContext ?: return
        val view = LayoutInflater.from(themed).inflate(R.layout.dialog_admin_ad, null)
        val etTitle = view.findViewById<TextInputEditText>(R.id.etAdTitle)
        val etSubtitle = view.findViewById<TextInputEditText>(R.id.etAdSubtitle)
        val etImage = view.findViewById<TextInputEditText>(R.id.etAdImage)
        val etLink = view.findViewById<TextInputEditText>(R.id.etAdLink)
        val cbChats = view.findViewById<CheckBox>(R.id.cbAdChats)
        val cbFunctions = view.findViewById<CheckBox>(R.id.cbAdFunctions)
        val cbActive = view.findViewById<CheckBox>(R.id.cbAdActive)
        val themeRow = view.findViewById<LinearLayout>(R.id.adThemeRow)
        val previewSlot = view.findViewById<FrameLayout>(R.id.adPreviewSlot)
        val pickButton = view.findViewById<MaterialButton>(R.id.btnAdPickImage)
        val thumb = view.findViewById<ImageView>(R.id.ivAdImageThumb)
        val imageStatus = view.findViewById<TextView>(R.id.tvAdImageStatus)
        val clearButton = view.findViewById<TextView>(R.id.btnAdClearImage)

        val preview = YuzAdBannerView(themed)
        previewSlot.addView(
            preview,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        adPendingImage = null
        adDialogBusy = false
        adUploading = false
        adDialogPreview = preview
        adDialogStatus = imageStatus
        adDialogPick = pickButton

        var selectedTheme = existing?.theme
            ?.takeIf { key -> YuzAdStore.THEMES.any { it.key == key } }
            ?: YuzAdStore.THEMES[0].key
        val swatches = HashMap<String, View>()

        fun currentBanner(): AdBanner = AdBanner(
            id = existing?.id.orEmpty(),
            title = etTitle.text?.toString()?.trim().orEmpty(),
            subtitle = etSubtitle.text?.toString()?.trim().orEmpty(),
            imageUrl = etImage.text?.toString()?.trim().orEmpty(),
            linkUrl = etLink.text?.toString()?.trim().orEmpty(),
            theme = selectedTheme,
            showInChats = cbChats.isChecked,
            showInFunctions = cbFunctions.isChecked,
            isActive = cbActive.isChecked,
            createdAt = existing?.createdAt ?: 0L,
            updatedAt = System.currentTimeMillis()
        )

        fun updatePreview() = preview.bind(currentBanner())

        fun renderSwatches() {
            for ((key, swatch) in swatches) {
                val t = YuzAdStore.theme(key)
                val selected = key == selectedTheme
                swatch.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = AndroidUtilities.dp(11f).toFloat()
                    setColor(t.start)
                    if (selected) setStroke(AndroidUtilities.dp(2.5f), Color.WHITE)
                }
                swatch.scaleX = if (selected) 1.12f else 1f
                swatch.scaleY = if (selected) 1.12f else 1f
            }
        }

        for (t in YuzAdStore.THEMES) {
            val swatch = View(themed)
            swatch.contentDescription = themed.getString(t.labelRes)
            val lp = LinearLayout.LayoutParams(
                AndroidUtilities.dp(34f), AndroidUtilities.dp(34f)
            )
            lp.marginEnd = AndroidUtilities.dp(10f)
            swatch.setOnClickListener {
                selectedTheme = t.key
                renderSwatches()
                updatePreview()
            }
            swatches[t.key] = swatch
            themeRow.addView(swatch, lp)
        }
        renderSwatches()

        if (existing != null) {
            etTitle.setText(existing.title)
            etSubtitle.setText(existing.subtitle)
            etImage.setText(existing.imageUrl)
            etLink.setText(existing.linkUrl)
            cbChats.isChecked = existing.showInChats
            cbFunctions.isChecked = existing.showInFunctions
            cbActive.isChecked = existing.isActive
        } else {
            cbChats.isChecked = true
            cbActive.isChecked = true
        }
        updatePreview()

        fun refreshImageUi(loadThumb: Boolean) {
            val picked = adPendingImage
            val remote = etImage.text?.toString()?.trim().orEmpty()
            val source: Any? = picked ?: remote.takeIf { it.isNotBlank() }
            if (source == null) {
                thumb.visibility = View.GONE
                try {
                    Glide.with(thumb).clear(thumb)
                } catch (_: Exception) {
                }
            } else if (loadThumb) {
                thumb.visibility = View.VISIBLE
                try {
                    Glide.with(thumb).load(source).centerCrop().into(thumb)
                } catch (_: Exception) {
                    thumb.visibility = View.GONE
                }
            }
            imageStatus.text = when {
                picked != null -> getString(R.string.yuz_admin_ad_image_selected)
                remote.isNotBlank() -> getString(R.string.yuz_admin_ad_image_remote)
                else -> getString(R.string.yuz_admin_ad_image_none)
            }
            clearButton.visibility =
                if (picked != null || remote.isNotBlank()) View.VISIBLE else View.GONE
            pickButton.text = getString(
                if (source != null) R.string.yuz_admin_ad_image_change
                else R.string.yuz_admin_ad_image_pick
            )
        }
        adDialogRefreshImageUi = { loadThumb -> refreshImageUi(loadThumb) }
        refreshImageUi(true)

        pickButton.setOnClickListener { pickAdImage() }
        clearButton.setOnClickListener {
            adPendingImage = null
            etImage.setText("")
            preview.showLocalImage(null)
            refreshImageUi(false)
            updatePreview()
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePreview()
                adDialogRefreshImageUi?.invoke(false)
            }
        }
        etTitle.addTextChangedListener(watcher)
        etSubtitle.addTextChangedListener(watcher)
        etImage.addTextChangedListener(watcher)
        etLink.addTextChangedListener(watcher)

        val titleRes =
            if (existing == null) R.string.yuz_admin_ad_dlg_add else R.string.yuz_admin_ad_dlg_edit
        val dialog = AlertDialog.Builder(themed)
            .setTitle(titleRes)
            .setView(view)
            .setNegativeButton(R.string.kino_f_cancel, null)
            .setPositiveButton(R.string.kino_f_save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val banner = currentBanner()
                if (banner.title.isBlank() || banner.linkUrl.isBlank()) {
                    toast(R.string.yuz_admin_ad_required)
                    return@setOnClickListener
                }
                if (!banner.linkUrl.startsWith("http://") &&
                    !banner.linkUrl.startsWith("https://")
                ) {
                    toast(R.string.yuz_admin_ad_link_invalid)
                    return@setOnClickListener
                }
                if (banner.imageUrl.isNotBlank() &&
                    !banner.imageUrl.startsWith("http://") &&
                    !banner.imageUrl.startsWith("https://")
                ) {
                    toast(R.string.yuz_admin_ad_image_invalid)
                    return@setOnClickListener
                }
                if (!banner.showInChats && !banner.showInFunctions) {
                    toast(R.string.yuz_admin_ad_place_none)
                    return@setOnClickListener
                }
                saveAd(banner, dialog, existing?.imageUrl.orEmpty())
            }
        }
        dialog.setOnDismissListener {
            if (adUploading) adSaveJob?.cancel()
            adSaveJob = null
            adPendingImage = null
            adDialogPreview = null
            adDialogStatus = null
            adDialogPick = null
            adDialogRefreshImageUi = null
            adDialogBusy = false
            adUploading = false
        }
        dialog.show()
    }

    private fun pickAdImage() {
        if (adDialogBusy || adDialogPreview == null) return
        val activity = parentActivity ?: return
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivityForResult(
                Intent.createChooser(intent, getString(R.string.yuz_admin_ad_image_pick_title)),
                REQ_PICK_AD_IMAGE
            )
        } catch (e: Exception) {
            toast(R.string.yuz_admin_ad_image_pick_failed)
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_AD_IMAGE) return
        if (resultCode != Activity.RESULT_OK || data == null) return
        val uri = data.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            ?: data.data
            ?: return
        if (adDialogPreview == null) return
        adPendingImage = uri
        adDialogPreview?.showLocalImage(uri)
        adDialogRefreshImageUi?.invoke(true)
    }

    /**
     * Saqlash: agar admin lokal surat tanlagan bo'lsa, avval uni Storage'ga
     * yuklaydi va `imageUrl`ni download URL bilan almashtiradi. Yuklash
     * muvaffaqiyatsiz bo'lsa reklama saqlanmaydi — admin xatoni ko'radi.
     */
    private fun saveAd(banner: AdBanner, dialog: AlertDialog, previousImageUrl: String) {
        val s = scope ?: return
        if (adDialogBusy) return
        val pending = adPendingImage
        val appContext = (themedContext ?: context ?: return).applicationContext
        adDialogBusy = true
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        positive?.isEnabled = false
        negative?.isEnabled = false
        adDialogPick?.isEnabled = false

        fun restoreButtons() {
            adDialogBusy = false
            positive?.isEnabled = true
            negative?.isEnabled = true
            adDialogPick?.isEnabled = true
            adDialogRefreshImageUi?.invoke(false)
        }

        adSaveJob = s.launch {
            var finalBanner = banner
            if (pending != null) {
                adUploading = true
                val upload = AdBannerStorageHelper.uploadImage(appContext, pending) { pct ->
                    AndroidUtilities.runOnUIThread {
                        adDialogStatus?.text =
                            getString(R.string.yuz_admin_ad_image_uploading, pct)
                    }
                }
                adUploading = false
                val uploadedUrl = upload.getOrNull().orEmpty()
                if (upload.isFailure || uploadedUrl.isBlank()) {
                    if (fragmentView == null) return@launch
                    restoreButtons()
                    val msg = upload.exceptionOrNull()?.message ?: "xato"
                    toast(getString(R.string.yuz_admin_ad_image_upload_failed, msg))
                    return@launch
                }
                finalBanner = banner.copy(imageUrl = uploadedUrl)
            }
            val result = YuzAdStore.save(finalBanner)
            if (fragmentView == null) return@launch
            if (result.isSuccess) {
                dialog.dismiss()
                toast(R.string.yuz_admin_ad_saved)
                loadAds()
                if (previousImageUrl.isNotBlank() &&
                    previousImageUrl != finalBanner.imageUrl
                ) {
                    AdBannerStorageHelper.deleteByUrl(previousImageUrl)
                }
            } else {
                restoreButtons()
                if (finalBanner.imageUrl != banner.imageUrl) {
                    AdBannerStorageHelper.deleteByUrl(finalBanner.imageUrl)
                }
                toast(
                    getString(
                        R.string.yuz_admin_ad_save_failed,
                        result.exceptionOrNull()?.message ?: "xato"
                    )
                )
            }
        }
    }

    private fun confirmDeleteAd(ad: AdBanner) {
        val themed = themedContext ?: return
        val title = ad.title.ifBlank { getString(R.string.yuz_admin_ad_untitled) }
        try {
            AlertDialog.Builder(themed)
                .setTitle(R.string.kino_admin_delete)
                .setMessage(getString(R.string.yuz_admin_ad_delete_confirm, title))
                .setNegativeButton(R.string.kino_f_cancel, null)
                .setPositiveButton(R.string.kino_admin_delete) { _, _ -> deleteAd(ad) }
                .show()
        } catch (_: Exception) {
            toast(R.string.kino_admin_delete_dlg_failed)
        }
    }

    private fun deleteAd(ad: AdBanner) {
        val s = scope ?: return
        s.launch {
            val result = YuzAdStore.delete(ad.id)
            if (fragmentView == null) return@launch
            if (result.isSuccess) {
                toast(R.string.kino_admin_deleted)
                loadAds()
                if (ad.imageUrl.isNotBlank()) {
                    AdBannerStorageHelper.deleteByUrl(ad.imageUrl)
                }
            } else {
                toast(
                    getString(
                        R.string.yuz_admin_ad_save_failed,
                        result.exceptionOrNull()?.message ?: "xato"
                    )
                )
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
        rvAds?.adapter = null
        adsAdapter = null
        rvModeration?.adapter = null
        moderationAdapter = null
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
        private const val TAB_LIMITS = 3
        private const val TAB_ADS = 4
        private const val TAB_MODERATION = 5
        private const val REQ_PICK_AD_IMAGE = 4473
    }
}
