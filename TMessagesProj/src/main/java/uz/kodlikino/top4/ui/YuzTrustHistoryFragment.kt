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
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.trust.TrustSnapshotBuilder
import uz.kodlikino.top4.trust.TrustStore
import java.util.Calendar
import java.util.Date

/**
 * Ishonch orqali ochilgan suhbat tarixi — faqat o'qish.
 *
 * Xabarlar egasining o'zi nashr qilgan snapshot hujjatidan olinadi; bu ekran
 * hech narsa yozmaydi, o'zgartirmaydi va Telegram bilan to'g'ridan-to'g'ri
 * bog'lanmaydi. Kunlar ajratgichi, guruhlarda jo'natuvchi avatari va bosib
 * nusxalash mavjud.
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
        val kind = chat?.kind ?: TrustSnapshotBuilder.KIND_USER

        banner?.text = buildString {
            append(
                ctx().getString(
                    R.string.yuz_trust_history_hint,
                    messages.size,
                    ownerName.ifBlank { "YuzGram" }
                )
            )
            if (messages.isNotEmpty()) {
                append("\n")
                append(ctx().getString(R.string.yuz_trust_history_copy_hint))
            }
        }

        list?.removeAllViews()
        var lastDay = Long.MIN_VALUE
        for (msg in messages) {
            val day = dayStart(msg.date * 1000L)
            if (day != lastDay) {
                list?.addView(buildDayRow(themed, msg.date))
                lastDay = day
            }
            list?.addView(buildRow(themed, msg, kind))
        }

        empty?.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        empty?.text = ctx().getString(R.string.yuz_trust_history_empty)

        if (messages.isNotEmpty()) {
            scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun buildDayRow(themed: Context, seconds: Long): View {
        val row = LayoutInflater.from(themed)
            .inflate(R.layout.item_yuz_trust_day, list, false)
        row.findViewById<TextView>(R.id.dayLabel).text = dayLabel(seconds)
        return row
    }

    private fun buildRow(
        themed: Context,
        msg: TrustStore.MsgMeta,
        kind: Int
    ): View {
        val row = LayoutInflater.from(themed)
            .inflate(R.layout.item_yuz_trust_message, list, false) as LinearLayout
        row.gravity = if (msg.out) Gravity.END else Gravity.START
        val column = row.findViewById<LinearLayout>(R.id.msgColumn)
        column.gravity = if (msg.out) Gravity.END else Gravity.START

        val avatar = row.findViewById<View>(R.id.msgAvatar)
        val sender = row.findViewById<TextView>(R.id.msgSender)
        // Shaxsiy chat va botlarda jo'natuvchi ma'lum — nom takrorlanmaydi.
        val showSender = !msg.out &&
            (kind == TrustSnapshotBuilder.KIND_GROUP || kind == TrustSnapshotBuilder.KIND_CHANNEL)

        if (showSender) {
            val from = msg.from.ifBlank {
                ctx().getString(R.string.yuz_trust_history_unknown)
            }
            sender.text = from
            sender.visibility = View.VISIBLE
            avatar.visibility = View.VISIBLE
            row.findViewById<TextView>(R.id.msgInitials).text = initials(from)
        } else {
            sender.visibility = View.GONE
            avatar.visibility = View.GONE
        }

        val bubble = row.findViewById<View>(R.id.msgBubble)
        bubble.setBackgroundResource(
            if (msg.out) R.drawable.bg_yuz_trust_msg_out
            else R.drawable.bg_yuz_trust_msg_in
        )
        bubble.setOnClickListener { copy(msg.text) }

        val text = row.findViewById<TextView>(R.id.msgText)
        text.text = msg.text
        if (bubbleMaxWidth > 0) {
            text.maxWidth = if (showSender) {
                bubbleMaxWidth - dp(32)
            } else {
                bubbleMaxWidth
            }
        }
        row.findViewById<TextView>(R.id.msgTime).text = timeText(msg.date)
        return row
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        AndroidUtilities.addToClipboard(text)
        Toast.makeText(ctx(), ctx().getString(R.string.yuz_trust_message_copied), Toast.LENGTH_SHORT).show()
    }

    private fun dayStart(ms: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun dayLabel(seconds: Long): String {
        val day = dayStart(seconds * 1000L)
        val today = dayStart(System.currentTimeMillis())
        val yesterday = dayStart(today - 24L * 60L * 60L * 1000L)
        return when (day) {
            today -> ctx().getString(R.string.yuz_trust_day_today)
            yesterday -> ctx().getString(R.string.yuz_trust_day_yesterday)
            else -> {
                val cal = Calendar.getInstance().apply { timeInMillis = day }
                val now = Calendar.getInstance()
                val formatter = if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
                    LocaleController.getInstance().formatterDayMonth
                } else {
                    LocaleController.getInstance().formatterYear
                }
                formatter.format(Date(day))
            }
        }
    }

    private fun timeText(seconds: Long): String =
        if (seconds > 0L) {
            LocaleController.getInstance().formatterDay.format(Date(seconds * 1000L))
        } else {
            ""
        }

    private fun dp(value: Int): Int =
        org.telegram.messenger.AndroidUtilities.dp(value.toFloat())

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
