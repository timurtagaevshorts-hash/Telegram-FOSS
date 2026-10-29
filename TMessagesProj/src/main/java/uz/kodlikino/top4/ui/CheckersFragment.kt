package uz.kodlikino.top4.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.games.CheckersEngine
import uz.kodlikino.top4.games.CheckersRoom
import uz.kodlikino.top4.games.GameSfx
import uz.kodlikino.top4.games.GameVoice
import uz.kodlikino.top4.games.GamesCore
import java.util.Locale

/**
 * Shashka stoli: 8x8 taxta, soatlar, taslim/qayta o‘ynash.
 *
 * Yurish mantiqiy ravishda bitta atomar harakat — [CheckersBoardView] zanjirni
 * bosqichma-bosqich teradi va to‘liq variantga yetganda `wire` satrini beradi.
 */
class CheckersFragment(
    private val roomId: String = "",
    private val chatTitle: String = ""
) : BaseFragment() {

    private enum class FinishAction { NONE, REMATCH, TIMEOUT }

    private var scope: CoroutineScope? = null
    private var themedContext: Context? = null
    private var listener: ListenerRegistration? = null

    private var game: CheckersRoom.Game? = null
    private var busy = false
    private var liveActive = false
    private var finishAction = FinishAction.NONE

    private var content: View? = null
    private var waitingPanel: View? = null
    private var board: CheckersBoardView? = null
    private var oppAvatar: TextView? = null
    private var oppName: TextView? = null
    private var oppMeta: TextView? = null
    private var oppClock: TextView? = null
    private var myAvatar: TextView? = null
    private var myName: TextView? = null
    private var myMeta: TextView? = null
    private var myClock: TextView? = null
    private var status: TextView? = null
    private var btnResign: MaterialButton? = null
    private var btnRematch: MaterialButton? = null
    private var btnCancel: MaterialButton? = null
    private var mic: GameMicButton? = null
    private var voiceView: TextView? = null
    private var btnRules: TextView? = null
    private var voiceToggle: TextView? = null
    private var reactionOverlay: GameReactionView? = null

    private var voiceEnabled = true
    private var lastReactionAt = 0L
    private var reactionSeeded = false

    private var voiceBusy = false
    private var lastVoiceAt = 0L
    private var playingVoiceAt = 0L
    private var soundKey = ""
    private var prevTurn: CheckersEngine.Side? = null
    private var prevActive = false
    private var prevFinished = false
    private var prevPieces = -1
    private var lastAutoClaimAt = 0L

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClocks()
            if (GameVoice.isRecording()) mic?.setElapsed(GameVoice.elapsedMs())
            maybeAutoClaim()
            clockHandler.postDelayed(this, if (GameVoice.isRecording()) 200L else 1000L)
        }
    }

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_checkers_game, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)
        YuzTopInset.applyPadding(root.findViewById(R.id.chkTopBar))

        root.findViewById<ImageButton>(R.id.btnChkBack)
            .setOnClickListener { removeSelfFromStack() }
        root.findViewById<TextView>(R.id.tvChkSubtitle).text = chatTitle

        content = root.findViewById(R.id.chkContent)
        waitingPanel = root.findViewById(R.id.chkWaitingPanel)
        oppAvatar = root.findViewById(R.id.tvChkOppAvatar)
        oppName = root.findViewById(R.id.tvChkOppName)
        oppMeta = root.findViewById(R.id.tvChkOppMeta)
        oppClock = root.findViewById(R.id.tvChkOppClock)
        myAvatar = root.findViewById(R.id.tvChkMyAvatar)
        myName = root.findViewById(R.id.tvChkMyName)
        myMeta = root.findViewById(R.id.tvChkMyMeta)
        myClock = root.findViewById(R.id.tvChkMyClock)
        status = root.findViewById(R.id.tvChkStatus)
        btnResign = root.findViewById(R.id.btnChkResign)
        btnRematch = root.findViewById(R.id.btnChkRematch)
        btnCancel = root.findViewById(R.id.btnChkCancel)
        mic = root.findViewById(R.id.btnChkMic)
        voiceView = root.findViewById(R.id.tvChkVoice)
        btnRules = root.findViewById(R.id.btnChkRules)
        voiceToggle = root.findViewById(R.id.btnChkVoiceToggle)
        reactionOverlay = root.findViewById(R.id.chkReactionOverlay)

        voiceEnabled = GameVoice.isEnabled(themed)
        GameReactionView.buildChips(root.findViewById(R.id.chkReactionRow)) { sendReaction(it) }
        voiceToggle?.setOnClickListener { toggleVoiceEnabled() }
        paintVoiceState()

        mic?.onHoldStart = { startVoice() }
        mic?.onHoldEnd = { finishVoice() }
        mic?.onHoldCancel = {
            GameVoice.cancel()
            mic?.setMode(GameMicButton.Mode.IDLE)
            voiceView?.text = str(R.string.games_voice_idle)
        }
        btnRules?.setOnClickListener {
            GameRulesDialog.show(ctx(), GameRulesDialog.CHECKERS)
        }
        voiceView?.setOnClickListener { toggleVoicePlayback() }

        board = root.findViewById<CheckersBoardView>(R.id.chkBoard).apply {
            onMove = { wire -> submitMove(wire) }
        }

        btnResign?.setOnClickListener { confirmResign() }
        btnRematch?.setOnClickListener { onFinishAction() }
        btnCancel?.setOnClickListener { cancelInvite() }

        GameSfx.prepare(themed)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        return root
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        if (fragmentView == null) return
        liveActive = true
        scope?.launch {
            try {
                GamesCore.signIn(currentAccount)
            } catch (_: Exception) {
                liveActive = false
                toast(R.string.games_sign_in_error)
                return@launch
            }
            if (!liveActive || listener != null) return@launch
            listener = CheckersRoom.listen(roomId) { g -> onGame(g) }
        }
        clockHandler.removeCallbacks(clockTick)
        clockHandler.post(clockTick)
    }

    override fun onPause() {
        liveActive = false
        listener?.remove()
        listener = null
        clockHandler.removeCallbacks(clockTick)
        GameVoice.cancel()
        GameVoice.stopPlayback()
        playingVoiceAt = 0L
        mic?.setMode(GameMicButton.Mode.IDLE)
        reactionOverlay?.hideNow()
        super.onPause()
    }

    override fun onFragmentDestroy() {
        listener?.remove()
        listener = null
        clockHandler.removeCallbacks(clockTick)
        GameVoice.release()
        GameSfx.release()
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }

    override fun onRequestPermissionsResultFragment(
        requestCode: Int,
        permissions: Array<out String>?,
        grantResults: IntArray?
    ) {
        if (requestCode != REQ_MIC) return
        val granted = grantResults?.isNotEmpty() == true &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            toast(R.string.games_voice_denied)
        } else {
            voiceView?.text = str(R.string.games_voice_hint)
        }
    }

    // ----------------------------------------------------------------- state

    private fun onGame(g: CheckersRoom.Game?) {
        if (g == null) {
            toast(R.string.chk_popped)
            removeSelfFromStack()
            return
        }
        game = g
        render(g)
        updateClocks()
    }

    private fun render(g: CheckersRoom.Game) {
        val me = GamesCore.uidOf(currentAccount)
        val mySide = g.sideOf(me)
        val waiting = g.status == GamesCore.STATUS_INVITE

        syncVoice(g, me)
        syncReaction(g)

        waitingPanel?.visibility = if (waiting) View.VISIBLE else View.GONE
        content?.visibility = if (waiting) View.GONE else View.VISIBLE
        if (waiting) return

        syncFeedback(g, me, mySide)

        val top: GamesCore.Player?
        val bottom: GamesCore.Player?
        if (mySide == CheckersEngine.Side.BLACK) {
            top = g.white
            bottom = g.black
        } else {
            top = g.black
            bottom = g.white
        }

        bindRow(top, oppAvatar, oppName, oppMeta, g)
        bindRow(bottom, myAvatar, myName, myMeta, g)

        board?.setPosition(
            board = g.board,
            mySide = mySide,
            turn = g.turn,
            lastFrom = g.lastFrom,
            lastTo = g.lastTo,
            interactive = g.status == GamesCore.STATUS_ACTIVE && !busy
        )

        status?.text = statusText(g, me, mySide)

        val finished = g.isFinished()
        val canTimeout = g.canClaimTimeout(me)
        finishAction = when {
            finished -> FinishAction.REMATCH
            canTimeout -> FinishAction.TIMEOUT
            else -> FinishAction.NONE
        }
        btnResign?.visibility =
            if (g.status == GamesCore.STATUS_ACTIVE && mySide != null) View.VISIBLE else View.GONE
        btnRematch?.visibility =
            if (finishAction == FinishAction.NONE) View.GONE else View.VISIBLE
        btnRematch?.text = str(
            if (finishAction == FinishAction.TIMEOUT) R.string.chk_timeout_claim
            else R.string.chk_rematch
        )
    }

    private fun bindRow(
        player: GamesCore.Player?,
        avatar: TextView?,
        name: TextView?,
        meta: TextView?,
        game: CheckersRoom.Game
    ) {
        val me = GamesCore.uidOf(currentAccount)
        val avatarView = avatar ?: return
        if (player == null) {
            avatarView.text = "?"
            avatarView.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(ctx(), R.color.yuz_field))
            name?.text = str(R.string.chk_waiting_title)
            meta?.text = ""
            return
        }
        val p = player
        avatarView.text = GamesCore.initials(p.name)
        avatarView.backgroundTintList = ColorStateList.valueOf(GamesCore.colorFor(p.uid))
        name?.text = p.name

        val sideLabel = when {
            game.white?.uid == p.uid -> str(R.string.chk_white)
            game.black?.uid == p.uid -> str(R.string.chk_black)
            else -> ""
        }
        val mine = p.uid == me && game.sideOf(me) != null
        meta?.text = listOf(sideLabel, if (mine) str(R.string.chk_you) else "")
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    private fun statusText(
        g: CheckersRoom.Game,
        me: String,
        mySide: CheckersEngine.Side?
    ): String {
        if (g.isFinished()) {
            val outcome = when {
                g.result == CheckersEngine.End.DRAW || g.winnerUid.isBlank() ->
                    str(R.string.chk_draw)
                g.winnerUid == me -> str(R.string.chk_win)
                else -> str(R.string.chk_lose)
            }
            val reason = reasonText(g.resultReason)
            return if (reason.isBlank()) outcome else "$outcome · $reason"
        }
        if (g.status != GamesCore.STATUS_ACTIVE) return ""
        if (mySide == null) return str(R.string.games_status_active)
        if (g.turn == mySide) {
            val moves = CheckersEngine.legalMoves(g.board, mySide)
            return if (moves.isNotEmpty() && moves.all { it.isCapture }) {
                str(R.string.chk_must_capture)
            } else {
                str(R.string.chk_your_turn_voice)
            }
        }
        val opponent = if (mySide == CheckersEngine.Side.WHITE) g.black else g.white
        return str(R.string.chk_turn_opponent, opponent?.name.orEmpty())
    }

    private fun reasonText(reason: String): String = when (reason) {
        "no_pieces" -> str(R.string.chk_reason_no_pieces)
        "no_moves" -> str(R.string.chk_reason_no_moves)
        "draw_15" -> str(R.string.chk_reason_draw_15)
        "resign" -> str(R.string.chk_reason_resign)
        "timeout" -> str(R.string.chk_reason_timeout)
        else -> ""
    }

    private fun updateClocks() {
        val g = game ?: return
        val me = GamesCore.uidOf(currentAccount)
        val mySide = g.sideOf(me)
        if (g.status != GamesCore.STATUS_ACTIVE || g.deadlineAt <= 0L) {
            paintClock(myClock, null, false)
            paintClock(oppClock, null, false)
            return
        }
        val remaining = g.remainingMs()
        val full = g.moveSeconds * 1000L
        val myTurn = mySide != null && g.turn == mySide
        paintClock(myClock, if (myTurn) remaining else full, myTurn)
        paintClock(oppClock, if (!myTurn) remaining else full, !myTurn)
    }

    private fun paintClock(view: TextView?, ms: Long?, counting: Boolean) {
        val target = view ?: return
        if (ms == null) {
            target.text = "--:--"
            target.setTextColor(ContextCompat.getColor(ctx(), R.color.yuz_text_secondary))
            return
        }
        target.text = formatClock(ms)
        val danger = counting && ms < 15_000L
        target.setTextColor(
            ContextCompat.getColor(
                ctx(),
                if (danger) R.color.yuz_danger else R.color.yuz_action
            )
        )
    }

    private fun formatClock(ms: Long): String {
        val seconds = ((ms + 999L) / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", seconds / 60L, seconds % 60L)
    }

    // --------------------------------------------------------------- actions

    private fun submitMove(wire: String) {
        if (busy || wire.isBlank()) return
        busy = true
        game?.let { render(it) }
        scope?.launch {
            val result = try {
                CheckersRoom.move(currentAccount, roomId, wire)
            } catch (e: Exception) {
                CheckersRoom.MoveResult.ERROR
            }
            busy = false
            when (result) {
                CheckersRoom.MoveResult.OK -> Unit
                CheckersRoom.MoveResult.ILLEGAL,
                CheckersRoom.MoveResult.NOT_TURN,
                CheckersRoom.MoveResult.NOT_PLAYER -> {
                    board?.clearSelection()
                    toast(R.string.chk_illegal)
                }
                CheckersRoom.MoveResult.TIMEOUT -> toast(R.string.chk_timeout_claim)
                else -> toast(R.string.games_error)
            }
            game?.let { render(it) }
        }
    }

    // ------------------------------------------------------- sound and voice

    private fun syncFeedback(
        g: CheckersRoom.Game,
        me: String,
        mySide: CheckersEngine.Side?
    ) {
        val ctx = ctx()
        val active = g.status == GamesCore.STATUS_ACTIVE
        val finished = g.isFinished()
        val pieces = CheckersEngine.count(g.board, CheckersEngine.Side.WHITE) +
            CheckersEngine.count(g.board, CheckersEngine.Side.BLACK)

        if (!prevActive && active) GameSfx.play(ctx, GameSfx.START)
        if (!prevFinished && finished) {
            when {
                g.winnerUid.isNotEmpty() && g.winnerUid == me -> GameSfx.play(ctx, GameSfx.WIN)
                g.winnerUid.isEmpty() -> GameSfx.play(ctx, GameSfx.TURN)
                else -> GameSfx.play(ctx, GameSfx.LOSE)
            }
        }
        if (g.lastMove.isNotBlank() && g.lastMove != soundKey) {
            val first = soundKey.isBlank()
            soundKey = g.lastMove
            if (!first) {
                val captured = prevPieces >= 0 && pieces < prevPieces
                GameSfx.play(ctx, if (captured) GameSfx.CAPTURE else GameSfx.MOVE)
            }
        }
        if (prevTurn != null && prevTurn != g.turn && active &&
            mySide != null && g.turn == mySide
        ) {
            GameSfx.play(ctx, GameSfx.TURN)
        }
        prevTurn = g.turn
        prevActive = active
        prevFinished = finished
        prevPieces = pieces
    }

    // ------------------------------------------------------------ reactions

    /** Doskadagi yangi reaksiyani 2 soniyaga chiqaradi (kirishda eskisini ko‘rsatmaydi). */
    private fun syncReaction(g: CheckersRoom.Game) {
        val reaction = g.reaction
        if (!reactionSeeded) {
            reactionSeeded = true
            lastReactionAt = reaction?.at ?: 0L
            return
        }
        if (reaction == null || reaction.at <= lastReactionAt) return
        lastReactionAt = reaction.at
        reactionOverlay?.show(reaction.emoji, reaction.name)
    }

    private fun sendReaction(emoji: String) {
        GameSfx.play(ctx(), GameSfx.TAP, 0.7f)
        scope?.launch {
            val ok = try {
                CheckersRoom.sendReaction(currentAccount, roomId, emoji)
            } catch (e: Exception) {
                false
            }
            if (!ok) toast(R.string.games_reaction_failed)
        }
    }

    private fun toggleVoiceEnabled() {
        voiceEnabled = !voiceEnabled
        GameVoice.setEnabled(ctx(), voiceEnabled)
        if (!voiceEnabled) {
            playingVoiceAt = 0L
            mic?.setMode(GameMicButton.Mode.IDLE)
        }
        paintVoiceState()
        GameSfx.play(ctx(), GameSfx.TAP, 0.7f)
    }

    private fun paintVoiceState() {
        voiceToggle?.text =
            str(if (voiceEnabled) R.string.games_voice_on else R.string.games_voice_off)
        voiceToggle?.setTextColor(
            ContextCompat.getColor(
                ctx(),
                if (voiceEnabled) R.color.yuz_action else R.color.yuz_text_secondary
            )
        )
        mic?.alpha = if (voiceEnabled) 1f else 0.45f
        if (playingVoiceAt == 0L) {
            voiceView?.text = str(
                if (voiceEnabled) R.string.games_voice_idle else R.string.games_voice_off
            )
        }
    }

    private fun syncVoice(g: CheckersRoom.Game, me: String) {
        val voice = g.voice ?: return
        if (me.isNotEmpty() && voice.uid == me) return
        if (voice.at <= lastVoiceAt) return
        lastVoiceAt = voice.at
        if (!voiceEnabled) return
        playVoice(voice)
    }

    private fun playVoice(voice: GameVoice.Message) {
        val name = voice.name.ifBlank { str(R.string.games_unknown_host) }
        playingVoiceAt = voice.at
        voiceView?.text = str(R.string.games_voice_playing, name)
        GameSfx.play(ctx(), GameSfx.JOIN, 0.5f)
        GameVoice.play(voice.url) {
            if (playingVoiceAt == voice.at) {
                playingVoiceAt = 0L
                voiceView?.text = str(R.string.games_voice_idle)
            }
        }
    }

    private fun toggleVoicePlayback() {
        if (!voiceEnabled) {
            toast(R.string.games_voice_muted_hint)
            return
        }
        if (GameVoice.isPlaying()) {
            GameVoice.stopPlayback()
            playingVoiceAt = 0L
            voiceView?.text = str(R.string.games_voice_idle)
            return
        }
        val voice = game?.voice ?: return
        if (voice.url.isBlank()) return
        playVoice(voice)
    }

    private fun startVoice() {
        val ctx = ctx()
        if (!voiceEnabled) {
            mic?.setMode(GameMicButton.Mode.IDLE)
            toast(R.string.games_voice_muted_hint)
            return
        }
        if (!GameVoice.hasPermission(ctx)) {
            mic?.setMode(GameMicButton.Mode.IDLE)
            requestMicPermission()
            toast(R.string.games_voice_need_permission)
            return
        }
        if (voiceBusy) {
            mic?.setMode(GameMicButton.Mode.IDLE)
            return
        }
        GameVoice.stopPlayback()
        playingVoiceAt = 0L
        if (!GameVoice.start(ctx)) {
            mic?.setMode(GameMicButton.Mode.IDLE)
            toast(R.string.games_voice_failed)
            return
        }
        voiceView?.text = str(R.string.games_voice_hold)
        GameSfx.play(ctx, GameSfx.TAP)
    }

    private fun finishVoice() {
        if (!GameVoice.isRecording()) {
            mic?.setMode(GameMicButton.Mode.IDLE)
            return
        }
        val clip = GameVoice.stop()
        if (clip == null) {
            mic?.setMode(GameMicButton.Mode.IDLE)
            voiceView?.text = str(R.string.games_voice_idle)
            return
        }
        if (voiceBusy) {
            clip.file.delete()
            mic?.setMode(GameMicButton.Mode.IDLE)
            return
        }
        voiceBusy = true
        mic?.setMode(GameMicButton.Mode.BUSY)
        voiceView?.text = str(R.string.games_voice_sending)
        val me = GamesCore.uidOf(currentAccount)
        val name = GamesCore.me(currentAccount).name
        scope?.launch {
            val ok = try {
                GameVoice.publish(roomId, me, name, clip)
            } catch (e: Exception) {
                false
            }
            voiceBusy = false
            mic?.setMode(GameMicButton.Mode.IDLE)
            voiceView?.text = str(R.string.games_voice_idle)
            if (ok) {
                GameSfx.play(ctx(), GameSfx.TAP)
            } else {
                toast(R.string.games_voice_failed)
            }
        }
    }

    private fun requestMicPermission() {
        val activity = parentActivity ?: return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_MIC
        )
    }

    private fun maybeAutoClaim() {
        val g = game ?: return
        if (busy) return
        val me = GamesCore.uidOf(currentAccount)
        if (!g.canClaimTimeout(me)) return
        val now = GamesCore.now()
        if (now - lastAutoClaimAt < 12_000L) return
        lastAutoClaimAt = now
        claimTimeout()
    }

    private fun confirmResign() {
        val ctx = themedContext ?: return
        AlertDialog.Builder(ctx)
            .setMessage(R.string.chk_resign_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.chk_resign) { _, _ -> resign() }
            .show()
    }

    private fun resign() {
        scope?.launch {
            val ok = try {
                CheckersRoom.resign(currentAccount, roomId)
            } catch (e: Exception) {
                false
            }
            toast(if (ok) R.string.chk_resign_done else R.string.games_error)
        }
    }

    private fun onFinishAction() {
        when (finishAction) {
            FinishAction.TIMEOUT -> claimTimeout()
            FinishAction.REMATCH -> rematch()
            FinishAction.NONE -> Unit
        }
    }

    private fun claimTimeout() {
        scope?.launch {
            val ok = try {
                CheckersRoom.claimTimeout(currentAccount, roomId)
            } catch (e: Exception) {
                false
            }
            if (!ok) toast(R.string.games_error)
        }
    }

    private fun rematch() {
        val existing = game?.rematchRoomId.orEmpty()
        if (existing.isNotBlank()) {
            presentFragment(CheckersFragment(existing, chatTitle), false)
            return
        }
        scope?.launch {
            val id = try {
                CheckersRoom.rematch(currentAccount, roomId)
            } catch (e: Exception) {
                null
            }
            if (id.isNullOrBlank()) {
                toast(R.string.games_error)
            } else {
                presentFragment(CheckersFragment(id, chatTitle), true)
            }
        }
    }

    private fun cancelInvite() {
        scope?.launch {
            try {
                CheckersRoom.decline(currentAccount, roomId)
            } catch (_: Exception) {
            }
            removeSelfFromStack()
        }
    }

    // --------------------------------------------------------------- helpers

    private companion object {
        const val REQ_MIC = 4419
    }

    private fun ctx(): Context = themedContext ?: context ?: throw IllegalStateException()

    private fun str(resId: Int): String = ctx().getString(resId)

    private fun str(resId: Int, vararg args: Any): String = ctx().getString(resId, *args)

    private fun toast(resId: Int) {
        Toast.makeText(ctx(), str(resId), Toast.LENGTH_SHORT).show()
    }
}
