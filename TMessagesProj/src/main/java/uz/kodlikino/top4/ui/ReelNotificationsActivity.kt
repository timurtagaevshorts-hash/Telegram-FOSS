package uz.kodlikino.top4.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.ReelNotification
import uz.kodlikino.top4.utils.ReelNotificationManager
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzReelNotify

/**
 * Reels bildirishnomalari ekrani — donor `ProfileNotificationsActivity`
 * varianti ([BaseFragment] sifatida, YuzGram uslubida). Profil qo'ng'irog'idan
 * ochiladi ([org.telegram.ui.ProfileActivity], `open_reel_notifications`).
 *
 *  - ro'yxat Firestore `user_notifications/{uid}/items` listener'idan;
 *  - ochilganda hammasi o'qilgan deb belgilanadi;
 *  - yangi item kelganda (ilk snapshot'dan keyin) heads-up bildirishnoma +
 *    ovoz ([YuzReelNotify]);
 *  - yuqorida ovoz tugmasi (SwitchCompat) — yoqish/o'chirish;
 *  - bosilgan bildirma tegishli reel'ni (izoh/reply bo'lsa izohlar paneli bilan)
 *    ochadi; chapda o'chirish, tepada "barchasini tozalash".
 */
class ReelNotificationsActivity : BaseFragment() {

    private var themedContext: Context? = null
    private var rv: RecyclerView? = null
    private var emptyView: TextView? = null
    private var progress: ProgressBar? = null
    private var switchSound: SwitchCompat? = null
    private var adapter: ReelNotificationsAdapter? = null

    private var listener: ListenerRegistration? = null
    private var scope: CoroutineScope? = null
    private val seenIds = HashSet<String>()
    private var firstSnapshot = true
    private var uid = ""

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.activity_reel_notifications, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)
        YuzTopInset.applyPadding(root.findViewById(R.id.notifAppBar))

        uid = YuzGramAdsAuth.expectedUid(currentAccount)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        rv = root.findViewById(R.id.rvNotifications)
        emptyView = root.findViewById(R.id.tvNotificationsEmpty)
        progress = root.findViewById(R.id.notificationsProgress)
        switchSound = root.findViewById(R.id.switchReelNotifSound)

        val listAdapter = ReelNotificationsAdapter(
            appContext = themed,
            onClick = { openNotification(it) },
            onDelete = { deleteNotification(it) }
        )
        adapter = listAdapter
        rv?.layoutManager = LinearLayoutManager(themed)
        rv?.adapter = listAdapter

        root.findViewById<ImageButton>(R.id.btnNotifBack).setOnClickListener { removeSelfFromStack() }
        root.findViewById<ImageButton>(R.id.btnDeleteAllNotifications).setOnClickListener { confirmDeleteAll() }

        bindSoundSwitch()

        if (uid.isBlank()) {
            progress?.visibility = View.GONE
            emptyView?.visibility = View.VISIBLE
            return root
        }

        YuzReelNotify.ensureChannels(themed)
        listener = ReelNotificationManager.listen(uid) { list ->
            if (fragmentView == null) return@listen
            progress?.visibility = View.GONE
            adapter?.submit(list)
            emptyView?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            announceNew(list)
            hydrateMissingThumbs(list)
        }
        scope?.launch(Dispatchers.IO) {
            try { ReelNotificationManager.markAllRead(uid) } catch (_: Exception) {}
        }
        return root
    }

    /** Ikkinchi va keyingi snapshot'larda faqat YANGI itemlar uchun heads-up. */
    private fun announceNew(list: List<ReelNotification>) {
        val ctx = parentActivity ?: return
        if (firstSnapshot) {
            firstSnapshot = false
            list.forEach { seenIds.add(it.id) }
            return
        }
        var fresh = 0
        for (item in list) {
            if (seenIds.add(item.id)) {
                YuzReelNotify.show(ctx, item)
                fresh++
            }
        }
        if (fresh > 0) YuzReelNotify.playInAppAlert(ctx)
    }

    /** Reel muqovasi bo'sh bildirmalar uchun thumbnail'larni reels'dan o'qiydi. */
    private fun hydrateMissingThumbs(list: List<ReelNotification>) {
        val ids = list.filter { it.reelThumbUrl.isBlank() && it.reelId.isNotBlank() }
            .map { it.reelId }.distinct()
        if (ids.isEmpty()) return
        val s = scope ?: return
        val current = adapter?.snapshot().orEmpty()
        s.launch {
            val thumbs = try {
                withContext(Dispatchers.IO) { loadThumbs(ids) }
            } catch (_: Exception) {
                emptyMap()
            }
            if (thumbs.isEmpty() || fragmentView == null) return@launch
            adapter?.submit(
                current.map { item ->
                    val url = thumbs[item.reelId]
                    if (item.reelThumbUrl.isBlank() && !url.isNullOrBlank()) item.copy(reelThumbUrl = url) else item
                }
            )
        }
    }

    private suspend fun loadThumbs(ids: List<String>): Map<String, String> {
        val result = HashMap<String, String>()
        val db = FirebaseFirestore.getInstance()
        ids.chunked(10).forEach { chunk ->
            try {
                val snaps = db.collection("reels")
                    .whereIn(FieldPath.documentId(), chunk)
                    .get().await()
                snaps.documents.forEach { doc ->
                    val url = doc.getString("thumbnailUrl")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("uploadedByPhotoUrl").orEmpty()
                    if (url.isNotBlank()) result[doc.id] = url
                }
            } catch (_: Exception) {
            }
        }
        return result
    }

    private fun openNotification(item: ReelNotification) {
        val s = scope
        if (s != null && uid.isNotBlank() && item.id.isNotBlank()) {
            s.launch(Dispatchers.IO) {
                try { ReelNotificationManager.markRead(uid, item.id) } catch (_: Exception) {}
            }
        }
        if (item.reelId.isBlank()) return
        val openComments = item.type == ReelNotification.TYPE_COMMENT ||
            item.type == ReelNotification.TYPE_REPLY
        presentFragment(
            ReelsActivity(targetReelId = item.reelId, openTargetComments = openComments),
            false
        )
    }

    private fun deleteNotification(item: ReelNotification) {
        val s = scope ?: return
        if (uid.isBlank() || item.id.isBlank()) return
        s.launch {
            val deleted = withContext(Dispatchers.IO) {
                try { ReelNotificationManager.delete(uid, item.id) } catch (_: Exception) { false }
            }
            if (fragmentView == null) return@launch
            toast(if (deleted) R.string.yuz_notif_deleted else R.string.yuz_notif_delete_failed)
        }
    }

    private fun confirmDeleteAll() {
        val activity = parentActivity ?: return
        if ((adapter?.itemCount ?: 0) == 0) return
        try {
            android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.yuz_notif_delete_all)
                .setMessage(R.string.yuz_notif_delete_all_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.yuz_notif_delete_all) { _, _ ->
                    val s = scope ?: return@setPositiveButton
                    s.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try { ReelNotificationManager.deleteAll(uid) } catch (_: Exception) { false }
                        }
                        if (fragmentView == null) return@launch
                        toast(if (ok) R.string.yuz_notif_cleared else R.string.yuz_notif_delete_failed)
                    }
                }
                .show()
        } catch (_: Exception) {
        }
    }

    /** Ovoz tugmasi — preflik + kanal qayta yaratish + preview signal. */
    private fun bindSoundSwitch() {
        val sw = switchSound ?: return
        val ctx = themedContext ?: return
        var ready = false
        sw.isChecked = YuzReelNotify.isSoundEnabled(ctx)
        sw.post { ready = true }
        sw.setOnCheckedChangeListener { _, checked ->
            if (!ready) return@setOnCheckedChangeListener
            YuzReelNotify.setSoundEnabled(ctx, checked)
            toast(if (checked) R.string.yuz_notif_sound_on else R.string.yuz_notif_sound_off)
            if (checked) YuzReelNotify.playInAppAlert(ctx)
        }
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
    }

    override fun onFragmentDestroy() {
        listener?.remove()
        listener = null
        scope?.cancel()
        scope = null
        adapter = null
        rv = null
        emptyView = null
        progress = null
        switchSound = null
        super.onFragmentDestroy()
    }

    private fun toast(resId: Int) {
        val ctx = themedContext ?: context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }
}
