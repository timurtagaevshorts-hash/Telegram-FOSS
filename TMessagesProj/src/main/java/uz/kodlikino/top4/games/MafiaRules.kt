package uz.kodlikino.top4.games

/**
 * Mafiya (partiya o‘yini) modeli va qoidalari.
 *
 * Rollar va tunda bajariladigan amallar `yuz_games/{id}/secrets/{uid}` hujjatlarida
 * saqlanadi — ularni faqat o‘yinchi o‘zi va xost o‘qiydi (Firestore qoidalari).
 * Ommaviy hujjatda faqat hammaga ko‘rinadigan narsa: o‘yinchi ro‘yxati, faza,
 * ovozlar va jurnal.
 */
object MafiaRules {

    const val MIN_PLAYERS = 5
    const val MAX_PLAYERS = 12

    const val NIGHT_SECONDS = 45
    const val DAWN_SECONDS = 7
    const val DAY_SECONDS = 90
    const val VOTE_SECONDS = 40
    const val EXECUTION_SECONDS = 7

    // Har partiyada 6 s'lik "tirik" yozuvi bepul kvotani tez yeb qo'yardi:
    // 18 s — 3 baravar kam yozuv, 60 s stale esa uchta o'tkazib yuborilgan
    // beat'ga toqat qiladi (sekin tarmoqda xostni behuda almashtirmaydi).
    const val HOST_STALE_MS = 60_000L
    const val HOST_BEAT_MS = 18_000L

    enum class Role(val code: String) {
        CIVILIAN("civ"),
        MAFIA("maf"),
        DON("don"),
        COMMISSAR("com"),
        DOCTOR("doc");

        val isMafia: Boolean get() = this == MAFIA || this == DON
        val isCity: Boolean get() = !isMafia

        companion object {
            fun fromCode(code: String?): Role? = when (code?.trim()?.lowercase()) {
                "civ", "civilian" -> CIVILIAN
                "maf", "mafia" -> MAFIA
                "don" -> DON
                "com", "commissar" -> COMMISSAR
                "doc", "doctor" -> DOCTOR
                else -> null
            }
        }
    }

    enum class Phase(val code: String) {
        LOBBY("lobby"),
        NIGHT("night"),
        DAWN("dawn"),
        DAY("day"),
        VOTE("vote"),
        EXECUTION("execution"),
        END("end");

        companion object {
            fun fromCode(code: String?): Phase? = when (code?.trim()?.lowercase()) {
                "lobby" -> LOBBY
                "night" -> NIGHT
                "dawn" -> DAWN
                "day" -> DAY
                "vote" -> VOTE
                "execution" -> EXECUTION
                "end" -> END
                else -> null
            }
        }
    }

    enum class Winner(val code: String) {
        MAFIA("mafia"),
        CITY("city");

        companion object {
            fun fromCode(code: String?): Winner? = when (code?.trim()?.lowercase()) {
                "mafia" -> MAFIA
                "city" -> CITY
                else -> null
            }
        }
    }

    data class Seat(
        val uid: String,
        val tgId: Long,
        val name: String,
        val seat: Int,
        val alive: Boolean = true
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "uid" to uid,
            "tgId" to tgId,
            "name" to name,
            "seat" to seat,
            "alive" to alive
        )
    }

    fun parseSeat(raw: Any?): Seat? {
        val map = raw as? Map<*, *> ?: return null
        val uid = map["uid"]?.toString()?.trim().orEmpty()
        if (uid.isBlank()) return null
        return Seat(
            uid = uid,
            tgId = (map["tgId"] as? Number)?.toLong() ?: 0L,
            name = map["name"]?.toString().orEmpty().take(40).ifBlank { "O‘yinchi" },
            seat = (map["seat"] as? Number)?.toInt() ?: 0,
            alive = map["alive"] as? Boolean ?: true
        )
    }

    fun parseSeats(raw: Any?): List<Seat> {
        val list = raw as? List<*> ?: return emptyList()
        val out = ArrayList<Seat>(list.size)
        for (item in list) parseSeat(item)?.let { out.add(it) }
        return out
    }

    fun seatsToMaps(seats: List<Seat>): List<Map<String, Any>> = seats.map { it.toMap() }

    /**
     * Rol tarkibi (klassik balans):
     *  5–6  → don, komissar, shifokor, qolganlari fuqaro
     *  7–8  → +1 mafiya
     *  9–10 → +2 mafiya
     *  11–12→ +3 mafiya
     */
    fun composition(count: Int): List<Role> {
        val n = count.coerceIn(MIN_PLAYERS, MAX_PLAYERS)
        val mafia = when {
            n <= 6 -> 1
            n <= 8 -> 2
            n <= 10 -> 3
            else -> 4
        }
        val roles = ArrayList<Role>(n)
        roles.add(Role.DON)
        repeat(mafia - 1) { roles.add(Role.MAFIA) }
        roles.add(Role.COMMISSAR)
        roles.add(Role.DOCTOR)
        while (roles.size < n) roles.add(Role.CIVILIAN)
        return roles
    }

    fun mafiaCount(count: Int): Int = composition(count).count { it.isMafia }

    fun canStart(count: Int): Boolean = count in MIN_PLAYERS..MAX_PLAYERS

    /** Rollarni o‘yinchi tartibiga tasodifiy taqsimlaydi. */
    fun assign(seats: List<Seat>): Map<String, Role> {
        val roles = composition(seats.size).shuffled()
        val out = HashMap<String, Role>(seats.size)
        for (index in seats.indices) {
            out[seats[index].uid] = roles[index]
        }
        return out
    }

    /**
     * G‘olibni aniqlaydi. [aliveRoles] — tirik o‘yinchilarning rollari (faqat xost biladi).
     * Mafiya shahar sonidan kam bo‘lmasa — mafiya yutadi.
     */
    fun winner(aliveRoles: List<Role>): Winner? {
        if (aliveRoles.isEmpty()) return null
        val mafia = aliveRoles.count { it.isMafia }
        val city = aliveRoles.size - mafia
        if (mafia == 0) return Winner.CITY
        if (mafia >= city) return Winner.MAFIA
        return null
    }

    /** Tunda mafiya nishonini tanlaydi: donning ovozi ustuvor, aks holda ko‘pchilik. */
    fun mafiaTarget(votes: List<Pair<Role, String>>): String {
        val donChoice = votes.firstOrNull { it.first == Role.DON && it.second.isNotBlank() }?.second
        if (donChoice != null) return donChoice
        val counts = HashMap<String, Int>()
        for ((_, target) in votes) {
            if (target.isBlank()) continue
            counts[target] = (counts[target] ?: 0) + 1
        }
        var best = ""
        var bestCount = 0
        for ((target, count) in counts) {
            if (count > bestCount) {
                best = target
                bestCount = count
            }
        }
        return best
    }

    /** Kunlik ovoz natijasi: ko‘pchilik; tenglik yoki ovoz yo‘q bo‘lsa — hech kim. */
    fun lynchTarget(votes: Collection<String>): String {
        val counts = HashMap<String, Int>()
        for (target in votes) {
            if (target.isBlank()) continue
            counts[target] = (counts[target] ?: 0) + 1
        }
        var best = ""
        var bestCount = 0
        var tied = false
        for ((target, count) in counts) {
            if (count > bestCount) {
                best = target
                bestCount = count
                tied = false
            } else if (count == bestCount) {
                tied = true
            }
        }
        return if (tied || bestCount == 0) "" else best
    }

    fun phaseSeconds(phase: Phase): Int = when (phase) {
        Phase.NIGHT -> NIGHT_SECONDS
        Phase.DAWN -> DAWN_SECONDS
        Phase.DAY -> DAY_SECONDS
        Phase.VOTE -> VOTE_SECONDS
        Phase.EXECUTION -> EXECUTION_SECONDS
        Phase.LOBBY, Phase.END -> 0
    }

    fun nextPhase(phase: Phase): Phase = when (phase) {
        Phase.LOBBY -> Phase.NIGHT
        Phase.NIGHT -> Phase.DAWN
        Phase.DAWN -> Phase.DAY
        Phase.DAY -> Phase.VOTE
        Phase.VOTE -> Phase.EXECUTION
        Phase.EXECUTION -> Phase.NIGHT
        Phase.END -> Phase.END
    }
}
