package uz.kodlikino.top4.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.games.CheckersEngine
import uz.kodlikino.top4.games.CheckersRoom
import uz.kodlikino.top4.games.GameInvitePolicy
import uz.kodlikino.top4.games.GamesCore
import uz.kodlikino.top4.games.MafiaRoom
import uz.kodlikino.top4.games.MafiaRules
import uz.kodlikino.top4.utils.YuzGramProfileStore

/**
 * Chat ichidagi o‘yinlar markazi.
 *
 * Hub chat kontekstida ochiladi ([chatId] — joriy dialog). Shashka stoli yoki
 * mafiya xonasi yaratilganda chatga qisqa taklif xabari yuboriladi, shunda
 * suhbatdosh bildirishnoma oladi va shu bo‘limdan qo‘shiladi.
 *
 * Shaxsiy chatda shashka taklifi to‘g‘ridan-to‘g‘ri suhbatdoshga yuboriladi;
 * guruhda esa "ochiq stol" ochiladi — birinchi qabul qilgan o‘ynaydi.
 */
class GamesHubFragment(
    private val chatId: Long = 0L,
    private val chatTitle: String = "",
    private val peerUid: String = "",
    private val peerName: String = "",
    private val isGroup: Boolean = false
) : BaseFragment() {

    private data class Entry(
        val id: String,
        val title: String,
        val subtitle: String,
        val updatedAt: Long,
        val primary: String?,
        val secondary: String?,
        val onClick: () -> Unit,
        val onSecondary: (() -> Unit)? = null
    )

    private var scope: CoroutineScope? = null
    private var themedContext: Context? = null

    private var checkersCard: View? = null
    private var mafiaCard: View? = null
    private var activeList: LinearLayout? = null
    private var emptyView: TextView? = null
    private var invitesHeader: View? = null
    private var inviteList: LinearLayout? = null
    private var progress: ProgressBar? = null

    private var checkersListener: ListenerRegistration? = null
    private var mafiaListener: ListenerRegistration? = null
    private var invitesListener: ListenerRegistration? = null
    private var mafiaInvitesListener: ListenerRegistration? = null

    private var chatCheckers: List<CheckersRoom.Game> = emptyList()
    private var chatMafia: List<MafiaRoom.Room> = emptyList()
    private var invites: List<CheckersRoom.Game> = emptyList()
    private var mafiaInvites: List<MafiaRoom.Room> = emptyList()
    private var gotCheckers = false
    private var gotMafia = false
    private var busy = false
    private var liveActive = false

    private fun myUid(): String = GamesCore.uidOf(currentAccount)

    override fun createView(context: Context): View {
        val themed = YuzTheme.wrap(context)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_games_hub, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        root.findViewById<View>(R.id.btnGamesBack)
            .setOnClickListener { removeSelfFromStack() }
        YuzTopInset.applyPadding(root.findViewById(R.id.hubTopBar))

        root.findViewById<TextView>(R.id.tvHubChat).text =
            chatTitle.ifBlank { str(R.string.games_subtitle) }

        checkersCard = root.findViewById(R.id.cardCheckers)
        mafiaCard = root.findViewById(R.id.cardMafia)
        activeList = root.findViewById(R.id.hubActiveList)
        emptyView = root.findViewById(R.id.tvHubEmpty)
        invitesHeader = root.findViewById(R.id.tvInvitesHeader)
        inviteList = root.findViewById(R.id.hubInviteList)
        progress = root.findViewById(R.id.hubProgress)

        checkersCard?.setOnClickListener { createCheckers() }
        mafiaCard?.setOnClickListener { createMafia() }

        root.findViewById<View>(R.id.btnHubRulesChk).setOnClickListener {
            GameRulesDialog.show(themed, GameRulesDialog.CHECKERS)
        }
        root.findViewById<View>(R.id.btnHubRulesMaf).setOnClickListener {
            GameRulesDialog.show(themed, GameRulesDialog.MAFIA)
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        updateProgress()
        return root
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        if (fragmentView != null) startLive()
    }

    override fun onPause() {
        stopLive()
        super.onPause()
    }

    override fun onFragmentDestroy() {
        stopLive()
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }

    // ------------------------------------------------------------------ live

    private fun startLive() {
        if (checkersListener != null || mafiaListener != null) return
        liveActive = true
        scope?.launch {
            try {
                GamesCore.signIn(currentAccount)
            } catch (_: Exception) {
                liveActive = false
                toast(R.string.games_sign_in_error)
                return@launch
            }
            if (!liveActive) return@launch
            val me = GamesCore.me(currentAccount)
            if (me.uid.isNotBlank()) {
                YuzGramProfileStore.ensureSelfProfile(me.uid, me.name, "", "")
            }
            attachListeners()
        }
    }

    private fun attachListeners() {
        if (checkersListener != null) return
        checkersListener = CheckersRoom.listenChatGames(chatId) {
            chatCheckers = it
            gotCheckers = true
            updateProgress()
            render()
        }
        mafiaListener = MafiaRoom.listenChatRooms(chatId) {
            chatMafia = it
            gotMafia = true
            updateProgress()
            render()
        }
        invitesListener = CheckersRoom.listenInvites(myUid()) {
            invites = it
            render()
        }
        mafiaInvitesListener = MafiaRoom.listenInvites(myUid()) {
            mafiaInvites = it
            render()
        }
    }

    private fun stopLive() {
        liveActive = false
        checkersListener?.remove()
        mafiaListener?.remove()
        invitesListener?.remove()
        mafiaInvitesListener?.remove()
        checkersListener = null
        mafiaListener = null
        invitesListener = null
        mafiaInvitesListener = null
        gotCheckers = false
        gotMafia = false
        chatCheckers = emptyList()
        chatMafia = emptyList()
        invites = emptyList()
        mafiaInvites = emptyList()
    }

    // ---------------------------------------------------------------- create

    private fun createCheckers() {
        if (busy) return
        val opponent = if (!isGroup && peerUid.isNotBlank() && peerUid != myUid()) {
            GamesCore.playerOf(peerUid, peerName)
        } else {
            null
        }
        setBusy(true)
        scope?.launch {
            if (opponent != null && GameInvitePolicy.peerUsesYuzGram(opponent.uid) == false) {
                setBusy(false)
                sendChatMessage(str(R.string.games_upgrade_checkers))
                toast(R.string.games_peer_no_yuzgram, opponent.name)
                return@launch
            }
            val id = try {
                CheckersRoom.create(currentAccount, chatId, chatTitle, opponent, opponent == null)
            } catch (e: Exception) {
                null
            }
            setBusy(false)
            if (id.isNullOrBlank()) {
                toast(R.string.games_error)
                return@launch
            }
            sendChatMessage(str(R.string.games_invite_message, GamesCore.me(currentAccount).name))
            presentFragment(CheckersFragment(id, chatTitle), false)
        }
    }

    private fun createMafia() {
        if (busy) return
        val invitee = if (!isGroup && peerUid.isNotBlank() && peerUid != myUid()) {
            peerUid
        } else {
            ""
        }
        setBusy(true)
        scope?.launch {
            if (invitee.isNotBlank() && GameInvitePolicy.peerUsesYuzGram(invitee) == false) {
                setBusy(false)
                sendChatMessage(str(R.string.games_upgrade_mafia))
                toast(R.string.games_peer_no_yuzgram, peerName)
                return@launch
            }
            val id = try {
                MafiaRoom.create(
                    currentAccount,
                    chatId,
                    chatTitle,
                    if (invitee.isBlank()) emptyList() else listOf(invitee)
                )
            } catch (e: Exception) {
                null
            }
            setBusy(false)
            if (id.isNullOrBlank()) {
                toast(R.string.games_error)
                return@launch
            }
            sendChatMessage(
                str(R.string.games_invite_mafia_message, GamesCore.me(currentAccount).name)
            )
            presentFragment(MafiaFragment(id, chatTitle), false)
        }
    }

    private fun sendChatMessage(text: String) {
        if (chatId == 0L) return
        try {
            SendMessagesHelper.getInstance(currentAccount)
                .sendMessage(SendMessagesHelper.SendMessageParams.of(text, chatId))
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        val list = activeList ?: return
        val me = myUid()
        list.removeAllViews()

        val entries = ArrayList<Entry>()
        for (game in chatCheckers) {
            if (isAddressedInvite(game, me)) continue
            entries.add(checkersEntry(game, me))
        }
        for (room in chatMafia) entries.add(mafiaEntry(room, me))
        entries.sortByDescending { it.updatedAt }

        val themed = themedContext ?: context ?: return
        for (entry in entries) {
            list.addView(buildRow(themed, entry))
        }
        emptyView?.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        val inviteRows = invites.filter { it.status == GamesCore.STATUS_INVITE }
        val mafiaInviteRows = mafiaInvites.filter { it.isLobby() }
        val hasInvites = inviteRows.isNotEmpty() || mafiaInviteRows.isNotEmpty()
        invitesHeader?.visibility = if (hasInvites) View.VISIBLE else View.GONE
        inviteList?.removeAllViews()
        for (invite in inviteRows) {
            inviteList?.addView(buildRow(themed, inviteEntry(invite, me)))
        }
        for (room in mafiaInviteRows) {
            inviteList?.addView(buildRow(themed, mafiaInviteEntry(room)))
        }
    }

    private fun isAddressedInvite(game: CheckersRoom.Game, me: String): Boolean =
        game.status == GamesCore.STATUS_INVITE && me.isNotEmpty() && game.invited.contains(me)

    private fun checkersEntry(game: CheckersRoom.Game, me: String): Entry {
        val mine = game.sideOf(me) != null
        val opponentName = when {
            game.sideOf(me) == CheckersEngine.Side.WHITE -> game.black?.name
            game.sideOf(me) == CheckersEngine.Side.BLACK -> game.white?.name
            else -> null
        }.orEmpty().ifBlank { str(R.string.chk_waiting_title) }

        val title: String
        val subtitle: String
        when {
            game.status == GamesCore.STATUS_INVITE -> {
                title = str(R.string.games_checkers) + " · " +
                    (game.host?.name ?: str(R.string.games_unknown_host))
                subtitle = str(R.string.games_status_invite)
            }
            game.isFinished() -> {
                title = str(R.string.games_checkers) + " · " + playersLine(game)
                subtitle = resultLine(game)
            }
            else -> {
                title = str(R.string.games_checkers) + " · " + playersLine(game)
                subtitle = if (mine && game.isMyTurn(me)) {
                    str(R.string.games_your_turn)
                } else if (mine) {
                    str(R.string.chk_turn_opponent, opponentName)
                } else {
                    str(R.string.games_status_active)
                }
            }
        }

        val open: () -> Unit =
            { presentFragment(CheckersFragment(game.id, game.chatTitle.ifBlank { chatTitle }), false) }
        return when {
            game.status == GamesCore.STATUS_INVITE && game.hostUid == me ->
                Entry(game.id, title, subtitle, game.updatedAt, str(R.string.games_continue),
                    str(R.string.chk_cancel_invite),
                    onClick = open,
                    onSecondary = { declineCheckers(game.id) })
            game.status == GamesCore.STATUS_INVITE && game.open ->
                Entry(game.id, title, subtitle, game.updatedAt, str(R.string.games_join), null,
                    onClick = {
                        joinCheckers(game.id)
                    })
            else -> Entry(
                game.id, title, subtitle, game.updatedAt,
                if (mine) str(R.string.games_continue) else str(R.string.games_watch),
                null, open
            )
        }
    }

    private fun mafiaEntry(room: MafiaRoom.Room, me: String): Entry {
        val seated = room.seatOf(me) != null
        val title = str(R.string.games_mafia) + " · " +
            str(R.string.games_players_count, room.seats.size, MafiaRules.MAX_PLAYERS)
        val subtitle = when {
            room.isFinished() -> when (room.winner) {
                MafiaRules.Winner.MAFIA -> str(R.string.maf_winner_mafia)
                MafiaRules.Winner.CITY -> str(R.string.maf_winner_city)
                null -> str(R.string.games_status_finished)
            }
            room.isLobby() -> str(R.string.maf_lobby)
            else -> phaseLabel(room) + " · " + str(R.string.maf_round, room.day)
        }
        val open: () -> Unit =
            { presentFragment(MafiaFragment(room.id, room.chatTitle.ifBlank { chatTitle }), false) }
        val primary = when {
            seated -> str(R.string.games_continue)
            room.isLobby() -> str(R.string.games_join)
            else -> null
        }
        return Entry(room.id, title, subtitle, room.updatedAt, primary, null,
            onClick = { if (!seated && room.isLobby()) joinMafia(room.id) else open() })
    }

    private fun inviteEntry(game: CheckersRoom.Game, me: String): Entry {
        val host = game.host?.name.orEmpty().ifBlank { str(R.string.games_unknown_host) }
        val subtitle = listOfNotNull(
            game.chatTitle.takeIf { it.isNotBlank() },
            str(R.string.games_status_invite)
        ).joinToString(" · ")
        return Entry(
            game.id,
            str(R.string.games_checkers) + " · " + host,
            subtitle,
            game.updatedAt,
            str(R.string.games_accept),
            str(R.string.games_decline),
            onClick = { acceptCheckers(game.id) },
            onSecondary = { declineCheckers(game.id) }
        )
    }

    private fun mafiaInviteEntry(room: MafiaRoom.Room): Entry {
        val host = room.seats.firstOrNull()?.name.orEmpty()
            .ifBlank { str(R.string.games_unknown_host) }
        val subtitle = listOfNotNull(
            room.chatTitle.takeIf { it.isNotBlank() },
            str(R.string.games_status_invite)
        ).joinToString(" · ")
        return Entry(
            room.id,
            str(R.string.games_mafia) + " · " + host,
            subtitle,
            room.updatedAt,
            str(R.string.games_accept),
            str(R.string.games_decline),
            onClick = { joinMafia(room.id) },
            onSecondary = { declineMafia(room.id) }
        )
    }

    private fun playersLine(game: CheckersRoom.Game): String {
        val white = game.white?.name.orEmpty().ifBlank { str(R.string.chk_white) }
        val black = game.black?.name.orEmpty().ifBlank { str(R.string.chk_black) }
        return str(R.string.games_vs, white, black)
    }

    private fun resultLine(game: CheckersRoom.Game): String {
        val winner = game.players.firstOrNull { it.uid == game.winnerUid }?.name
        return when {
            game.result == CheckersEngine.End.DRAW || game.winnerUid.isBlank() ->
                str(R.string.games_result_draw)
            winner.isNullOrBlank() -> str(R.string.games_status_finished)
            else -> str(R.string.games_result_win, winner)
        }
    }

    private fun phaseLabel(room: MafiaRoom.Room): String = when (room.phase) {
        MafiaRules.Phase.LOBBY -> str(R.string.maf_lobby)
        MafiaRules.Phase.NIGHT -> str(R.string.maf_phase_night)
        MafiaRules.Phase.DAWN -> str(R.string.maf_phase_dawn)
        MafiaRules.Phase.DAY -> str(R.string.maf_phase_day)
        MafiaRules.Phase.VOTE -> str(R.string.maf_phase_vote)
        MafiaRules.Phase.EXECUTION -> str(R.string.maf_phase_execution)
        MafiaRules.Phase.END -> str(R.string.maf_phase_end)
    }

    private fun buildRow(ctx: Context, entry: Entry): View {
        val row = LayoutInflater.from(ctx).inflate(R.layout.item_yuz_game_room, null)
        row.findViewById<TextView>(R.id.tvRoomTitle).text = entry.title
        row.findViewById<TextView>(R.id.tvRoomSubtitle).text = entry.subtitle

        val primary = row.findViewById<TextView>(R.id.btnRoomAction)
        val secondary = row.findViewById<TextView>(R.id.btnRoomAction2)

        val label = entry.primary
        if (label.isNullOrBlank()) {
            primary.visibility = View.GONE
        } else {
            primary.text = label
            primary.setOnClickListener { entry.onClick() }
        }
        val second = entry.secondary
        if (second.isNullOrBlank() || entry.onSecondary == null) {
            secondary.visibility = View.GONE
        } else {
            secondary.text = second
            secondary.visibility = View.VISIBLE
            secondary.setOnClickListener { entry.onSecondary.invoke() }
        }
        row.setOnClickListener { entry.onClick() }
        return row
    }

    // ---------------------------------------------------------------- actions

    private fun joinCheckers(roomId: String) {
        setBusy(true)
        scope?.launch {
            val ok = try {
                CheckersRoom.accept(currentAccount, roomId)
            } catch (e: Exception) {
                false
            }
            setBusy(false)
            if (ok) {
                presentFragment(CheckersFragment(roomId, chatTitle), false)
            } else {
                toast(R.string.games_error)
            }
        }
    }

    private fun acceptCheckers(roomId: String) {
        joinCheckers(roomId)
    }

    private fun declineCheckers(roomId: String) {
        scope?.launch {
            try {
                CheckersRoom.decline(currentAccount, roomId)
            } catch (_: Exception) {
            }
            render()
        }
    }

    private fun declineMafia(roomId: String) {
        scope?.launch {
            try {
                MafiaRoom.decline(currentAccount, roomId)
            } catch (_: Exception) {
            }
            render()
        }
    }

    private fun joinMafia(roomId: String) {
        setBusy(true)
        scope?.launch {
            val result = try {
                MafiaRoom.join(currentAccount, roomId)
            } catch (e: Exception) {
                MafiaRoom.JoinResult.ERROR
            }
            setBusy(false)
            when (result) {
                MafiaRoom.JoinResult.OK, MafiaRoom.JoinResult.ALREADY ->
                    presentFragment(MafiaFragment(roomId, chatTitle), false)
                MafiaRoom.JoinResult.FULL -> toast(R.string.maf_full)
                MafiaRoom.JoinResult.STARTED -> toast(R.string.maf_already_started)
                else -> toast(R.string.maf_join_failed)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun setBusy(value: Boolean) {
        busy = value
        checkersCard?.isEnabled = !value
        mafiaCard?.isEnabled = !value
        updateProgress()
    }

    private fun updateProgress() {
        val initial = !gotCheckers && !gotMafia
        progress?.visibility = if (busy || initial) View.VISIBLE else View.GONE
    }

    private fun ctx(): Context = themedContext ?: context ?: throw IllegalStateException()

    private fun str(resId: Int): String = ctx().getString(resId)

    private fun str(resId: Int, vararg args: Any): String = ctx().getString(resId, *args)

    private fun toast(resId: Int) {
        Toast.makeText(ctx(), str(resId), Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int, vararg args: Any) {
        Toast.makeText(ctx(), str(resId, *args), Toast.LENGTH_LONG).show()
    }
}
