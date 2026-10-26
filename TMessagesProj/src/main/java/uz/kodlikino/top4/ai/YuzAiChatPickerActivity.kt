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
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import uz.kodlikino.top4.utils.YuzStoryFeed
import kotlin.math.min

/**
 * Bir xil ismga bir nechta chat mos kelganda ekran markazida chiqadigan
 * avatarli tanlov oynasi. Ilova ichidagi AI oqimi natijani
 * [Activity.onActivityResult] orqali oladi; fon xizmatidagi tezkor tugma esa
 * [listener] orqali (ActivityResult mavjud emas) tanlovni qaytaradi.
 *
 * Ataylab [BaseFragment] emas: tezkor tugma ilova yopiq bo'lganda ham
 * ishlaydi, fragment stack'i esa bu holatda mavjud emas.
 */
class YuzAiChatPickerActivity : Activity() {

    private var picked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        applyEdgeToEdge()
        val account = intent.getIntExtra(EXTRA_ACCOUNT, UserConfig.selectedAccount)
        setContentView(
            buildRoot(
                account,
                intent.getStringExtra(EXTRA_MESSAGE).orEmpty(),
                intent.getLongArrayExtra(EXTRA_IDS) ?: longArrayOf(),
                intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray(),
                intent.getStringArrayExtra(EXTRA_USERNAMES) ?: emptyArray()
            )
        )
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

    private fun buildRoot(
        account: Int,
        message: String,
        ids: LongArray,
        names: Array<String>,
        usernames: Array<String>
    ): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(color(R.color.yuz_scrim_strong))
            isClickable = true
            setOnClickListener { close(-1) }
        }

        val card = LinearLayout(this).apply {
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

        card.addView(
            TextView(this).apply {
                text = getString(R.string.ai_picker_title)
                setTextColor(color(R.color.yuz_text_bright))
                textSize = 18f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setPadding(dp(20), dp(18), dp(20), if (message.isBlank()) dp(12) else dp(4))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        if (message.isNotBlank()) {
            card.addView(
                TextView(this).apply {
                    text = getString(R.string.ai_picker_sending, message)
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
                buildRow(
                    account,
                    ids[i],
                    names.getOrElse(i) { "" },
                    usernames.getOrElse(i) { "" },
                    i
                ),
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
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (ids.size * dp(66)).coerceAtMost(maxList).coerceAtLeast(dp(66))
            )
        )

        card.addView(
            View(this).apply { setBackgroundColor(color(R.color.yuz_stroke)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        )
        card.addView(
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

        val cardWidth = min(
            resources.displayMetrics.widthPixels - dp(48),
            dp(380)
        ).coerceAtLeast(dp(240))
        root.addView(
            card,
            FrameLayout.LayoutParams(cardWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        return root
    }

    private fun buildRow(
        account: Int,
        dialogId: Long,
        name: String,
        username: String,
        index: Int
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            background = ripple(dp(14))
            setOnClickListener { close(index) }
        }

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
        return row
    }

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

    private fun close(index: Int) {
        if (picked) return
        picked = true
        val callback = listener
        listener = null
        if (callback != null) {
            if (index >= 0) callback.invoke(index)
        } else {
            setResult(
                if (index >= 0) RESULT_OK else RESULT_CANCELED,
                Intent().putExtra(EXTRA_INDEX, index)
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        close(-1)
    }

    override fun onDestroy() {
        if (isFinishing) listener = null
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
        const val EXTRA_INDEX = "index"

        /**
         * Fon xizmati Activity natijasini ololmaydi — tanlov shu callback orqali
         * qaytadi. Xizmat yo'q qilinsa, callback tozalanadi.
         */
        @Volatile
        var listener: ((Int) -> Unit)? = null

        @JvmStatic
        fun intent(
            context: Context,
            message: String,
            peers: List<YuzAiEngine.Peer>,
            account: Int
        ): Intent = Intent(context, YuzAiChatPickerActivity::class.java).apply {
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_IDS, peers.map { it.dialogId }.toLongArray())
            putExtra(EXTRA_NAMES, peers.map { it.name }.toTypedArray())
            putExtra(EXTRA_USERNAMES, peers.map { it.username ?: "" }.toTypedArray())
            putExtra(EXTRA_ACCOUNT, account)
        }
    }
}
