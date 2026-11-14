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
 * Ro'yxat egasining o'zi nashr qilgan metama'lumotdan olinadi. Bu ekranda
 * hech qanday amal yo'q: chat ochilmaydi, xabar yozilmaydi, o'zgartirilmaydi.
 * Xabarlari nashr qilingan suhbat qatorini bosib, tarixni faqat o'qish
 * rejimida ko'rish mumkin ([YuzTrustHistoryFragment]).
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

        val updated = snapshot?.updatedAt ?: 0L
        val updatedText = if (updated > 0L) {
            LocaleController.getInstance().formatterStats.format(updated)
        } else {
            ""
        }
        banner?.text = buildString {
            append(ctx().getString(R.string.yuz_trust_viewer_hint, name))
            if (updatedText.isNotBlank()) {
                append("\n")
                append(ctx().getString(R.string.yuz_trust_viewer_updated, updatedText))
            }
            if (snapshot?.chats.orEmpty().any { it.history.isNotEmpty() }) {
                append("\n")
                append(ctx().getString(R.string.yuz_trust_viewer_open_hint))
            }
        }

        val chats = snapshot?.chats.orEmpty()
        list?.removeAllViews()
        for (chat in chats) {
            val row = LayoutInflater.from(themed)
                .inflate(R.layout.item_yuz_trust_chat, list, false)
            row.findViewById<TextView>(R.id.chatInitials).text = initials(chat.title)
            row.findViewById<TextView>(R.id.chatTitle).text = chat.title
            row.findViewById<TextView>(R.id.chatMeta).text = metaText(chat)
            row.findViewById<TextView>(R.id.chatTime).text = timeText(chat.time)
            if (chat.history.isEmpty()) {
                row.isClickable = false
                row.foreground = null
            } else {
                row.setOnClickListener {
                    presentFragment(
                        YuzTrustHistoryFragment(ownerUid, ownerName, chat.id, chat.title),
                        false
                    )
                }
            }
            list?.addView(row)
        }

        val isEmpty = chats.isEmpty()
        empty?.visibility = if (isEmpty) View.VISIBLE else View.GONE
        empty?.text = ctx().getString(R.string.yuz_trust_viewer_empty, name)
    }

    private fun metaText(chat: TrustStore.ChatMeta): String {
        val kind = when (chat.kind) {
            TrustSnapshotBuilder.KIND_CHANNEL -> ctx().getString(R.string.yuz_trust_kind_channel)
            TrustSnapshotBuilder.KIND_GROUP -> ctx().getString(R.string.yuz_trust_kind_group)
            else -> ctx().getString(R.string.yuz_trust_kind_user)
        }
        val parts = ArrayList<String>(3)
        parts.add(kind)
        if (chat.unread > 0) {
            parts.add(ctx().getString(R.string.yuz_trust_unread_count, chat.unread))
        }
        if (chat.history.isNotEmpty()) {
            parts.add(ctx().getString(R.string.yuz_trust_history_count, chat.history.size))
        }
        return parts.joinToString(" · ")
    }

    private fun timeText(time: Long): String =
        if (time > 0L) LocaleController.getInstance().formatterStats.format(time) else ""

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