package uz.kodlikino.top4.trust

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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
        val updatedAt: Long
    ) {
        val isPending: Boolean get() = status == STATUS_PENDING
        val isActive: Boolean get() = status == STATUS_ACCEPTED
    }

    /** Chiquvchi so'rov (men birovning ro'yxatini ko'rmoqchiman). */
    data class Outgoing(
        val peerUid: String,
        val peerName: String,
        val status: String,
        val createdAt: Long
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
        val out: Boolean
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
                        updatedAt = doc.getLong("updatedAt") ?: 0L
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
                        createdAt = doc.getLong("createdAt") ?: 0L
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
            inbox(peerUid).document(meUid).set(
                hashMapOf(
                    "requesterUid" to meUid,
                    "approverUid" to peerUid,
                    "requesterName" to myName.take(64),
                    "status" to STATUS_PENDING,
                    "shareMine" to false,
                    "createdAt" to now,
                    "updatedAt" to now
                )
            ).await()
            sent(meUid).document(peerUid).set(
                hashMapOf(
                    "approverUid" to peerUid,
                    "approverName" to peerName.take(64),
                    "status" to STATUS_PENDING,
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
            Snapshot(
                ownerUid = ownerUid,
                updatedAt = snap.getLong("updatedAt") ?: 0L,
                since = snap.getLong("since") ?: 0L,
                chats = chats
            )
        } catch (e: Exception) {
            Log.w(TAG, "loadSnapshot($ownerUid): ${e.message}")
            null
        }
    }

    /** Ruxsat berilgan bo'lsa bitta suhbat yozishmasini o'qiydi. */
    suspend fun loadHistory(ownerUid: String, chatId: String): List<MsgMeta>? =
        withContext(Dispatchers.IO) {
            try {
                val snap = historyDoc(ownerUid, chatId).get().await()
                if (!snap.exists()) return@withContext null
                parseMsgs(snap.get("msgs") as? List<*>)
            } catch (e: Exception) {
                Log.w(TAG, "loadHistory($ownerUid/$chatId): ${e.message}")
                null
            }
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
        "o" to m.out
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
                out = hm["o"] as? Boolean ?: false
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