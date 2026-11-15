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
 * Suhbatlar tur bo'yicha alohida bo'limlarga ajratiladi (shaxsiy, guruhlar,
 * kanallar, botlar); har bir qatorda oxirgi xabar ko'rsatiladi. Ro'yxat
 * egasining o'zi nashr qilgan metama'lumotdan olinadi. Bu ekranda hech qanday
 * amal yo'q: chat ochilmaydi, xabar yozilmaydi, o'zgartirilmaydi.
 */
class YuzTrustViewerFragment(
    private val ownerUid: String,
    private val ownerName: String
) : BaseFragment() {

    private var scope: CoroutineScope? = null
    private var themedContext: Context? = null

    private var list: LinearLayout? = null
    private var empty: TextView? = null
    private var progress: ProgressBar? = null
    private var banner: TextView? = null
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
            val snapshot = TrustStore.loadSnapshot(ownerUid)
            loading = false
            render(snapshot)
        }
    }

    private fun render(snapshot: TrustStore.Snapshot?) {
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
                append(ctx().getString(R.string.yuz_trust_viewer_open_hint))
            }
        }

        list?.removeAllViews()
        val ordered = chats.sortedWith(
            compareByDescending<TrustStore.ChatMeta> { it.pinned }
                .thenByDescending { it.time }
        )
        val known = setOf(
            TrustSnapshotBuilder.KIND_USER,
            TrustSnapshotBuilder.KIND_GROUP,
            TrustSnapshotBuilder.KIND_CHANNEL,
            TrustSnapshotBuilder.KIND_BOT
        )
        addSection(
            themed,
            R.string.yuz_trust_group_people,
            ordered.filter { it.kind == TrustSnapshotBuilder.KIND_USER || it.kind !in known }
        )
        addSection(
            themed,
            R.string.yuz_trust_group_groups,
            ordered.filter { it.kind == TrustSnapshotBuilder.KIND_GROUP }
        )
        addSection(
            themed,
            R.string.yuz_trust_group_channels,
            ordered.filter { it.kind == TrustSnapshotBuilder.KIND_CHANNEL }
        )
        addSection(
            themed,
            R.string.yuz_trust_group_bots,
            ordered.filter { it.kind == TrustSnapshotBuilder.KIND_BOT }
        )

        val isEmpty = chats.isEmpty()
        empty?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        empty?.text = ctx().getString(R.string.yuz_trust_viewer_empty, name)
    }

    private fun addSection(
        themed: Context,
        titleRes: Int,
        chats: List<TrustStore.ChatMeta>
    ) {
        if (chats.isEmpty()) return
        val header = LayoutInflater.from(themed)
            .inflate(R.layout.item_yuz_trust_section, list, false)
        header.findViewById<TextView>(R.id.sectionTitle).text = ctx().getString(titleRes)
        header.findViewById<TextView>(R.id.sectionCount).text = chats.size.toString()
        list?.addView(header)
        for (chat in chats) {
            list?.addView(buildChatRow(themed, chat))
        }
    }

    private fun buildChatRow(themed: Context, chat: TrustStore.ChatMeta): View {
        val row = LayoutInflater.from(themed)
            .inflate(R.layout.item_yuz_trust_chat, list, false)
        row.findViewById<TextView>(R.id.chatInitials).text = initials(chat.title)
        row.findViewById<TextView>(R.id.chatTitle).text = chat.title
        row.findViewById<TextView>(R.id.chatMeta).text = previewText(chat)
        row.findViewById<TextView>(R.id.chatTime).text = timeText(chat.time)
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

    /** Qator ostidagi matn: oxirgi xabar (bo'lmasa turi va holati). */
    private fun previewText(chat: TrustStore.ChatMeta): String {
        val last = chat.history.lastOrNull()
        if (last != null) {
            val body = last.text.replace('\n', ' ').trim()
            val preview = if (last.out) {
                ctx().getString(R.string.yuz_trust_viewer_preview_you, body)
            } else {
                body
            }
            return if (chat.unread > 0) {
                ctx().getString(R.string.yuz_trust_unread_count, chat.unread) + " · " + preview
            } else {
                preview
            }
        }
        val parts = ArrayList<String>(2)
        parts.add(kindLabel(chat.kind))
        if (chat.pinned) parts.add(ctx().getString(R.string.yuz_trust_chat_pinned))
        return parts.joinToString(" · ")
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
