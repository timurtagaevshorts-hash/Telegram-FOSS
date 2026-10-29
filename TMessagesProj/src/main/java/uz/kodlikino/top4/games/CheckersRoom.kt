package uz.kodlikino.top4.games

import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Shashka (rus damasi) onlayn xonasi — Firestore hujjati.
 *
 * Bitta hujjat butun o‘yin holatini saqlaydi; har bir yurish tranzaksiyada
 * tekshiriladi (navbat, qonuniylik, vaqt) — shuning uchun ikki qurilma bir vaqtda
 * yozsa ham holat buzilmaydi. Qoidalar [CheckersEngine]da (sof funksiyalar).
 */
object CheckersRoom {

    private const val TAG = "CheckersRoom"

    const val KIND = GamesCore.KIND_CHECKERS

    /** Har bir yurishga beriladigan vaqt. */
    const val MOVE_SECONDS = 90
    /** Taklif amal qilish muddati. */
    const val INVITE_TTL_MS = 60_000L
    /** Vaqt tugaganini da'vo qilishdan oldin qo‘shimcha soniya (soat siljishi). */
    const val TIMEOUT_GRACE_MS = 5_000L

    enum class MoveResult { OK, NOT_PLAYER, NOT_TURN, ILLEGAL, OVER, TIMEOUT, ERROR }

    data class Game(
        val id: String,
        val chatId: Long,
        val chatTitle: String,
        val hostUid: String,
        val status: String,
        val players: List<GamesCore.Player>,
        val invited: List<String>,
        val open: Boolean,
        val board: String,
        val turn: CheckersEngine.Side,
        val idlePlies: Int,
        val moveSeconds: Int,
        val turnStartedAt: Long,
        val deadlineAt: Long,
        val result: CheckersEngine.End?,
        val resultReason: String,
        val winnerUid: String,
        val lastMove: String,
        val lastFrom: String,
        val lastTo: String,
        val expiresAt: Long,
        val rematchRoomId: String,
        val voice: GameVoice.Message?,
        val reaction: GamesCore.Reaction?,
        val createdAt: Long,
        val updatedAt: Long
    ) {
        val white: GamesCore.Player? get() = players.firstOrNull { it.seat == 0 }
        val black: GamesCore.Player? get() = players.firstOrNull { it.seat == 1 }
        val host: GamesCore.Player? get() = players.firstOrNull { it.uid == hostUid }

        fun sideOf(uid: String): CheckersEngine.Side? {
            val clean = uid.trim()
            if (clean.isEmpty()) return null
            return when (clean) {
                white?.uid -> CheckersEngine.Side.WHITE
                black?.uid -> CheckersEngine.Side.BLACK
                else -> null
            }
        }

        fun isWaiting(): Boolean = status == GamesCore.STATUS_INVITE
        fun isFinished(): Boolean = status == GamesCore.STATUS_FINISHED

        fun isMyTurn(uid: String): Boolean =
            status == GamesCore.STATUS_ACTIVE && sideOf(uid) == turn

        fun remainingMs(now: Long = GamesCore.now()): Long =
            (deadlineAt - now).coerceAtLeast(0L)

        fun canClaimTimeout(uid: String, now: Long = GamesCore.now()): Boolean =
            status == GamesCore.STATUS_ACTIVE &&
                sideOf(uid) != null &&
                sideOf(uid) != turn &&
                deadlineAt > 0L &&
                now > deadlineAt + TIMEOUT_GRACE_MS
    }

    // --------------------------------------------------------------- create

    /**
     * Taklif yaratadi. [opponent] null va [open] true bo‘lsa — "ochiq stol"
     * (guruhda birinchi qabul qilgan o‘ynaydi).
     */
    suspend fun create(
        account: Int,
        chatId: Long,
        chatTitle: String,
        opponent: GamesCore.Player?,
        open: Boolean
    ): String? = withContext(Dispatchers.IO) {
        try {
            val signedUid = GamesCore.signIn(account)
            val me = GamesCore.me(account, 0)
            if (signedUid != me.uid || me.uid.isEmpty()) return@withContext null
            if (opponent != null && opponent.uid == me.uid) return@withContext null

            cancelStaleInvites(me.uid, chatId)

            val id = "chk_" + UUID.randomUUID().toString().replace("-", "").take(16)
            val now = GamesCore.now()
            val doc = mapOf(
                "kind" to KIND,
                "chatId" to chatId,
                "chatTitle" to chatTitle.take(90),
                "hostUid" to me.uid,
                "status" to GamesCore.STATUS_INVITE,
                "uids" to listOf(me.uid),
                "players" to listOf(GamesCore.playerMap(me)),
                "invited" to (opponent?.let { listOf(it.uid) } ?: emptyList<String>()),
                "open" to open,
                "board" to CheckersEngine.initialBoard(),
                "turn" to CheckersEngine.Side.WHITE.code,
                "idlePlies" to 0,
                "moveSeconds" to MOVE_SECONDS,
                "turnStartedAt" to 0L,
                "deadlineAt" to 0L,
                "result" to "",
                "resultReason" to "",
                "winnerUid" to "",
                "lastMove" to "",
                "lastFrom" to "",
                "lastTo" to "",
                "expiresAt" to now + INVITE_TTL_MS,
                "rematchRoomId" to "",
                "createdAt" to now,
                "updatedAt" to now
            )
            GamesCore.rooms().document(id).set(doc).await()
            id
        } catch (e: Exception) {
            Log.e(TAG, "create: ${e.message}", e)
            null
        }
    }

    private suspend fun cancelStaleInvites(hostUid: String, chatId: Long) {
        try {
            val snap = GamesCore.rooms()
                .whereEqualTo("chatId", chatId)
                .get()
                .await()
            val now = GamesCore.now()
            for (doc in snap.documents) {
                if (doc.getString("kind") != KIND) continue
                if (doc.getString("hostUid") != hostUid) continue
                if (doc.getString("status") != GamesCore.STATUS_INVITE) continue
                doc.reference.update(
                    "status", GamesCore.STATUS_CANCELLED,
                    "updatedAt", now
                ).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cancelStaleInvites: ${e.message}")
        }
    }

    // --------------------------------------------------------------- accept

    suspend fun accept(account: Int, roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val me = GamesCore.me(account, 1)
            val ref = GamesCore.rooms().document(roomId.trim())
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) throw IllegalStateException("missing")
                if (snap.getString("status") != GamesCore.STATUS_INVITE) {
                    throw IllegalStateException("not_invite")
                }
                val players = GamesCore.parsePlayers(snap.get("players"))
                if (players.size >= 2) throw IllegalStateException("full")
                if (players.any { it.uid == uid }) throw IllegalStateException("already")
                val invited = GamesCore.stringsOf(snap.data, "invited")
                val open = snap.getBoolean("open") == true
                if (!open && !invited.contains(uid)) throw IllegalStateException("not_invited")

                val now = GamesCore.now()
                val moveSeconds = (snap.getLong("moveSeconds") ?: MOVE_SECONDS.toLong()).toInt()
                tx.update(
                    ref,
                    mapOf(
                        "status" to GamesCore.STATUS_ACTIVE,
                        "uids" to (players.map { it.uid } + uid),
                        "players" to (players.map { GamesCore.playerMap(it) } +
                            GamesCore.playerMap(me.copy(seat = 1))),
                        "turnStartedAt" to now,
                        "deadlineAt" to now + moveSeconds * 1000L,
                        "updatedAt" to now
                    )
                )
            }.await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "accept: ${e.message}", e)
            false
        }
    }

    suspend fun decline(account: Int, roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) throw IllegalStateException("missing")
                if (snap.getString("status") != GamesCore.STATUS_INVITE) {
                    throw IllegalStateException("not_invite")
                }
                val hostUid = snap.getString("hostUid").orEmpty()
                if (hostUid == uid) {
                    tx.update(ref, "status", GamesCore.STATUS_CANCELLED, "updatedAt", GamesCore.now())
                } else {
                    val invited = GamesCore.stringsOf(snap.data, "invited")
                    if (!invited.contains(uid)) throw IllegalStateException("not_invited")
                    tx.update(
                        ref,
                        "invited", invited.filter { it != uid },
                        "updatedAt", GamesCore.now()
                    )
                }
            }.await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "decline: ${e.message}", e)
            false
        }
    }

    // ----------------------------------------------------------------- move

    suspend fun move(account: Int, roomId: String, wire: String): MoveResult =
        withContext(Dispatchers.IO) {
            try {
                val uid = GamesCore.signIn(account)
                val ref = GamesCore.rooms().document(roomId.trim())
                val now = GamesCore.now()
                GamesCore.db.runTransaction { tx ->
                    val snap = tx.get(ref)
                    if (!snap.exists()) throw IllegalStateException("missing")
                    val game = parse(snap.id, snap.data.orEmpty())
                        ?: throw IllegalStateException("bad")
                    if (game.status != GamesCore.STATUS_ACTIVE) return@runTransaction MoveResult.OVER

                    val side = game.sideOf(uid) ?: return@runTransaction MoveResult.NOT_PLAYER
                    if (game.turn != side) return@runTransaction MoveResult.NOT_TURN
                    if (game.deadlineAt > 0L && now > game.deadlineAt + TIMEOUT_GRACE_MS) {
                        return@runTransaction MoveResult.TIMEOUT
                    }

                    val chosen = CheckersEngine.moveFromWire(game.board, side, wire)
                        ?: return@runTransaction MoveResult.ILLEGAL
                    val applied = CheckersEngine.apply(game.board, chosen, side, game.idlePlies)
                        ?: return@runTransaction MoveResult.ILLEGAL

                    val moveSeconds = game.moveSeconds.coerceIn(15, 600)
                    val updates = HashMap<String, Any>(12)
                    updates["board"] = applied.board
                    updates["turn"] = applied.nextTurn.code
                    updates["idlePlies"] = applied.idlePlies
                    updates["lastMove"] = chosen.wire()
                    updates["lastFrom"] = chosen.from.key()
                    updates["lastTo"] = chosen.to.key()
                    updates["updatedAt"] = now
                    updates["turnStartedAt"] = now
                    updates["deadlineAt"] = now + moveSeconds * 1000L

                    val end = applied.end
                    if (end != null) {
                        updates["status"] = GamesCore.STATUS_FINISHED
                        updates["result"] = end.code
                        updates["resultReason"] = reasonOf(applied.board, end, applied.idlePlies)
                        updates["winnerUid"] = when (end) {
                            CheckersEngine.End.WHITE_WIN -> game.white?.uid.orEmpty()
                            CheckersEngine.End.BLACK_WIN -> game.black?.uid.orEmpty()
                            CheckersEngine.End.DRAW -> ""
                        }
                        updates["deadlineAt"] = 0L
                    }
                    tx.update(ref, updates)
                    MoveResult.OK
                }.await()
            } catch (e: Exception) {
                Log.e(TAG, "move: ${e.message}", e)
                MoveResult.ERROR
            }
        }

    // ------------------------------------------------------------- reactions

    /** Reaksiya yuborish — raqibning doskasi ustida 2 soniya ko‘rinadi. */
    suspend fun sendReaction(account: Int, roomId: String, emoji: String): Boolean =
        withContext(Dispatchers.IO) {
            val clean = emoji.trim().take(8)
            if (!GamesCore.REACTIONS.contains(clean)) return@withContext false
            try {
                GamesCore.signIn(account)
                val me = GamesCore.me(account)
                GamesCore.rooms().document(roomId.trim()).update(
                    "reaction", GamesCore.reactionMap(me, clean, GamesCore.now())
                ).await()
                true
            } catch (e: Exception) {
                Log.e(TAG, "sendReaction: ${e.message}", e)
                false
            }
        }

    // -------------------------------------------------------- end conditions

    suspend fun resign(account: Int, roomId: String): Boolean = finish(
        account = account,
        roomId = roomId,
        reason = "resign",
        loserSideOfCaller = true
    )

    suspend fun claimTimeout(account: Int, roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            val now = GamesCore.now()
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) throw IllegalStateException("missing")
                val game = parse(snap.id, snap.data.orEmpty()) ?: throw IllegalStateException("bad")
                if (game.status != GamesCore.STATUS_ACTIVE) return@runTransaction
                val side = game.sideOf(uid) ?: return@runTransaction
                if (side == game.turn) return@runTransaction
                if (game.deadlineAt <= 0L || now <= game.deadlineAt + TIMEOUT_GRACE_MS) {
                    return@runTransaction
                }
                val winnerUid = if (side == CheckersEngine.Side.WHITE) {
                    game.white?.uid.orEmpty()
                } else {
                    game.black?.uid.orEmpty()
                }
                val resultCode = if (side == CheckersEngine.Side.WHITE) {
                    CheckersEngine.End.WHITE_WIN.code
                } else {
                    CheckersEngine.End.BLACK_WIN.code
                }
                tx.update(
                    ref,
                    mapOf(
                        "status" to GamesCore.STATUS_FINISHED,
                        "result" to resultCode,
                        "resultReason" to "timeout",
                        "winnerUid" to winnerUid,
                        "deadlineAt" to 0L,
                        "updatedAt" to now
                    )
                )
            }.await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "claimTimeout: ${e.message}", e)
            false
        }
    }

    private suspend fun finish(
        account: Int,
        roomId: String,
        reason: String,
        loserSideOfCaller: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) throw IllegalStateException("missing")
                val game = parse(snap.id, snap.data.orEmpty()) ?: throw IllegalStateException("bad")
                if (game.status != GamesCore.STATUS_ACTIVE) return@runTransaction
                val side = game.sideOf(uid) ?: throw IllegalStateException("not_player")
                val winnerSide = if (loserSideOfCaller) side.opposite() else side
                val winnerUid = if (winnerSide == CheckersEngine.Side.WHITE) {
                    game.white?.uid.orEmpty()
                } else {
                    game.black?.uid.orEmpty()
                }
                val resultCode = if (winnerSide == CheckersEngine.Side.WHITE) {
                    CheckersEngine.End.WHITE_WIN.code
                } else {
                    CheckersEngine.End.BLACK_WIN.code
                }
                tx.update(
                    ref,
                    mapOf(
                        "status" to GamesCore.STATUS_FINISHED,
                        "result" to resultCode,
                        "resultReason" to reason,
                        "winnerUid" to winnerUid,
                        "deadlineAt" to 0L,
                        "updatedAt" to GamesCore.now()
                    )
                )
            }.await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "finish($reason): ${e.message}", e)
            false
        }
    }

    // -------------------------------------------------------------- rematch

    /** Qayta o‘ynash: ranglar almashinadi, yangi xona yaratiladi. */
    suspend fun rematch(account: Int, roomId: String): String? = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            var made: String? = null
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) return@runTransaction
                val game = parse(snap.id, snap.data.orEmpty()) ?: return@runTransaction
                if (game.status != GamesCore.STATUS_FINISHED) return@runTransaction
                if (game.sideOf(uid) == null) return@runTransaction
                val existing = snap.getString("rematchRoomId").orEmpty()
                if (existing.isNotBlank()) {
                    made = existing
                    return@runTransaction
                }

                val white = game.black ?: return@runTransaction
                val black = game.white ?: return@runTransaction
                val newId = "chk_" + UUID.randomUUID().toString().replace("-", "").take(16)
                val now = GamesCore.now()
                val moveSeconds = game.moveSeconds.coerceIn(15, 600)
                tx.set(
                    GamesCore.rooms().document(newId),
                    mapOf(
                        "kind" to KIND,
                        "chatId" to game.chatId,
                        "chatTitle" to game.chatTitle,
                        "hostUid" to white.uid,
                        "status" to GamesCore.STATUS_ACTIVE,
                        "uids" to listOf(white.uid, black.uid),
                        "players" to listOf(
                            GamesCore.playerMap(white.copy(seat = 0)),
                            GamesCore.playerMap(black.copy(seat = 1))
                        ),
                        "invited" to emptyList<String>(),
                        "open" to false,
                        "board" to CheckersEngine.initialBoard(),
                        "turn" to CheckersEngine.Side.WHITE.code,
                        "idlePlies" to 0,
                        "moveSeconds" to moveSeconds,
                        "turnStartedAt" to now,
                        "deadlineAt" to now + moveSeconds * 1000L,
                        "result" to "",
                        "resultReason" to "",
                        "winnerUid" to "",
                        "lastMove" to "",
                        "lastFrom" to "",
                        "lastTo" to "",
                        "expiresAt" to 0L,
                        "rematchRoomId" to "",
                        "rematchOf" to game.id,
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                )
                tx.update(
                    ref,
                    "rematchRoomId", newId,
                    "updatedAt", now
                )
                made = newId
            }.await()
            made
        } catch (e: Exception) {
            Log.e(TAG, "rematch: ${e.message}", e)
            null
        }
    }

    // ---------------------------------------------------------------- reads

    fun listen(roomId: String, onUpdate: (Game?) -> Unit): ListenerRegistration {
        return GamesCore.rooms().document(roomId.trim())
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.e(TAG, "listen: ${error.message}", error)
                    return@addSnapshotListener
                }
                if (snap == null || !snap.exists()) onUpdate(null)
                else onUpdate(parse(snap.id, snap.data.orEmpty()))
            }
    }

    suspend fun fetch(roomId: String): Game? = withContext(Dispatchers.IO) {
        try {
            val snap = GamesCore.rooms().document(roomId.trim()).get().await()
            if (!snap.exists()) null else parse(snap.id, snap.data.orEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "fetch: ${e.message}", e)
            null
        }
    }

    /** Chatdagi faol/tugagan shashka xonalari (UI ro‘yxati uchun). */
    fun listenChatGames(chatId: Long, onUpdate: (List<Game>) -> Unit): ListenerRegistration {
        return GamesCore.rooms()
            .whereEqualTo("chatId", chatId)
            .limit(24)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.e(TAG, "listenChatGames: ${error.message}", error)
                    return@addSnapshotListener
                }
                val list = ArrayList<Game>()
                snap?.documents?.forEach { doc ->
                    if (doc.getString("kind") != KIND) return@forEach
                    val game = parse(doc.id, doc.data.orEmpty()) ?: return@forEach
                    if (game.status == GamesCore.STATUS_CANCELLED) return@forEach
                    list.add(game)
                }
                list.sortByDescending { it.createdAt }
                onUpdate(list)
            }
    }

    /** Menga kelgan takliflar (istalgan chatdan). */
    fun listenInvites(uid: String, onUpdate: (List<Game>) -> Unit): ListenerRegistration? {
        val clean = uid.trim()
        if (clean.isEmpty()) return null
        return GamesCore.rooms()
            .whereArrayContains("invited", clean)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.e(TAG, "listenInvites: ${error.message}", error)
                    return@addSnapshotListener
                }
                val now = GamesCore.now()
                val list = ArrayList<Game>()
                snap?.documents?.forEach { doc ->
                    if (doc.getString("kind") != KIND) return@forEach
                    val game = parse(doc.id, doc.data.orEmpty()) ?: return@forEach
                    if (game.status != GamesCore.STATUS_INVITE) return@forEach
                    if (game.expiresAt in 1..now) return@forEach
                    list.add(game)
                }
                onUpdate(list)
            }
    }

    // ---------------------------------------------------------------- parse

    fun parse(id: String, data: Map<String, Any?>): Game? {
        if (data["kind"]?.toString() != KIND) return null
        val board = (data["board"]?.toString())
            .takeIf { CheckersEngine.isValidBoard(it) }
            ?: CheckersEngine.initialBoard()
        return Game(
            id = id,
            chatId = GamesCore.longOf(data, "chatId"),
            chatTitle = GamesCore.stringOf(data, "chatTitle"),
            hostUid = GamesCore.stringOf(data, "hostUid"),
            status = GamesCore.stringOf(data, "status").ifBlank { GamesCore.STATUS_INVITE },
            players = GamesCore.parsePlayers(data["players"]),
            invited = GamesCore.stringsOf(data, "invited"),
            open = GamesCore.boolOf(data, "open"),
            board = board,
            turn = CheckersEngine.Side.fromCode(data["turn"]?.toString())
                ?: CheckersEngine.Side.WHITE,
            idlePlies = GamesCore.intOf(data, "idlePlies"),
            moveSeconds = GamesCore.intOf(data, "moveSeconds", MOVE_SECONDS),
            turnStartedAt = GamesCore.longOf(data, "turnStartedAt"),
            deadlineAt = GamesCore.longOf(data, "deadlineAt"),
            result = CheckersEngine.End.fromCode(data["result"]?.toString()),
            resultReason = GamesCore.stringOf(data, "resultReason"),
            winnerUid = GamesCore.stringOf(data, "winnerUid"),
            lastMove = GamesCore.stringOf(data, "lastMove"),
            lastFrom = GamesCore.stringOf(data, "lastFrom"),
            lastTo = GamesCore.stringOf(data, "lastTo"),
            expiresAt = GamesCore.longOf(data, "expiresAt"),
            rematchRoomId = GamesCore.stringOf(data, "rematchRoomId"),
            voice = GameVoice.parse(data["voice"]),
            reaction = GamesCore.parseReaction(data["reaction"]),
            createdAt = GamesCore.longOf(data, "createdAt"),
            updatedAt = GamesCore.longOf(data, "updatedAt")
        )
    }

    private fun reasonOf(board: String, end: CheckersEngine.End, idlePlies: Int): String {
        if (end == CheckersEngine.End.DRAW) {
            return if (idlePlies >= CheckersEngine.IDLE_DRAW_PLIES) "draw_15" else "draw"
        }
        val white = CheckersEngine.count(board, CheckersEngine.Side.WHITE)
        val black = CheckersEngine.count(board, CheckersEngine.Side.BLACK)
        return if (white == 0 || black == 0) "no_pieces" else "no_moves"
    }
}
