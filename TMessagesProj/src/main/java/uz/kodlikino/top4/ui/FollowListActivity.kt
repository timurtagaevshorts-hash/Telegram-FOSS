package uz.kodlikino.top4.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.FollowUserItem
import uz.kodlikino.top4.utils.FollowManager

/**
 * Obunachilar / obunalar ro'yxati. Bir fragment ikkala rejimni ham ishlatadi:
 * [followers] = true → [FollowManager.fetchFollowers], false → fetchFollowing.
 * Elementni bosish o'sha foydalanuvchining [YuzGramProfileActivity] profilini ochadi.
 */
class FollowListActivity(
    private val uid: String,
    private val followers: Boolean
) : BaseFragment() {

    private var themedContext: Context? = null
    private var list: RecyclerView? = null
    private var emptyView: TextView? = null
    private var adapter: FollowListAdapter? = null
    private var scope: CoroutineScope? = null

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_follow_list, null) as ViewGroup
        fragmentView = root
        actionBar?.visibility = View.GONE

        val topBar = root.findViewById<View>(R.id.followListTopBar)
        topBar?.setPadding(
            topBar.paddingLeft, AndroidUtilities.statusBarHeight,
            topBar.paddingRight, topBar.paddingBottom
        )
        root.findViewById<View>(R.id.btnFollowListBack)?.setOnClickListener { removeSelfFromStack() }
        root.findViewById<TextView>(R.id.tvFollowListTitle)?.text = getString(
            if (followers) R.string.profile_followers_title else R.string.profile_following_title
        )

        list = root.findViewById(R.id.rvFollowList)
        emptyView = root.findViewById(R.id.emptyFollowList)

        val rows = FollowListAdapter(themed) { item -> openProfile(item) }
        adapter = rows
        val lm = LinearLayoutManager(themed, RecyclerView.VERTICAL, false)
        list?.layoutManager = lm
        list?.adapter = rows

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        load()
        return root
    }

    private fun load() {
        val s = scope ?: return
        s.launch {
            val data = if (followers) FollowManager.fetchFollowers(uid)
            else FollowManager.fetchFollowing(uid)
            if (fragmentView == null) return@launch
            adapter?.submit(data)
            emptyView?.visibility = if (data.isEmpty()) View.VISIBLE else View.GONE
            list?.visibility = if (data.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun openProfile(item: FollowUserItem) {
        if (item.uid.isBlank()) return
        presentFragment(
            YuzGramProfileActivity(
                uid = item.uid,
                seedName = item.name,
                seedPhotoUrl = item.photoUrl,
                isSelf = false,
                knownYuzGram = true
            ),
            false
        )
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
    }

    override fun onFragmentDestroy() {
        scope?.cancel()
        scope = null
        adapter = null
        list = null
        emptyView = null
        super.onFragmentDestroy()
    }

    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }
}
