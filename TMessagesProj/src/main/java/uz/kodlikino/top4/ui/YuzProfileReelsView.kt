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
import uz.kodlikino.top4.utils.YuzReelUploads

/**
 * Native Telegram profilidagi "Reels" tab kontenti (arxiv postlar o'rniga).
 * [SharedMediaLayout] bu view'ni tab tanlanganda media sahifasi ustiga to'siq
 * (opaque) qatlam sifatida qo'yadi — foydalanuvchining o'z reel'lari 3 ustunli
 * grid'da ko'rinadi, katak bosilsa scoped [ReelsActivity] feed ochiladi.
 *
 * O'z profilida ([isSelf]) grid tepasida yuklanayotgan reels ham turadi:
 * yozuvlar [YuzReelUploads] trekeridan olinadi va yuklash tugagach o'z-o'zidan
 * Firestore'dagi reel bilan almashtiriladi. Begona profilida faqat ko'rish.
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

    /** Oldingi treker holati: yuklash tugaganini shu bilan sezamiz (id → xato). */
    private var lastPending: Map<String, Boolean> = emptyMap()

    /**
     * Yuklash trekeri har progress tikida chaqiriladi — `notifyDataSetChanged`
     * o'rniga faqat kataklarning progress'i yangilanadi (adapter payload).
     */
    private val uploadsListener = Runnable { refreshPending() }

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
            onDelete = { reel -> confirmDelete(reel) },
            onPendingTap = { upload -> onPendingClicked(upload) },
            onPendingCancel = { upload -> confirmCancel(upload) })
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isSelf) {
            YuzReelUploads.addListener(uploadsListener)
            refreshPending()
        }
    }

    override fun onDetachedFromWindow() {
        YuzReelUploads.removeListener(uploadsListener)
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    /** Tab ko'rsatilganda — yangi/nimcha bo'lgan reel'larni qayta yuklaydi. */
    fun refresh() {
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        refreshPending()
        load()
    }

    private fun openFeed(index: Int) {
        if (host.parentActivity == null || myUid.isBlank()) return
        host.presentFragment(ReelsActivity(myUid, index), false)
    }

    /** Trekerdagi yuklanayotgan reels — faqat o'z profilida ko'rinadi. */
    private fun refreshPending() {
        if (!isSelf) {
            lastPending = emptyMap()
            adapter.submitPending(emptyList())
            updateEmptyState()
            return
        }
        val list = YuzReelUploads.listForAccount(account)
        val ids = list.associate { it.id to it.failed }
        val finishedOk = lastPending.any { (id, failed) -> !failed && !ids.containsKey(id) }
        lastPending = ids
        adapter.submitPending(list)
        updateEmptyState()
        // Yuklash muvaffaqiyatli tugadi — Firestore'dagi yangi reel grid'ga qo'shiladi.
        if (finishedOk && !loading && scope != null) load()
    }

    private fun updateEmptyState() {
        val empty = reels.isEmpty() && adapter.itemCount == 0
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun load() {
        if (loading) return
        val s = scope ?: return
        loading = true
        // Kesh bo'lsa grid birinchi kadrda ko'rinadi; tarmoq javobi keyin keladi.
        val cached = ReelsRepository.peekReelsByUploader(myUid)
        if (cached != null && cached.isNotEmpty()) {
            reels = cached
            adapter.submit(cached)
            updateEmptyState()
        } else {
            progressView.visibility = View.VISIBLE
        }
        s.launch {
            // Sovuq start: xotira keshisiz ham disk keşi gridni birinchi kadrda
            // beradi (tarmoq sekin/oflayn bo'lsa ham). Keyin tarmoq yangilaydi.
            if (cached == null) {
                val disk = try {
                    ReelsRepository.getReelsByUploaderFromCache(myUid)
                } catch (_: Exception) {
                    null
                }
                if (disk != null && disk.isNotEmpty() && reels.isEmpty() && host.parentActivity != null) {
                    reels = disk
                    adapter.submit(disk)
                    updateEmptyState()
                    progressView.visibility = View.GONE
                }
            }
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
            updateEmptyState()
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

    /**
     * Katak bosilganda: faol yuklash — faqat eslatma; xato yuklash — lokal
     * nusxadan qayta boshlanadi (video qaytadan tanlanmaydi).
     */
    private fun onPendingClicked(upload: YuzReelUploads.Upload) {
        if (!upload.failed) {
            toast(context.getString(R.string.reels_pending_tap))
            return
        }
        if (YuzReelUploads.retry(context, upload)) {
            toast(context.getString(R.string.reels_uploading_in_background))
            refreshPending()
        } else {
            toast(context.getString(R.string.reels_pending_retry_unavailable))
        }
    }

    /** ✕ — yuklashni bekor qilish (faol) yoki xato yozuvni tozalash. */
    private fun confirmCancel(upload: YuzReelUploads.Upload) {
        val activity = host.parentActivity ?: return
        val name = upload.title.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: upload.fileName
        try {
            AlertDialog.Builder(activity)
                .setMessage(context.getString(R.string.reels_pending_cancel_confirm, name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.reels_pending_cancel) { _, _ ->
                    YuzReelUploads.dismiss(context, upload.id, cancelRunning = !upload.failed)
                    refreshPending()
                }
                .show()
        } catch (_: Exception) {
            YuzReelUploads.dismiss(context, upload.id, cancelRunning = !upload.failed)
            refreshPending()
        }
    }

    private fun deleteReel(reel: Reel) {
        val s = scope ?: return
        s.launch {
            try {
                YuzGramAdsAuth.ensureSignedIn(account)
                ReelsRepository.deleteReel(reel.id)
                if (myUid.isNotBlank()) {
                    ReelStorageHelper.deleteVideo(myUid, reel.videoUrl)
                    ReelStorageHelper.deleteThumbnail(myUid, reel.thumbnailUrl)
                }
                if (host.parentActivity == null) return@launch
                val idx = reels.indexOfFirst { it.id == reel.id }
                if (idx >= 0) {
                    adapter.removeAt(idx)
                    reels = reels.filter { it.id != reel.id }
                }
                updateEmptyState()
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
}
