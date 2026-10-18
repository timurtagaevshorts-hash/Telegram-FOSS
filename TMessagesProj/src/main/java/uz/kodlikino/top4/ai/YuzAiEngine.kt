package uz.kodlikino.top4.ai

import android.text.TextUtils
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import java.util.Locale

/**
 * AI yordamchining qoida-based "beyni" (Gemini'siz):
 * ovoz matnini tahlil qilib chat topadi, xabar yuboradi, bo'limlarga o'tadi,
 * yoki savolni YuzgramAiBrain'ga uzatadi.
 *
 * #79: nom moslash kengaytirildi — ismning BARCHA tokenlari (familiya ham),
 * apostrof/kirill normallashtirish, va "xabar yubor" buyrug'i topilmasa
 * miyaga (Ask) ketmaydi — to'g'ridan-to'g'ri "topilmadi" deyadi.
 */
object YuzAiEngine {

    data class Peer(val dialogId: Long, val name: String, val username: String?, val userId: Long = 0L)

    enum class Section { REELS, ELONLAR, KINOLAR, PROFIL, CHATS, MUSIQA }

    sealed class Intent {
        data class SendText(val peer: Peer, val text: String) : Intent()
        data class NeedBody(val peer: Peer) : Intent()
        data class NotFound(val name: String) : Intent()
        data class OpenChat(val peer: Peer) : Intent()
        data class Call(val peer: Peer, val video: Boolean) : Intent()
        data class Goto(val section: Section) : Intent()
        data class Ask(val question: String) : Intent()
    }

    private val SEND_WORDS_RAW = listOf("yubor", "yubordim", "jo'nat", "jonat", "yoz", "olibsana", "xabar", "xabarlang")
    private val CALL_WORDS_RAW = listOf("qo'ng'iroq", "qong'iroq", "qo'ngiroq", "qongiroq", "qoingiroq", "telefon", "zvonit", "qo'ng'iroqni")
    private val NAV_WORDS_RAW = listOf(
        "reels" to Section.REELS,
        "reka" to Section.REELS,
        "videolar" to Section.REELS,
        "elon" to Section.ELONLAR,
        "kino" to Section.KINOLAR,
        "kinolar" to Section.KINOLAR,
        "qo'shiq" to Section.MUSIQA,
        "qoshiq" to Section.MUSIQA,
        "musiqa" to Section.MUSIQA,
        "ashula" to Section.MUSIQA,
        "music" to Section.MUSIQA,
        "profil" to Section.PROFIL,
        "sozlam" to Section.PROFIL,
        "chat" to Section.CHATS,
        "xabarlar" to Section.CHATS,
        "suhat" to Section.CHATS
    )
    // Savol so'zlari: "xabar yuborish qanday?" kabi yordam so'rovlari
    // buyruq emas — miyaga (Ask) ketadi.
    private val QUESTION_WORDS = listOf(
        "qanday", "qanaqa", "qandiq", "nima", "nega", "necha", "qayerda", "qayerdan",
        "qachon", "kerak", "mumkin", "yordam", "tushuntir", "qilsam", "qilsam"
    )
    private val EXTRA_STOP = setOf("men", "menga", "deb", "des", "qil", "video", "msg", "ayt", "ber")

    private val SEND_WORDS: List<String> = SEND_WORDS_RAW.map { norm(it) }
    private val CALL_WORDS: List<String> = CALL_WORDS_RAW.map { norm(it) }
    private val NAV_MAP: List<Pair<String, Section>> = NAV_WORDS_RAW.map { norm(it.first) to it.second }
    private val QUESTION_SET: Set<String> = QUESTION_WORDS.map { norm(it) }.toSet()

    /** Mavjud dialoglardan nomlarni yig'adi (shifrlangan va papka dialoglari tashlanadi). */
    fun peers(account: Int): List<Peer> {
        val controller = MessagesController.getInstance(account)
        val result = ArrayList<Peer>()
        val dialogs = controller.dialogs_dict
        if (dialogs == null) return result
        for (i in 0 until dialogs.size()) {
            val dialog = dialogs.valueAt(i) ?: continue
            val peer = dialog.peer ?: continue
            val id = DialogObject.getPeerDialogId(peer)
            if (id == 0L || DialogObject.isEncryptedDialog(id) || DialogObject.isFolderDialogId(id)) continue
            val name = peerName(controller, peer) ?: continue
            if (name.isBlank()) continue
            val username = peerNameLower(controller, peer, true)
            result.add(Peer(id, name, username, peer.user_id))
        }
        return result
    }

    /** Kichik harf + apostrofsiz + kirill->lotin: "Sardor", "Сардор", "sardor'" bir xil bo'ladi. */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase(Locale.getDefault())) {
            val t = cyrToLat(ch)
            for (c in t) if (c in 'a'..'z' || c.isDigit()) sb.append(c)
        }
        return sb.toString()
    }

    private fun cyrToLat(ch: Char): String = when (ch) {
        'а' -> "a"; 'б' -> "b"; 'в' -> "v"; 'г' -> "g"; 'д' -> "d"; 'е' -> "e"; 'ё' -> "o"
        'ж' -> "j"; 'з' -> "z"; 'и' -> "i"; 'й' -> "y"; 'к' -> "k"; 'л' -> "l"; 'м' -> "m"
        'н' -> "n"; 'о' -> "o"; 'п' -> "p"; 'р' -> "r"; 'с' -> "s"; 'т' -> "t"; 'у' -> "u"
        'ф' -> "f"; 'х' -> "x"; 'ц' -> "ts"; 'ч' -> "ch"; 'ш' -> "sh"; 'щ' -> "sh"
        'ъ' -> ""; 'ы' -> "i"; 'ь' -> ""; 'э' -> "e"; 'ю' -> "yu"; 'я' -> "ya"
        'ғ' -> "g"; 'қ' -> "q"; 'ҳ' -> "h"; 'ў' -> "o"
        else -> ch.toString()
    }

    private fun peerName(controller: MessagesController, peer: TLRPC.Peer): String? {
        if (peer.user_id != 0L) {
            val user = controller.getUser(peer.user_id) ?: return null
            if (UserObject.isReplyUser(user)) return null
            return UserObject.getUserName(user)
        } else if (peer.chat_id != 0L) {
            return controller.getChat(peer.chat_id)?.title
        } else if (peer.channel_id != 0L) {
            return controller.getChat(-peer.channel_id)?.title
        }
        return null
    }

    private fun peerNameLower(controller: MessagesController, peer: TLRPC.Peer, usernameOnly: Boolean): String? {
        return try {
            if (peer.user_id != 0L) {
                val u = controller.getUser(peer.user_id) ?: return null
                if (usernameOnly) u.username?.takeIf { it.isNotBlank() } else u.username
            } else {
                val chat = if (peer.chat_id != 0L) controller.getChat(peer.chat_id)
                else controller.getChat(-peer.channel_id)
                if (usernameOnly) chat?.username?.takeIf { it.isNotBlank() } else chat?.username
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Asosiy tahlil. Tartib: aniq "yubor" buyrug'i -> chat "och" -> navigatsiya ->
     * bilinadigan nomli aqlli yuborish -> savol (brain).
     */
    fun parse(raw: String, account: Int): Intent {
        val normalized = raw.lowercase(Locale.getDefault())
            .replace(",", " ")
            .replace("’", "'")
            .trim()
        if (normalized.isBlank()) return Intent.Ask(raw)
        val tokens = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return Intent.Ask(raw)
        val normed = tokens.map { norm(it) }

        val hasSend = SEND_WORDS.any { w -> normed.any { it.contains(w) } } ||
            normed.any { it == "msg" }
        val openChat = normed.any { it == "och" || it == "ochqol" || it.startsWith("ochib") }
        val hasCall = CALL_WORDS.any { w -> normed.any { it.contains(w) } }

        // 0) Qo'ng'iroq: "Islomga telefon qil" -> Call (video so'zi bo'lsa video).
        if (hasCall) {
            val match = findPeer(tokens, normed, account)
            if (match != null) return Intent.Call(match.peer, normed.any { it.contains("video") })
            val guessed = guessNameToken(normed)
            if (guessed != null) return Intent.NotFound(guessed)
        }

        // 1) Aniq yuborish buyrug'i yoki chat ochish — nomni qidir.
        if (hasSend || openChat) {
            val match = findPeer(tokens, normed, account)
            if (match != null) {
                if (openChat && !hasSend) return Intent.OpenChat(match.peer)
                val body = cleanBody(tokens, match.nameIndices)
                return if (body.isBlank()) Intent.NeedBody(match.peer) else Intent.SendText(match.peer, body)
            }
            // Nom topilmadi: "xyz ga xabar yubor" -> "xyz ismli chat topilmadi".
            val guessed = guessNameToken(normed)
            if (guessed != null) return Intent.NotFound(guessed)
            // Yordam so'rovi ("xabar yuborish qanday?") — miyaga; buyruq bo'lsa — "topilmadi".
            if (hasSend) {
                return if (isQuestion(normed)) Intent.Ask(raw)
                else Intent.NotFound(residual(tokens, normed))
            }
        }

        // 2) Navigatsiya kalit so'zlari (reels/taksi/...) — nom bilan aralashmasa.
        val nav = findNav(normed)
        if (nav != null && findPeer(tokens, normed, account) == null) return Intent.Goto(nav)

        // 3) Bilinadigan nom: "sardor qalesan bugun kelasanmi" -> Sardor'ga yuborish.
        val smart = findPeer(tokens, normed, account)
        if (smart != null) {
            val body = cleanBody(tokens, smart.nameIndices)
            return if (body.isBlank()) Intent.NeedBody(smart.peer) else Intent.SendText(smart.peer, body)
        }

        // 4) Savol -> brain.
        return Intent.Ask(raw.trim())
    }

    private class PeerMatch(val peer: Peer, val nameIndices: List<Int>)

    /**
     * Nomni BARCHA tokenlar bo'yicha izlaydi (ism, familiya, username),
     * eng uzun/aniq moslik g'olib bo'ladi.
     */
    private fun findPeer(tokens: List<String>, normed: List<String>, account: Int): PeerMatch? {
        val list = peers(account)
        if (list.isEmpty()) return null
        var bestPeer: Peer? = null
        var bestIndex = -1
        var bestScore = 0
        for (i in normed.indices) {
            val token = normed[i]
            if (token.length < 3) continue
            if (isStopToken(token)) continue
            for (peer in list) {
                val score = scoreToken(token, peer)
                if (score > bestScore) {
                    bestScore = score
                    bestPeer = peer
                    bestIndex = i
                }
            }
        }
        val peer = bestPeer ?: return null
        // Nomning barcha qisplari ("Aziz Karimov") xabar matnidan tushirib qolsin.
        val nameTs = nameTokens(peer.name)
        val uname = norm(peer.username ?: "")
        val indices = ArrayList<Int>()
        for (i in normed.indices) {
            val token = normed[i]
            if (token.length < 3) continue
            var hit = false
            for (nt in nameTs) {
                if (pairScore(token, nt) > 0) { hit = true; break }
            }
            if (!hit && uname.length >= 4 && pairScore(token, uname) > 0) hit = true
            if (hit) indices.add(i)
        }
        if (bestIndex >= 0 && bestIndex !in indices) indices.add(bestIndex)
        return PeerMatch(peer, indices)
    }

    private fun scoreToken(token: String, peer: Peer): Int {
        var best = 0
        for (nt in nameTokens(peer.name)) {
            val s = pairScore(token, nt)
            if (s > best) best = s
        }
        val uname = norm(peer.username ?: "")
        if (uname.length >= 4) {
            val s = pairScore(token, uname)
            if (s > best) best = s
        }
        return best
    }

    private fun pairScore(token: String, nameToken: String): Int = when {
        token == nameToken -> 1000 + nameToken.length
        // Dativ/yo'nalish shakllari: "sardorga" ~ "sardor", "sardordan" ~ "sardor"
        token.length > nameToken.length && token.startsWith(nameToken) -> nameToken.length
        nameToken.length > token.length && nameToken.startsWith(token) && token.length >= 4 -> token.length
        else -> 0
    }

    private fun isStopToken(token: String): Boolean =
        SEND_WORDS.any { token.contains(it) } ||
            CALL_WORDS.any { token.contains(it) } ||
            NAV_MAP.any { token.startsWith(it.first) }

    private fun guessNameToken(normed: List<String>): String? {
        // "xyz ga xabar yubor" — nomga o'xshash token (buyruq/savol so'zlari emas)
        for (token in normed) {
            if (token.length < 3) continue
            if (isStopToken(token)) continue
            if (token in EXTRA_STOP || token in QUESTION_SET) continue
            return token
        }
        return null
    }

    private fun isQuestion(normed: List<String>): Boolean = normed.any { it in QUESTION_SET }

    private fun residual(tokens: List<String>, normed: List<String>): String {
        val parts = ArrayList<String>()
        for (i in tokens.indices) {
            val t = normed.getOrElse(i) { "" }
            if (t.length >= 3 && !isStopToken(t)) parts.add(tokens[i])
        }
        return if (parts.isEmpty()) TextUtils.join(" ", tokens).trim() else TextUtils.join(" ", parts).trim()
    }

    private fun findNav(normed: List<String>): Section? {
        for (token in normed) {
            for ((key, section) in NAV_MAP) {
                if (key.isNotBlank() && token.startsWith(key)) return section
            }
        }
        return null
    }

    private fun cleanBody(tokens: List<String>, nameIndices: List<Int>): String {
        val parts = ArrayList<String>()
        for (i in tokens.indices) {
            if (i in nameIndices) continue
            parts.add(tokens[i])
        }
        var body = TextUtils.join(" ", parts).trim()
        // Old va qo'shimcha buyruq qoldiqlarini tozalash
        body = body.replace(Regex("^(menga\\s+|va\\s+)+"), "")
        body = body.replace(Regex("^(xabar|msg|habar)\\s+"), "")
        body = body.replace(Regex("^(ga|kga|nga|gha|qa)\\s+"), "")
        // Ortidagi wrapper'lar: "... deb yubor", "... deb ayt", "... desang"
        body = body.replace(Regex("\\s+(deb|des|dedi|demoqchiman|demoqchimancha)\\s+(yubor|yoz|ayt|des|qilib\\s+ber|aytib\\s+ber)\\s*$"), "")
        body = body.replace(Regex("\\s+(deb|des|dedi)\\s*$"), "")
        body = body.replace(Regex("\\s+(yubor|jo'nat|jonat|yozib\\s+yubor|aytib\\s+ber|qilib\\s+ber)\\s*$"), "")
        body = body.trim().trim('.', ',', '!', '?', ' ')
        // Qolgan buyruq so'zlari ham xabar matni bo'lib qolmasin
        val normBody = norm(body)
        if (SEND_WORDS.any { it == normBody }) return ""
        return body
    }

    private fun nameTokens(name: String): List<String> =
        name.split(Regex("\\s+")).map { norm(it) }.filter { it.length >= 3 }
}
