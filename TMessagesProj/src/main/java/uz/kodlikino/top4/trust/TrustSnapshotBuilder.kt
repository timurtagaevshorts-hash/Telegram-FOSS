package uz.kodlikino.top4.trust

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import kotlin.random.Random

/**
 * Chatlar ro'yxatini va eng so'nggi suhbatlarning oxirgi xabarlarini yig'adi.
 *
 * Ro'yxat har doim metama'lumot bilan cheklanadi; xabar matni esa FAQAT
 * [HISTORY_CHATS] ta eng faol suhbat uchun va FAQAT ruxsat bergan
 * foydalanuvchilar o'qiy oladigan snapshot hujjatiga yoziladi. Ruxsat
 * olib tashlansa, keyingi nashr hujjatni bo'sh `readers` bilan yozadi va
 * qoidalar uni hech kimga o'qitmaydi.
 */
object TrustSnapshotBuilder {

    const val LIMIT = 200

    const val KIND_USER = 0
    const val KIND_GROUP = 1
    const val KIND_CHANNEL = 2

    /** Oxirgi xabarlari nashr qilinadigan suhbatlar soni (hujjat hajmi chegarasi). */
    private const val HISTORY_CHATS = 32
    /** Bitta suhbatdan olinadigan oxirgi xabarlar soni. */
    private const val HISTORY_PER_CHAT = 24
    /** Tarmoqdan qayta so'rash oralig'i — ticker har 90 s da ishlaydi. */
    private const val HISTORY_TTL_MS = 240_000L
    /** Xabarlar kelmasa, nashrni kutib qolmaslik uchun chegara. */
    private const val HISTORY_TIMEOUT_MS = 12_000L
    /** Bitta xabar matni uchun yuqori chegara (Firestore 1 MiB limiti). */
    private const val TEXT_CAP = 280

    /** chatId -> (yozilgan vaqt, xabarlar). Faqat oxirgi nashr holatini saqlaydi. */
    private val historyCache = HashMap<String, Pair<Long, List<TrustStore.MsgMeta>>>()

    @Volatile
    private var publishing = false

    fun meUid(account: Int): String {
        val id = UserConfig.getInstance(account).clientUserId
        return if (id > 0L) "tg_$id" else ""
    }

    fun meName(account: Int): String {
        val user = UserConfig.getInstance(account).currentUser ?: return "YuzGram"
        return listOfNotNull(user.first_name, user.last_name)
            .joinToString(" ").trim()
            .ifBlank { user.username ?: "" }
            .ifBlank { "YuzGram" }
    }

    fun build(account: Int): List<TrustStore.ChatMeta> {
        val mc = MessagesController.getInstance(account)
        val out = ArrayList<TrustStore.ChatMeta>(LIMIT)
        val seen = HashSet<Long>()
        for (folder in intArrayOf(0, 1)) {
            for (dialog in mc.getDialogs(folder)) {
                if (!seen.add(dialog.id)) continue
                if (DialogObject.isFolderDialogId(dialog.id)) continue

                val title: String
                val kind: Int
                if (dialog.id > 0L) {
                    val user = mc.getUser(dialog.id) ?: continue
                    if (user.self || user.deleted) continue
                    title = UserObject.getUserName(user)
                    kind = KIND_USER
                } else {
                    val chat = mc.getChat(-dialog.id) ?: continue
                    if (ChatObject.isNotInChat(chat)) continue
                    title = chat.title.orEmpty()
                    kind = if (ChatObject.isChannel(chat)) {
                        if (chat.megagroup) KIND_GROUP else KIND_CHANNEL
                    } else {
                        KIND_GROUP
                    }
                }
                if (title.isBlank()) continue

                out.add(
                    TrustStore.ChatMeta(
                        id = dialog.id.toString(),
                        title = title.take(80),
                        kind = kind,
                        unread = dialog.unread_count,
                        time = dialog.last_message_date.toLong(),
                        pinned = dialog.pinned
                    )
                )
                if (out.size >= LIMIT) return out
            }
        }
        return out
    }

    /** Bir marta nashr qilish: ro'yxat + oxirgi xabarlar, ruxsat egalari bilan. */
    suspend fun publish(account: Int): Boolean {
        if (publishing) return false
        publishing = true
        try {
            val uid = meUid(account)
            if (uid.isBlank()) return false
            val readers = TrustStore.readersForMe(uid)
            if (readers.isEmpty()) return false
            // Dialoglar ro'yxati main thread'dagi keshdan o'qiladi.
            val chats = withContext(Dispatchers.Main) { build(account) }
            val withHistory = attachHistory(account, chats)
            return TrustStore.publishSnapshot(uid, withHistory)
        } finally {
            publishing = false
        }
    }

    // --------------------------------------------------------------- history

    private suspend fun attachHistory(
        account: Int,
        chats: List<TrustStore.ChatMeta>
    ): List<TrustStore.ChatMeta> {
        if (chats.isEmpty()) return chats
        val top = chats.take(HISTORY_CHATS)
        val now = System.currentTimeMillis()
        val stale = top.filter { c ->
            val cached = historyCache[c.id]
            cached == null || cached.second.isEmpty() || now - cached.first > HISTORY_TTL_MS
        }
        if (stale.isNotEmpty()) {
            val fresh = loadHistories(account, stale.map { it.id.toLong() })
            for (c in stale) {
                val loaded = fresh[c.id.toLong()]
                // Bo'sh javob eski (ishlaydigan) tarixni o'chirib yubormasin.
                if (loaded.isNullOrEmpty()) {
                    val previous = historyCache[c.id]
                    if (previous != null && previous.second.isNotEmpty()) continue
                    historyCache[c.id] = now to emptyList()
                } else {
                    historyCache[c.id] = now to loaded
                }
            }
        }
        return chats.map { c ->
            val history = historyCache[c.id]?.second
            if (history.isNullOrEmpty()) c else c.copy(history = history)
        }
    }

    /**
     * Bir nechta suhbat oxirgi xabarlarini bitta so'rov to'plami bilan yuklaydi.
     * Natija `messagesDidLoad` orqali keladi; classGuid shu chaqiruvni ajratadi.
     */
    private suspend fun loadHistories(
        account: Int,
        ids: List<Long>
    ): Map<Long, List<TrustStore.MsgMeta>> = withContext(Dispatchers.Main) {
        val result = HashMap<Long, List<TrustStore.MsgMeta>>()
        val pending = HashSet<Long>(ids)
        val done = CompletableDeferred<Unit>()
        val guid = Random.nextInt(1, Int.MAX_VALUE)
        // MUHIM: `messagesDidLoad` akkaunt bo'yicha NotificationCenter'ga post
        // qilinadi (MessagesController.getNotificationCenter), global'ga emas.
        val nc = NotificationCenter.getInstance(account)

        val observer = NotificationCenter.NotificationCenterDelegate { id, _, args ->
            if (id != NotificationCenter.messagesDidLoad) return@NotificationCenterDelegate
            if (args.size < 11) return@NotificationCenterDelegate
            if ((args[10] as? Int) != guid) return@NotificationCenterDelegate
            val dialogId = args[0] as? Long ?: return@NotificationCenterDelegate
            if (dialogId !in pending) return@NotificationCenterDelegate
            @Suppress("UNCHECKED_CAST")
            val objects = args[2] as? ArrayList<MessageObject>
            if (objects.isNullOrEmpty() && args[3] == true) {
                // Bo'sh kesh javobi — tarmoqdan haqiqiy javobni kutamiz.
                return@NotificationCenterDelegate
            }
            pending.remove(dialogId)
            result[dialogId] = toHistory(account, objects.orEmpty())
            if (pending.isEmpty() && !done.isCompleted) done.complete(Unit)
        }

        nc.addObserver(observer, NotificationCenter.messagesDidLoad)
        try {
            val mc = MessagesController.getInstance(account)
            var loadIndex = 0
            for (dialogId in ids) {
                try {
                    mc.loadMessages(
                        dialogId, 0L, false, HISTORY_PER_CHAT,
                        0, 0, false, 0, guid,
                        MessagesController.LOAD_BACKWARD, 0, 0,
                        0L, 0, loadIndex++, false
                    )
                } catch (e: Exception) {
                    pending.remove(dialogId)
                }
            }
            if (pending.isNotEmpty()) {
                withTimeoutOrNull(HISTORY_TIMEOUT_MS) { done.await() }
            }
        } finally {
            nc.removeObserver(observer, NotificationCenter.messagesDidLoad)
        }
        result
    }

    private fun toHistory(
        account: Int,
        objects: List<MessageObject>
    ): List<TrustStore.MsgMeta> {
        val out = ArrayList<TrustStore.MsgMeta>(HISTORY_PER_CHAT)
        for (obj in objects) {
            val owner = obj.messageOwner ?: continue
            if (owner.action != null) continue
            val raw = obj.messageText?.toString()?.trim().orEmpty()
            if (raw.isEmpty()) continue
            val text = if (raw.length > TEXT_CAP) raw.substring(0, TEXT_CAP) + "…" else raw
            out.add(
                TrustStore.MsgMeta(
                    id = owner.id.toString(),
                    from = if (owner.out) "" else senderName(account, owner.from_id),
                    text = text.replace('\n', ' ').replace('\r', ' '),
                    date = owner.date.toLong(),
                    out = owner.out
                )
            )
        }
        out.sortBy { it.date }
        return out
    }

    private fun senderName(account: Int, peer: TLRPC.Peer?): String {
        if (peer == null) return ""
        val mc = MessagesController.getInstance(account)
        return when (peer) {
            is TLRPC.TL_peerUser -> {
                val user = mc.getUser(peer.user_id) ?: return ""
                UserObject.getUserName(user)
            }
            is TLRPC.TL_peerChannel -> mc.getChat(peer.channel_id)?.title.orEmpty()
            is TLRPC.TL_peerChat -> mc.getChat(peer.chat_id)?.title.orEmpty()
            else -> ""
        }
    }
}
