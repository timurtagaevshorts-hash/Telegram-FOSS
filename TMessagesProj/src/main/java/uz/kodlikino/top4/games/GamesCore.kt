package uz.kodlikino.top4.games

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.telegram.messenger.UserConfig
import uz.kodlikino.top4.utils.YuzGramAdsAuth

/**
 * Chat ichidagi o‘yinlar uchun umumiy qatlam.
 *
 * Transport — Firestore (RTDB bu ilovada yo‘q). Identitet — `tg_<telegramId>`:
 * `mintTelegramCustomToken` orqali olingan Firebase sessiyasi shu uid'ni beradi,
 * ya'ni har bir Telegram akkauntiga bitta barqaror o‘yin identiteti to‘g‘ri keladi.
 *
 * Odamlar ro‘yxati Firebase'da saqlanadi; avatar sifatida ism bosh harflari +
 * uid'dan olinadigan barqaror rang ishlatiladi (rasm yuklash shart emas, har
 * qurilmada bir xil ko‘rinadi).
 */
object GamesCore {

    const val COLLECTION = "yuz_games"

    const val KIND_CHECKERS = "checkers"
    const val KIND_MAFIA = "mafia"

    const val STATUS_INVITE = "invite"
    const val STATUS_ACTIVE = "active"
    const val STATUS_FINISHED = "finished"
    const val STATUS_CANCELLED = "cancelled"

    val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    fun rooms(): CollectionReference = db.collection(COLLECTION)

    data class Player(
        val uid: String,
        val tgId: Long,
        val name: String,
        val seat: Int = 0
    )

    fun uidOf(account: Int): String {
        val id = UserConfig.getInstance(account).clientUserId
        return if (id > 0L) "tg_$id" else ""
    }

    fun telegramId(account: Int): Long = UserConfig.getInstance(account).clientUserId

    /** Firebase sessiyasi (`tg_<id>`) — tarmoq xatosida [IllegalStateException]. */
    suspend fun signIn(account: Int): String = YuzGramAdsAuth.ensureSignedIn(account)

    /**
     * Sessiya allaqachon ochiqmi (yangi kirishni talab qilmaydi). Fon
     * o‘quvlari (masalan, taklif siyosati) uchun — u yerda akkaunt raqami
     * yo‘q va bloklovchi kirish keraksiz.
     */
    fun hasSession(): Boolean {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        return !uid.isNullOrBlank()
    }

    fun me(account: Int, seat: Int = 0): Player {
        val cfg = UserConfig.getInstance(account)
        val user = cfg.currentUser
        val name = listOfNotNull(user?.first_name, user?.last_name)
            .joinToString(" ")
            .trim()
            .ifBlank { user?.username ?: "" }
            .ifBlank { "O‘yinchi" }
        return Player(uidOf(account), cfg.clientUserId, name.take(40), seat)
    }

    fun playerOf(uid: String, name: String, seat: Int = 0): Player {
        val id = uid.removePrefix("tg_").toLongOrNull() ?: 0L
        return Player(uid, id, name.take(40).ifBlank { "O‘yinchi" }, seat)
    }

    fun parsePlayer(raw: Any?): Player? {
        val map = raw as? Map<*, *> ?: return null
        val uid = map["uid"]?.toString()?.trim().orEmpty()
        if (uid.isBlank()) return null
        val tgId = (map["tgId"] as? Number)?.toLong()
            ?: uid.removePrefix("tg_").toLongOrNull()
            ?: 0L
        return Player(
            uid = uid,
            tgId = tgId,
            name = map["name"]?.toString().orEmpty().take(40).ifBlank { "O‘yinchi" },
            seat = (map["seat"] as? Number)?.toInt() ?: 0
        )
    }

    fun playerMap(player: Player): Map<String, Any> = mapOf(
        "uid" to player.uid,
        "tgId" to player.tgId,
        "name" to player.name,
        "seat" to player.seat
    )

    // ------------------------------------------------------------- reactions

    /** Doska ustida ko‘rsatiladigan reaksiya (2 soniya). */
    data class Reaction(
        val uid: String,
        val name: String,
        val emoji: String,
        val at: Long
    )

    /** O‘yin ichidagi reaksiya tanlovi. */
    val REACTIONS = listOf("👍", "😂", "😮", "🤔", "🔥", "👏")

    fun parseReaction(raw: Any?): Reaction? {
        val map = raw as? Map<*, *> ?: return null
        val emoji = map["emoji"]?.toString().orEmpty()
        if (emoji.isBlank()) return null
        return Reaction(
            uid = map["uid"]?.toString().orEmpty(),
            name = map["name"]?.toString().orEmpty().take(40),
            emoji = emoji.take(8),
            at = (map["at"] as? Number)?.toLong() ?: 0L
        )
    }

    fun reactionMap(player: Player, emoji: String, at: Long): Map<String, Any> = mapOf(
        "uid" to player.uid,
        "name" to player.name,
        "emoji" to emoji,
        "at" to at
    )

    fun parsePlayers(raw: Any?): List<Player> {
        val list = raw as? List<*> ?: return emptyList()
        val out = ArrayList<Player>(list.size)
        for (item in list) parsePlayer(item)?.let { out.add(it) }
        return out
    }

    fun listenRoom(roomId: String, onUpdate: (Map<String, Any?>?) -> Unit): ListenerRegistration {
        return rooms().document(roomId.trim()).addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) onUpdate(null)
            else onUpdate(snap.data.orEmpty())
        }
    }

    // ------------------------------------------------------------- avatars

    private val AVATAR_COLORS = intArrayOf(
        0xFF2AABEE.toInt(), // brand action
        0xFF5B6CF9.toInt(), // brand decor
        0xFF38BDF8.toInt(),
        0xFF34D399.toInt(),
        0xFFF59E0B.toInt(),
        0xFFF472B6.toInt(),
        0xFFA78BFA.toInt(),
        0xFF22D3EE.toInt()
    )

    /** Barqaror (uid bo‘yicha) avatar rangi. */
    fun colorFor(uid: String): Int {
        if (uid.isBlank()) return AVATAR_COLORS[0]
        var hash = 0
        for (ch in uid) hash = hash * 31 + ch.code
        val index = (hash xor (hash ushr 16)) % AVATAR_COLORS.size
        return AVATAR_COLORS[if (index < 0) index + AVATAR_COLORS.size else index]
    }

    fun initials(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        if (parts.isEmpty()) return "?"
        if (parts.size == 1) {
            val single = parts[0]
            return single.substring(0, minOf(2, single.length)).uppercase()
        }
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).uppercase()
    }

    // -------------------------------------------------------------- helpers

    fun now(): Long = System.currentTimeMillis()

    fun longOf(map: Map<String, Any?>?, key: String, fallback: Long = 0L): Long =
        (map?.get(key) as? Number)?.toLong() ?: fallback

    fun intOf(map: Map<String, Any?>?, key: String, fallback: Int = 0): Int =
        (map?.get(key) as? Number)?.toInt() ?: fallback

    fun boolOf(map: Map<String, Any?>?, key: String, fallback: Boolean = false): Boolean =
        (map?.get(key) as? Boolean) ?: fallback

    fun stringOf(map: Map<String, Any?>?, key: String): String =
        map?.get(key)?.toString().orEmpty()

    fun stringsOf(map: Map<String, Any?>?, key: String): List<String> {
        val list = map?.get(key) as? List<*> ?: return emptyList()
        val out = ArrayList<String>(list.size)
        for (item in list) {
            val value = item?.toString()?.trim().orEmpty()
            if (value.isNotEmpty()) out.add(value)
        }
        return out
    }
}
