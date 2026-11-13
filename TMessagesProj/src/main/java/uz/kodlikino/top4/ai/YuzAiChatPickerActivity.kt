package uz.kodlikino.top4.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import uz.kodlikino.top4.ui.YuzTheme
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import uz.kodlikino.top4.utils.YuzStoryFeed
import kotlin.math.min

/**
 * Ekran markazidagi AI oynasi, ikki rejimda:
 *
 * 1) TANLOV — bir xil ismga bir nechta chat mos kelganda avatarli ro'yxat.
 * 2) TASDIQ — xabar yuborishdan oldin kimga va qanday matn ketishini ko'rsatadi;
 *    matn yonidagi qalam bilan tahrirlanadi. Bitta chat topilganda ham shu karta
 *    ochiladi — foydalanuvchi nima yuborilishini ko'rib tasdiqlaydi.
 *
 * Ilova ichidagi AI oqimi natijani [Activity.onActivityResult] orqali oladi;
 * fon xizmatidagi tezkor tugma esa [listener] orqali (ActivityResult mavjud emas).
 *
 * Ataylab [BaseFragment] emas: tezkor tugma ilova yopiq bo'lganda ham
 * ishlaydi, fragment stack'i esa bu holatda mavjud emas.
 */
class YuzAiChatPickerActivity : Activity() {

    private var picked = false
    // Fon xizmati chaqirig'i (listener) va ilova ichidagi chaqiriq
    // (setResult) aralashib ketmasligi uchun: token qaysi so'rovga tegishli
    // ekanini aniqlaydi, overlayMode esa natija qayerga ketishini hal qiladi.
    private var overlayMode = false
    private var requestToken = 0

    private var account = 0
    private var ids: LongArray = LongArray(0)
    private var names: Array<String> = emptyArray()
    private var usernames: Array<String> = emptyArray()
    private var message = ""
    /** Xabar yuborish oqimi: tanlovdan keyin ham tasdiq kartasi ochiladi. */
    private var sendFlow = false
    private var selected = -1
    private var editing = false

    private lateinit var root: FrameLayout
    private var card: LinearLayout? = null
    private var messageView: TextView? = null
    private var messageInput: EditText? = null
    private var sendButton: TextView? = null
    private var pencilButton: View? = null
    private var doneButton: View? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(YuzTheme.themeContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        overlayMode = intent.getBooleanExtra(EXTRA_OVERLAY, false)
        requestToken = intent.getIntExtra(EXTRA_TOKEN, 0)
        account = intent.getIntExtra(EXTRA_ACCOUNT, UserConfig.selectedAccount)
        ids = intent.getLongArrayExtra(EXTRA_IDS) ?: LongArray(0)
        names = intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
        usernames = intent.getStringArrayExtra(EXTRA_USERNAMES) ?: emptyArray()
        message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        sendFlow = intent.getBooleanExtra(EXTRA_SEND, false)
        applyEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        root = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.yuz_scrim_strong))
            isClickable = true
            setOnClickListener { close(-1) }
        }
        setContentView(root)

        if (sendFlow && ids.size == 1) showConfirm(0) else showList()
    }

    private fun applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    // ------------------------------------------------------------ card host

    private fun newCard(): LinearLayout {
        card?.let { runCatching { root.removeView(it) } }
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { }
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(color(R.color.yuz_surface_raised))
                setStroke(dp(1), color(R.color.yuz_stroke_strong))
            }
            elevation = dp(18).toFloat()
        }
        val lp = FrameLayout.LayoutParams(
            min(resources.displayMetrics.widthPixels - dp(48), dp(400)).coerceAtLeast(dp(240)),
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        if (sendFlow) {
            // Klaviatura ochilganda karta yopilib qolmasin — yuqoriga bog'laymiz.
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.topMargin = dp(64)
        } else {
            lp.gravity = Gravity.CENTER
        }
        root.addView(c, lp)
        card = c
        return c
    }

    private fun cardTitle(text: String, bottom: Int): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color(R.color.yuz_text_bright))
        textSize = 18f
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setPadding(dp(20), dp(18), dp(20), bottom)
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(color(R.color.yuz_stroke))
    }

    private fun dividerParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
    )

    // --------------------------------------------------------------- list

    private fun showList() {
        editing = false
        selected = -1
        val c = newCard()
        c.addView(
            cardTitle(getString(R.string.ai_picker_title), if (message.isBlank()) dp(12) else dp(4)),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        if (message.isNotBlank()) {
            c.addView(
                TextView(this).apply {
                    text = getString(R.string.ai_picker_sending, oneLine(message))
                    setTextColor(color(R.color.yuz_text_secondary))
                    textSize = 13f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(20), 0, dp(20), dp(12))
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in ids.indices) {
            list.addView(
                buildRow(i),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66))
            )
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(
                list,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val maxList = min(dp(384), resources.displayMetrics.heightPixels - dp(240))
            .coerceAtLeast(dp(66))
        c.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ids.size * dp(66)).coerceAtMost(maxList).coerceAtLeast(dp(66))
            )
        )

        c.addView(divider(), dividerParams())
        c.addView(
            TextView(this).apply {
                text = getString(R.string.ai_picker_cancel)
                setTextColor(color(R.color.yuz_action))
                textSize = 15f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = ripple(dp(20))
                setOnClickListener { close(-1) }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        )
    }

    private fun buildRow(index: Int): View {
        val dialogId = ids.getOrNull(index) ?: return View(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            background = ripple(dp(14))
            setOnClickListener {
                // Xabar oqimida avval tasdiq kartasi: kimga + qanday matn.
                if (sendFlow) showConfirm(index) else close(index, message)
            }
        }
        addPeerColumns(row, index, dialogId)
        return row
    }

    private fun addPeerColumns(row: LinearLayout, index: Int, dialogId: Long) {
        val name = names.getOrElse(index) { "" }
        val username = usernames.getOrElse(index) { "" }

        val avatarHost = FrameLayout(this)
        avatarHost.addView(
            TextView(this).apply {
                text = name.trim().take(1).ifBlank { "?" }.uppercase()
                setTextColor(color(R.color.yuz_text_secondary))
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color(R.color.yuz_field))
                }
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        loadAvatar(avatarHost, account, dialogId)
        row.addView(avatarHost, LinearLayout.LayoutParams(dp(48), dp(48)))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        column.addView(
            TextView(this).apply {
                text = name
                setTextColor(color(R.color.yuz_text))
                textSize = 16f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        if (username.isNotBlank()) {
            column.addView(
                TextView(this).apply {
                    text = "@$username"
                    setTextColor(color(R.color.yuz_text_secondary))
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            )
        }
        row.addView(
            column,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(14) }
        )
    }

    // ------------------------------------------------------------- confirm

    /** Kimga va qanday matn ketishini ko'rsatadigan karta; matn tahrirlanadi. */
    private fun showConfirm(index: Int) {
        val dialogId = ids.getOrNull(index) ?: return
        selected = index
        editing = false
        val c = newCard()

        c.addView(
            cardTitle(getString(R.string.ai_confirm_title), dp(6)),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        c.addView(
            TextView(this).apply {
                text = getString(R.string.ai_confirm_to)
                setTextColor(color(R.color.yuz_text_secondary))
                textSize = 12f
                setPadding(dp(20), 0, dp(20), dp(2))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val chatRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        addPeerColumns(chatRow, index, dialogId)
        c.addView(
            chatRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62))
        )

        c.addView(divider(), dividerParams())

        val block = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(6), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(color(R.color.yuz_field))
                setStroke(dp(1), color(R.color.yuz_stroke))
            }
        }

        messageView = TextView(this).apply {
            text = message
            setTextColor(color(R.color.yuz_text))
            textSize = 15f
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        block.addView(
            messageView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        messageInput = EditText(this).apply {
            setText(message)
            setTextColor(color(R.color.yuz_text))
            setHintTextColor(color(R.color.yuz_text_secondary))
            hint = getString(R.string.ai_confirm_hint)
            textSize = 15f
            background = null
            setPadding(0, 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setLineSpacing(dp(2).toFloat(), 1f)
            maxLines = 5
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = updateSendState()
            })
        }
        block.addView(
            messageInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        pencilButton = ImageButton(this).apply {
            setImageResource(R.drawable.msg_edit)
            imageTintList = ColorStateList.valueOf(color(R.color.yuz_action))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = getString(R.string.ai_confirm_edit)
            background = ripple(dp(20))
            setOnClickListener { startEdit() }
        }
        block.addView(
            pencilButton,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) }
        )

        doneButton = TextView(this).apply {
            text = getString(R.string.ai_confirm_done)
            setTextColor(color(R.color.yuz_action))
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            isClickable = true
            isFocusable = true
            background = ripple(dp(14))
            visibility = View.GONE
            setOnClickListener { finishEdit() }
        }
        block.addView(
            doneButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
                .apply { leftMargin = dp(4); rightMargin = dp(4) }
        )

        c.addView(
            block,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(20); rightMargin = dp(20); topMargin = dp(12)
            }
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.addView(
            TextView(this).apply {
                text = getString(R.string.ai_picker_cancel)
                setTextColor(color(R.color.yuz_text_secondary))
                textSize = 15f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = ripple(dp(14))
                setOnClickListener { close(-1) }
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        sendButton = TextView(this).apply {
            text = getString(R.string.ai_confirm_send)
            // yuz_action (#C2185B) ustida oq matn 2.57:1 — pleyer tugmasidagi
            // kabi to'q siyoh 7.3:1 kontrast beradi.
            setTextColor(color(R.color.yuz_text_on_accent))
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(color(R.color.yuz_action))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { commit() }
        }
        buttons.addView(
            sendButton,
            LinearLayout.LayoutParams(0, dp(48), 1.4f).apply { leftMargin = dp(12) }
        )
        c.addView(
            buttons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(20); rightMargin = dp(20); topMargin = dp(16); bottomMargin = dp(18)
            }
        )
        updateSendState()
    }

    private fun startEdit() {
        val input = messageInput ?: return
        editing = true
        messageView?.visibility = View.GONE
        pencilButton?.visibility = View.GONE
        doneButton?.visibility = View.VISIBLE
        input.visibility = View.VISIBLE
        input.setText(message)
        input.setSelection(input.text.length)
        input.requestFocus()
        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            runCatching { imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
        }
        updateSendState()
    }

    private fun finishEdit() {
        val input = messageInput ?: return
        message = input.text?.toString().orEmpty().trim()
        editing = false
        input.visibility = View.GONE
        messageView?.text = message
        messageView?.visibility = View.VISIBLE
        pencilButton?.visibility = View.VISIBLE
        doneButton?.visibility = View.GONE
        hideKeyboard()
        updateSendState()
    }

    private fun currentBody(): String =
        if (editing) messageInput?.text?.toString().orEmpty().trim() else message

    private fun updateSendState() {
        val button = sendButton ?: return
        val ok = currentBody().isNotBlank() && selected >= 0
        button.isEnabled = ok
        button.alpha = if (ok) 1f else 0.45f
    }

    /** Tanlangan chatga tahrirlangan matnni qaytaradi. */
    private fun commit() {
        val body = currentBody()
        if (body.isBlank() || selected < 0) return
        message = body
        close(selected, body)
    }

    // ------------------------------------------------------------- helpers

    private fun oneLine(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun loadAvatar(host: FrameLayout, account: Int, dialogId: Long) {
        try {
            val obj = YuzStoryFeed.peerObject(account, dialogId) ?: return
            val drawable = when (obj) {
                is TLRPC.User -> AvatarDrawable(obj)
                is TLRPC.Chat -> AvatarDrawable(obj)
                else -> return
            }
            val avatar = BackupImageView(this).apply {
                setRoundRadius(dp(24))
                imageReceiver.setCurrentAccount(account)
                setForUserOrChat(obj, drawable)
            }
            host.addView(
                avatar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        } catch (_: Exception) {
            // Avatar yuklanmasa — ism bosh harfi ko'rinadi.
        }
    }

    private fun ripple(radiusDp: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(color(R.color.yuz_ripple)), null, mask
        )
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(root.windowToken, 0)
        } catch (_: Exception) {
        }
    }

    private fun close(index: Int, text: String = "") {
        if (picked) return
        picked = true
        hideKeyboard()
        val callback = takeOverlayCallback()
        if (callback != null) {
            if (index >= 0) callback.invoke(index, text)
        } else {
            setResult(
                if (index >= 0) RESULT_OK else RESULT_CANCELED,
                Intent()
                    .putExtra(EXTRA_INDEX, index)
                    .putExtra(EXTRA_TEXT, text)
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }

    /**
     * Statik callback faqat shu Activity'ni ochgan overlay so'roviga tegishli
     * bo'lsa olinadi. Aks holda (ilova ichidagi tanlov, yoki eskirgan oyna)
     * listener tegilmaydi — u yangi so'rovnikidir.
     */
    private fun takeOverlayCallback(): ((Int, String) -> Unit)? {
        if (!overlayMode || requestToken == 0 || requestToken != overlayRequestToken) {
            return null
        }
        val callback = listener
        listener = null
        overlayRequestToken = 0
        return callback
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sendFlow && ids.size > 1 && selected >= 0 && !picked) {
            // Tasdiqdan ro'yxatga qaytamiz — oyna yopilib ketmasin.
            showList()
            return
        }
        close(-1)
    }

    override fun onDestroy() {
        if (isFinishing) takeOverlayCallback()
        super.onDestroy()
    }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_IDS = "ids"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_USERNAMES = "usernames"
        private const val EXTRA_ACCOUNT = "account"
        private const val EXTRA_OVERLAY = "overlay"
        private const val EXTRA_SEND = "send"
        private const val EXTRA_TOKEN = "token"
        const val EXTRA_INDEX = "index"
        const val EXTRA_TEXT = "text"

        /**
         * Fon xizmati Activity natijasini ololmaydi — tanlov shu callback orqali
         * qaytadi. Matn tahrirlangan bo'lsa, ikkinchi argument yangilangan matn.
         * Xizmat yo'q qilinsa, callback tozalanadi.
         */
        @Volatile
        var listener: ((Int, String) -> Unit)? = null

        /** Oxirgi overlay so'rovi tokeni — eskirgan oyna uni o'g'irlamasin. */
        @Volatile
        private var overlayRequestToken = 0

        /**
         * [send] = true bo'lsa xabar oqimi: bitta chat bo'lsa darhol tasdiq
         * kartasi, bir nechta bo'lsa ro'yxatdan keyin tasdiq kartasi ochiladi.
         */
        @JvmStatic
        @JvmOverloads
        fun intent(
            context: Context,
            message: String,
            peers: List<YuzAiEngine.Peer>,
            account: Int,
            overlay: Boolean = false,
            send: Boolean = false
        ): Intent = Intent(context, YuzAiChatPickerActivity::class.java).apply {
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_IDS, peers.map { it.dialogId }.toLongArray())
            putExtra(EXTRA_NAMES, peers.map { it.name }.toTypedArray())
            putExtra(EXTRA_USERNAMES, peers.map { it.username ?: "" }.toTypedArray())
            putExtra(EXTRA_ACCOUNT, account)
            putExtra(EXTRA_OVERLAY, overlay)
            putExtra(EXTRA_SEND, send)
            if (overlay) {
                overlayRequestToken += 1
                putExtra(EXTRA_TOKEN, overlayRequestToken)
            }
        }
    }
}
