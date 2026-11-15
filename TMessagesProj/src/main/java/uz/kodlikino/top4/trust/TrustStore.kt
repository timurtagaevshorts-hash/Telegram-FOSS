package uz.kodlikino.top4.trust

import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader

/**
 * Ishonchli kuzatuv (trust) — Firestore transporti.
 *
 * Model (faqat rozilikka asoslangan, bir tomonlama):
 *  - `yuz_trust/{approver}/inbox/{requester}` — so'rov. Uni FAQAT requester
 *    yaratadi (qoidalar `requesterUid == auth.uid` talab qiladi), holatni esa
 *    approver o'zgartiradi. `shareMine` — approver o'z ro'yxatini ko'rsatishga
 *    ruxsat berganini bildiradi.
 *  - `yuz_trust/{requester}/sent/{approver}` — requester tomonidagi nusxa
 *    (ro'yxat uchun). Holat approver hujjatidan tinglanib yangilanadi.
 *  - `yuz_trust/{owner}/snapshot/main` — owner chatlar ro'yxati va har bir
 *    suhbatdagi oxirgi 10 xabar; `readers` massivida faqat owner ruxsat
 *    bergan odamlar (va owner o'zi) bo'ladi, o'qish qoidasi shu massivga
 *    tayanadi.
 *  - `yuz_trust/{owner}/snapshot/h_{chatId}` — bitta suhbatning ishonch
 *    faollashtirilgandan buyon to'liq yozishmasi; `readers` o'sha massiv.
 *
 * Snapshot faqat owner rozilik bergan odamlar uchun yoziladi (readers bo'sh
 * bo'lsa nashr umuman bo'lmaydi) va har doim faqat-o'qish oynasida ko'riladi:
 * hech kim chatga yoza olmaydi, o'chira olmaydi yoki o'zgartira olmaydi.
 */
object TrustStore {

    private const val TAG = "TrustStore"
    const val COLLECTION = "yuz_trust"

    const val STATUS_PENDING = "pending"
    const val STATUS_ACCEPTED = "accepted"
    const val STATUS_DECLINED = "declined"
    const val STATUS_CANCELLED = "cancelled"
    const val STATUS_REVOKED = "revoked"

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    /** Kiruvchi so'rov (kimdir mening ro'yxatimni ko'rmoqchi). */
    data class Incoming(
        val peerUid: String,
        val peerName: String,
        val status: String,
        val shareMine: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
        val locked: Boolean = false,
        val unlockRequested: Boolean = false
    ) {
        val isPending: Boolean get() = status == STATUS_PENDING
        val isActive: Boolean get() = status == STATUS_ACCEPTED
    }

    /** Chiquvchi so'rov (men birovning ro'yxatini ko'rmoqchiman). */
    data class Outgoing(
        val peerUid: String,
        val peerName: String,
        val status: String,
        val createdAt: Long,
        val locked: Boolean = false,
        val unlockRequested: Boolean = false
    ) {
        val isPending: Boolean get() = status == STATUS_PENDING
        val isActive: Boolean get() = status == STATUS_ACCEPTED
    }

    /** Bitta suhbatdagi bitta xabar — faqat o'qish uchun ko'rsatiladi. */
    data class MsgMeta(
        val id: String,
        val from: String,
        val text: String,
        val date: Long,
        val out: Boolean,
        /** O'chirilgan, lekin arxivda saqlanib qolgan xabar. */
        val deleted: Boolean = false
    )

    data class ChatMeta(
        val id: String,
        val title: String,
        val kind: Int,
        val unread: Int,
        val time: Long,
        val pinned: Boolean,
        /** Ishonch faollashtirilgandan buyon saqlangan xabarlar soni. */
        val count: Int = 0,
        val history: List<MsgMeta> = emptyList()
    )

    data class Snapshot(
        val ownerUid: String,
        val updatedAt: Long,
        /** Ishonch faollashgan payt (ms) — shu vaqtdan keyingi xabarlar ko'rinadi. */
        val since: Long,
        val chats: List<ChatMeta>
    )

    private fun root(uid: String) = db.collection(COLLECTION).document(uid)
    fun inbox(uid: String) = root(uid).collection("inbox")
    fun sent(uid: String) = root(uid).collection("sent")
    private fun snapshotCol(uid: String) = root(uid).collection("snapshot")
    private fun snapshotDoc(uid: String) = snapshotCol(uid).document("main")
    fun historyDoc(uid: String, chatId: String) = snapshotCol(uid).document("h_$chatId")

    // ------------------------------------------------------------- listening

    fun listenInbox(uid: String, onChange: (List<Incoming>) -> Unit): ListenerRegistration =
        inbox(uid).addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "inbox: ${err.message}")
                return@addSnapshotListener
            }
            val list = snap?.documents.orEmpty().mapNotNull { doc ->
                try {
                    Incoming(
                        peerUid = doc.id,
                        peerName = doc.getString("requesterName").orEmpty(),
                        status = doc.getString("status") ?: STATUS_PENDING,
                        shareMine = doc.getBoolean("shareMine") ?: false,
                        createdAt = doc.getLong("createdAt") ?: 0L,
                        updatedAt = doc.getLong("updatedAt") ?: 0L,
                        locked = doc.getBoolean("locked") ?: false,
                        unlockRequested = doc.getBoolean("unlockRequested") ?: false
                    )
                } catch (e: Exception) {
                    null
                }
            }
            onChange(list.sortedByDescending { it.createdAt })
        }

    fun listenSent(uid: String, onChange: (List<Outgoing>) -> Unit): ListenerRegistration =
        sent(uid).addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "sent: ${err.message}")
                return@addSnapshotListener
            }
            val list = snap?.documents.orEmpty().mapNotNull { doc ->
                try {
                    Outgoing(
                        peerUid = doc.id,
                        peerName = doc.getString("approverName").orEmpty(),
                        status = doc.getString("status") ?: STATUS_PENDING,
                        createdAt = doc.getLong("createdAt") ?: 0L,
                        locked = doc.getBoolean("locked") ?: false,
                        unlockRequested = doc.getBoolean("unlockRequested") ?: false
                    )
                } catch (e: Exception) {
                    null
                }
            }
            onChange(list.sortedByDescending { it.createdAt })
        }

    /**
     * Approver hujjatidagi holatni kuzatadi. Requester o'zining `sent`
     * nusxasini shu yerga tayanib yangilaydi.
     */
    fun watchApproval(approverUid: String, requesterUid: String,
                      onChange: (String, Boolean) -> Unit): ListenerRegistration =
        inbox(approverUid).document(requesterUid)
            .addSnapshotListener { snap, err ->
                if (err != null) return@addSnapshotListener
                val status = snap?.getString("status") ?: return@addSnapshotListener
                onChange(status, snap.getBoolean("shareMine") ?: false)
            }

    // ---------------------------------------------------------------- actions

    suspend fun sendRequest(
        meUid: String,
        myName: String,
        peerUid: String,
        peerName: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (meUid.isBlank() || peerUid.isBlank() || meUid == peerUid) return@withContext false
        val now = System.currentTimeMillis()
        try {
            val inboxRef = inbox(peerUid).document(meUid)
            val existing = inboxRef.get().await()
            val status = if (existing.exists()) existing.getString("status").orEmpty() else ""
            if (existing.exists()) {
                if (status.isNotEmpty() && status != STATUS_PENDING &&
                    status != STATUS_CANCELLED && status != STATUS_DECLINED &&
                    status != STATUS_REVOKED
                ) {
                    return@withContext false
                }
                // Qayta so'rov: qoidalar requester'ga faqat shu maydonlarni
                // yangilashga ruxsat beradi (createdAt tegilmaydi).
                inboxRef.set(
                    hashMapOf(
                        "status" to STATUS_PENDING,
                        "locked" to false,
                        "lockedAt" to 0L,
                        "unlockRequested" to false,
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                ).await()
            } else {
                inboxRef.set(
                    hashMapOf(
                        "requesterUid" to meUid,
                        "approverUid" to peerUid,
                        "requesterName" to myName.take(64),
                        "status" to STATUS_PENDING,
                        "shareMine" to false,
                        "locked" to false,
                        "createdAt" to now,
                        "updatedAt" to now
                    )
                ).await()
            }
            sent(meUid).document(peerUid).set(
                hashMapOf(
                    "approverUid" to peerUid,
                    "approverName" to peerName.take(64),
                    "status" to STATUS_PENDING,
                    "locked" to false,
                    "createdAt" to now,
                    "updatedAt" to now
                )
            ).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendRequest: ${e.message}")
            false
        }
    }

    suspend fun cancelRequest(meUid: String, peerUid: String) = withContext(Dispatchers.IO) {
        try {
            inbox(peerUid).document(meUid).set(
                hashMapOf("status" to STATUS_CANCELLED, "updatedAt" to System.currentTimeMillis()),
                SetOptions.merge()
            ).await()
            sent(meUid).document(peerUid).set(
                hashMapOf("status" to STATUS_CANCELLED, "updatedAt" to System.currentTimeMillis()),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "cancelRequest: ${e.message}")
        }
    }

    suspend fun accept(meUid: String, requesterUid: String, share: Boolean = true) =
        withContext(Dispatchers.IO) {
            try {
                inbox(meUid).document(requesterUid).set(
                    hashMapOf(
                        "status" to STATUS_ACCEPTED,
                        "shareMine" to share,
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                ).await()
            } catch (e: Exception) {
                Log.w(TAG, "accept: ${e.message}")
            }
        }

    suspend fun decline(meUid: String, requesterUid: String) = withContext(Dispatchers.IO) {
        try {
            inbox(meUid).document(requesterUid).set(
                hashMapOf(
                    "status" to STATUS_DECLINED,
                    "shareMine" to false,
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "decline: ${e.message}")
        }
    }

    suspend fun setShare(meUid: String, requesterUid: String, share: Boolean) =
        withContext(Dispatchers.IO) {
            try {
                inbox(meUid).document(requesterUid).set(
                    hashMapOf(
                        "shareMine" to share,
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                ).await()
            } catch (e: Exception) {
                Log.w(TAG, "setShare: ${e.message}")
            }
        }

    /** Approver ishonchni bekor qiladi (o'z ro'yxatini yopadi). */
    suspend fun revokeIncoming(meUid: String, requesterUid: String) = withContext(Dispatchers.IO) {
        try {
            inbox(meUid).document(requesterUid).set(
                hashMapOf(
                    "status" to STATUS_REVOKED,
                    "shareMine" to false,
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "revokeIncoming: ${e.message}")
        }
    }

    /** Requester o'z so'rovini bekor qiladi. */
    suspend fun removeSent(meUid: String, peerUid: String) = withContext(Dispatchers.IO) {
        try {
            sent(meUid).document(peerUid).delete().await()
            inbox(peerUid).document(meUid).set(
                hashMapOf("status" to STATUS_CANCELLED, "updatedAt" to System.currentTimeMillis()),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "removeSent: ${e.message}")
        }
    }

    /** Approver qarorini requester nusxasiga yozadi (ro'yxat uchun). */
    suspend fun markSentStatus(meUid: String, peerUid: String, status: String) =
        withContext(Dispatchers.IO) {
            try {
                sent(meUid).document(peerUid).set(
                    hashMapOf("status" to status, "updatedAt" to System.currentTimeMillis()),
                    SetOptions.merge()
                ).await()
            } catch (e: Exception) {
                Log.w(TAG, "markSentStatus: ${e.message}")
            }
        }

    // ------------------------------------------------------------------ lock

    /**
     * Requester ulanishni mustahkamlaydi. Mustahkamlangan ulanishni approver
     * so'rovsiz bekor qila olmaydi — avval [requestUnlock] yuboriladi.
     */
    suspend fun setLocked(meUid: String, peerUid: String, locked: Boolean) =
        withContext(Dispatchers.IO) {
            if (meUid.isBlank() || peerUid.isBlank()) return@withContext
            val now = System.currentTimeMillis()
            try {
                inbox(peerUid).document(meUid).set(
                    hashMapOf("locked" to locked, "lockedAt" to now),
                    SetOptions.merge()
                ).await()
                sent(meUid).document(peerUid).set(
                    hashMapOf("locked" to locked, "lockedAt" to now),
                    SetOptions.merge()
                ).await()
            } catch (e: Exception) {
                Log.w(TAG, "setLocked: ${e.message}")
            }
        }

    /** Approver, mustahkamlangan ulanishni bekor qilishdan oldin ruxsat so'raydi. */
    suspend fun requestUnlock(meUid: String, requesterUid: String) =
        withContext(Dispatchers.IO) {
            try {
                inbox(meUid).document(requesterUid).set(
                    hashMapOf(
                        "unlockRequested" to true,
                        "updatedAt" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                ).await()
            } catch (e: Exception) {
                Log.w(TAG, "requestUnlock: ${e.message}")
            }
        }

    /** Requester ochish so'rovini hal qiladi: `allow` = qulf yechiladi. */
    suspend fun resolveUnlock(meUid: String, peerUid: String, allow: Boolean) =
        withContext(Dispatchers.IO) {
            if (meUid.isBlank() || peerUid.isBlank()) return@withContext
            try {
                inbox(peerUid).document(meUid).set(
                    hashMapOf(
                        "locked" to !allow,
                        "unlockRequested" to false,
                        "lockedAt" to System.currentTimeMillis()
                    ),
                    SetOptions.merge()
                ).await()
                sent(meUid).document(peerUid).set(
                    hashMapOf("locked" to !allow, "unlockRequested" to false),
                    SetOptions.merge()
                ).await()
            } catch (e: Exception) {
                Log.w(TAG, "resolveUnlock: ${e.message}")
            }
        }

    /** Chiquvchi so'rovning joriy holati (viewer'dagi qulf tugmasi uchun). */
    suspend fun fetchSent(meUid: String, peerUid: String): Outgoing? =
        withContext(Dispatchers.IO) {
            try {
                val doc = sent(meUid).document(peerUid).get().await()
                if (!doc.exists()) return@withContext null
                Outgoing(
                    peerUid = peerUid,
                    peerName = doc.getString("approverName").orEmpty(),
                    status = doc.getString("status") ?: STATUS_PENDING,
                    createdAt = doc.getLong("createdAt") ?: 0L,
                    locked = doc.getBoolean("locked") ?: false,
                    unlockRequested = doc.getBoolean("unlockRequested") ?: false
                )
            } catch (e: Exception) {
                Log.w(TAG, "fetchSent: ${e.message}")
                null
            }
        }

    // -------------------------------------------------------------- snapshot

    /** Menga ko'rish ruxsati bergan odamlar ro'yxati (o'z inbox'imdan). */
    suspend fun readersForMe(meUid: String): List<String> = withContext(Dispatchers.IO) {
        try {
            inbox(meUid).get().await().documents
                .filter {
                    it.getString("status") == STATUS_ACCEPTED &&
                        (it.getBoolean("shareMine") ?: false)
                }
                .map { it.id }
        } catch (e: Exception) {
            Log.w(TAG, "readersForMe: ${e.message}")
            emptyList()
        }
    }

    /**
     * Ishonch faollashgan payt: joriy rozilik berilgan so'rovlarning eng erta
     * `updatedAt` qiymati. 0 = aniqlab bo'lmadi (tarix nashr qilinmaydi).
     */
    suspend fun activationSince(meUid: String): Long = withContext(Dispatchers.IO) {
        try {
            inbox(meUid).get().await().documents
                .filter {
                    it.getString("status") == STATUS_ACCEPTED &&
                        (it.getBoolean("shareMine") ?: false)
                }
                .mapNotNull { it.getLong("updatedAt")?.takeIf { t -> t > 0L } }
                .minOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "activationSince: ${e.message}")
            0L
        }
    }

    /**
     * O'z chatlar ro'yxatimni e'lon qiladi. `readers` har doim qayta
     * hisoblanadi — ruxsat olib tashlansa, keyingi nashr uni o'chiradi.
     * Owner o'zi ham `readers`da bo'ladi: shu bilan u o'z hujjatlarini
     * (masalan bekor qilishda `hid` ro'yxatini) o'qiy oladi.
     */
    suspend fun publishSnapshot(
        meUid: String,
        chats: List<ChatMeta>,
        since: Long,
        historyIds: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        if (meUid.isBlank()) return@withContext false
        try {
            val viewers = readersForMe(meUid)
            val readers = (viewers + meUid).distinct()
            val hid = historyIds.distinct()
            val payload = hashMapOf<String, Any>(
                "uid" to meUid,
                "updatedAt" to System.currentTimeMillis(),
                "since" to since,
                "readers" to readers,
                "hid" to hid,
                "chats" to chats.map { c ->
                    val row = hashMapOf<String, Any>(
                        "id" to c.id,
                        "t" to c.title,
                        "k" to c.kind,
                        "u" to c.unread,
                        "d" to c.time,
                        "p" to c.pinned,
                        "c" to c.count
                    )
                    if (c.history.isNotEmpty()) {
                        row["h"] = c.history.map { m -> msgRow(m) }
                    }
                    row
                }
            )
            snapshotDoc(meUid).set(payload).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "publishSnapshot: ${e.message}")
            false
        }
    }

    /** Bitta suhbatning to'liq yozishmasi (`snapshot/h_{chatId}`). */
    suspend fun publishChatHistory(
        meUid: String,
        chatId: String,
        msgs: List<MsgMeta>,
        viewers: List<String>,
        since: Long
    ): Boolean = withContext(Dispatchers.IO) {
        if (meUid.isBlank() || chatId.isBlank()) return@withContext false
        try {
            val readers = (viewers + meUid).distinct()
            historyDoc(meUid, chatId).set(
                hashMapOf(
                    "uid" to meUid,
                    "chatId" to chatId,
                    "since" to since,
                    "updatedAt" to System.currentTimeMillis(),
                    "readers" to readers,
                    "count" to msgs.size,
                    "msgs" to msgs.map { m -> msgRow(m) }
                )
            ).await()
            true
        } catch (e: Exception) {
            Log.w(TAG, "publishChatHistory($chatId): ${e.message}")
            false
        }
    }

    /** Faqat ruxsat berilgan bo'lsa muvaffaqiyatli bo'ladi (qoida tekshiradi). */
    suspend fun loadSnapshot(ownerUid: String): Snapshot? = withContext(Dispatchers.IO) {
        try {
            val snap = snapshotDoc(ownerUid).get().await()
            if (!snap.exists()) return@withContext null
            val parsed = parseSnapshotDoc(ownerUid, snap)
            putCache(cacheKey(ownerUid), snapshotJson(parsed))
            parsed
        } catch (e: Exception) {
            Log.w(TAG, "loadSnapshot($ownerUid): ${e.message}")
            if (denied(e)) {
                // Ruxsat olib tashlangan — eski nusxa ham qolmasin.
                putCache(cacheKey(ownerUid), null)
                null
            } else {
                cachedSnapshot(ownerUid)
            }
        }
    }

    /** Ruxsat berilgan bo'lsa bitta suhbat yozishmasini o'qiydi. */
    suspend fun loadHistory(ownerUid: String, chatId: String): List<MsgMeta>? =
        withContext(Dispatchers.IO) {
            try {
                val snap = historyDoc(ownerUid, chatId).get().await()
                if (!snap.exists()) return@withContext null
                val msgs = parseMsgs(snap.get("msgs") as? List<*>)
                putCache(cacheKey(ownerUid, chatId), msgsJson(msgs))
                msgs
            } catch (e: Exception) {
                Log.w(TAG, "loadHistory($ownerUid/$chatId): ${e.message}")
                if (denied(e)) {
                    putCache(cacheKey(ownerUid, chatId), null)
                    null
                } else {
                    cachedHistory(ownerUid, chatId)
                }
            }
        }

    /**
     * Snapshot'ni jonli kuzatadi — yangi xabar nashr etilishi bilan ekran
     * o'zi yangilanadi. Ruxsat olib tashlansa, kesh tozalanib `null` keladi.
     */
    fun listenSnapshot(ownerUid: String, onChange: (Snapshot?) -> Unit): ListenerRegistration =
        snapshotDoc(ownerUid).addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "listenSnapshot: ${err.message}")
                if (denied(err)) putCache(cacheKey(ownerUid), null)
                onChange(null)
                return@addSnapshotListener
            }
            if (snap == null || !snap.exists()) return@addSnapshotListener
            val parsed = parseSnapshotDoc(ownerUid, snap)
            putCache(cacheKey(ownerUid), snapshotJson(parsed))
            onChange(parsed)
        }

    /** Bitta suhbat yozishmasini jonli kuzatadi. */
    fun listenHistory(
        ownerUid: String,
        chatId: String,
        onChange: (List<MsgMeta>?) -> Unit
    ): ListenerRegistration = historyDoc(ownerUid, chatId)
        .addSnapshotListener { snap, err ->
            if (err != null) {
                Log.w(TAG, "listenHistory: ${err.message}")
                if (denied(err)) putCache(cacheKey(ownerUid, chatId), null)
                onChange(null)
                return@addSnapshotListener
            }
            if (snap == null || !snap.exists()) return@addSnapshotListener
            val msgs = parseMsgs(snap.get("msgs") as? List<*>)
            putCache(cacheKey(ownerUid, chatId), msgsJson(msgs))
            onChange(msgs)
        }

    // ------------------------------------------------------------ offline cache

    private val cachePrefs: SharedPreferences? by lazy {
        try {
            ApplicationLoader.applicationContext
                ?.getSharedPreferences("yuz_trust_cache", android.content.Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
    }

    private fun cacheKey(ownerUid: String, chatId: String? = null): String =
        if (chatId == null) "snap_$ownerUid" else "h_${ownerUid}_$chatId"

    fun cachedSnapshot(ownerUid: String): Snapshot? = try {
        cachePrefs?.getString(cacheKey(ownerUid), null)
            ?.let { parseSnapshotJson(ownerUid, JSONObject(it)) }
    } catch (e: Exception) {
        null
    }

    fun cachedHistory(ownerUid: String, chatId: String): List<MsgMeta>? = try {
        cachePrefs?.getString(cacheKey(ownerUid, chatId), null)
            ?.let { parseMsgsJson(JSONArray(it)) }
    } catch (e: Exception) {
        null
    }

    /** Ruxsat bekor qilinganda ko'ruvchi tomondagi nusxalarni ham o'chiradi. */
    fun clearOwnerCache(ownerUid: String) {
        val prefs = cachePrefs ?: return
        try {
            val prefix = "h_${ownerUid}_"
            val edit = prefs.edit().remove(cacheKey(ownerUid))
            for (key in prefs.all.keys) {
                if (key.startsWith(prefix)) edit.remove(key)
            }
            edit.apply()
        } catch (e: Exception) {
            Log.w(TAG, "clearOwnerCache: ${e.message}")
        }
    }

    private fun putCache(key: String, value: String?) {
        val prefs = cachePrefs ?: return
        try {
            if (value == null) prefs.edit().remove(key).apply()
            else prefs.edit().putString(key, value).apply()
        } catch (e: Exception) {
            Log.w(TAG, "cache write: ${e.message}")
        }
    }

    private fun denied(e: Exception): Boolean =
        e is com.google.firebase.firestore.FirebaseFirestoreException &&
            e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED

    private fun parseSnapshotDoc(
        ownerUid: String,
        snap: com.google.firebase.firestore.DocumentSnapshot
    ): Snapshot {
        val rows = snap.get("chats") as? List<*> ?: emptyList<Any>()
        val chats = rows.mapNotNull { row ->
            val m = row as? Map<*, *> ?: return@mapNotNull null
            ChatMeta(
                id = m["id"] as? String ?: return@mapNotNull null,
                title = m["t"] as? String ?: "",
                kind = (m["k"] as? Number)?.toInt() ?: 0,
                unread = (m["u"] as? Number)?.toInt() ?: 0,
                time = (m["d"] as? Number)?.toLong() ?: 0L,
                pinned = m["p"] as? Boolean ?: false,
                count = (m["c"] as? Number)?.toInt() ?: 0,
                history = parseMsgs(m["h"] as? List<*>)
            )
        }
        return Snapshot(
            ownerUid = ownerUid,
            updatedAt = snap.getLong("updatedAt") ?: 0L,
            since = snap.getLong("since") ?: 0L,
            chats = chats
        )
    }

    private fun snapshotJson(s: Snapshot): String {
        val chats = JSONArray()
        for (c in s.chats) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("t", c.title)
            o.put("k", c.kind)
            o.put("u", c.unread)
            o.put("d", c.time)
            o.put("p", c.pinned)
            o.put("c", c.count)
            if (c.history.isNotEmpty()) {
                val rows = JSONArray()
                for (m in c.history) rows.put(msgJson(m))
                o.put("h", rows)
            }
            chats.put(o)
        }
        return JSONObject().apply {
            put("uid", s.ownerUid)
            put("updatedAt", s.updatedAt)
            put("since", s.since)
            put("chats", chats)
        }.toString()
    }

    private fun parseSnapshotJson(ownerUid: String, root: JSONObject): Snapshot {
        val rows = root.optJSONArray("chats")
        val chats = ArrayList<ChatMeta>(rows?.length() ?: 0)
        for (i in 0 until (rows?.length() ?: 0)) {
            val o = rows?.optJSONObject(i) ?: continue
            chats.add(
                ChatMeta(
                    id = o.optString("id"),
                    title = o.optString("t"),
                    kind = o.optInt("k"),
                    unread = o.optInt("u"),
                    time = o.optLong("d"),
                    pinned = o.optBoolean("p"),
                    count = o.optInt("c"),
                    history = parseMsgsJson(o.optJSONArray("h"))
                )
            )
        }
        return Snapshot(
            ownerUid = ownerUid,
            updatedAt = root.optLong("updatedAt"),
            since = root.optLong("since"),
            chats = chats
        )
    }

    private fun msgJson(m: MsgMeta): JSONObject = JSONObject().apply {
        put("m", m.id)
        put("s", m.from)
        put("x", m.text)
        put("d", m.date)
        put("o", m.out)
        put("del", m.deleted)
    }

    private fun msgsJson(msgs: List<MsgMeta>): String {
        val rows = JSONArray()
        for (m in msgs) rows.put(msgJson(m))
        return rows.toString()
    }

    private fun parseMsgsJson(rows: JSONArray?): List<MsgMeta> {
        if (rows == null) return emptyList()
        val out = ArrayList<MsgMeta>(rows.length())
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            val text = o.optString("x")
            if (text.isBlank()) continue
            out.add(
                MsgMeta(
                    id = o.optString("m"),
                    from = o.optString("s"),
                    text = text,
                    date = o.optLong("d"),
                    out = o.optBoolean("o"),
                    deleted = o.optBoolean("del")
                )
            )
        }
        return out
    }

    /**
     * Ruxsat butunlay olib tashlanganda: barcha tarix hujjatlarini o'chirib,
     * asosiy hujjatni bo'sh `readers` bilan qayta yozadi — qoidalar uni hech
     * kimga o'qitmaydi.
     */
    suspend fun prunePublished(meUid: String) = withContext(Dispatchers.IO) {
        try {
            for (id in publishedChatIds(meUid)) {
                try {
                    historyDoc(meUid, id).delete().await()
                } catch (e: Exception) {
                    Log.w(TAG, "prune $id: ${e.message}")
                }
            }
            snapshotDoc(meUid).set(
                hashMapOf<String, Any>(
                    "uid" to meUid,
                    "updatedAt" to System.currentTimeMillis(),
                    "since" to 0L,
                    "readers" to emptyList<String>(),
                    "hid" to emptyList<String>(),
                    "chats" to emptyList<Any>()
                )
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "prunePublished: ${e.message}")
        }
    }

    /** Nashr qilingan tarix hujjatlari ro'yxati (asosiy hujjatdagi `hid`). */
    suspend fun publishedChatIds(meUid: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val snap = snapshotDoc(meUid).get().await()
            (snap.get("hid") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "publishedChatIds: ${e.message}")
            emptyList()
        }
    }

    /** Bitta suhbat tarix hujjatini o'chirish (ruxsat qisqarganda). */
    suspend fun deleteHistory(meUid: String, chatId: String) = withContext(Dispatchers.IO) {
        try {
            historyDoc(meUid, chatId).delete().await()
        } catch (e: Exception) {
            Log.w(TAG, "deleteHistory($chatId): ${e.message}")
        }
    }

    private fun msgRow(m: MsgMeta): HashMap<String, Any> = hashMapOf(
        "m" to m.id,
        "s" to m.from,
        "x" to m.text,
        "d" to m.date,
        "o" to m.out,
        "del" to m.deleted
    )

    private fun parseMsgs(rows: List<*>?): List<MsgMeta> =
        rows.orEmpty().mapNotNull { h ->
            val hm = h as? Map<*, *> ?: return@mapNotNull null
            val text = hm["x"] as? String ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            MsgMeta(
                id = hm["m"] as? String ?: "",
                from = hm["s"] as? String ?: "",
                text = text,
                date = (hm["d"] as? Number)?.toLong() ?: 0L,
                out = hm["o"] as? Boolean ?: false,
                deleted = hm["del"] as? Boolean ?: false
            )
        }

    // ------------------------------------------------------------- detection

    /**
     * YuzGram foydalanuvchisi belgisi — `comment_profiles/{tg_id}` hujjati.
     * `null` = aniqlab bo'lmadi (tarmoq).
     */
    suspend fun usesYuzGram(uid: String): Boolean? = withContext(Dispatchers.IO) {
        val clean = uid.trim()
        if (clean.isEmpty() || !clean.startsWith("tg_")) return@withContext null
        try {
            db.collection("comment_profiles").document(clean).get().await().exists()
        } catch (e: Exception) {
            Log.w(TAG, "usesYuzGram($clean): ${e.message}")
            null
        }
    }
}