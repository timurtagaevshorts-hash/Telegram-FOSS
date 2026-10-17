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
 */
object YuzAiEngine {

    data class Peer(val dialogId: Long, val name: String, val username: String?)

    enum class Section { REELS, ELONLAR, KINOLAR, TAKSI, PROFIL, CHATS }

    sealed class Intent {
        data class SendText(val peer: Peer, val text: String) : Intent()
        data class NeedBody(val peer: Peer) : Intent()
        data class NotFound(val name: String) : Intent()
        data class OpenChat(val peer: Peer) : Intent()
        data class Goto(val section: Section) : Intent()
        data class Ask(val question: String) : Intent()
    }

    private val SEND_WORDS = listOf("yubor", "yubordim", "jo'nat", "jonat", "yoz", "olibsana", "xabar")
    private val NAV_MAP = listOf(
        "reels" to Section.REELS,
        "reka" to Section.REELS,
        "videolar" to Section.REELS,
        "elon" to Section.ELONLAR,
        "kino" to Section.KINOLAR,
        "kinolar" to Section.KINOLAR,
        "taksi" to Section.TAKSI,
        "haydovchi" to Section.TAKSI,
        "profil" to Section.PROFIL,
        "sozlam" to Section.PROFIL,
        "chat" to Section.CHATS,
        "xabarlar" to Section.CHATS,
        "suhat" to Section.CHATS
    )

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
            result.add(Peer(id, name, username))
        }
        return result
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

        val hasSend = SEND_WORDS.any { w -> tokens.any { it.contains(w) } } ||
            normalized.contains("xabar yubor") || normalized.contains("msg")
        val openChat = tokens.any { it == "och" || it == "ochqol" || it.startsWith("ochib") }

        // 1) Aniq yuborish buyrug'i yoki chat ochish — nomni qidir.
        if (hasSend || openChat) {
            val match = findPeer(tokens, account)
            if (match != null) {
                if (openChat && !hasSend) return Intent.OpenChat(match.peer)
                val body = cleanBody(tokens, match.tokenIndex)
                return if (body.isBlank()) Intent.NeedBody(match.peer) else Intent.SendText(match.peer, body)
            }
            // "xabar yubor" deyildi, faqat nom topilmadi — nom ehtimolini tekshir
            val guessed = guessNameToken(tokens)
            if (guessed != null) return Intent.NotFound(guessed)
            if (hasSend) return Intent.Ask(raw)
        }

        // 2) Navigatsiya kalit so'zlari (reels/taksi/...) — nom bilan aralashmasa.
        val nav = findNav(tokens)
        if (nav != null && findPeer(tokens, account) == null) return Intent.Goto(nav)

        // 3) Bilinadigan nom: "sardor qalesan bugun kelasanmi" -> Sardor'ga yuborish.
        val smart = findPeer(tokens, account)
        if (smart != null) {
            val body = cleanBody(tokens, smart.tokenIndex)
            return if (body.isBlank()) Intent.NeedBody(smart.peer) else Intent.SendText(smart.peer, body)
        }

        // 4) Savol -> brain.
        return Intent.Ask(raw.trim())
    }

    private class PeerMatch(val peer: Peer, val tokenIndex: Int, val token: String)

    private fun findPeer(tokens: List<String>, account: Int): PeerMatch? {
        val list = peers(account)
        if (list.isEmpty()) return null
        var best: PeerMatch? = null
        for (i in tokens.indices) {
            val token = stripPunct(tokens[i])
            if (token.length < 3) continue
            if (SEND_WORDS.any { token.contains(it) }) continue
            if (NAV_MAP.any { token.startsWith(it.first) }) continue
            for (peer in list) {
                val nameToken = firstTokenLower(peer.name)
                if (nameToken.isNullOrBlank() || nameToken.length < 3) continue
                if (!matches(token, nameToken, peer.username)) continue
                val score = nameToken.length
                if (best == null || score > best!!.token.length) {
                    best = PeerMatch(peer, i, token)
                }
            }
        }
        return best
    }

    private fun matches(token: String, nameToken: String, username: String?): Boolean {
        if (token == nameToken) return true
        // Dativ/yo'nalish shakllari: "sardorga" ~ "sardor", "sardordan" ~ "sardor"
        if (token.length > nameToken.length && token.startsWith(nameToken)) return true
        if (nameToken.length > token.length && nameToken.startsWith(token) && token.length >= 4) return true
        if (!username.isNullOrBlank()) {
            val uname = username.lowercase(Locale.getDefault())
            if (token == uname || (token.length >= 4 && (token.startsWith(uname) || uname.startsWith(token)))) return true
        }
        return false
    }

    private fun guessNameToken(tokens: List<String>): String? {
        // "xyz ga xabar yubor" — nomga o'xshash token (Send/NAV so'zlari emas)
        for (token in tokens) {
            val t = stripPunct(token)
            if (t.length < 3) continue
            if (SEND_WORDS.any { t.contains(it) }) continue
            if (NAV_MAP.any { t.startsWith(it.first) }) continue
            if (t == "xabar" || t == "msg" || t == "deb" || t == "men" || t == "menga") continue
            return t
        }
        return null
    }

    private fun findNav(tokens: List<String>): Section? {
        for (token in tokens) {
            val t = stripPunct(token)
            for ((key, section) in NAV_MAP) {
                if (t.startsWith(key)) return section
            }
        }
        return null
    }

    private fun cleanBody(tokens: List<String>, nameIndex: Int): String {
        val parts = ArrayList<String>()
        for (i in tokens.indices) {
            if (i == nameIndex) continue
            parts.add(tokens[i])
        }
        var body = TextUtils.join(" ", parts).trim()
        // Old va qo'shimcha buyruq qoldiqlarini tozalash
        body = body.replace(Regex("^(menga\\s+|va\\s+)+"), "")
        body = body.replace(Regex("^(xabar|msg|habar)\\s+"), "")
        // Ortidagi wrapper'lar: "... deb yubor", "... deb ayt", "... desang"
        body = body.replace(Regex("\\s+(deb|des|dedi|demoqchiman|demoqchimancha)\\s+(yubor|yoz|ayt|des|qilib\\s+ber|aytib\\s+ber)\\s*$"), "")
        body = body.replace(Regex("\\s+(deb|des|dedi)\\s*$"), "")
        body = body.replace(Regex("\\s+(yubor|jo'nat|jonat|yozib\\s+yubor|aytib\\s+ber|qilib\\s+ber)\\s*$"), "")
        body = body.trim().trim('.', ',', '!', '?', ' ')
        // Qolgan buyruq so'zlari ham xabar matni bo'lib qolmasin
        if (SEND_WORDS.any { it == body.lowercase(Locale.getDefault()) }) return ""
        return body
    }

    private fun firstTokenLower(name: String): String? {
        val n = name.lowercase(Locale.getDefault()).trim()
        return n.split(Regex("\\s+")).firstOrNull { it.isNotBlank() }?.let { stripPunct(it) }
    }

    private fun stripPunct(token: String): String =
        token.replace(Regex("[^a-z0-9'\\u0430-\\u044f-]"), "")
}
