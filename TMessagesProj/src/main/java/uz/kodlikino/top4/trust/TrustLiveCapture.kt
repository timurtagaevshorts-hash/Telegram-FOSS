package uz.kodlikino.top4.trust

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC

/**
 * Ishonch faol bo'lganda xabarlarni darhol ushlaydi.
 *
 *  - Yangi kelgan/yuborilgan xabar shu zahoti arxivga yoziladi va o'sha
 *    suhbatni nashr qilish so'raladi (1.5 s debounce) — 90 s ticker kutilmaydi.
 *  - Xabar o'chirilsa, arxivdan olib tashlanmaydi, `deleted = true` qilib
 *    belgilanadi — ko'ruvchi o'chirilgan yozishmani ham ko'radi.
 *
 * Arxiv faqat qurilmada (SharedPreferences) saqlanadi; tashqariga faqat
 * [TrustStore.publishChatHistory] orqali, ruxsat egalari uchun chiqadi.
 */
object TrustLiveCapture {

    private const val TAG = "TrustLiveCapture"
    private const val MAX_PER_CHAT = 500
    private const val PUBLISH_DEBOUNCE_MS = 1_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs: SharedPreferences? by lazy {
        try {
            ApplicationLoader.applicationContext
                ?.getSharedPreferences("yuz_trust_live", android.content.Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.w(TAG, "prefs: ${e.message}")
            null
        }
    }

    private val installed = HashSet<Int>()
    private val archives = HashMap<Int, HashMap<String, LinkedHashMap<String, TrustStore.MsgMeta>>>()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingChats = HashMap<Int, MutableSet<String>>()
    private val flush = Runnable {
        val batch = synchronized(pendingChats) {
            val copy = ArrayList<Pair<Int, List<String>>>(pendingChats.size)
            for ((account, ids) in pendingChats) copy.add(account to ids.toList())
            pendingChats.clear()
            copy
        }
        if (batch.isEmpty()) return@Runnable
        scope.launch {
            for ((account, ids) in batch) {
                for (chatId in ids) {
                    try {
                        TrustSnapshotBuilder.publishChat(account, chatId)
                    } catch (e: Exception) {
                        Log.w(TAG, "publishChat($chatId): ${e.message}")
                    }
                }
                try {
                    TrustSnapshotBuilder.publish(account)
                } catch (e: Exception) {
                    Log.w(TAG, "publish: ${e.message}")
                }
            }
        }
    }

    // --------------------------------------------------------------- lifecycle

    fun installAll() {
        for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
            try {
                if (UserConfig.getInstance(account).isClientActivated) install(account)
            } catch (e: Exception) {
                Log.w(TAG, "installAll($account): ${e.message}")
            }
        }
    }

    @Synchronized
    fun install(account: Int) {
        if (installed.contains(account)) return
        installed.add(account)
        val delegate = NotificationCenter.NotificationCenterDelegate { id, _, args ->
            try {
                when (id) {
                    NotificationCenter.didReceiveNewMessages -> onNewMessages(account, args)
                    NotificationCenter.messagesDeleted -> onDeleted(account, args)
                    NotificationCenter.messageReceivedByServer -> onMessageConfirmed(account, args)
                }
            } catch (e: Exception) {
                Log.w(TAG, "event($id): ${e.message}")
            }
        }
        NotificationCenter.getInstance(account).let { nc ->
            nc.addObserver(delegate, NotificationCenter.didReceiveNewMessages)
            nc.addObserver(delegate, NotificationCenter.messagesDeleted)
            nc.addObserver(delegate, NotificationCenter.messageReceivedByServer)
        }
    }

    /** Ruxsat holati — nashr qiluvchi tomondan o'rnatiladi. */
    fun setSharing(account: Int, sharing: Boolean, since: Long) {
        val p = prefs ?: return
        try {
            if (sharing) {
                p.edit().putBoolean("enabled_$account", true).apply()
                if (since > 0L) p.edit().putLong("since_$account", since).apply()
            } else {
                p.edit()
                    .putBoolean("enabled_$account", false)
                    .putLong("since_$account", 0L)
                    .apply()
                clearAll(account)
            }
        } catch (e: Exception) {
            Log.w(TAG, "setSharing: ${e.message}")
        }
    }

    /** Ro'yxatdan chiqib ketgan suhbatlar arxivini tozalaydi. */
    fun retainChats(account: Int, ids: Set<String>) {
        val p = prefs ?: return
        try {
            val prefix = "c_${account}_"
            val stale = p.all.keys
                .filter { it.startsWith(prefix) && it.removePrefix(prefix) !in ids }
                .map { it.removePrefix(prefix) }
            if (stale.isEmpty()) return
            val edit = p.edit()
            for (chatId in stale) edit.remove("$prefix$chatId")
            edit.apply()
            synchronized(archives) {
                val map = archives[account] ?: return
                for (chatId in stale) map.remove(chatId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "retainChats: ${e.message}")
        }
    }

    fun clearAll(account: Int) {
        val p = prefs ?: return
        try {
            val prefix = "c_${account}_"
            val edit = p.edit()
            for (key in p.all.keys) if (key.startsWith(prefix)) edit.remove(key)
            edit.apply()
            synchronized(archives) { archives.remove(account) }
        } catch (e: Exception) {
            Log.w(TAG, "clearAll: ${e.message}")
        }
    }

    /** Arxivdagi xabarlar (faollashtirilgandan buyon, o'chirilganlar ham). */
    fun archive(account: Int, chatId: String): List<TrustStore.MsgMeta> {
        val chat = chatMap(account, chatId)
        synchronized(chat) { return chat.values.toList() }
    }

    // ------------------------------------------------------------------ events

    private fun enabled(account: Int): Boolean =
        prefs?.getBoolean("enabled_$account", false) == true

    private fun since(account: Int): Long =
        prefs?.getLong("since_$account", 0L) ?: 0L

    private fun onNewMessages(account: Int, args: Array<Any>) {
        if (!enabled(account)) return
        val dialogId = args.getOrNull(0) as? Long ?: return
        @Suppress("UNCHECKED_CAST")
        val list = args.getOrNull(1) as? ArrayList<MessageObject> ?: return
        capture(account, dialogId, list)
    }

    /** O'z yuborilgan xabari serverda tasdiqlanganda. */
    private fun onMessageConfirmed(account: Int, args: Array<Any>) {
        if (!enabled(account)) return
        val dialogId = args.getOrNull(3) as? Long ?: return
        // args[2] — SendMessagesHelper'da TLRPC.Message, secret chat'da
        // MessageObject, MessagesController yo'lida null bo'ladi.
        val obj = try {
            when (val payload = args.getOrNull(2)) {
                is MessageObject -> payload
                is TLRPC.Message -> MessageObject(account, payload, null as MessageObject?, false, false)
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "confirm: ${e.message}")
            null
        } ?: return
        capture(account, dialogId, listOf(obj))
    }

    private fun capture(account: Int, dialogId: Long, list: List<MessageObject>) {
        val chatId = dialogId.toString()
        val minDate = since(account)
        val metas = ArrayList<TrustStore.MsgMeta>(list.size)
        for (obj in list) {
            val meta = TrustSnapshotBuilder.metaFor(account, obj) ?: continue
            if (meta.date * 1000L < minDate) continue
            metas.add(meta)
        }
        if (metas.isEmpty()) return
        append(account, chatId, metas)
        requestPublish(account, chatId)
    }

    private fun append(account: Int, chatId: String, items: List<TrustStore.MsgMeta>) {
        val chat = chatMap(account, chatId)
        synchronized(chat) {
            for (m in items) {
                val old = chat[m.id]
                chat[m.id] = if (old?.deleted == true) m.copy(deleted = true) else m
            }
            while (chat.size > MAX_PER_CHAT) {
                val oldest = chat.values.minByOrNull { it.date } ?: break
                chat.remove(oldest.id)
            }
            save(account, chatId, chat.values.toList())
        }
    }

    private fun onDeleted(account: Int, args: Array<Any>) {
        if (!enabled(account)) return
        @Suppress("UNCHECKED_CAST")
        val ids = (args.getOrNull(0) as? ArrayList<Int>)?.toHashSet() ?: return
        if (ids.isEmpty()) return
        val channelId = (args.getOrNull(1) as? Number)?.toLong() ?: 0L

        val targets = ArrayList<String>(2)
        if (channelId != 0L) {
            val dialogId = if (channelId > 0L) -channelId else channelId
            targets.add(dialogId.toString())
        } else {
            // Shaxsiy chatda qaysi suhbat ekani aytilmaydi: xabar id'si faqat
            // bitta arxivda uchrasa, o'sha suhbat deb belgilanadi.
            preloadArchives(account)
            val map = archiveMap(account)
            var unique: String? = null
            var ambiguous = false
            synchronized(map) {
                for ((chatId, chat) in map) {
                    if (chat.keys.any { key -> key.toIntOrNull()?.let { it in ids } == true }) {
                        if (unique == null) unique = chatId else ambiguous = true
                    }
                }
            }
            if (unique != null && !ambiguous) targets.add(unique!!)
        }

        for (chatId in targets) {
            val chat = chatMap(account, chatId)
            var changed = false
            synchronized(chat) {
                for ((key, m) in chat) {
                    if (key.toIntOrNull()?.let { it in ids } == true && !m.deleted) {
                        chat[key] = m.copy(deleted = true)
                        changed = true
                    }
                }
                if (changed) save(account, chatId, chat.values.toList())
            }
            if (changed) requestPublish(account, chatId)
        }
    }

    private fun requestPublish(account: Int, chatId: String) {
        synchronized(pendingChats) {
            pendingChats.getOrPut(account) { HashSet() }.add(chatId)
        }
        handler.removeCallbacks(flush)
        handler.postDelayed(flush, PUBLISH_DEBOUNCE_MS)
    }

    // ---------------------------------------------------------------- storage

    private fun archiveMap(account: Int): HashMap<String, LinkedHashMap<String, TrustStore.MsgMeta>> =
        synchronized(archives) {
            archives.getOrPut(account) { HashMap() }
        }

    private fun chatMap(account: Int, chatId: String): LinkedHashMap<String, TrustStore.MsgMeta> {
        val map = archiveMap(account)
        synchronized(map) {
            map[chatId]?.let { return it }
            val loaded = loadChat(account, chatId)
            map[chatId] = loaded
            return loaded
        }
    }

    /** Barcha suhbat arxivlarini xotiraga ko'taradi (o'chirishni aniqlash uchun). */
    private fun preloadArchives(account: Int) {
        val p = prefs ?: return
        val prefix = "c_${account}_"
        for (key in p.all.keys) {
            if (!key.startsWith(prefix)) continue
            val chatId = key.removePrefix(prefix)
            chatMap(account, chatId)
        }
    }

    private fun loadChat(account: Int, chatId: String): LinkedHashMap<String, TrustStore.MsgMeta> {
        val out = LinkedHashMap<String, TrustStore.MsgMeta>()
        val p = prefs ?: return out
        val raw = p.getString("c_${account}_$chatId", null) ?: return out
        try {
            val rows = JSONArray(raw)
            for (i in 0 until rows.length()) {
                val o = rows.optJSONObject(i) ?: continue
                val text = o.optString("x")
                if (text.isBlank()) continue
                val meta = TrustStore.MsgMeta(
                    id = o.optString("m"),
                    from = o.optString("s"),
                    text = text,
                    date = o.optLong("d"),
                    out = o.optBoolean("o"),
                    deleted = o.optBoolean("del")
                )
                out[meta.id] = meta
            }
        } catch (e: Exception) {
            Log.w(TAG, "loadChat($chatId): ${e.message}")
        }
        return out
    }

    private fun save(account: Int, chatId: String, msgs: List<TrustStore.MsgMeta>) {
        val p = prefs ?: return
        try {
            val rows = JSONArray()
            for (m in msgs) {
                rows.put(
                    JSONObject().apply {
                        put("m", m.id)
                        put("s", m.from)
                        put("x", m.text)
                        put("d", m.date)
                        put("o", m.out)
                        put("del", m.deleted)
                    }
                )
            }
            p.edit().putString("c_${account}_$chatId", rows.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "save($chatId): ${e.message}")
        }
    }
}
