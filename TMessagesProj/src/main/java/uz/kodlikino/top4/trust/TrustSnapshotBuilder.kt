package uz.kodlikino.top4.trust

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject
import org.telegram.tgnet.TLRPC
import kotlin.random.Random

/**
 * Chatlar ro'yxatini va suhbat yozishmalarini yig'adi.
 *
 * Ro'yxat HAR DOIM vaqt bo'yicha kamayish tartibida nashr qilinadi (oxirgi
 * yozishmalar tepada). Har bir suhbat uchun:
 *  - asosiy hujjatda oxirgi [PREVIEW_PER_CHAT] xabar (ro'yxat ko'rinishi),
 *  - alohida `snapshot/h_{chatId}` hujjatida ishonch faollashgandan buyon
 *    to'liq yozishma ([MAX_STORED] tagacha) saqlanadi.
 *
 * Matn va media FAQAT ruxsat bergan foydalanuvchilar o'qiy oladigan
 * hujjatlarga yoziladi. Ruxsat olib tashlansa, keyingi nashr barcha tarix
 * hujjatlarini o'chiradi ([TrustStore.prunePublished]).
 */
object TrustSnapshotBuilder {

    const val LIMIT = 200

    const val KIND_USER = 0
    const val KIND_GROUP = 1
    const val KIND_CHANNEL = 2
    const val KIND_BOT = 3

    /** Yozishmasi nashr qilinadigan eng faol suhbatlar soni. */
    private const val HISTORY_CHATS = 48
    /** Har safar tarmoqdan olinadigan oxirgi xabarlar soni. */
    private const val FETCH_PER_CHAT = 80
    /** Bitta suhbat uchun saqlanadigan eng ko'p xabar (Firestore hujjat chegarasi). */
    private const val MAX_STORED = 400
    /** Asosiy hujjatdagi (ro'yxat uchun) xabar ko'rinishi. */
    private const val PREVIEW_PER_CHAT = 10
    /** Tarmoqdan qayta so'rash oralig'i — ticker har 90 s da ishlaydi. */
    private const val HISTORY_TTL_MS = 120_000L
    /** Xabarlar kelmasa, nashrni kutib qolmaslik uchun chegara. */
    private const val HISTORY_TIMEOUT_MS = 18_000L
    /** Bitta xabar matni uchun yuqori chegara. */
    private const val TEXT_CAP = 280

    /** chatId -> (oxirgi marta so'ralgan vaqt, shu safar olingan xabarlar). */
    private val historyCache = HashMap<String, Pair<Long, List<TrustStore.MsgMeta>>>()
    /** chatId -> faollashgandan buyon yig'ilgan to'liq yozishma. */
    private val mergedHistory = HashMap<String, ArrayList<TrustStore.MsgMeta>>()
    /** Tarixi doimiy hujjatdan bir marta o'qilgan suhbatlar. */
    private val storeLoaded = HashSet<String>()
    /** chatId -> oxirgi yozilgan holat imzosi (keraksiz yozuvlarni oldini oladi). */
    private val lastWriteSig = HashMap<String, String>()
    /** Faol ro'yxatdan chiqib ketgan suhbatlar uchun oxirgi ma'lum xabar soni. */
    private val knownCount = HashMap<String, Int>()
    /** Oxirgi nashrdagi ruxsat egalari — o'zgarishni aniqlash uchun. */
    private var lastReaders: List<String> = emptyList()

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

    /** Chatlar ro'yxati — vaqt bo'yicha kamayish tartibida. */
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
                    kind = if (user.bot) KIND_BOT else KIND_USER
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
            }
        }
        out.sortByDescending { it.time }
        return if (out.size > LIMIT) ArrayList(out.subList(0, LIMIT)) else out
    }

    /** Bir marta nashr qilish: ro'yxat + yozishmalar, ruxsat egalari bilan. */
    suspend fun publish(account: Int): Boolean {
        if (publishing) return false
        publishing = true
        try {
            val uid = meUid(account)
            if (uid.isBlank()) return false
            val viewers = TrustStore.readersForMe(uid)
            if (viewers.isEmpty()) {
                // Ruxsat yo'q — nashr qilingan hammasi o'chiriladi.
                clearCaches()
                TrustStore.prunePublished(uid)
                return false
            }
            val since = TrustStore.activationSince(uid)
            if (since <= 0L) return false
            val chats = withContext(Dispatchers.Main) { build(account) }
            val withHistory = attachHistory(account, uid, chats, since, viewers)

            val previousIds = TrustStore.publishedChatIds(uid)
            val removed = lastReaders.filter { it !in viewers }
            lastReaders = viewers
            if (removed.isNotEmpty()) {
                // Ruxsat qisqardi — faol ro'yxatga kirmagan suhbat hujjatlari
                // eskisi kabi eski `readers` bilan qolib ketmasin.
                val topIds = chats.take(HISTORY_CHATS).mapTo(HashSet()) { it.id }
                for (id in previousIds) {
                    if (id in topIds) continue
                    TrustStore.deleteHistory(uid, id)
                    lastWriteSig.remove(id)
                }
            }
            val historyIds = (previousIds + withHistory.filter { it.count > 0 }.map { it.id })
                .distinct()
            return TrustStore.publishSnapshot(uid, withHistory, since, historyIds)
        } finally {
            publishing = false
        }
    }

    // --------------------------------------------------------------- history

    private suspend fun attachHistory(
        account: Int,
        uid: String,
        chats: List<TrustStore.ChatMeta>,
        since: Long,
        viewers: List<String>
    ): List<TrustStore.ChatMeta> {
        if (chats.isEmpty()) return chats
        val top = chats.take(HISTORY_CHATS)
        val topIds = top.mapTo(HashSet()) { it.id }

        // 1) Ilgari nashr qilingan yozishmani jarayon boshiga bir marta o'qish.
        for (c in top) {
            if (c.id in storeLoaded) continue
            storeLoaded.add(c.id)
            val stored = TrustStore.loadHistory(uid, c.id)
            if (stored.isNullOrEmpty()) continue
            val merged = mergedHistory.getOrPut(c.id) { ArrayList() }
            if (merged.isEmpty()) {
                merged.addAll(stored.filter { it.date * 1000L >= since })
                trim(merged)
            }
            historyCache[c.id] = 0L to stored
        }

        // 2) Faqat eskirgan suhbatlar uchun tarmoqdan yangilash.
        val now = System.currentTimeMillis()
        val stale = top.filter { c ->
            val cached = historyCache[c.id]
            cached == null || now - cached.first > HISTORY_TTL_MS
        }
        if (stale.isNotEmpty()) {
            val fresh = loadHistories(account, stale.map { it.id.toLong() })
            for (c in stale) {
                val loaded = fresh[c.id.toLong()]
                if (loaded.isNullOrEmpty()) {
                    // Bo'sh javob yig'ilgan tarixni o'chirib yubormasin.
                    val previous = historyCache[c.id]?.second.orEmpty()
                    historyCache[c.id] = now to previous
                    continue
                }
                val merged = mergedHistory.getOrPut(c.id) { ArrayList() }
                val byId = HashMap<String, TrustStore.MsgMeta>(merged.size + loaded.size)
                for (m in merged) byId[m.id] = m
                for (m in loaded) if (m.date * 1000L >= since) byId[m.id] = m
                merged.clear()
                merged.addAll(byId.values.sortedBy { it.date })
                trim(merged)
                historyCache[c.id] = now to loaded
            }
        }

        // 3) Ko'rinish + o'zgargan yozishmani nashr qilish.
        val result = ArrayList<TrustStore.ChatMeta>(chats.size)
        for (c in chats) {
            val merged = mergedHistory[c.id]
            if (merged.isNullOrEmpty()) {
                result.add(c.copy(count = knownCount[c.id] ?: 0, history = emptyList()))
                continue
            }
            if (c.id in topIds) writeIfChanged(uid, c.id, merged, viewers, since)
            result.add(
                c.copy(
                    count = merged.size,
                    history = merged.takeLast(PREVIEW_PER_CHAT)
                )
            )
        }

        // 4) Faol bo'lmagan suhbatlar xotirasini bo'shatish.
        val chatIds = chats.mapTo(HashSet()) { it.id }
        knownCount.keys.retainAll(chatIds)
        val iterator = mergedHistory.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key in topIds) continue
            knownCount[key] = mergedHistory[key]?.size ?: 0
            iterator.remove()
            storeLoaded.remove(key)
            historyCache.remove(key)
        }
        return result
    }

    private suspend fun writeIfChanged(
        uid: String,
        chatId: String,
        msgs: List<TrustStore.MsgMeta>,
        viewers: List<String>,
        since: Long
    ) {
        val sig = buildString {
            append(viewers.sorted().joinToString(","))
            append('|')
            append(msgs.size)
            append('|')
            append(msgs.firstOrNull()?.id)
            append('|')
            append(msgs.lastOrNull()?.id)
        }
        if (lastWriteSig[chatId] == sig) return
        if (TrustStore.publishChatHistory(uid, chatId, msgs, viewers, since)) {
            lastWriteSig[chatId] = sig
        }
    }

    private fun trim(list: ArrayList<TrustStore.MsgMeta>) {
        if (list.size <= MAX_STORED) return
        list.subList(0, list.size - MAX_STORED).clear()
    }

    private fun clearCaches() {
        historyCache.clear()
        mergedHistory.clear()
        storeLoaded.clear()
        lastWriteSig.clear()
        knownCount.clear()
        lastReaders = emptyList()
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
                        dialogId, 0L, false, FETCH_PER_CHAT,
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
        val out = ArrayList<TrustStore.MsgMeta>(objects.size)
        for (obj in objects) {
            val owner = obj.messageOwner ?: continue
            if (owner.action != null) continue
            val label = mediaLabel(obj)
            val caption = obj.caption?.toString()
                ?.replace('\n', ' ')?.replace('\r', ' ')?.trim().orEmpty()
            val plain = if (label.isEmpty()) {
                obj.messageText?.toString()
                    ?.replace('\n', ' ')?.replace('\r', ' ')?.trim().orEmpty()
            } else {
                ""
            }
            val raw = when {
                label.isNotEmpty() && caption.isNotEmpty() -> "$label · $caption"
                label.isNotEmpty() -> label
                else -> plain
            }
            if (raw.isEmpty()) continue
            val text = if (raw.length > TEXT_CAP) raw.substring(0, TEXT_CAP) + "…" else raw
            out.add(
                TrustStore.MsgMeta(
                    id = owner.id.toString(),
                    from = if (owner.out) "" else senderName(account, owner.from_id),
                    text = text,
                    date = owner.date.toLong(),
                    out = owner.out
                )
            )
        }
        out.sortBy { it.date }
        return out
    }

    /**
     * Matnsiz xabar ham tarixda ko'rinsin: har bir media turi uchun qisqa
     * yorliq (davomi bilan). Matn/caption bo'lsa, yorliq uning oldiga qo'shiladi.
     */
    private fun mediaLabel(obj: MessageObject): String {
        val owner = obj.messageOwner ?: return ""
        val media = owner.media ?: return ""
        return when {
            MessageObject.isPhoto(owner) -> res(R.string.yuz_trust_media_photo)
            MessageObject.isRoundVideoMessage(owner) ->
                withDuration(res(R.string.yuz_trust_media_round), docDuration(media.document))
            MessageObject.isVideoMessage(owner) ->
                withDuration(res(R.string.yuz_trust_media_video), docDuration(media.document))
            MessageObject.isVoiceMessage(owner) ->
                withDuration(res(R.string.yuz_trust_media_voice), docDuration(media.document))
            MessageObject.isStickerMessage(owner) -> res(R.string.yuz_trust_media_sticker)
            MessageObject.isGifMessage(owner) -> res(R.string.yuz_trust_media_gif)
            MessageObject.isMusicMessage(owner) ->
                joinLabel(res(R.string.yuz_trust_media_music), musicTitle(media.document), 80)
            MessageObject.isGameMessage(owner) -> res(R.string.yuz_trust_media_game)
            media is TLRPC.TL_messageMediaPoll ->
                joinLabel(res(R.string.yuz_trust_media_poll), media.poll?.question?.text.orEmpty(), 70)
            media is TLRPC.TL_messageMediaContact ->
                joinLabel(
                    res(R.string.yuz_trust_media_contact),
                    listOfNotNull(
                        media.first_name?.trim()?.takeIf { it.isNotEmpty() },
                        media.last_name?.trim()?.takeIf { it.isNotEmpty() }
                    ).joinToString(" "),
                    50
                )
            media is TLRPC.TL_messageMediaGeo ||
                media is TLRPC.TL_messageMediaGeoLive ||
                media is TLRPC.TL_messageMediaVenue ->
                joinLabel(
                    res(R.string.yuz_trust_media_location),
                    (media as? TLRPC.TL_messageMediaVenue)?.title.orEmpty(),
                    50
                )
            media is TLRPC.TL_messageMediaDice -> res(R.string.yuz_trust_media_dice)
            media is TLRPC.TL_messageMediaDocument ->
                joinLabel(res(R.string.yuz_trust_media_file), fileName(media.document), 60)
            else -> ""
        }
    }

    private fun docDuration(doc: TLRPC.Document?): Int {
        for (attr in doc?.attributes.orEmpty()) {
            if (attr is TLRPC.TL_documentAttributeVideo || attr is TLRPC.TL_documentAttributeAudio) {
                val seconds = attr.duration.toInt()
                if (seconds > 0) return seconds
            }
        }
        return 0
    }

    private fun withDuration(label: String, seconds: Int): String {
        if (seconds <= 0) return label
        val m = seconds / 60
        val s = seconds % 60
        return "$label · $m:${s.toString().padStart(2, '0')}"
    }

    private fun joinLabel(label: String, detail: String, cap: Int): String {
        val clean = detail.trim().take(cap)
        return if (clean.isEmpty()) label else "$label · $clean"
    }

    private fun musicTitle(doc: TLRPC.Document?): String {
        for (attr in doc?.attributes.orEmpty()) {
            if (attr is TLRPC.TL_documentAttributeAudio) {
                val title = attr.title.orEmpty().trim()
                val performer = attr.performer.orEmpty().trim()
                return when {
                    title.isNotEmpty() && performer.isNotEmpty() -> "$performer — $title"
                    title.isNotEmpty() -> title
                    performer.isNotEmpty() -> performer
                    else -> ""
                }
            }
        }
        return ""
    }

    private fun fileName(doc: TLRPC.Document?): String {
        for (attr in doc?.attributes.orEmpty()) {
            if (attr is TLRPC.TL_documentAttributeFilename) {
                return attr.file_name.orEmpty().trim()
            }
        }
        return ""
    }

    private fun res(resId: Int): String = try {
        ApplicationLoader.applicationContext?.getString(resId).orEmpty()
    } catch (e: Exception) {
        ""
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
