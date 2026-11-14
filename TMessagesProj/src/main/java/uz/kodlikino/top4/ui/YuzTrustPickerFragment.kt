package uz.kodlikino.top4.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.ui.ActionBar.BaseFragment

/**
 * Ishonch so'rovi uchun suhbatdosh tanlash.
 *
 * Faqat shaxsiy suhbatlar ko'rsatiladi — guruh/kanal orqali ishonch so'ralmaydi.
 * Tanlangandan keyin YuzGram'da ekanligi [YuzTrustFragment]da tekshiriladi.
 */
class YuzTrustPickerFragment(
    private val onPick: (uid: String, name: String, telegramId: Long) -> Unit
) : BaseFragment() {

    private data class Entry(val id: Long, val name: String, val username: String)

    private var themedContext: Context? = null
    private var all: List<Entry> = emptyList()
    private var list: LinearLayout? = null
    private var empty: TextView? = null
    private var progress: ProgressBar? = null

    override fun createView(context: Context): View {
        val themed = YuzTheme.wrap(context)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_yuz_trust_picker, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        root.findViewById<View>(R.id.btnPickerBack)
            .setOnClickListener { finishFragment() }
        YuzTopInset.applyPadding(root.findViewById(R.id.pickerTopBar))

        list = root.findViewById(R.id.pickerList)
        empty = root.findViewById(R.id.pickerEmpty)
        progress = root.findViewById(R.id.pickerProgress)

        loadEntries()

        val search = root.findViewById<EditText>(R.id.pickerSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                render(s?.toString().orEmpty())
            }
        })
        render("")
        return root
    }

    private fun loadEntries() {
        val mc = MessagesController.getInstance(currentAccount)
        val me = UserConfig.getInstance(currentAccount).clientUserId
        val found = ArrayList<Pair<Long, Entry>>(256)
        for (dialog in mc.getDialogs(0)) {
            val id = dialog.id
            if (id <= 0L || id == me) continue
            val user = mc.getUser(id) ?: continue
            if (user.self || user.deleted || user.bot) continue
            val name = UserObject.getUserName(user).orEmpty().trim()
            if (name.isBlank()) continue
            found.add(dialog.last_message_date.toLong() to Entry(id, name, user.username.orEmpty()))
        }
        found.sortByDescending { it.first }
        all = found.map { it.second }
    }

    private fun render(query: String) {
        val themed = themedContext ?: return
        progress?.visibility = View.GONE
        val q = query.trim().lowercase()
        val rows = if (q.isEmpty()) all else all.filter {
            it.name.lowercase().contains(q) || it.username.lowercase().contains(q)
        }
        list?.removeAllViews()
        for (entry in rows) {
            val row = LayoutInflater.from(themed)
                .inflate(R.layout.item_yuz_trust_chat, list, false)
            val initials = initials(entry.name)
            row.findViewById<TextView>(R.id.chatInitials).text = initials
            row.findViewById<TextView>(R.id.chatTitle).text = entry.name
            row.findViewById<TextView>(R.id.chatMeta).text =
                if (entry.username.isNotBlank()) "@${entry.username}" else ""
            row.findViewById<TextView>(R.id.chatTime).text = ""
            row.setOnClickListener {
                onPick("tg_${entry.id}", entry.name, entry.id)
                finishFragment()
            }
            list?.addView(row)
        }
        empty?.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun initials(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        val letters = parts.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
        return if (letters.isEmpty()) "?" else letters.joinToString("")
    }
}