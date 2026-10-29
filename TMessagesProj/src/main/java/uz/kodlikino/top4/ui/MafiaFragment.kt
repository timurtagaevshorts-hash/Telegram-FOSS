package uz.kodlikino.top4.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
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
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.games.GameSfx
import uz.kodlikino.top4.games.GameVoice
import uz.kodlikino.top4.games.GamesCore
import uz.kodlikino.top4.games.MafiaRoom
import uz.kodlikino.top4.games.MafiaRules
import java.util.Locale

/**
 * Mafiya xonasi: kutish zali, tun tanlovi, kunduzgi muhokama, ovoz berish.
 *
 * Rol va tungi tanlovlar faqat o‘z maxfiy hujjatidan o‘qiladi. Faza vaqtini
 * xost boshqaradi: taymer tugagach xost tranzaksiyada keyingi fazaga o‘tadi.
 * Xost 25 s jim qolsa, tirik o‘yinchi xostlikni o‘z zimmasiga olishi mumkin.
 */
class MafiaFragment(
    private val roomId: String = "",
    private val chatTitle: String = ""
) : BaseFragment() {

    private enum class Primary { NONE, START, TAKE_HOST, ADVANCE }

    private enum class Secondary { NONE, LEAVE, SKIP }

    private var scope: CoroutineScope? = null
    private var themedContext: Context? = null
    private var roomListener: ListenerRegistration? = null
    private var secretListener: ListenerRegistration? = null

    private var room: MafiaRoom.Room? = null
    private var secret: MafiaRoom.Secret? = null
    private var working = false
    private var liveActive = false

    private var primaryMode = Primary.NONE
    private var secondaryMode = Secondary.NONE

    private var phaseView: TextView? = null
    private var timerView: TextView? = null
    private var phaseHint: TextView? = null
    private var roleCard: View? = null
    private var roleName: TextView? = null
    private var roleDesc: TextView? = null
    private var checkView: TextView? = null
    private var winnerView: TextView? = null
    private var seatsMeta: TextView? = null
    private var seatGrid: LinearLayout? = null
    private var hintView: TextView? = null
    private var btnPrimary: MaterialButton? = null
    private var btnSecondary: MaterialButton? = null
    private var logBox: LinearLayout? = null
    private var noticeView: TextView? = null
    private var teamView: TextView? = null
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
    private var prevPhase: MafiaRules.Phase? = null
    private var prevAlive = -1
    private var prevSeats = -1
    private var prevWinner: MafiaRules.Winner? = null
    private var prevStatus = ""

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateTimer()
            if (GameVoice.isRecording()) mic?.setElapsed(GameVoice.elapsedMs())
            driveHost()
            beatHost()
            ticker.postDelayed(this, 1000L)
        }
    }

    private var lastBeatAt = 0L

    private fun myUid(): String = GamesCore.uidOf(currentAccount)

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_mafia_game, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)
        YuzTopInset.applyPadding(root.findViewById(R.id.mafTopBar))

        root.findViewById<ImageButton>(R.id.btnMafBack)
            .setOnClickListener { removeSelfFromStack() }
        root.findViewById<TextView>(R.id.tvMafSubtitle).text = chatTitle

        phaseView = root.findViewById(R.id.tvMafPhase)
        timerView = root.findViewById(R.id.tvMafTimer)
        phaseHint = root.findViewById(R.id.tvMafPhaseHint)
        roleCard = root.findViewById(R.id.mafRoleCard)
        roleName = root.findViewById(R.id.tvMafRoleName)
        roleDesc = root.findViewById(R.id.tvMafRoleDesc)
        checkView = root.findViewById(R.id.tvMafCheck)
        winnerView = root.findViewById(R.id.tvMafWinner)
        seatsMeta = root.findViewById(R.id.tvMafSeatsMeta)
        seatGrid = root.findViewById(R.id.mafSeatGrid)
        hintView = root.findViewById(R.id.tvMafHint)
        btnPrimary = root.findViewById(R.id.btnMafPrimary)
        btnSecondary = root.findViewById(R.id.btnMafSecondary)
        logBox = root.findViewById(R.id.mafLog)
        noticeView = root.findViewById(R.id.tvMafNotice)
        teamView = root.findViewById(R.id.tvMafTeam)
        mic = root.findViewById(R.id.btnMafMic)
        voiceView = root.findViewById(R.id.tvMafVoice)
        btnRules = root.findViewById(R.id.btnMafRules)
        voiceToggle = root.findViewById(R.id.btnMafVoiceToggle)
        reactionOverlay = root.findViewById(R.id.mafReactionOverlay)

        voiceEnabled = GameVoice.isEnabled(themed)
        GameReactionView.buildChips(root.findViewById(R.id.mafReactionRow)) { sendReaction(it) }
        voiceToggle?.setOnClickListener { toggleVoiceEnabled() }
        paintVoiceState()

        btnPrimary?.setOnClickListener { onPrimary() }
        btnSecondary?.setOnClickListener { onSecondary() }

        mic?.onHoldStart = { startVoice() }
        mic?.onHoldEnd = { finishVoice() }
        mic?.onHoldCancel = {
            GameVoice.cancel()
            mic?.setMode(GameMicButton.Mode.IDLE)
            voiceView?.text = str(R.string.games_voice_idle)
        }
        btnRules?.setOnClickListener {
            GameRulesDialog.show(ctx(), GameRulesDialog.MAFIA)
        }
        voiceView?.setOnClickListener { toggleVoicePlayback() }

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
            if (!liveActive || roomListener != null) return@launch
            val uid = myUid()
            roomListener = MafiaRoom.listen(roomId) { r -> onRoom(r) }
            if (secretListener == null && uid.isNotBlank()) {
                secretListener = MafiaRoom.listenSecret(roomId, uid) { s ->
                    secret = s
                    room?.let { render(it) }
                }
            }
        }
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    override fun onPause() {
        liveActive = false
        roomListener?.remove()
        roomListener = null
        secretListener?.remove()
        secretListener = null
        ticker.removeCallbacks(tick)
        GameVoice.cancel()
        GameVoice.stopPlayback()
        playingVoiceAt = 0L
        mic?.setMode(GameMicButton.Mode.IDLE)
        reactionOverlay?.hideNow()
        super.onPause()
    }

    override fun onFragmentDestroy() {
        roomListener?.remove()
        roomListener = null
        secretListener?.remove()
        secretListener = null
        ticker.removeCallbacks(tick)
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

    private fun onRoom(r: MafiaRoom.Room?) {
        if (r == null) {
            toast(R.string.games_error)
            removeSelfFromStack()
            return
        }
        room = r
        render(r)
        updateTimer()
    }

    private fun render(r: MafiaRoom.Room) {
        val me = myUid()
        val mine = r.seatOf(me)
        val alive = mine?.alive == true
        val host = r.isHost(me)

        phaseView?.text = phaseLabel(r)
        phaseHint?.text = phaseHintText(r)
        phaseHint?.visibility = if (phaseHint?.text.isNullOrBlank()) View.GONE else View.VISIBLE

        val role = secret?.role
        val showRole = role != null && !r.isLobby()
        roleCard?.visibility = if (showRole) View.VISIBLE else View.GONE
        if (showRole && role != null) {
            roleName?.text = roleLabel(role)
            roleDesc?.text = roleDesc(role)
            val check = secret?.check.orEmpty()
            if (role == MafiaRules.Role.COMMISSAR && check.isNotBlank()) {
                val target = r.seats.firstOrNull { it.uid == check }?.name.orEmpty()
                val verdict = if (secret?.checkRole?.isMafia == true) {
                    str(R.string.maf_check_mafia)
                } else {
                    str(R.string.maf_check_city)
                }
                checkView?.text = str(R.string.maf_check_result, target, verdict)
                checkView?.visibility = View.VISIBLE
            } else {
                checkView?.visibility = View.GONE
            }
        }

        winnerView?.visibility = if (r.isFinished() && r.winner != null) View.VISIBLE else View.GONE
        winnerView?.text = when (r.winner) {
            MafiaRules.Winner.MAFIA -> str(R.string.maf_winner_mafia)
            MafiaRules.Winner.CITY -> str(R.string.maf_winner_city)
            null -> ""
        }

        renderNotice(r)
        renderTeam(r)
        syncVoice(r, me)
        syncReaction(r)
        syncFeedback(r, me)

        seatsMeta?.text = listOf(
            str(R.string.maf_alive, r.aliveCount()),
            str(R.string.maf_round, r.day)
        ).joinToString(" · ")

        renderSeats(r, me, alive)
        renderHint(r, me, mine, alive)
        renderButtons(r, me, host, alive)
        renderLog(r)
    }

    private fun renderSeats(r: MafiaRoom.Room, me: String, alive: Boolean) {
        val grid = seatGrid ?: return
        grid.removeAllViews()
        for (seat in r.seats.sortedBy { it.seat }) {
            grid.addView(buildSeatRow(r, seat, me, alive))
        }
    }

    private fun buildSeatRow(
        r: MafiaRoom.Room,
        seat: MafiaRules.Seat,
        me: String,
        alive: Boolean
    ): View {
        val ctx = ctx()
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = ContextCompat.getDrawable(ctx, R.drawable.bg_yuz_games_panel)
        row.setPadding(dp(12), dp(10), dp(12), dp(10))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        row.layoutParams = lp
        if (!seat.alive) row.alpha = 0.5f

        val avatar = TextView(ctx)
        avatar.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        avatar.gravity = Gravity.CENTER
        avatar.background = ContextCompat.getDrawable(ctx, R.drawable.bg_yuz_games_avatar)
        avatar.backgroundTintList = ColorStateList.valueOf(GamesCore.colorFor(seat.uid))
        avatar.setTextColor(ContextCompat.getColor(ctx, R.color.yuz_bg))
        avatar.textSize = 13f
        avatar.setTypeface(avatar.typeface, android.graphics.Typeface.BOLD)
        avatar.text = GamesCore.initials(seat.name)
        row.addView(avatar)

        val column = LinearLayout(ctx)
        column.orientation = LinearLayout.VERTICAL
        val colLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        colLp.marginStart = dp(12)
        column.layoutParams = colLp

        val name = TextView(ctx)
        name.setTextColor(ContextCompat.getColor(ctx, R.color.yuz_text))
        name.textSize = 15f
        name.setTypeface(name.typeface, android.graphics.Typeface.BOLD)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        name.text = seat.name
        if (!seat.alive) name.paintFlags = name.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        column.addView(name)

        val metaParts = ArrayList<String>(3)
        if (r.hostUid == seat.uid) metaParts.add(str(R.string.maf_host))
        if (seat.uid == me) metaParts.add(str(R.string.maf_you))
        if (!seat.alive) metaParts.add(str(R.string.maf_dead))
        val meta = TextView(ctx)
        meta.setTextColor(ContextCompat.getColor(ctx, R.color.yuz_text_muted))
        meta.textSize = 12f
        meta.text = metaParts.joinToString(" · ")
        column.addView(meta)
        row.addView(column)

        val votes = r.votesFor(seat.uid)
        if (votes > 0) {
            val chip = TextView(ctx)
            chip.background = ContextCompat.getDrawable(ctx, R.drawable.bg_yuz_games_chip_active)
            chip.setTextColor(ContextCompat.getColor(ctx, R.color.yuz_action))
            chip.textSize = 13f
            chip.setTypeface(chip.typeface, android.graphics.Typeface.BOLD)
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(10), dp(3), dp(10), dp(3))
            chip.text = votes.toString()
            row.addView(chip)
        }

        if (seat.alive && alive && seat.uid != me && canTarget(r)) {
            row.isClickable = true
            row.setOnClickListener { onSeatTap(r, seat) }
        }
        return row
    }

    private fun canTarget(r: MafiaRoom.Room): Boolean = when (r.phase) {
        MafiaRules.Phase.NIGHT -> {
            val role = secret?.role
            role != null && (role.isMafia || role == MafiaRules.Role.DOCTOR ||
                role == MafiaRules.Role.COMMISSAR) && secret?.nightTarget.orEmpty().isBlank()
        }
        MafiaRules.Phase.VOTE -> !r.hasVoted(myUid())
        else -> false
    }

    private fun renderHint(
        r: MafiaRoom.Room,
        me: String,
        mine: MafiaRules.Seat?,
        alive: Boolean
    ) {
        val view = hintView ?: return
        val text = when {
            r.isFinished() -> str(R.string.maf_phase_end)
            mine == null -> str(R.string.maf_dead)
            !alive -> str(R.string.maf_dead)
            r.isLobby() -> if (r.isHost(me)) {
                if (MafiaRules.canStart(r.seats.size)) "" else str(R.string.maf_need_players)
            } else {
                str(R.string.maf_waiting_host)
            }
            r.phase == MafiaRules.Phase.NIGHT -> {
                if (secret?.nightTarget.orEmpty().isNotBlank()) {
                    str(R.string.maf_night_sent)
                } else {
                    val role = secret?.role
                    val canAct = role != null && (role.isMafia ||
                        role == MafiaRules.Role.DOCTOR || role == MafiaRules.Role.COMMISSAR)
                    if (canAct) nightHint(role) else str(R.string.maf_night_hint_civilian)
                }
            }
            r.phase == MafiaRules.Phase.VOTE ->
                if (r.hasVoted(me)) str(R.string.maf_voted_wait) else str(R.string.maf_vote_hint)
            else -> ""
        }
        view.text = text
        view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun renderButtons(r: MafiaRoom.Room, me: String, host: Boolean, alive: Boolean) {
        val mine = r.seatOf(me)
        primaryMode = when {
            r.isLobby() && host -> Primary.START
            host && r.isActive() && !r.isLobby() && r.phase in ADVANCE_PHASES -> Primary.ADVANCE
            r.isActive() && !host && mine != null && mine.alive && r.hostStale() ->
                Primary.TAKE_HOST
            else -> Primary.NONE
        }
        secondaryMode = when {
            r.isLobby() && mine != null -> Secondary.LEAVE
            r.phase == MafiaRules.Phase.VOTE && mine != null && mine.alive && !r.hasVoted(me) ->
                Secondary.SKIP
            else -> Secondary.NONE
        }

        val primary = btnPrimary ?: return
        when (primaryMode) {
            Primary.START -> {
                primary.visibility = View.VISIBLE
                primary.text = str(R.string.maf_start)
                primary.isEnabled = MafiaRules.canStart(r.seats.size)
            }
            Primary.ADVANCE -> {
                primary.visibility = View.VISIBLE
                primary.text = advanceLabel(r.phase)
                primary.isEnabled = true
            }
            Primary.TAKE_HOST -> {
                primary.visibility = View.VISIBLE
                primary.text = str(R.string.maf_take_host)
                primary.isEnabled = true
            }
            Primary.NONE -> primary.visibility = View.GONE
        }

        val secondary = btnSecondary
        if (secondary == null) return
        when (secondaryMode) {
            Secondary.LEAVE -> {
                secondary.visibility = View.VISIBLE
                secondary.text = str(R.string.maf_leave)
            }
            Secondary.SKIP -> {
                secondary.visibility = View.VISIBLE
                secondary.text = str(R.string.maf_vote_skip)
            }
            Secondary.NONE -> secondary.visibility = View.GONE
        }
    }

    private fun renderLog(r: MafiaRoom.Room) {
        val box = logBox ?: return
        box.removeAllViews()
        val lines = r.log.takeLast(12).reversed()
        if (lines.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        for (line in lines) {
            val view = TextView(ctx())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            view.layoutParams = lp
            view.setTextColor(ContextCompat.getColor(ctx(), R.color.yuz_text_secondary))
            view.textSize = 12.5f
            view.text = line
            box.addView(view)
        }
    }

    private fun updateTimer() {
        val r = room ?: return
        val view = timerView ?: return
        if (r.phaseEndsAt <= 0L || r.isFinished()) {
            view.text = "--:--"
            return
        }
        val remaining = r.remainingMs()
        val seconds = ((remaining + 999L) / 1000L).coerceAtLeast(0L)
        view.text = String.format(Locale.US, "%d:%02d", seconds / 60L, seconds % 60L)
        view.setTextColor(
            ContextCompat.getColor(
                ctx(),
                if (remaining < 10_000L) R.color.yuz_danger else R.color.yuz_games_mafia
            )
        )
    }

    // ------------------------------------------------------- sound and voice

    private fun renderNotice(r: MafiaRoom.Room) {
        val view = noticeView ?: return
        if (r.isLobby() || r.isFinished()) {
            view.visibility = View.GONE
            return
        }
        val text = when (r.phase) {
            MafiaRules.Phase.DAWN, MafiaRules.Phase.DAY -> {
                val victim = r.seats.firstOrNull { it.uid == r.nightVictimUid }
                when {
                    victim != null -> str(R.string.maf_victim_named, victim.name)
                    r.nightSaved -> str(R.string.maf_night_saved)
                    else -> str(R.string.maf_night_quiet)
                }
            }
            MafiaRules.Phase.EXECUTION -> {
                val executed = r.seats.firstOrNull { it.uid == r.executedUid }
                val role = r.executedRole
                if (executed != null && role != null) {
                    str(R.string.maf_executed, executed.name, roleLabel(role))
                } else {
                    str(R.string.maf_nobody_executed)
                }
            }
            else -> ""
        }
        view.text = text
        view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun renderTeam(r: MafiaRoom.Room) {
        val view = teamView ?: return
        val role = secret?.role
        if (role == null || !role.isMafia || r.isLobby()) {
            view.visibility = View.GONE
            return
        }
        val mates = secret?.team.orEmpty()
            .mapNotNull { uid -> r.seats.firstOrNull { it.uid == uid }?.name }
            .filter { it.isNotBlank() }
        view.text = if (mates.isEmpty()) {
            str(R.string.maf_team_none)
        } else {
            str(R.string.maf_team_list, mates.joinToString(", "))
        }
        view.visibility = View.VISIBLE
    }

    private fun syncFeedback(r: MafiaRoom.Room, me: String) {
        val ctx = ctx()
        val seats = r.seats.size
        val alive = r.aliveCount()
        val winner = r.winner

        if (prevStatus == GamesCore.STATUS_INVITE && r.status == GamesCore.STATUS_ACTIVE) {
            GameSfx.play(ctx, GameSfx.START)
        }
        if (prevSeats >= 0 && seats > prevSeats) GameSfx.play(ctx, GameSfx.JOIN)
        if (prevPhase != null && prevPhase != r.phase) {
            when (r.phase) {
                MafiaRules.Phase.NIGHT -> GameSfx.play(ctx, GameSfx.NIGHT)
                MafiaRules.Phase.VOTE -> GameSfx.play(ctx, GameSfx.VOTE)
                MafiaRules.Phase.EXECUTION -> GameSfx.play(ctx, GameSfx.BAD)
                else -> Unit
            }
        }
        if (prevAlive >= 0 && alive < prevAlive) GameSfx.play(ctx, GameSfx.DEATH)
        if (prevWinner == null && winner != null) {
            val role = secret?.role
            if (role != null) {
                val mafiaWon = winner == MafiaRules.Winner.MAFIA
                GameSfx.play(ctx, if (role.isMafia == mafiaWon) GameSfx.WIN else GameSfx.LOSE)
            }
        }

        prevStatus = r.status
        prevSeats = seats
        prevPhase = r.phase
        prevAlive = alive
        prevWinner = winner
    }

    // ------------------------------------------------------------ reactions

    private fun syncReaction(r: MafiaRoom.Room) {
        val reaction = r.reaction
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
                MafiaRoom.sendReaction(currentAccount, roomId, emoji)
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

    private fun syncVoice(r: MafiaRoom.Room, me: String) {
        val voice = r.voice ?: return
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
        val voice = room?.voice ?: return
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

    // --------------------------------------------------------------- actions

    private fun onSeatTap(r: MafiaRoom.Room, seat: MafiaRules.Seat) {
        when (r.phase) {
            MafiaRules.Phase.NIGHT -> {
                val role = secret?.role ?: return
                confirm(nightHint(role), seat.name) { submitNight(seat.uid) }
            }
            MafiaRules.Phase.VOTE ->
                confirm(str(R.string.maf_vote_hint), seat.name) { submitVote(seat.uid) }
            else -> Unit
        }
    }

    private fun confirm(title: String, name: String, action: () -> Unit) {
        AlertDialog.Builder(ctx())
            .setTitle(title)
            .setMessage(name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> action() }
            .show()
    }

    private fun onPrimary() {
        when (primaryMode) {
            Primary.START -> startGame()
            Primary.ADVANCE -> advance()
            Primary.TAKE_HOST -> takeHost()
            Primary.NONE -> Unit
        }
    }

    private fun onSecondary() {
        when (secondaryMode) {
            Secondary.LEAVE -> confirmLeave()
            Secondary.SKIP -> submitVote("")
            Secondary.NONE -> Unit
        }
    }

    private fun startGame() {
        val r = room ?: return
        if (!MafiaRules.canStart(r.seats.size)) {
            toast(R.string.maf_need_players)
            return
        }
        scope?.launch {
            val ok = try {
                MafiaRoom.start(currentAccount, roomId)
            } catch (e: Exception) {
                false
            }
            if (!ok) toast(R.string.maf_start_failed)
        }
    }

    private fun advance() {
        if (working) return
        working = true
        scope?.launch {
            try {
                MafiaRoom.advance(currentAccount, roomId)
            } catch (_: Exception) {
            } finally {
                working = false
            }
        }
    }

    private fun submitNight(targetUid: String) {
        if (working) return
        working = true
        scope?.launch {
            val ok = try {
                MafiaRoom.submitNight(currentAccount, roomId, targetUid)
            } catch (e: Exception) {
                false
            }
            working = false
            if (!ok) toast(R.string.games_error) else toast(R.string.maf_night_sent)
        }
    }

    private fun submitVote(targetUid: String) {
        if (working) return
        working = true
        scope?.launch {
            val ok = try {
                MafiaRoom.submitVote(currentAccount, roomId, targetUid)
            } catch (e: Exception) {
                false
            }
            working = false
            if (!ok) toast(R.string.games_error)
        }
    }

    private fun takeHost() {
        scope?.launch {
            val ok = try {
                MafiaRoom.takeHost(currentAccount, roomId)
            } catch (e: Exception) {
                false
            }
            if (!ok) toast(R.string.games_error)
        }
    }

    private fun confirmLeave() {
        AlertDialog.Builder(ctx())
            .setMessage(R.string.maf_leave)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.maf_leave) { _, _ -> leave() }
            .show()
    }

    private fun leave() {
        scope?.launch {
            try {
                MafiaRoom.leave(currentAccount, roomId)
            } catch (_: Exception) {
            }
            removeSelfFromStack()
        }
    }

    /** Xost fazani o‘zi yuritadi: taymer tugagach keyingi fazaga o‘tadi. */
    private fun driveHost() {
        val r = room ?: return
        if (working) return
        if (r.hostUid != myUid()) return
        if (r.isLobby() || r.isFinished()) return
        if (r.phaseEndsAt <= 0L || GamesCore.now() < r.phaseEndsAt) return
        working = true
        scope?.launch {
            try {
                when (r.phase) {
                    MafiaRules.Phase.NIGHT -> MafiaRoom.resolveNight(currentAccount, roomId)
                    MafiaRules.Phase.VOTE -> MafiaRoom.resolveVote(currentAccount, roomId)
                    else -> MafiaRoom.advance(currentAccount, roomId)
                }
            } catch (_: Exception) {
            }
            working = false
        }
    }

    /** Xost "tirik" ekanini bildiradi — aks holda boshqalar xostlikni oladi. */
    private fun beatHost() {
        val r = room ?: return
        if (r.hostUid != myUid() || r.isFinished()) return
        val now = GamesCore.now()
        if (now - lastBeatAt < MafiaRules.HOST_BEAT_MS) return
        lastBeatAt = now
        scope?.launch {
            try {
                MafiaRoom.heartbeat(currentAccount, roomId)
            } catch (_: Exception) {
            }
        }
    }

    // --------------------------------------------------------------- labels

    private fun phaseLabel(r: MafiaRoom.Room): String = when (r.phase) {
        MafiaRules.Phase.LOBBY -> str(R.string.maf_lobby)
        MafiaRules.Phase.NIGHT -> str(R.string.maf_phase_night)
        MafiaRules.Phase.DAWN -> str(R.string.maf_phase_dawn)
        MafiaRules.Phase.DAY -> str(R.string.maf_phase_day)
        MafiaRules.Phase.VOTE -> str(R.string.maf_phase_vote)
        MafiaRules.Phase.EXECUTION -> str(R.string.maf_phase_execution)
        MafiaRules.Phase.END -> str(R.string.maf_phase_end)
    }

    private fun phaseHintText(r: MafiaRoom.Room): String = when (r.phase) {
        MafiaRules.Phase.LOBBY -> str(R.string.maf_waiting_start)
        MafiaRules.Phase.NIGHT -> str(R.string.maf_night_hint_civilian)
        MafiaRules.Phase.VOTE -> str(R.string.maf_vote_hint)
        else -> ""
    }

    private fun advanceLabel(phase: MafiaRules.Phase): String = when (phase) {
        MafiaRules.Phase.DAWN -> str(R.string.maf_phase_day)
        MafiaRules.Phase.DAY -> str(R.string.maf_phase_vote)
        MafiaRules.Phase.EXECUTION -> str(R.string.maf_phase_night)
        else -> str(R.string.maf_phase_end)
    }

    private fun nightHint(role: MafiaRules.Role?): String = when (role) {
        MafiaRules.Role.MAFIA, MafiaRules.Role.DON -> str(R.string.maf_night_hint_mafia)
        MafiaRules.Role.DOCTOR -> str(R.string.maf_night_hint_doctor)
        MafiaRules.Role.COMMISSAR -> str(R.string.maf_night_hint_commissar)
        else -> str(R.string.maf_night_hint_civilian)
    }

    private fun roleLabel(role: MafiaRules.Role): String = when (role) {
        MafiaRules.Role.MAFIA -> str(R.string.maf_role_mafia)
        MafiaRules.Role.DON -> str(R.string.maf_role_don)
        MafiaRules.Role.COMMISSAR -> str(R.string.maf_role_commissar)
        MafiaRules.Role.DOCTOR -> str(R.string.maf_role_doctor)
        MafiaRules.Role.CIVILIAN -> str(R.string.maf_role_civilian)
    }

    private fun roleDesc(role: MafiaRules.Role): String = when (role) {
        MafiaRules.Role.MAFIA -> str(R.string.maf_role_mafia_desc)
        MafiaRules.Role.DON -> str(R.string.maf_role_don_desc)
        MafiaRules.Role.COMMISSAR -> str(R.string.maf_role_commissar_desc)
        MafiaRules.Role.DOCTOR -> str(R.string.maf_role_doctor_desc)
        MafiaRules.Role.CIVILIAN -> str(R.string.maf_role_civilian_desc)
    }

    // --------------------------------------------------------------- helpers

    private companion object {
        const val REQ_MIC = 4420

        val ADVANCE_PHASES = setOf(
            MafiaRules.Phase.DAWN,
            MafiaRules.Phase.DAY,
            MafiaRules.Phase.EXECUTION
        )
    }

    private fun dp(value: Int): Int = AndroidUtilities.dp(value.toFloat())

    private fun ctx(): Context = themedContext ?: context ?: throw IllegalStateException()

    private fun str(resId: Int): String = ctx().getString(resId)

    private fun str(resId: Int, vararg args: Any): String = ctx().getString(resId, *args)

    private fun toast(resId: Int) {
        Toast.makeText(ctx(), str(resId), Toast.LENGTH_SHORT).show()
    }
}
