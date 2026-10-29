package uz.kodlikino.top4.games

import android.util.Log
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import uz.kodlikino.top4.games.MafiaRules.Phase
import uz.kodlikino.top4.games.MafiaRules.Role
import uz.kodlikino.top4.games.MafiaRules.Seat
import uz.kodlikino.top4.games.MafiaRules.Winner
import java.util.UUID

/**
 * Mafiya xonasi. Bitta ommaviy hujjat + har bir o‘yinchi uchun maxfiy hujjat.
 *
 * Faza o‘tishlari va tunda olingan qarorlar FAQAT xost tomonidan yoziladi —
 * xost boshqa o‘yinchilarning maxfiy hujjatlarini o‘qiy oladi (Firestore qoidasi
 * `hostUid`ga ruxsat beradi). Xost yo‘qolsa, [takeHost] orqali boshqa tirik
 * o‘yinchi xostlikni oladi — shunda o‘yin davom etadi.
 */
object MafiaRoom {

    private const val TAG = "MafiaRoom"

    const val KIND = GamesCore.KIND_MAFIA

    /** Taklif etilgan o‘yinchi kutishi mumkin bo‘lgan muddat. */
    const val INVITE_TTL_MS = 10 * 60_000L

    enum class JoinResult { OK, FULL, STARTED, ALREADY, ERROR }

    data class Room(
        val id: String,
        val chatId: Long,
        val chatTitle: String,
        val hostUid: String,
        val status: String,
        val seats: List<Seat>,
        val phase: Phase,
        val day: Int,
        val phaseEndsAt: Long,
        val hostSeenAt: Long,
        val votes: Map<String, String>,
        val nightVictimUid: String,
        val nightSaved: Boolean,
        val executedUid: String,
        val executedRole: Role?,
        val winner: Winner?,
        val log: List<String>,
        val voice: GameVoice.Message?,
        val reaction: GamesCore.Reaction?,
        val createdAt: Long,
        val updatedAt: Long
    ) {
        fun seatOf(uid: String): Seat? = seats.firstOrNull { it.uid == uid }
        fun aliveSeats(): List<Seat> = seats.filter { it.alive }
        fun aliveCount(): Int = seats.count { it.alive }
        fun isHost(uid: String): Boolean = hostUid == uid
        fun isActive(): Boolean = status == GamesCore.STATUS_ACTIVE
        fun isLobby(): Boolean = phase == Phase.LOBBY
        fun isFinished(): Boolean = phase == Phase.END
        fun remainingMs(now: Long = GamesCore.now()): Long =
            (phaseEndsAt - now).coerceAtLeast(0L)
        fun hostStale(now: Long = GamesCore.now()): Boolean =
            hostSeenAt > 0L && now - hostSeenAt > MafiaRules.HOST_STALE_MS
        fun votesFor(uid: String): Int = votes.values.count { it == uid }
        fun myVote(uid: String): String = votes[uid].orEmpty()
        fun hasVoted(uid: String): Boolean = votes.containsKey(uid)
    }

    data class Secret(
        val role: Role?,
        val nightTarget: String,
        val check: String,
        val checkRole: Role?,
        val team: List<String>
    )

    // --------------------------------------------------------------- create

    suspend fun create(
        account: Int,
        chatId: Long,
        chatTitle: String,
        invited: List<String> = emptyList()
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                val signed = GamesCore.signIn(account)
                val me = GamesCore.me(account, 0)
                if (signed != me.uid || me.uid.isEmpty()) return@withContext null
                cancelStaleLobbies(me.uid, chatId)
                val id = "maf_" + UUID.randomUUID().toString().replace("-", "").take(16)
                val now = GamesCore.now()
                val seat = Seat(me.uid, me.tgId, me.name, 0, true)
                GamesCore.rooms().document(id).set(
                    mapOf(
                        "kind" to KIND,
                        "chatId" to chatId,
                        "chatTitle" to chatTitle.take(90),
                        "hostUid" to me.uid,
                        "status" to GamesCore.STATUS_INVITE,
                        "uids" to listOf(me.uid),
                        "players" to listOf(seat.toMap()),
                        "invited" to invited.filter { it.isNotBlank() && it != me.uid },
                        "phase" to Phase.LOBBY.code,
                        "day" to 0,
                        "phaseEndsAt" to 0L,
                        "hostSeenAt" to now,
                        "votes" to emptyMap<String, String>(),
                        "nightVictimUid" to "",
                        "nightSaved" to false,
                        "executedUid" to "",
                        "executedRole" to "",
                        "winner" to "",
                        "log" to listOf("🎭 Xona yaratildi. O‘yinchilar kutilmoqda."),
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                ).await()
                id
            } catch (e: Exception) {
                Log.e(TAG, "create: ${e.message}", e)
                null
            }
        }

    private suspend fun cancelStaleLobbies(hostUid: String, chatId: Long) {
        try {
            val snap = GamesCore.rooms().whereEqualTo("chatId", chatId).limit(24).get().await()
            val now = GamesCore.now()
            for (doc in snap.documents) {
                if (doc.getString("kind") != KIND) continue
                if (doc.getString("hostUid") != hostUid) continue
                if (doc.getString("phase") != Phase.LOBBY.code) continue
                doc.reference.update(
                    "status", GamesCore.STATUS_CANCELLED,
                    "updatedAt", now
                ).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cancelStaleLobbies: ${e.message}")
        }
    }

    // ----------------------------------------------------------- join/leave

    suspend fun join(account: Int, roomId: String): JoinResult = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val me = GamesCore.me(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) return@runTransaction JoinResult.ERROR
                if (snap.getString("status") == GamesCore.STATUS_CANCELLED) {
                    return@runTransaction JoinResult.ERROR
                }
                val seats = MafiaRules.parseSeats(snap.get("players"))
                if (seats.any { it.uid == uid }) return@runTransaction JoinResult.ALREADY
                if (snap.getString("phase") != Phase.LOBBY.code) {
                    return@runTransaction JoinResult.STARTED
                }
                if (seats.size >= MafiaRules.MAX_PLAYERS) return@runTransaction JoinResult.FULL
                val now = GamesCore.now()
                val next = ArrayList(seats)
                next.add(Seat(me.uid, me.tgId, me.name, next.size, true))
                val log = appendLog(snap.get("log"), "➕ ${me.name} qo‘shildi.")
                tx.update(
                    ref,
                    mapOf(
                        "players" to MafiaRules.seatsToMaps(next),
                        "uids" to next.map { it.uid },
                        "log" to log,
                        "updatedAt" to now
                    )
                )
                JoinResult.OK
            }.await()
        } catch (e: Exception) {
            Log.e(TAG, "join: ${e.message}", e)
            JoinResult.ERROR
        }
    }

    suspend fun leave(account: Int, roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) return@runTransaction
                val seats = MafiaRules.parseSeats(snap.get("players"))
                val me = seats.firstOrNull { it.uid == uid } ?: return@runTransaction
                val now = GamesCore.now()
                val lobby = snap.getString("phase") == Phase.LOBBY.code
                if (lobby) {
                    val next = seats.filter { it.uid != uid }
                    if (next.isEmpty()) {
                        tx.update(
                            ref,
                            "status", GamesCore.STATUS_CANCELLED,
                            "updatedAt", now
                        )
                        return@runTransaction
                    }
                    val resorted = next.mapIndexed { index, seat -> seat.copy(seat = index) }
                    val nextHost = if (snap.getString("hostUid") == uid) {
                        resorted.first().uid
                    } else {
                        snap.getString("hostUid").orEmpty()
                    }
                    tx.update(
                        ref,
                        mapOf(
                            "players" to MafiaRules.seatsToMaps(resorted),
                            "uids" to resorted.map { it.uid },
                            "hostUid" to nextHost,
                            "log" to appendLog(snap.get("log"), "➖ ${me.name} chiqdi."),
                            "updatedAt" to now
                        )
                    )
                } else {
                    if (!me.alive) return@runTransaction
                    val next = seats.map { if (it.uid == uid) it.copy(alive = false) else it }
                    val winner = winnerOf(next, tx, roomId)
                    val updates = HashMap<String, Any>(6)
                    updates["players"] = MafiaRules.seatsToMaps(next)
                    updates["updatedAt"] = now
                    val log = ArrayList<String>(2)
                    log.add("🚪 ${me.name} o‘yindan chiqdi.")
                    if (winner != null && snap.getString("phase") != Phase.END.code) {
                        updates["phase"] = Phase.END.code
                        updates["winner"] = winner.code
                        updates["status"] = GamesCore.STATUS_FINISHED
                        updates["phaseEndsAt"] = 0L
                        log.add(winnerText(winner))
                    }
                    updates["log"] = appendLog(snap.get("log"), log)
                    tx.update(ref, updates)
                }
            }.await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "leave: ${e.message}", e)
            false
        }
    }

    // ---------------------------------------------------------------- start

    suspend fun start(account: Int, roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) return@runTransaction false
                if (snap.getString("hostUid") != uid) return@runTransaction false
                if (snap.getString("phase") != Phase.LOBBY.code) return@runTransaction false
                val seats = MafiaRules.parseSeats(snap.get("players"))
                if (!MafiaRules.canStart(seats.size)) return@runTransaction false

                val roles = MafiaRules.assign(seats)
                val mafiaNames = HashMap<String, String>(seats.size)
                for (rest in seats) {
                    if (roles[rest.uid]?.isMafia == true) mafiaNames[rest.uid] = rest.name
                }
                val now = GamesCore.now()
                for (seat in seats) {
                    val role = roles[seat.uid] ?: Role.CIVILIAN
                    val team = if (role.isMafia) {
                        mafiaNames.filterKeys { it != seat.uid }.values.toList()
                    } else {
                        emptyList()
                    }
                    tx.set(
                        secretRef(roomId, seat.uid),
                        mapOf(
                            "role" to role.code,
                            "nightTarget" to "",
                            "check" to "",
                            "checkRole" to "",
                            "team" to team
                        )
                    )
                }
                val mafiaCount = roles.values.count { it.isMafia }
                tx.update(
                    ref,
                    mapOf(
                        "status" to GamesCore.STATUS_ACTIVE,
                        "phase" to Phase.NIGHT.code,
                        "day" to 1,
                        "phaseEndsAt" to now + MafiaRules.NIGHT_SECONDS * 1000L,
                        "hostSeenAt" to now,
                        "votes" to emptyMap<String, String>(),
                        "nightVictimUid" to "",
                        "nightSaved" to false,
                        "executedUid" to "",
                        "executedRole" to "",
                        "winner" to "",
                        "players" to MafiaRules.seatsToMaps(seats.map { it.copy(alive = true) }),
                        "log" to appendLog(
                            snap.get("log"),
                            "🌙 1-tun boshlandi. Shaharda $mafiaCount mafiya bor."
                        ),
                        "updatedAt" to now
                    )
                )
                true
            }.await()
        } catch (e: Exception) {
            Log.e(TAG, "start: ${e.message}", e)
            false
        }
    }

    // ---------------------------------------------------------------- night

    suspend fun submitNight(account: Int, roomId: String, targetUid: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val uid = GamesCore.signIn(account)
                val ref = GamesCore.rooms().document(roomId.trim())
                GamesCore.db.runTransaction { tx ->
                    val snap = tx.get(ref)
                    if (!snap.exists()) return@runTransaction false
                    if (snap.getString("phase") != Phase.NIGHT.code) return@runTransaction false
                    val seats = MafiaRules.parseSeats(snap.get("players"))
                    val me = seats.firstOrNull { it.uid == uid } ?: return@runTransaction false
                    if (!me.alive) return@runTransaction false
                    val secretDoc = tx.get(secretRef(roomId, uid))
                    val role = Role.fromCode(secretDoc.getString("role"))
                        ?: return@runTransaction false
                    if (role == Role.CIVILIAN) return@runTransaction false
                    val target = seats.firstOrNull { it.uid == targetUid && it.alive }
                        ?: return@runTransaction false
                    if (role.isMafia && target.uid == uid) return@runTransaction false
                    val now = GamesCore.now()
                    tx.set(
                        secretRef(roomId, uid),
                        mapOf(
                            "role" to role.code,
                            "nightTarget" to target.uid,
                            "check" to (secretDoc.getString("check") ?: ""),
                            "checkRole" to (secretDoc.getString("checkRole") ?: ""),
                            "team" to (secretDoc.get("team") ?: emptyList<String>())
                        )
                    )
                    tx.update(ref, "updatedAt", now)
                    true
                }.await()
            } catch (e: Exception) {
                Log.e(TAG, "submitNight: ${e.message}", e)
                false
            }
        }

    /** Xost tunga yakun yozadi. */
    suspend fun resolveNight(account: Int, roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) return@runTransaction false
                if (snap.getString("hostUid") != uid) return@runTransaction false
                if (snap.getString("phase") != Phase.NIGHT.code) return@runTransaction false
                val seats = MafiaRules.parseSeats(snap.get("players"))

                val actions = ArrayList<Triple<Seat, Role, String>>()
                val teams = HashMap<String, Any?>(seats.size)
                for (seat in seats) {
                    if (!seat.alive) continue
                    val doc = tx.get(secretRef(roomId, seat.uid))
                    val role = Role.fromCode(doc.getString("role")) ?: continue
                    teams[seat.uid] = doc.get("team")
                    actions.add(Triple(seat, role, doc.getString("nightTarget").orEmpty()))
                }

                val mafiaVotes = actions.filter { it.second.isMafia }
                    .map { it.second to it.third }
                val killTarget = MafiaRules.mafiaTarget(mafiaVotes)
                val healTarget = actions.firstOrNull { it.second == Role.DOCTOR }?.third.orEmpty()
                val checkSeat = actions.firstOrNull { it.second == Role.COMMISSAR }
                val checkTarget = checkSeat?.third.orEmpty()

                var victim: Seat? = null
                var saved = false
                if (killTarget.isNotBlank()) {
                    val target = seats.firstOrNull { it.uid == killTarget && it.alive }
                    if (target != null) {
                        if (healTarget.isNotBlank() && healTarget == killTarget) {
                            saved = true
                        } else {
                            victim = target
                        }
                    }
                }

                val nextSeats = if (victim == null) seats else
                    seats.map { if (it.uid == victim.uid) it.copy(alive = false) else it }
                // Firestore tranzaksiyasi barcha o‘qishlarni yozishdan oldin talab qiladi.
                val winner = winnerOf(nextSeats, tx, roomId)

                // Komissar tekshiruvi natijasi — faqat uning maxfiy hujjatiga.
                if (checkSeat != null && checkTarget.isNotBlank()) {
                    val targetRole = actions.firstOrNull { it.first.uid == checkTarget }?.second
                    if (targetRole != null) {
                        tx.set(
                            secretRef(roomId, checkSeat.first.uid),
                            mapOf(
                                "role" to checkSeat.second.code,
                                "nightTarget" to checkSeat.third,
                                "check" to checkTarget,
                                "checkRole" to targetRole.code,
                                "team" to (teams[checkSeat.first.uid] ?: emptyList<String>())
                            )
                        )
                    }
                }

                val now = GamesCore.now()
                val log = ArrayList<String>(3)
                if (victim != null) {
                    log.add("☠️ ${victim.name} tunda o‘ldirildi.")
                } else if (saved) {
                    log.add("🩹 Bu tun hujum bo‘ldi — shifokor ulgurdi.")
                } else {
                    log.add("🌙 Tun tinch o‘tdi.")
                }

                val updates = HashMap<String, Any>(10)
                updates["players"] = MafiaRules.seatsToMaps(nextSeats)
                updates["nightVictimUid"] = victim?.uid.orEmpty()
                updates["nightSaved"] = saved
                updates["updatedAt"] = now
                if (winner != null) {
                    updates["phase"] = Phase.END.code
                    updates["winner"] = winner.code
                    updates["status"] = GamesCore.STATUS_FINISHED
                    updates["phaseEndsAt"] = 0L
                    log.add(winnerText(winner))
                } else {
                    updates["phase"] = Phase.DAWN.code
                    updates["phaseEndsAt"] = now + MafiaRules.DAWN_SECONDS * 1000L
                }
                updates["log"] = appendLog(snap.get("log"), log)
                tx.update(ref, updates)
                true
            }.await()
        } catch (e: Exception) {
            Log.e(TAG, "resolveNight: ${e.message}", e)
            false
        }
    }

    // ----------------------------------------------------------------- vote

    suspend fun submitVote(account: Int, roomId: String, targetUid: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val uid = GamesCore.signIn(account)
                val ref = GamesCore.rooms().document(roomId.trim())
                GamesCore.db.runTransaction { tx ->
                    val snap = tx.get(ref)
                    if (!snap.exists()) return@runTransaction false
                    if (snap.getString("phase") != Phase.VOTE.code) return@runTransaction false
                    val seats = MafiaRules.parseSeats(snap.get("players"))
                    val me = seats.firstOrNull { it.uid == uid } ?: return@runTransaction false
                    if (!me.alive) return@runTransaction false
                    if (targetUid.isNotBlank()) {
                        val target = seats.firstOrNull { it.uid == targetUid && it.alive }
                            ?: return@runTransaction false
                        if (target.uid == uid) return@runTransaction false
                    }
                    val votes = HashMap<String, String>()
                    val raw = snap.get("votes") as? Map<*, *>
                    raw?.forEach { (key, value) ->
                        val k = key?.toString().orEmpty()
                        val v = value?.toString().orEmpty()
                        if (k.isNotEmpty()) votes[k] = v
                    }
                    votes[uid] = targetUid
                    tx.update(
                        ref,
                        mapOf(
                            "votes" to votes,
                            "updatedAt" to GamesCore.now()
                        )
                    )
                    true
                }.await()
            } catch (e: Exception) {
                Log.e(TAG, "submitVote: ${e.message}", e)
                false
            }
        }

    /** Xost ovozlarni sanaydi va natijani e’lon qiladi. */
    suspend fun resolveVote(account: Int, roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) return@runTransaction false
                if (snap.getString("hostUid") != uid) return@runTransaction false
                if (snap.getString("phase") != Phase.VOTE.code) return@runTransaction false
                val seats = MafiaRules.parseSeats(snap.get("players"))
                val votes = ArrayList<String>()
                val raw = snap.get("votes") as? Map<*, *>
                raw?.forEach { (_, value) -> votes.add(value?.toString().orEmpty()) }

                val targetUid = MafiaRules.lynchTarget(votes)
                val executed = seats.firstOrNull { it.uid == targetUid && it.alive }
                val now = GamesCore.now()
                val log = ArrayList<String>(2)
                var executedRole: Role? = null

                val nextSeats: List<Seat>
                if (executed == null) {
                    nextSeats = seats
                    log.add("⚖️ Ovozlar teng — hech kim chiqarilmadi.")
                } else {
                    val secret = tx.get(secretRef(roomId, executed.uid))
                    executedRole = Role.fromCode(secret.getString("role"))
                    nextSeats = seats.map {
                        if (it.uid == executed.uid) it.copy(alive = false) else it
                    }
                    val roleName = roleName(executedRole)
                    log.add("🗳️ ${executed.name} chiqarib yuborildi — $roleName.")
                }

                val winner = winnerOf(nextSeats, tx, roomId)
                val updates = HashMap<String, Any>(10)
                updates["players"] = MafiaRules.seatsToMaps(nextSeats)
                updates["executedUid"] = executed?.uid.orEmpty()
                updates["executedRole"] = executedRole?.code.orEmpty()
                updates["votes"] = emptyMap<String, String>()
                updates["updatedAt"] = now
                if (winner != null) {
                    updates["phase"] = Phase.END.code
                    updates["winner"] = winner.code
                    updates["status"] = GamesCore.STATUS_FINISHED
                    updates["phaseEndsAt"] = 0L
                    log.add(winnerText(winner))
                } else {
                    updates["phase"] = Phase.EXECUTION.code
                    updates["phaseEndsAt"] = now + MafiaRules.EXECUTION_SECONDS * 1000L
                }
                updates["log"] = appendLog(snap.get("log"), log)
                tx.update(ref, updates)
                true
            }.await()
        } catch (e: Exception) {
            Log.e(TAG, "resolveVote: ${e.message}", e)
            false
        }
    }

    // -------------------------------------------------------------- advance

    /**
     * Xostning oddiy faza o‘tishlari:
     *  DAWN → DAY, DAY → VOTE, EXECUTION → NIGHT (yangi tun).
     */
    suspend fun advance(account: Int, roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) return@runTransaction false
                if (snap.getString("hostUid") != uid) return@runTransaction false
                val phase = Phase.fromCode(snap.getString("phase")) ?: return@runTransaction false
                val seats = MafiaRules.parseSeats(snap.get("players"))
                val now = GamesCore.now()
                val day = GamesCore.intOf(snap.data, "day", 1)

                when (phase) {
                    Phase.DAWN -> {
                        tx.update(
                            ref,
                            mapOf(
                                "phase" to Phase.DAY.code,
                                "phaseEndsAt" to now + MafiaRules.DAY_SECONDS * 1000L,
                                "votes" to emptyMap<String, String>(),
                                "updatedAt" to now,
                                "log" to appendLog(
                                    snap.get("log"),
                                    "☀️ Muhokama boshlandi — kim mafiya?"
                                )
                            )
                        )
                    }
                    Phase.DAY -> {
                        tx.update(
                            ref,
                            mapOf(
                                "phase" to Phase.VOTE.code,
                                "phaseEndsAt" to now + MafiaRules.VOTE_SECONDS * 1000L,
                                "votes" to emptyMap<String, String>(),
                                "updatedAt" to now,
                                "log" to appendLog(snap.get("log"), "🗳️ Ovoz berish boshlandi.")
                            )
                        )
                    }
                    Phase.EXECUTION -> {
                        // Avval barcha o‘qishlar, keyin yozishlar (Firestore talabi).
                        val secrets = ArrayList<Triple<String, String, com.google.firebase.firestore.DocumentSnapshot>>(seats.size)
                        for (seat in seats) {
                            if (!seat.alive) continue
                            val doc = tx.get(secretRef(roomId, seat.uid))
                            val role = Role.fromCode(doc.getString("role")) ?: continue
                            secrets.add(Triple(seat.uid, role.code, doc))
                        }
                        for ((seatUid, roleCode, doc) in secrets) {
                            tx.set(
                                secretRef(roomId, seatUid),
                                mapOf(
                                    "role" to roleCode,
                                    "nightTarget" to "",
                                    "check" to (doc.getString("check") ?: ""),
                                    "checkRole" to (doc.getString("checkRole") ?: ""),
                                    "team" to (doc.get("team") ?: emptyList<String>())
                                )
                            )
                        }
                        val nextDay = day + 1
                        tx.update(
                            ref,
                            mapOf(
                                "phase" to Phase.NIGHT.code,
                                "day" to nextDay,
                                "phaseEndsAt" to now + MafiaRules.NIGHT_SECONDS * 1000L,
                                "votes" to emptyMap<String, String>(),
                                "nightVictimUid" to "",
                                "nightSaved" to false,
                                "executedUid" to "",
                                "executedRole" to "",
                                "updatedAt" to now,
                                "log" to appendLog(
                                    snap.get("log"),
                                    "🌙 $nextDay-tun boshlandi."
                                )
                            )
                        )
                    }
                    else -> return@runTransaction false
                }
                true
            }.await()
        } catch (e: Exception) {
            Log.e(TAG, "advance: ${e.message}", e)
            false
        }
    }

    // ------------------------------------------------------- host heartbeat

    suspend fun heartbeat(account: Int, roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            GamesCore.signIn(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            // O'qishsiz yozuv: qoidalar `hostSeenAt`ni faqat xostga yozdiradi,
            // hujjat yo'q bo'lsa update istisno beradi — har beat'da 1 o'qish tejaladi.
            ref.update("hostSeenAt", GamesCore.now()).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "heartbeat: ${e.message}")
            false
        }
    }

    /** Xost uzoq vaqt "jim" bo‘lsa — boshqa tirik o‘yinchi xostlikni oladi. */
    suspend fun takeHost(account: Int, roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) return@runTransaction false
                if (snap.getString("phase") == Phase.END.code) return@runTransaction false
                val seats = MafiaRules.parseSeats(snap.get("players"))
                val me = seats.firstOrNull { it.uid == uid } ?: return@runTransaction false
                if (!me.alive) return@runTransaction false
                val now = GamesCore.now()
                val seenAt = snap.getLong("hostSeenAt") ?: 0L
                if (seenAt > 0L && now - seenAt <= MafiaRules.HOST_STALE_MS) {
                    return@runTransaction false
                }
                tx.update(
                    ref,
                    mapOf(
                        "hostUid" to uid,
                        "hostSeenAt" to now,
                        "log" to appendLog(snap.get("log"), "👑 ${me.name} xost bo‘ldi."),
                        "updatedAt" to now
                    )
                )
                true
            }.await()
        } catch (e: Exception) {
            Log.e(TAG, "takeHost: ${e.message}", e)
            false
        }
    }

    // --------------------------------------------------------------- secret

    // ------------------------------------------------------------- reactions

    /** Taklifni rad etish — o‘zini `invited` dan olib tashlaydi (xost lobbini bekor qiladi). */
    suspend fun decline(account: Int, roomId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val ref = GamesCore.rooms().document(roomId.trim())
            GamesCore.db.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) return@runTransaction
                if (snap.getString("status") != GamesCore.STATUS_INVITE) return@runTransaction
                val now = GamesCore.now()
                if (snap.getString("hostUid") == uid) {
                    tx.update(
                        ref,
                        mapOf(
                            "status" to GamesCore.STATUS_CANCELLED,
                            "updatedAt" to now
                        )
                    )
                } else {
                    val invited = GamesCore.stringsOf(snap.data, "invited")
                    if (!invited.contains(uid)) return@runTransaction
                    tx.update(
                        ref,
                        mapOf(
                            "invited" to invited.filter { it != uid },
                            "updatedAt" to now
                        )
                    )
                }
            }.await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "decline: ${e.message}", e)
            false
        }
    }

    /** Reaksiya — barcha o‘yinchilarga 2 soniya ko‘rinadi. */
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

    suspend fun readSecret(account: Int, roomId: String): Secret? = withContext(Dispatchers.IO) {
        try {
            val uid = GamesCore.signIn(account)
            val doc = secretRef(roomId, uid).get().await()
            if (!doc.exists()) return@withContext null
            Secret(
                role = Role.fromCode(doc.getString("role")),
                nightTarget = doc.getString("nightTarget").orEmpty(),
                check = doc.getString("check").orEmpty(),
                checkRole = Role.fromCode(doc.getString("checkRole")),
                team = (doc.get("team") as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
            )
        } catch (e: Exception) {
            Log.e(TAG, "readSecret: ${e.message}", e)
            null
        }
    }

    fun listenSecret(roomId: String, uid: String, onUpdate: (Secret?) -> Unit): ListenerRegistration? {
        if (uid.isBlank()) return null
        return secretRef(roomId, uid).addSnapshotListener { snap, error ->
            if (error != null) {
                Log.e(TAG, "listenSecret: ${error.message}", error)
                return@addSnapshotListener
            }
            if (snap == null || !snap.exists()) {
                onUpdate(null)
                return@addSnapshotListener
            }
            onUpdate(
                Secret(
                    role = Role.fromCode(snap.getString("role")),
                    nightTarget = snap.getString("nightTarget").orEmpty(),
                    check = snap.getString("check").orEmpty(),
                    checkRole = Role.fromCode(snap.getString("checkRole")),
                    team = (snap.get("team") as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
                )
            )
        }
    }

    private fun secretRef(roomId: String, uid: String) =
        GamesCore.rooms().document(roomId.trim()).collection("secrets").document(uid.trim())

    // ---------------------------------------------------------------- reads

    fun listen(roomId: String, onUpdate: (Room?) -> Unit): ListenerRegistration {
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

    fun listenChatRooms(chatId: Long, onUpdate: (List<Room>) -> Unit): ListenerRegistration {
        return GamesCore.rooms()
            .whereEqualTo("chatId", chatId)
            .whereEqualTo("kind", KIND)
            .limit(24)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.e(TAG, "listenChatRooms: ${error.message}", error)
                    return@addSnapshotListener
                }
                val list = ArrayList<Room>()
                snap?.documents?.forEach { doc ->
                    if (doc.getString("kind") != KIND) return@forEach
                    if (doc.getString("status") == GamesCore.STATUS_CANCELLED) return@forEach
                    parse(doc.id, doc.data.orEmpty())?.let { list.add(it) }
                }
                list.sortByDescending { it.updatedAt }
                onUpdate(list)
            }
    }

    /** Menga yo‘naltirilgan mafiya takliflari (hali boshlanmagan lobbi). */
    fun listenInvites(uid: String, onUpdate: (List<Room>) -> Unit): ListenerRegistration? {
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
                val list = ArrayList<Room>()
                snap?.documents?.forEach { doc ->
                    if (doc.getString("kind") != KIND) return@forEach
                    if (doc.getString("status") != GamesCore.STATUS_INVITE) return@forEach
                    val room = parse(doc.id, doc.data.orEmpty()) ?: return@forEach
                    if (!room.isLobby()) return@forEach
                    if (room.createdAt > 0L && now - room.createdAt > INVITE_TTL_MS) {
                        return@forEach
                    }
                    list.add(room)
                }
                list.sortByDescending { it.createdAt }
                onUpdate(list)
            }
    }

    fun parse(id: String, data: Map<String, Any?>): Room? {
        if (data["kind"]?.toString() != KIND) return null
        val votes = HashMap<String, String>()
        (data["votes"] as? Map<*, *>)?.forEach { (key, value) ->
            val k = key?.toString().orEmpty()
            if (k.isNotEmpty()) votes[k] = value?.toString().orEmpty()
        }
        return Room(
            id = id,
            chatId = GamesCore.longOf(data, "chatId"),
            chatTitle = GamesCore.stringOf(data, "chatTitle"),
            hostUid = GamesCore.stringOf(data, "hostUid"),
            status = GamesCore.stringOf(data, "status").ifBlank { GamesCore.STATUS_INVITE },
            seats = MafiaRules.parseSeats(data["players"]),
            phase = Phase.fromCode(data["phase"]?.toString()) ?: Phase.LOBBY,
            day = GamesCore.intOf(data, "day", 0),
            phaseEndsAt = GamesCore.longOf(data, "phaseEndsAt"),
            hostSeenAt = GamesCore.longOf(data, "hostSeenAt"),
            votes = votes,
            nightVictimUid = GamesCore.stringOf(data, "nightVictimUid"),
            nightSaved = GamesCore.boolOf(data, "nightSaved"),
            executedUid = GamesCore.stringOf(data, "executedUid"),
            executedRole = Role.fromCode(data["executedRole"]?.toString()),
            winner = Winner.fromCode(data["winner"]?.toString()),
            log = GamesCore.stringsOf(data, "log"),
            voice = GameVoice.parse(data["voice"]),
            reaction = GamesCore.parseReaction(data["reaction"]),
            createdAt = GamesCore.longOf(data, "createdAt"),
            updatedAt = GamesCore.longOf(data, "updatedAt")
        )
    }

    // -------------------------------------------------------------- helpers

    private fun appendLog(raw: Any?, add: String): List<String> {
        val existing = GamesCore.stringsOf(mapOf("log" to raw), "log").toMutableList()
        existing.addAll(add.lines().filter { it.isNotBlank() })
        while (existing.size > 40) existing.removeAt(0)
        return existing
    }

    private fun appendLog(raw: Any?, add: List<String>): List<String> {
        val existing = GamesCore.stringsOf(mapOf("log" to raw), "log").toMutableList()
        existing.addAll(add.filter { it.isNotBlank() })
        while (existing.size > 40) existing.removeAt(0)
        return existing
    }

    private fun roleName(role: Role?): String = when (role) {
        Role.MAFIA -> "mafiya"
        Role.DON -> "don"
        Role.COMMISSAR -> "komissar"
        Role.DOCTOR -> "shifokor"
        Role.CIVILIAN -> "fuqaro"
        null -> "noma’lum"
    }

    private fun winnerText(winner: Winner): String = when (winner) {
        Winner.MAFIA -> "🏆 Mafiya g‘alaba qozondi."
        Winner.CITY -> "🏆 Shahar g‘alaba qozondi."
    }

    /** Tirik o‘yinchilarning rollarini o‘qib, g‘olibni qaytaradi (xost tranzaksiyasi ichida). */
    private fun winnerOf(
        seats: List<Seat>,
        tx: com.google.firebase.firestore.Transaction,
        roomId: String
    ): Winner? {
        val aliveRoles = ArrayList<Role>(seats.size)
        for (seat in seats) {
            if (!seat.alive) continue
            val doc = tx.get(secretRef(roomId, seat.uid))
            val role = Role.fromCode(doc.getString("role")) ?: continue
            aliveRoles.add(role)
        }
        return MafiaRules.winner(aliveRoles)
    }
}
