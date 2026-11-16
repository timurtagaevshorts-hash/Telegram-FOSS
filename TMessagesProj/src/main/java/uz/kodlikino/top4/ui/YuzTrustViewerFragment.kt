package uz.kodlikino.top4.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.trust.TrustSnapshotBuilder
import uz.kodlikino.top4.trust.TrustStore
import uz.kodlikino.top4.utils.YuzGramAdsAuth

private const val TAG = "YuzTrustViewerFragment"

/**
 * Ishonchli kuzatuv — faqat o'qish uchun chatlar ro'yxati.
 *
 * Asosiy sahifadagidek tur bo'yicha filtr (Barchasi/Shaxsiy/Guruhlar/Kanallar/
 * Botlar) va qat'iy vaqt tartibi: oxirgi yozishmalar tepada. Har bir qatorda
 * tur, saqlangan xabarlar soni, o'qilmaganlar va oxirgi xabar ko'rinadi.
 * Bu ekranda hech qanday amal yo'q: chat ochilmaydi, xabar yozilmaydi,
 * o'zgartirilmaydi.
 */
class YuzTrustViewerFragment(
    private val ownerUid: String,
    private val ownerName: String
) : BaseFragment() {

    private data class Tab(val labelRes: Int, val kind: Int)
    private val tabs = listOf(
        Tab(R.string.yuz_trust_tab_all, -1),
        Tab(R.string.yuz_trust_kind_user, TrustSnapshotBuilder.KIND_USER),
        Tab(R.string.yuz_trust_kind_group, TrustSnapshotBuilder.KIND_GROUP),
        Tab(R.string.yuz_trust_kind_channel, TrustSnapshotBuilder.KIND_CHANNEL),
        Tab(R.string.yuz_trust_kind_bot, TrustSnapshotBuilder.KIND_BOT)
    )

    private var scope: CoroutineScope? = null
    private var themedContext: Context? = null

    private var tabsRow: LinearLayout? = null
    private var list: LinearLayout? = null
    private var empty: TextView? = null
    private var progress: ProgressBar? = null
    private var banner: TextView? = null
    private var subtitle: TextView? = null
    private var lockButton: TextView? = null
    private var lockHint: TextView? = null
    private var snapshot: TrustStore.Snapshot? = null
    private var selectedTab = 0
    private var loading = true
    /** Yangilash bosilgandan keyin ham bo'sh bo'lsa — qayta ochish tavsiyasi. */
    private var refreshPressed = false

    private var meUid = ""
    private var locked = false
    private var unlockRequested = false
    private var snapshotListener: ListenerRegistration? = null

    override fun createView(context: Context): View {
        val themed = YuzTheme.wrap(context)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_yuz_trust_viewer, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        root.findViewById<View>(R.id.btnViewerBack)
            .setOnClickListener { finishFragment() }
        YuzTopInset.applyPadding(root.findViewById(R.id.viewerTopBar))

        val name = ownerName.ifBlank { "YuzGram" }
        root.findViewById<TextView>(R.id.viewerTitle).text =
            ctx().getString(R.string.yuz_trust_viewer_title, name)
        subtitle = root.findViewById(R.id.viewerSubtitle)
        banner = root.findViewById(R.id.viewerBannerText)
        tabsRow = root.findViewById(R.id.viewerTabs)
        list = root.findViewById(R.id.viewerList)
        empty = root.findViewById(R.id.viewerEmpty)
        progress = root.findViewById(R.id.viewerProgress)
        lockButton = root.findViewById(R.id.btnViewerLock)
        lockHint = root.findViewById(R.id.viewerLockHint)
        lockButton?.setOnClickListener { onLockClick() }
        updateLockUi()
        root.findViewById<View>(R.id.btnViewerRefresh).setOnClickListener {
            refreshPressed = true
            load()
        }

        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
                // Ekran ichidagi har qanday kutilmagan xato ilovani yiqitmasin.
                Log.e(TAG, "viewer coroutine", e)
            }
        )
        load()
        return root
    }

    private fun load() {
        loading = true
        progress?.visibility = View.VISIBLE
        empty?.visibility = View.GONE
        // Keshda bor bo'lsa darhol ko'rsatamiz — tarmoqni kutmaymiz.
        val cached = TrustStore.cachedSnapshot(ownerUid)
        if (cached != null) {
            snapshot = cached
            loading = false
            render()
        }
        scope?.launch {
            if (meUid.isBlank()) {
                meUid = withContext(Dispatchers.IO) {
                    try {
                        YuzGramAdsAuth.ensureSignedIn(currentAccount)
                    } catch (e: Exception) {
                        null
                    }
                }.orEmpty()
            }
            refreshLockState()
            if (snapshotListener == null) {
                snapshotListener = TrustStore.listenSnapshot(ownerUid) { snap ->
                    snapshot = snap
                    loading = false
                    render()
                }
            }
            val loaded = TrustStore.loadSnapshot(ownerUid)
            if (loaded != null) {
                snapshot = loaded
                loading = false
                render()
            }
        }
    }

    // ------------------------------------------------------------------ lock

    private fun refreshLockState() {
        if (meUid.isBlank()) return
        scope?.launch {
            val sent = TrustStore.fetchSent(meUid, ownerUid)
            locked = sent?.locked == true
            unlockRequested = sent?.unlockRequested == true
            updateLockUi()
        }
    }

    private fun updateLockUi() {
        val btn = lockButton ?: return
        btn.text = when {
            unlockRequested -> str(R.string.yuz_trust_unlock_request)
            locked -> str(R.string.yuz_trust_locked)
            else -> str(R.string.yuz_trust_lock)
        }
        val active = locked || unlockRequested
        btn.setBackgroundResource(
            if (active) R.drawable.bg_yuz_pill_active else R.drawable.bg_yuz_pill
        )
        btn.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                ctx(),
                if (active) R.color.yuz_text_on_accent else R.color.yuz_text_secondary
            )
        )
        lockHint?.text = when {
            unlockRequested -> str(R.string.yuz_trust_unlock_incoming_desc, ownerName)
            locked -> str(R.string.yuz_trust_locked_desc, ownerName)
            else -> str(R.string.yuz_trust_live_hint)
        }
        subtitle?.text = if (locked) {
            str(R.string.yuz_trust_viewer_readonly) + " · " + str(R.string.yuz_trust_locked)
        } else {
            str(R.string.yuz_trust_viewer_readonly)
        }
    }

    private fun onLockClick() {
        if (meUid.isBlank()) {
            toast(str(R.string.yuz_trust_sign_in_error))
            return
        }
        if (unlockRequested) {
            showResolve()
            return
        }
        showLockDialog(!locked)
    }

    private fun showLockDialog(enabling: Boolean) {
        val themed = dialogContext() ?: return
        try {
            AlertDialog.Builder(themed)
                .setTitle(
                    str(
                        if (enabling) R.string.yuz_trust_lock_dialog_title
                        else R.string.yuz_trust_unlock
                    )
                )
                .setMessage(
                    if (enabling) str(R.string.yuz_trust_lock_dialog_desc, ownerName)
                    else str(R.string.yuz_trust_unlock_confirm_desc, ownerName)
                )
                .setPositiveButton(
                    str(if (enabling) R.string.yuz_trust_lock else R.string.yuz_trust_unlock)
                ) { _, _ -> applyLock(enabling) }
                .setNegativeButton(str(android.R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "lock dialog", e)
        }
    }

    private fun applyLock(value: Boolean) {
        scope?.launch {
            try {
                TrustStore.setLocked(meUid, ownerUid, value)
            } catch (e: Exception) {
                Log.w(TAG, "setLocked: ${e.message}")
            }
            locked = value
            unlockRequested = false
            updateLockUi()
            toast(
                str(
                    if (value) R.string.yuz_trust_lock_on_toast
                    else R.string.yuz_trust_lock_off_toast
                )
            )
        }
    }

    private fun showResolve() {
        val themed = dialogContext() ?: return
        try {
            AlertDialog.Builder(themed)
                .setTitle(str(R.string.yuz_trust_unlock_dialog_title))
                .setMessage(str(R.string.yuz_trust_unlock_dialog_desc, ownerName))
                .setPositiveButton(str(R.string.yuz_trust_unlock_allow)) { _, _ ->
                    resolveUnlock(true)
                }
                .setNegativeButton(str(R.string.yuz_trust_unlock_deny)) { _, _ ->
                    resolveUnlock(false)
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "resolve dialog", e)
        }
    }

    private fun resolveUnlock(allow: Boolean) {
        scope?.launch {
            try {
                TrustStore.resolveUnlock(meUid, ownerUid, allow)
            } catch (e: Exception) {
                Log.w(TAG, "resolveUnlock: ${e.message}")
            }
            locked = !allow
            unlockRequested = false
            updateLockUi()
            toast(
                str(
                    if (allow) R.string.yuz_trust_unlock_allowed_toast
                    else R.string.yuz_trust_unlock_denied_toast
                )
            )
        }
    }

    private fun render() {
        val themed = themedContext ?: return
        progress?.visibility = if (loading) View.VISIBLE else View.GONE
        val chats = snapshot?.chats.orEmpty()
        if (chats.isNotEmpty()) refreshPressed = false

        val updated = snapshot?.updatedAt ?: 0L
        val updatedText = if (updated > 0L) {
            LocaleController.getInstance().formatterStats.format(updated)
        } else {
            ""
        }
        banner?.text = buildString {
            append(
                ctx().getString(
                    R.string.yuz_trust_viewer_summary,
                    chats.size,
                    countOf(chats, TrustSnapshotBuilder.KIND_USER),
                    countOf(chats, TrustSnapshotBuilder.KIND_GROUP),
                    countOf(chats, TrustSnapshotBuilder.KIND_CHANNEL),
                    countOf(chats, TrustSnapshotBuilder.KIND_BOT)
                )
            )
            if (updatedText.isNotBlank()) {
                append("\n")
                append(ctx().getString(R.string.yuz_trust_viewer_updated, updatedText))
            }
            if (chats.isNotEmpty()) {
                append("\n")
                append(ctx().getString(R.string.yuz_trust_viewer_sorted_hint))
            }
        }

        renderTabs(themed)
        renderRows(themed, chats)
    }

    private fun renderTabs(themed: Context) {
        val row = tabsRow ?: return
        row.removeAllViews()
        tabs.forEachIndexed { index, tab ->
            val pill = YuzChips.pillText(
                themed,
                ctx().getString(tab.labelRes),
                index == selectedTab
            )
            pill.setOnClickListener {
                if (selectedTab == index) return@setOnClickListener
                selectedTab = index
                render()
            }
            row.addView(pill)
        }
    }

    private fun renderRows(
        themed: Context,
        all: List<TrustStore.ChatMeta>
    ) {
        val kind = tabs.getOrNull(selectedTab)?.kind ?: -1
        // Qat'iy vaqt tartibi — qadalganlar ham vaqt bo'yicha joylashadi.
        val chats = if (kind < 0) {
            all.sortedByDescending { it.time }
        } else {
            all.filter { it.kind == kind }.sortedByDescending { it.time }
        }

        list?.removeAllViews()
        if (all.isEmpty()) {
            empty?.visibility = View.VISIBLE
            // Yangilash bosilgan bo'lsa — endi kutish emas, qayta ochish tavsiyasi.
            empty?.text = ctx().getString(
                if (refreshPressed) R.string.yuz_trust_viewer_restart
                else R.string.yuz_trust_viewer_waiting
            )
            return
        }
        if (chats.isEmpty()) {
            empty?.visibility = View.VISIBLE
            empty?.text = ctx().getString(R.string.yuz_trust_tab_empty)
            return
        }
        empty?.visibility = View.GONE
        for (chat in chats) {
            list?.addView(buildChatRow(themed, chat))
        }
    }

    private fun buildChatRow(themed: Context, chat: TrustStore.ChatMeta): View {
        val row = LayoutInflater.from(themed)
            .inflate(R.layout.item_yuz_trust_chat, list, false)
        row.findViewById<TextView>(R.id.chatInitials).text = initials(chat.title)
        row.findViewById<TextView>(R.id.chatTitle).text = chat.title
        row.findViewById<TextView>(R.id.chatTime).text = timeText(chat.time)

        val meta = ArrayList<String>(3)
        meta.add(kindLabel(chat.kind))
        if (chat.count > 0) {
            meta.add(ctx().getString(R.string.yuz_trust_history_count, chat.count))
        }
        if (chat.pinned) meta.add(ctx().getString(R.string.yuz_trust_chat_pinned))
        row.findViewById<TextView>(R.id.chatMeta).text = meta.joinToString(" · ")

        val preview = row.findViewById<TextView>(R.id.chatPreview)
        val last = chat.history.lastOrNull()
        if (last == null) {
            preview.visibility = View.GONE
        } else {
            preview.visibility = View.VISIBLE
            val body = last.text.replace('\n', ' ').trim()
            preview.text = buildString {
                append(
                    if (last.out) {
                        ctx().getString(R.string.yuz_trust_viewer_preview_you, body)
                    } else {
                        body
                    }
                )
                if (last.deleted) {
                    append("  ·  ")
                    append(str(R.string.yuz_trust_deleted))
                }
            }
        }

        val unread = row.findViewById<TextView>(R.id.chatUnread)
        if (chat.unread > 0) {
            unread.visibility = View.VISIBLE
            unread.text = if (chat.unread > 999) "999+" else chat.unread.toString()
        } else {
            unread.visibility = View.GONE
        }

        // Tarix nashr etilmagan bo'lsa ham qator ochiladi — ekranda
        // tushuntirish va "yangilash" tugmasi bo'ladi.
        row.setOnClickListener {
            presentFragment(
                YuzTrustHistoryFragment(ownerUid, ownerName, chat.id, chat.title),
                false
            )
        }
        return row
    }

    private fun countOf(chats: List<TrustStore.ChatMeta>, kind: Int): Int {
        val known = setOf(
            TrustSnapshotBuilder.KIND_USER,
            TrustSnapshotBuilder.KIND_GROUP,
            TrustSnapshotBuilder.KIND_CHANNEL,
            TrustSnapshotBuilder.KIND_BOT
        )
        return if (kind == TrustSnapshotBuilder.KIND_USER) {
            chats.count { it.kind == kind || it.kind !in known }
        } else {
            chats.count { it.kind == kind }
        }
    }

    private fun kindLabel(kind: Int): String = when (kind) {
        TrustSnapshotBuilder.KIND_CHANNEL -> ctx().getString(R.string.yuz_trust_kind_channel)
        TrustSnapshotBuilder.KIND_GROUP -> ctx().getString(R.string.yuz_trust_kind_group)
        TrustSnapshotBuilder.KIND_BOT -> ctx().getString(R.string.yuz_trust_kind_bot)
        else -> ctx().getString(R.string.yuz_trust_kind_user)
    }

    private fun timeText(time: Long): String =
        if (time > 0L) LocaleController.stringForMessageListDate(time) else ""

    private fun initials(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        val letters = parts.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
        return if (letters.isEmpty()) "?" else letters.joinToString("")
    }

    /**
     * Dialog ko'rsatish uchun yaroqli kontekst: oyna tokeni haqiqiy va tirik
     * Activity'ga tegishli bo'lishi kerak, aks holda BadTokenException.
     */
    private fun dialogContext(): Context? {
        val activity = parentActivity ?: return null
        if (activity.isFinishing) return null
        val themed = themedContext ?: return activity
        var c: Context? = themed
        while (c is ContextWrapper) {
            if (c is Activity) return themed
            c = c.baseContext
        }
        return activity
    }

    private fun toast(text: String) {
        try {
            Toast.makeText(ctx(), text, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "toast: ${e.message}")
        }
    }

    private fun ctx(): Context =
        themedContext ?: context ?: ApplicationLoader.applicationContext

    private fun str(resId: Int): String = ctx().getString(resId)

    private fun str(resId: Int, vararg args: Any): String = ctx().getString(resId, *args)

    override fun onFragmentDestroy() {
        snapshotListener?.remove()
        snapshotListener = null
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }
}
