package uz.kodlikino.top4.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.trust.TrustSnapshotBuilder
import uz.kodlikino.top4.trust.TrustStore

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
    private var snapshot: TrustStore.Snapshot? = null
    private var selectedTab = 0
    private var loading = true

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
        banner = root.findViewById(R.id.viewerBannerText)
        tabsRow = root.findViewById(R.id.viewerTabs)
        list = root.findViewById(R.id.viewerList)
        empty = root.findViewById(R.id.viewerEmpty)
        progress = root.findViewById(R.id.viewerProgress)
        root.findViewById<View>(R.id.btnViewerRefresh).setOnClickListener { load() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        load()
        return root
    }

    private fun load() {
        loading = true
        progress?.visibility = View.VISIBLE
        empty?.visibility = View.GONE
        scope?.launch {
            val loaded = TrustStore.loadSnapshot(ownerUid)
            snapshot = loaded
            loading = false
            render()
        }
    }

    private fun render() {
        val themed = themedContext ?: return
        progress?.visibility = if (loading) View.VISIBLE else View.GONE
        val name = ownerName.ifBlank { "YuzGram" }
        val chats = snapshot?.chats.orEmpty()

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
        renderRows(themed, chats, name)
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
        all: List<TrustStore.ChatMeta>,
        name: String
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
            empty?.text = ctx().getString(R.string.yuz_trust_viewer_empty, name)
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
            preview.text = if (last.out) {
                ctx().getString(R.string.yuz_trust_viewer_preview_you, body)
            } else {
                body
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

    private fun ctx(): Context = themedContext ?: context ?: throw IllegalStateException()

    override fun onFragmentDestroy() {
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }
}
