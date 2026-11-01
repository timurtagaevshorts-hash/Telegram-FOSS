package uz.kodlikino.top4.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.games.CheckersRoom
import uz.kodlikino.top4.games.GamesCore
import uz.kodlikino.top4.games.MafiaRoom

/**
 * Kiruvchi o‘yin taklifi — 15 soniyalik sanoq bilan tasdiqlash/rad etish.
 *
 * Chat ochiq turganda [attach] chaqiriladi; Firestore’dagi `invited` massivi
 * orqali menga yo‘naltirilgan yangi taklif kelishi bilan karta ko‘rsatiladi.
 * Sanoq tugasa — taklif avtomatik rad etiladi, chatdagi xabar qoladi.
 */
object GameInvitePrompt {

    private const val COUNTDOWN_SECONDS = 15
    private const val MAFIA_INVITE_TTL_MS = 10 * 60_000L

    private var host: BaseFragment? = null
    private var account = 0
    private var chatId = 0L
    private var scope: CoroutineScope? = null
    private var listener: ListenerRegistration? = null

    private var dialog: android.app.Dialog? = null
    private var countView: TextView? = null
    private var progressView: ProgressBar? = null
    private var activeInvite: Invite? = null
    private var remaining = COUNTDOWN_SECONDS
    private var lastDocs: List<DocumentSnapshot> = emptyList()

    private val handled = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())

    private data class Invite(
        val id: String,
        val kind: String,
        val chatTitle: String,
        val hostName: String,
        val createdAt: Long
    )

    private val tick = object : Runnable {
        override fun run() {
            val invite = activeInvite ?: return
            remaining -= 1
            if (remaining <= 0) {
                decline(invite, expired = true)
                return
            }
            paintCountdown()
            handler.postDelayed(this, 1000L)
        }
    }

    // ------------------------------------------------------------- lifecycle

    @JvmStatic
    fun attach(fragment: BaseFragment, account: Int, chatId: Long) {
        if (chatId == 0L) return
        if (this.host === fragment && this.account == account &&
            this.chatId == chatId && listener != null
        ) {
            return
        }
        detach()
        host = fragment
        this.account = account
        this.chatId = chatId
        val uid = GamesCore.uidOf(account)
        if (uid.isBlank()) return
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = coroutineScope
        coroutineScope.launch {
            try {
                GamesCore.signIn(account)
            } catch (_: Exception) {
                return@launch
            }
            if (host !== fragment || scope !== coroutineScope) return@launch
            if (listener != null) return@launch
            listener = GamesCore.rooms()
                .whereArrayContains("invited", uid)
                .addSnapshotListener { snap, error ->
                    if (error != null || snap == null) return@addSnapshotListener
                    if (host !== fragment) return@addSnapshotListener
                    lastDocs = snap.documents
                    onDocs(lastDocs)
                }
        }
    }

    @JvmStatic
    fun detach() {
        closeDialog()
        listener?.remove()
        listener = null
        scope?.cancel()
        scope = null
        host = null
        lastDocs = emptyList()
        account = 0
        chatId = 0L
    }

    // ---------------------------------------------------------------- incoming

    private fun onDocs(docs: List<DocumentSnapshot>) {
        if (dialog != null) return
        if (host?.parentActivity == null) return
        val now = GamesCore.now()
        var best: Invite? = null
        for (doc in docs) {
            val kind = doc.getString("kind") ?: continue
            if (kind != GamesCore.KIND_CHECKERS && kind != GamesCore.KIND_MAFIA) continue
            if (doc.getString("status") != GamesCore.STATUS_INVITE) continue
            if (doc.getLong("chatId") != chatId) continue
            if (handled.contains(doc.id)) continue
            val created = doc.getLong("createdAt") ?: 0L
            if (kind == GamesCore.KIND_CHECKERS) {
                val expires = doc.getLong("expiresAt") ?: 0L
                if (expires in 1..now) continue
            } else if (created > 0L && now - created > MAFIA_INVITE_TTL_MS) {
                continue
            }
            val hostName = GamesCore.parsePlayers(doc.get("players")).firstOrNull()?.name ?: ""
            val candidate = Invite(
                id = doc.id,
                kind = kind,
                chatTitle = doc.getString("chatTitle").orEmpty(),
                hostName = hostName,
                createdAt = created
            )
            if (best == null || candidate.createdAt > best.createdAt) best = candidate
        }
        val invite = best ?: return
        show(invite)
    }

    // --------------------------------------------------------------- dialog

    private fun show(invite: Invite) {
        val fragment = host ?: return
        val activity = fragment.parentActivity ?: return
        handled.add(invite.id)
        activeInvite = invite

        val themed = YuzTheme.wrap(activity)
        val card = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(themed, R.color.yuz_surface_raised, 22f)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(16))
        }

        val badge = TextView(themed).apply {
            text = if (invite.kind == GamesCore.KIND_MAFIA) "🎭" else "🎮"
            textSize = 30f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            background = rounded(themed, R.color.yuz_field, 32f).apply {
                setStroke(dp(1.2f), ContextCompat.getColor(themed, R.color.yuz_stroke))
            }
        }
        card.addView(badge)

        card.addView(
            TextView(themed).apply {
                text = themed.getString(
                    if (invite.kind == GamesCore.KIND_MAFIA) R.string.games_invite_prompt_mafia
                    else R.string.games_invite_prompt_checkers
                )
                setTextColor(ContextCompat.getColor(themed, R.color.yuz_text_bright))
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            }
        )

        card.addView(
            TextView(themed).apply {
                text = themed.getString(
                    R.string.games_invite_prompt_body,
                    invite.hostName.ifBlank { themed.getString(R.string.games_unknown_host) }
                )
                setTextColor(ContextCompat.getColor(themed, R.color.yuz_text_secondary))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }
        )

        if (invite.chatTitle.isNotBlank()) {
            card.addView(
                TextView(themed).apply {
                    text = invite.chatTitle
                    setTextColor(ContextCompat.getColor(themed, R.color.yuz_action))
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }

        countView = TextView(themed).apply {
            text = COUNTDOWN_SECONDS.toString()
            setTextColor(ContextCompat.getColor(themed, R.color.yuz_action))
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        card.addView(countView)

        progressView = ProgressBar(
            themed,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = COUNTDOWN_SECONDS
            progress = COUNTDOWN_SECONDS
            progressTintList = ContextCompat.getColorStateList(themed, R.color.yuz_action)
            progressBackgroundTintList =
                ContextCompat.getColorStateList(themed, R.color.yuz_stroke)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(6)
            ).apply { topMargin = dp(6) }
        }
        card.addView(progressView)

        card.addView(
            TextView(themed).apply {
                text = themed.getString(R.string.games_invite_seconds, COUNTDOWN_SECONDS)
                setTextColor(ContextCompat.getColor(themed, R.color.yuz_text_secondary))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }
        )

        val accept = MaterialButton(themed).apply {
            text = themed.getString(R.string.games_accept)
            setTextColor(ContextCompat.getColor(themed, R.color.yuz_bg))
            backgroundTintList = ContextCompat.getColorStateList(themed, R.color.yuz_action)
            cornerRadius = dp(14)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(16) }
            setOnClickListener { accept(invite) }
        }
        card.addView(accept)

        val declineBtn = MaterialButton(
            themed,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = themed.getString(R.string.games_decline)
            setTextColor(ContextCompat.getColor(themed, R.color.yuz_text))
            strokeColor = ContextCompat.getColorStateList(themed, R.color.yuz_stroke)
            strokeWidth = dp(1.2f)
            cornerRadius = dp(14)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply { topMargin = dp(8) }
            setOnClickListener { decline(invite, expired = false) }
        }
        card.addView(declineBtn)

        remaining = COUNTDOWN_SECONDS
        val d = android.app.Dialog(themed, R.style.TransparentDialog)
        val frame = FrameLayout(themed).apply {
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            )
        }
        d.setContentView(frame)
        d.setCanceledOnTouchOutside(false)
        d.setOnCancelListener { decline(invite, expired = false) }
        d.show()
        dialog = d
        paintCountdown()
        handler.postDelayed(tick, 1000L)
    }

    private fun paintCountdown() {
        countView?.text = remaining.toString()
        progressView?.progress = remaining
    }

    private fun closeDialog() {
        handler.removeCallbacks(tick)
        activeInvite = null
        countView = null
        progressView = null
        val d = dialog
        dialog = null
        try {
            d?.dismiss()
        } catch (_: Exception) {
        }
        // Oyna yopilganda navbatdagi kutilayotgan taklifni ko‘rsatamiz.
        if (lastDocs.isNotEmpty()) handler.post { onDocs(lastDocs) }
    }

    // -------------------------------------------------------------- actions

    private fun accept(invite: Invite) {
        closeDialog()
        val fragment = host ?: return
        scope?.launch {
            val ok = try {
                if (invite.kind == GamesCore.KIND_MAFIA) {
                    when (MafiaRoom.join(account, invite.id)) {
                        MafiaRoom.JoinResult.OK, MafiaRoom.JoinResult.ALREADY -> true
                        else -> false
                    }
                } else {
                    CheckersRoom.accept(account, invite.id)
                }
            } catch (_: Exception) {
                false
            }
            val activity = fragment.parentActivity ?: return@launch
            if (!ok) {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.games_invite_accept_failed),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            if (invite.kind == GamesCore.KIND_MAFIA) {
                fragment.presentFragment(MafiaFragment(invite.id, invite.chatTitle), false)
            } else {
                fragment.presentFragment(CheckersFragment(invite.id, invite.chatTitle), false)
            }
        }
    }

    private fun decline(invite: Invite, expired: Boolean) {
        closeDialog()
        scope?.launch {
            try {
                if (invite.kind == GamesCore.KIND_MAFIA) {
                    MafiaRoom.decline(account, invite.id)
                } else {
                    CheckersRoom.decline(account, invite.id)
                }
            } catch (_: Exception) {
            }
        }
        if (expired) {
            val activity = host?.parentActivity ?: return
            Toast.makeText(
                activity,
                activity.getString(R.string.games_invite_expired),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // -------------------------------------------------------------- styling

    private fun rounded(context: Context, colorRes: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, colorRes))
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(value: Float): Int = AndroidUtilities.dp(value)

    private fun dp(value: Int): Int = AndroidUtilities.dp(value.toFloat())
}
