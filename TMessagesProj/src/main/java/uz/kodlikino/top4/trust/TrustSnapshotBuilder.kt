package uz.kodlikino.top4.trust

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.UserConfig
import org.telegram.messenger.UserObject

/**
 * Chatlar ro'yxatidan FAQAT metama'lumot yig'adi: sarlavha, tur, o'qilmagan
 * soni, oxirgi faollik vaqti. Xabar matni, media, telefon raqami — hech narsa
 * olinmaydi va saqlanmaydi.
 */
object TrustSnapshotBuilder {

    const val LIMIT = 200

    const val KIND_USER = 0
    const val KIND_GROUP = 1
    const val KIND_CHANNEL = 2

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

    /** Bir marta nashr qilish: ro'yxatni yig'ib, ruxsat egalari bilan yozadi. */
    suspend fun publish(account: Int): Boolean {
        val uid = meUid(account)
        if (uid.isBlank()) return false
        val readers = TrustStore.readersForMe(uid)
        if (readers.isEmpty()) return false
        // Dialoglar ro'yxati main thread'dagi keshdan o'qiladi.
        val chats = withContext(Dispatchers.Main) { build(account) }
        return TrustStore.publishSnapshot(uid, chats)
    }
}