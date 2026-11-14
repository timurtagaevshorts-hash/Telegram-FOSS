package uz.kodlikino.top4.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.trust.TrustStore

/**
 * Ishonch orqali ochilgan suhbat tarixi — faqat o'qish.
 *
 * Xabarlar egasining o'zi nashr qilgan snapshot hujjatidan olinadi; bu ekran
 * hech narsa yozmaydi, o'zgartirmaydi va Telegram bilan to'g'ridan-to'g'ri
 * bog'lanmaydi.
 */
class YuzTrustHistoryFragment(
    private val ownerUid: String,
    private val ownerName: String,
    private val chatId: String,
    private val chatTitle: String
) : BaseFragment() {

    private var scope: CoroutineScope? = null
    private var themedContext: Context? = null

    private var list: LinearLayout? = null
    private var empty: TextView? = null
    private var progress: ProgressBar? = null
    private var banner: TextView? = null
    private var scroll: ScrollView? = null
    private var bubbleMaxWidth = 0

    override fun createView(context: Context): View {
        val themed = YuzTheme.wrap(context)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_yuz_trust_history, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        root.findViewById<View>(R.id.btnHistoryBack)
            .setOnClickListener { finishFragment() }
        YuzTopInset.applyPadding(root.findViewById(R.id.historyTopBar))

        root.findViewById<TextView>(R.id.historyTitle).text = chatTitle.ifBlank { "YuzGram" }
        banner = root.findViewById(R.id.historyBannerText)
        list = root.findViewById(R.id.historyList)
        empty = root.findViewById(R.id.historyEmpty)
        progress = root.findViewById(R.id.historyProgress)
        scroll = root.findViewById(R.id.historyScroll)
        root.findViewById<View>(R.id.btnHistoryRefresh).setOnClickListener { load() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        root.post {
            bubbleMaxWidth = (root.width * 0.78f).toInt()
            load()
        }
        return root
    }

    private fun load() {
        progress?.visibility = View.VISIBLE
        empty?.visibility = View.GONE
        scope?.launch {
            val snapshot = TrustStore.loadSnapshot(ownerUid)
            render(snapshot)
        }
    }

    private fun render(snapshot: TrustStore.Snapshot?) {
        val themed = themedContext ?: return
        progress?.visibility = View.GONE

        val chat = snapshot?.chats?.firstOrNull { it.id == chatId }
        val messages = chat?.history.orEmpty()

        banner?.text = ctx().getString(
            R.string.yuz_trust_history_hint,
            messages.size,
            ownerName.ifBlank { "YuzGram" }
        )

        list?.removeAllViews()
        for (msg in messages) {
            list?.addView(buildRow(themed, msg))
        }

        empty?.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        empty?.text = ctx().getString(R.string.yuz_trust_history_empty)

        if (messages.isNotEmpty()) {
            scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun buildRow(themed: Context, msg: TrustStore.MsgMeta): View {
        val row = LayoutInflater.from(themed)
            .inflate(R.layout.item_yuz_trust_message, list, false) as LinearLayout
        row.gravity = if (msg.out) Gravity.END else Gravity.START

        val sender = row.findViewById<TextView>(R.id.msgSender)
        if (msg.out) {
            sender.visibility = View.GONE
        } else {
            sender.text = msg.from.ifBlank {
                ctx().getString(R.string.yuz_trust_history_unknown)
            }
        }

        row.findViewById<View>(R.id.msgBubble)
            .setBackgroundResource(
                if (msg.out) R.drawable.bg_yuz_trust_msg_out
                else R.drawable.bg_yuz_trust_msg_in
            )

        val text = row.findViewById<TextView>(R.id.msgText)
        text.text = msg.text
        if (bubbleMaxWidth > 0) {
            text.maxWidth = bubbleMaxWidth
        }
        row.findViewById<TextView>(R.id.msgTime).text = timeText(msg.date)
        return row
    }

    private fun timeText(seconds: Long): String =
        if (seconds > 0L) {
            LocaleController.getInstance().formatterStats.format(seconds * 1000L)
        } else {
            ""
        }

    private fun ctx(): Context = themedContext ?: context ?: throw IllegalStateException()

    override fun onFragmentDestroy() {
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }
}
