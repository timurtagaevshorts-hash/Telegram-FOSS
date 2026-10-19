package uz.kodlikino.top4.ui

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
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
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.utils.ReelStorageHelper
import uz.kodlikino.top4.utils.ReelsRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth

/**
 * Native Telegram profilidagi "Reels" tab kontenti (arxiv postlar o'rniga).
 * [SharedMediaLayout] bu view'ni tab tanlanganda media sahifasi ustiga to'siq
 * (opaque) qatlam sifatida qo'yadi — foydalanuvchining o'z reel'lari 3 ustunli
 * grid'da ko'rinadi, katak bosilsa scoped [ReelsActivity] feed ochiladi.
 * [isSelf]=true (o'z profili) bo'lgandagina ✕ bilan o'chirish rejimi yonadi;
 * begona profilida faqat ko'rish.
 */
class YuzProfileReelsView(
    context: Context,
    private val account: Int,
    private val host: BaseFragment,
    targetUid: String,
    private val isSelf: Boolean
) : FrameLayout(context) {

    private val recyclerView: RecyclerView
    private val progressView: ProgressBar
    private val emptyView: TextView
    private val adapter: ProfileReelsAdapter

    private var scope: CoroutineScope? = null
    private var myUid: String = targetUid
    private var reels: List<Reel> = emptyList()
    private var loading = false

    init {
        // Media sahifalarni to'liq yopishi uchun noto'qiladigan (opaque) fon.
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))

        progressView = ProgressBar(context)
        progressView.visibility = View.GONE
        addView(progressView,
            LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER))

        emptyView = TextView(context)
        emptyView.text = context.getString(R.string.profile_reels_empty)
        emptyView.setTextColor(
            androidx.core.content.ContextCompat.getColor(context, R.color.yuz_text_secondary)
        )
        emptyView.textSize = 15f
        emptyView.gravity = Gravity.CENTER
        emptyView.visibility = View.GONE
        addView(emptyView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT.toFloat(), Gravity.LEFT or Gravity.TOP, 20f, 72f, 20f, 0f))

        adapter = ProfileReelsAdapter(context,
            onClick = { index -> openFeed(index) },
            onDelete = { reel -> confirmDelete(reel) })
        // O'chirish faqat o'z profilida; begona profilida grid view-only.
        adapter.manageMode = isSelf
        recyclerView = RecyclerView(context)
        recyclerView.layoutManager = GridLayoutManager(context, 3)
        recyclerView.adapter = adapter
        recyclerView.setPadding(0, AndroidUtilities.dp(2f), 0, 0)
        recyclerView.clipToPadding = false
        recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        addView(recyclerView,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat()))
    }

    /** Tab ko'rsatilganda — yangi/nimcha bo'lgan reel'larni qayta yuklaydi. */
    fun refresh() {
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        load()
    }

    private fun openFeed(index: Int) {
        if (host.parentActivity == null || myUid.isBlank()) return
        host.presentFragment(ReelsActivity(myUid, index), false)
    }

    private fun load() {
        if (loading) return
        val s = scope ?: return
        loading = true
        progressView.visibility = View.VISIBLE
        s.launch {
            // Firestore o'qish sessiya talab qiladi; o'z profilida haqiqiy
            // imzo uid'ni qayta tekshiramiz, begona profilida maqsad o'zgarmaydi.
            val uid = try {
                YuzGramAdsAuth.ensureSignedIn(account)
            } catch (e: Exception) {
                ""
            }
            if (isSelf && uid.isNotBlank()) myUid = uid
            val list = try {
                ReelsRepository.getReelsByUploader(myUid)
            } catch (e: Exception) {
                emptyList()
            }
            loading = false
            if (host.parentActivity == null) return@launch
            reels = list
            adapter.submit(list)
            val empty = list.isEmpty()
            emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
            progressView.visibility = View.GONE
        }
    }

    private fun confirmDelete(reel: Reel) {
        val activity = host.parentActivity ?: return
        val title = reel.title.ifBlank { reel.caption() }.ifBlank { context.getString(R.string.reels_title) }
        try {
            AlertDialog.Builder(activity)
                .setTitle(R.string.reels_delete)
                .setMessage(context.getString(R.string.reels_delete_confirm, title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reels_delete) { _, _ -> deleteReel(reel) }
                .show()
        } catch (_: Exception) {
            toast(context.getString(R.string.reels_delete_failed))
        }
    }

    private fun deleteReel(reel: Reel) {
        val s = scope ?: return
        s.launch {
            try {
                YuzGramAdsAuth.ensureSignedIn(account)
                ReelsRepository.deleteReel(reel.id)
                if (myUid.isNotBlank()) ReelStorageHelper.deleteVideo(myUid, reel.videoUrl)
                if (host.parentActivity == null) return@launch
                val idx = reels.indexOfFirst { it.id == reel.id }
                if (idx >= 0) {
                    adapter.removeAt(idx)
                    reels = reels.filter { it.id != reel.id }
                }
                if (reels.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                }
                toast(context.getString(R.string.reels_deleted))
            } catch (e: Exception) {
                if (host.parentActivity == null) return@launch
                toast(context.getString(R.string.reels_delete_failed))
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }
}
