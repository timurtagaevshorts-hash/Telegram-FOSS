package uz.kodlikino.top4.utils

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import uz.kodlikino.top4.models.ReelNotification

/**
 * Reels faoliyat bildirishnomalari do'koni — donor KinoTOP4 porti.
 * Firestore: `user_notifications/{ownerUid}/items/{autoId}`.
 * Qoidalar (firestore.rules): yaratish — istalgan tashabbus foydalanuvchisi
 * (aktor o'zidan recipient qutisiga yozadi), o'qish/update — faqat egasi.
 */
object ReelNotificationManager {

    private const val TAG = "ReelNotificationManager"
    private const val ROOT = "user_notifications"
    private const val ITEMS = "items"

    private val db = FirebaseFirestore.getInstance()

    suspend fun pushLike(
        ownerUid: String,
        actorUid: String,
        actorName: String,
        actorPhoto: String,
        reelId: String,
        reelTitle: String,
        reelThumbUrl: String = ""
    ) {
        if (ownerUid.isBlank() || actorUid.isBlank() || ownerUid == actorUid) return
        write(
            ownerUid,
            ReelNotification(
                type = ReelNotification.TYPE_LIKE,
                reelId = reelId,
                actorUid = actorUid,
                actorName = actorName.ifBlank { "Foydalanuvchi" },
                actorPhotoUrl = actorPhoto,
                reelTitle = reelTitle,
                reelThumbUrl = reelThumbUrl,
                createdAt = System.currentTimeMillis(),
                read = false
            )
        )
    }

    suspend fun pushComment(
        ownerUid: String,
        actorUid: String,
        actorName: String,
        actorPhoto: String,
        reelId: String,
        reelTitle: String,
        reelThumbUrl: String = "",
        commentId: String,
        commentText: String
    ) {
        if (ownerUid.isBlank() || actorUid.isBlank() || ownerUid == actorUid) return
        write(
            ownerUid,
            ReelNotification(
                type = ReelNotification.TYPE_COMMENT,
                reelId = reelId,
                commentId = commentId,
                commentText = commentText.take(120),
                actorUid = actorUid,
                actorName = actorName.ifBlank { "Foydalanuvchi" },
                actorPhotoUrl = actorPhoto,
                reelTitle = reelTitle,
                reelThumbUrl = reelThumbUrl,
                createdAt = System.currentTimeMillis(),
                read = false
            )
        )
    }

    /** Izohga javob — faqat izoh egasiga. */
    suspend fun pushReply(
        commentOwnerUid: String,
        actorUid: String,
        actorName: String,
        actorPhoto: String,
        reelId: String,
        reelTitle: String,
        reelThumbUrl: String = "",
        commentId: String,
        commentText: String
    ) {
        if (commentOwnerUid.isBlank() || actorUid.isBlank() || commentOwnerUid == actorUid) return
        write(
            commentOwnerUid,
            ReelNotification(
                type = ReelNotification.TYPE_REPLY,
                reelId = reelId,
                commentId = commentId,
                commentText = commentText.take(120),
                actorUid = actorUid,
                actorName = actorName.ifBlank { "Foydalanuvchi" },
                actorPhotoUrl = actorPhoto,
                reelTitle = reelTitle,
                reelThumbUrl = reelThumbUrl,
                createdAt = System.currentTimeMillis(),
                read = false
            )
        )
    }

    private suspend fun write(ownerUid: String, item: ReelNotification) = withContext(Dispatchers.IO) {
        try {
            val ref = db.collection(ROOT).document(ownerUid.trim())
                .collection(ITEMS).document()
            ref.set(
                mapOf(
                    "id" to ref.id,
                    "type" to item.type,
                    "reelId" to item.reelId,
                    "commentId" to item.commentId,
                    "commentText" to item.commentText,
                    "actorUid" to item.actorUid,
                    "actorName" to item.actorName,
                    "actorPhotoUrl" to item.actorPhotoUrl,
                    "reelTitle" to item.reelTitle,
                    "reelThumbUrl" to item.reelThumbUrl,
                    "createdAt" to item.createdAt,
                    "read" to false
                )
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "write: ${e.message}")
        }
    }

    suspend fun fetch(ownerUid: String, limit: Long = 80): List<ReelNotification> =
        withContext(Dispatchers.IO) {
            val uid = ownerUid.trim()
            if (uid.isBlank()) return@withContext emptyList()
            try {
                db.collection(ROOT).document(uid).collection(ITEMS)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(limit)
                    .get().await()
                    .documents
                    .mapNotNull { parse(it.id, it.data) }
            } catch (e: Exception) {
                Log.w(TAG, "fetch: ${e.message}")
                emptyList()
            }
        }

    fun listen(
        ownerUid: String,
        onUpdate: (List<ReelNotification>) -> Unit
    ): ListenerRegistration? {
        val uid = ownerUid.trim()
        if (uid.isBlank()) return null
        return db.collection(ROOT).document(uid).collection(ITEMS)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(80)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "listen: ${err.message}")
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { parse(it.id, it.data) }.orEmpty()
                onUpdate(list)
            }
    }

    fun listenUnreadCount(ownerUid: String, onCount: (Int) -> Unit): ListenerRegistration? {
        val uid = ownerUid.trim()
        if (uid.isBlank()) return null
        return db.collection(ROOT).document(uid).collection(ITEMS)
            .whereEqualTo("read", false)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    onCount(0)
                    return@addSnapshotListener
                }
                onCount(snap?.size() ?: 0)
            }
    }

    suspend fun markRead(ownerUid: String, notificationId: String) = withContext(Dispatchers.IO) {
        val uid = ownerUid.trim()
        val id = notificationId.trim()
        if (uid.isBlank() || id.isBlank()) return@withContext
        try {
            db.collection(ROOT).document(uid).collection(ITEMS).document(id)
                .set(mapOf("read" to true), SetOptions.merge()).await()
        } catch (e: Exception) {
            Log.w(TAG, "markRead: ${e.message}")
        }
    }

    suspend fun markAllRead(ownerUid: String) = withContext(Dispatchers.IO) {
        val uid = ownerUid.trim()
        if (uid.isBlank()) return@withContext
        try {
            val docs = db.collection(ROOT).document(uid).collection(ITEMS)
                .whereEqualTo("read", false)
                .limit(100)
                .get().await()
                .documents
            if (docs.isEmpty()) return@withContext
            val batch = db.batch()
            docs.forEach { batch.set(it.reference, mapOf("read" to true), SetOptions.merge()) }
            batch.commit().await()
        } catch (e: Exception) {
            Log.w(TAG, "markAllRead: ${e.message}")
        }
    }

    suspend fun delete(ownerUid: String, notificationId: String): Boolean =
        withContext(Dispatchers.IO) {
            val uid = ownerUid.trim()
            val id = notificationId.trim()
            if (uid.isBlank() || id.isBlank()) return@withContext false
            try {
                db.collection(ROOT).document(uid).collection(ITEMS).document(id)
                    .delete().await()
                true
            } catch (e: Exception) {
                Log.w(TAG, "delete: ${e.message}")
                false
            }
        }

    suspend fun deleteAll(ownerUid: String): Boolean = withContext(Dispatchers.IO) {
        val uid = ownerUid.trim()
        if (uid.isBlank()) return@withContext false
        val items = db.collection(ROOT).document(uid).collection(ITEMS)
        try {
            while (true) {
                val docs = items.limit(400).get().await().documents
                if (docs.isEmpty()) break
                val batch = db.batch()
                docs.forEach { batch.delete(it.reference) }
                batch.commit().await()
                if (docs.size < 400) break
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "deleteAll: ${e.message}")
            false
        }
    }

    private fun parse(id: String, data: Map<String, Any>?): ReelNotification? {
        if (data == null) return null
        return ReelNotification(
            id = id,
            type = data["type"]?.toString().orEmpty().ifBlank { ReelNotification.TYPE_LIKE },
            reelId = data["reelId"]?.toString().orEmpty(),
            commentId = data["commentId"]?.toString().orEmpty(),
            commentText = data["commentText"]?.toString().orEmpty(),
            actorUid = data["actorUid"]?.toString().orEmpty(),
            actorName = data["actorName"]?.toString().orEmpty(),
            actorPhotoUrl = data["actorPhotoUrl"]?.toString().orEmpty(),
            reelTitle = data["reelTitle"]?.toString().orEmpty(),
            reelThumbUrl = data["reelThumbUrl"]?.toString().orEmpty(),
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
            read = data["read"] as? Boolean ?: false
        )
    }
}
