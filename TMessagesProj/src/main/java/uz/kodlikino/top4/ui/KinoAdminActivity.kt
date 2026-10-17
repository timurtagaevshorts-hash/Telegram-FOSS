package uz.kodlikino.top4.ui

import android.app.AlertDialog
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
import uz.kodlikino.top4.utils.KinoAdminAuth
import uz.kodlikino.top4.utils.MoviesRepository

/**
 * Kinolar admin paneli — eski KinoTOP4 login/paroli bilan (deploy qilingan
 * `claimAdminSession` Cloud Function'i orqali). Muvaffaqiyatli kirishdan so'ng
 * kino qo'shish / tahrirlash / o'chirish (`movies` kolleksiyasi, isAdminUser()).
 */
class KinoAdminActivity : BaseFragment() {

    private var themedContext: Context? = null
    private var loginPanel: View? = null
    private var managePanel: View? = null
    private var etUser: TextInputEditText? = null
    private var etPass: TextInputEditText? = null
    private var loginProgress: ProgressBar? = null
    private var btnLogin: MaterialButton? = null
    private var rv: RecyclerView? = null
    private var adapter: KinoAdminAdapter? = null
    private var empty: TextView? = null
    private var progress: ProgressBar? = null

    private var scope: CoroutineScope? = null
    private val repo = MoviesRepository()
    private var adminName = ""
    private var movies: List<Movie> = emptyList()
    private var loginBusy = false

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_kino_admin, null) as ViewGroup
        fragmentView = root
        actionBar?.visibility = View.GONE

        val topBar = root.findViewById<View>(R.id.kinoAdminTopBar)
        topBar?.setPadding(
            topBar.paddingLeft, AndroidUtilities.statusBarHeight,
            topBar.paddingRight, topBar.paddingBottom
        )
        root.findViewById<View>(R.id.btnKinoAdminBack)?.setOnClickListener { removeSelfFromStack() }

        loginPanel = root.findViewById(R.id.loginPanel)
        managePanel = root.findViewById(R.id.managePanel)
        etUser = root.findViewById(R.id.etKinoAdminUser)
        etPass = root.findViewById(R.id.etKinoAdminPass)
        loginProgress = root.findViewById(R.id.kinoLoginProgress)
        btnLogin = root.findViewById(R.id.btnKinoAdminLogin)
        rv = root.findViewById(R.id.rvAdminMovies)
        empty = root.findViewById(R.id.tvAdminEmpty)
        progress = root.findViewById(R.id.progressAdminMovies)

        adapter = KinoAdminAdapter(
            themed, emptyList(),
            onEdit = { showMovieDialog(it) },
            onDelete = { confirmDelete(it) }
        )
        rv?.layoutManager = LinearLayoutManager(themed)
        rv?.adapter = adapter

        btnLogin?.setOnClickListener { doLogin() }
        root.findViewById<FloatingActionButton>(R.id.btnAddKino)?.setOnClickListener {
            showMovieDialog(null)
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        return root
    }

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
                adminName = user
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
        loadMovies()
    }

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

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
    }

    override fun onFragmentDestroy() {
        rv?.adapter = null
        adapter = null
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
}
