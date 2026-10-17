package uz.kodlikino.top4.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

/**
 * Kinolar bo'limi — Firestore `movies` kolleksiyasidan feed (READ public,
 * authsiz ham ishlaydi). Katak → [KinoDetailActivity]; tepadagi "Admin panel"
 * → [KinoAdminActivity] (login/parol orqali kino qo'shish/tahrirlash).
 */
class KinolarActivity : BaseFragment() {

    private var themedContext: Context? = null
    private var rv: RecyclerView? = null
    private var empty: TextView? = null
    private var progress: ProgressBar? = null
    private var adapter: KinolarAdapter? = null

    private var scope: CoroutineScope? = null
    private val repo = MoviesRepository()

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_kinolar, null) as ViewGroup
        fragmentView = root
        actionBar?.visibility = View.GONE

        val topBar = root.findViewById<View>(R.id.kinoTopBar)
        topBar?.setPadding(
            topBar.paddingLeft, AndroidUtilities.statusBarHeight,
            topBar.paddingRight, topBar.paddingBottom
        )
        root.findViewById<View>(R.id.btnKinoBack)?.setOnClickListener { removeSelfFromStack() }
        root.findViewById<View>(R.id.btnKinoAdmin)?.setOnClickListener {
            presentFragment(KinoAdminActivity(), false)
        }

        rv = root.findViewById(R.id.rvKinolars)
        empty = root.findViewById(R.id.tvKinoEmpty)
        progress = root.findViewById(R.id.progressKino)

        adapter = KinolarAdapter(themed, emptyList()) { movie ->
            presentFragment(KinoDetailActivity(movie), false)
        }
        rv?.layoutManager = GridLayoutManager(themed, 2)
        rv?.adapter = adapter

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        loadMovies()
        return root
    }

    private fun loadMovies() {
        val s = scope ?: return
        progress?.visibility = View.VISIBLE
        empty?.visibility = View.GONE
        s.launch {
            val list = repo.listMovies()
            progress?.visibility = View.GONE
            if (fragmentView == null) return@launch
            adapter?.submit(list)
            empty?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
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

    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }
}
