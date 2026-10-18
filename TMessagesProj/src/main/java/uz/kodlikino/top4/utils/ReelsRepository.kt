package uz.kodlikino.top4.utils

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.models.ReelComment

/**
 * "Reels" ma'lumotlar ombori — Firestore `reels` kolleksiyasi.
 * KinoTOP4 bilan bir xil kollekciya/field nomlari, shuning uchun mavjud
 * reels ma'lumotlari ham ko'rinadi va yangi postlar umumiy tasмага qo'shiladi.
 */
object ReelsRepository {

    private const val TAG = "ReelsRepository"
    const val COLLECTION = "reels"
    private const val LIKES = "likes"
    private const val COMMENTS = "comments"
    private const val FEED_LIMIT = 100L
    private const val COMMENTS_LIMIT = 200L

    private val db = FirebaseFirestore.getInstance()

    /**
     * In-memory feed keshi (oldingi loyihadagi `ReelsDataCache` g'oyasi).
     * Maqsad: ekronga qaytishda (onResume/tab almashish) 100 hujjatlik
     * Firestore so'rovi tugashini kutmasdan [peekReels] orqali tasmani
     * BIRINCHI KADRDA ko'rsatish; tarmoq javobi kelgach ro'yxat yangilanadi.
     * Faqat muvaffaqiyatli o'qishlar saqlanadi (xato/null holat keshmaydi).
     */
    @Volatile
    private var cachedFeed: List<Reel>? = null
    private val cachedByUploader = HashMap<String, List<Reel>>()

    /** Oxirgi muvaffaqiyatli umumiy feed — bo'lsa, darhol render qilish uchun. */
    fun peekReels(): List<Reel>? = cachedFeed

    /** Oxirgi muvaffaqiyatli scoped feed (profil grid'i uchun). */
    fun peekReelsByUploader(uid: String): List<Reel>? {
        if (uid.isBlank()) return null
        return synchronized(cachedByUploader) { cachedByUploader[uid] }
    }

    /** Feed — oxirgi qo'shilgan reelslar (createdAt bo'yicha kamayish tartibida). */
    suspend fun getReels(): List<Reel> = getReelsOrNull() ?: emptyList()

    /**
     * [getReels]ning xato holatini ajratib qaytaradigan varianti:
     * `null` — o'qish XATO bilan tugadi (internet/qoida),
     * bo'sh ro'yxat — haqiqatan ham reels yo'q. Ekranda "bo'sh" va "yuklanmadi"
     * xabarlarini chalkashtirmaslik uchun kerak.
     */
    suspend fun getReelsOrNull(): List<Reel>? {
        return try {
            val snapshot = db.collection(COLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(FEED_LIMIT)
                .get()
                .await()
            val result = snapshot.documents.mapNotNull { doc ->
                try {
                    val reel = doc.toObject(Reel::class.java) ?: return@mapNotNull null
                    reel.copy(id = reel.id.ifBlank { doc.id })
                        .takeIf { it.videoUrl.isNotBlank() }
                } catch (e: Exception) {
                    Log.e(TAG, "parse ${doc.id}: ${e.message}", e)
                    null
                }
            }
            cachedFeed = result
            result
        } catch (e: Exception) {
            Log.e(TAG, "getReels: ${e.message}", e)
            null
        }
    }

    /** Muayyan uid tomonidan yuklangan reel'lar (monetizatsiya va kontent boshqaruvi uchun). */
    suspend fun getReelsByUploader(uid: String): List<Reel> {
        if (uid.isBlank()) return emptyList()
        return try {
            val snapshot = db.collection(COLLECTION)
                .whereEqualTo("uploadedByUid", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(FEED_LIMIT)
                .get()
                .await()
            val result = snapshot.documents.mapNotNull { doc ->
                try {
                    val reel = doc.toObject(Reel::class.java) ?: return@mapNotNull null
                    reel.copy(id = reel.id.ifBlank { doc.id })
                } catch (e: Exception) {
                    Log.e(TAG, "parse ${doc.id}: ${e.message}", e)
                    null
                }
            }
            synchronized(cachedByUploader) { cachedByUploader[uid] = result }
            result
        } catch (e: Exception) {
            Log.e(TAG, "getReelsByUploader: ${e.message}", e)
            emptyList()
        }
    }

    /** Yangi reel hujjatini `reels/{id}` ga yozadi. */
    suspend fun saveReel(reel: Reel) {
        val id = reel.id.ifBlank { System.currentTimeMillis().toString() }
        val data = hashMapOf<String, Any>(
            "id" to id,
            "videoUrl" to reel.videoUrl,
            "thumbnailUrl" to reel.thumbnailUrl,
            "views" to reel.views,
            "title" to reel.title,
            "hashtags" to reel.hashtags,
            "monetizationEnabled" to reel.monetizationEnabled,
            "movieUrl" to reel.movieUrl,
            "adTitle" to reel.adTitle,
            "adUrl" to reel.adUrl,
            "commentCount" to reel.commentCount,
            "likeCount" to reel.likeCount,
            "createdAt" to reel.createdAt,
            "uploadedByUid" to reel.uploadedByUid,
            "uploadedByName" to reel.uploadedByName,
            "uploadedByPhotoUrl" to reel.uploadedByPhotoUrl,
            "isUserUpload" to reel.isUserUpload
        )
        try {
            db.collection(COLLECTION).document(id).set(data).await()
            // Kesheski holat qolmasligi uchun: yangi reel keyingi ochilishda
            // darhol ko'rinishi kerak (network fetch yangi list keshlaydi).
            cachedFeed = null
            synchronized(cachedByUploader) { cachedByUploader.remove(reel.uploadedByUid) }
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (message.contains("PERMISSION_DENIED", true) || message.contains("permission", true)) {
                throw IllegalStateException(
                    "Reel saqlash ruxsati yo'q. Telegram orqali qayta kiring yoki biroz kutib urinib ko'ring."
                )
            }
            throw e
        }
    }

    /**
     * Like holati — `reels/{id}/likes/{uid}` hujjatining mavjudligi.
     * Firestore qoidalari bu subkolleksiyani o'qishga ham, o'z hujjatini
     * yozib/o'chirishga ham ruxsat beradi (rules: signedIn() && uid == userId).
     */
    suspend fun isLiked(reelId: String, uid: String): Boolean {
        if (reelId.isBlank() || uid.isBlank()) return false
        return try {
            db.collection(COLLECTION).document(reelId)
                .collection(LIKES).document(uid).get().await().exists()
        } catch (e: Exception) {
            Log.e(TAG, "isLiked $reelId: ${e.message}", e)
            false
        }
    }

    /**
     * Like qo'yish / olish. `likeCount` maydoni faqat 3 ta ruxsat etilgan
     * (`views`, `likeCount`, `commentCount`) dan biri — shuning uchun increment
     * oddiy `update()` orqali o'tadi (hasOnly([likeCount]) qoidasiga mos).
     * Muvaffaqiyatli LIKE'dan so'ng reel egasiga bildirishnoma fan-out qilinadi.
     */
    suspend fun setLiked(reelId: String, uid: String, liked: Boolean, actorName: String = "", actorPhoto: String = "") {
        if (reelId.isBlank() || uid.isBlank()) return
        val likeRef = db.collection(COLLECTION).document(reelId)
            .collection(LIKES).document(uid)
        val delta = if (liked) 1L else -1L
        if (liked) {
            likeRef.set(hashMapOf<String, Any>("userId" to uid, "createdAt" to System.currentTimeMillis()))
                .await()
        } else {
            likeRef.delete().await()
        }
        try {
            db.collection(COLLECTION).document(reelId)
                .update(mapOf("likeCount" to FieldValue.increment(delta))).await()
        } catch (e: Exception) {
            Log.e(TAG, "likeCount $reelId: ${e.message}", e)
        }
        if (liked) {
            try {
                val reel = reelMeta(reelId)
                if (reel != null && reel.uploadedByUid.isNotBlank() && reel.uploadedByUid != uid) {
                    ReelNotificationManager.pushLike(
                        ownerUid = reel.uploadedByUid,
                        actorUid = uid,
                        actorName = actorName,
                        actorPhoto = actorPhoto,
                        reelId = reelId,
                        reelTitle = reel.title,
                        reelThumbUrl = reel.thumbnailUrl
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "pushLike $reelId: ${e.message}", e)
            }
        }
    }

    /** Ko'rishni hisobga olish (har sessiyada bir marta chaqiriladi). */
    suspend fun registerView(reelId: String) {
        if (reelId.isBlank()) return
        try {
            db.collection(COLLECTION).document(reelId)
                .update(mapOf("views" to FieldValue.increment(1L))).await()
        } catch (e: Exception) {
            Log.e(TAG, "registerView $reelId: ${e.message}", e)
        }
    }

    /**
     * Izohlar — `reels reelId / comments` subkolleksiyasi (eskidana yangi).
     * O'qish qoidasi `if true`, shuning uchun Firebase sessiyasi shart emas.
     *
     * @return `null` — o'qish xatosi (tarmoq/ruxsat), bo'sh ro'yxat — izoh yo'q.
     */
    suspend fun getComments(reelId: String): List<ReelComment>? {
        if (reelId.isBlank()) return emptyList()
        return try {
            val snapshot = db.collection(COLLECTION).document(reelId)
                .collection(COMMENTS)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(COMMENTS_LIMIT)
                .get().await()
            snapshot.documents.mapNotNull { doc ->
                try {
                    val c = doc.toObject(ReelComment::class.java) ?: return@mapNotNull null
                    c.copy(
                        id = c.id.ifBlank { doc.id },
                        reelId = c.reelId.ifBlank { reelId }
                    ).takeIf { it.text.isNotBlank() }
                } catch (e: Exception) {
                    Log.e(TAG, "comment parse ${doc.id}: ${e.message}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getComments $reelId: ${e.message}", e)
            null
        }
    }

    /**
     * Yangi izoh yozadi va `commentCount` ni bittaga oshiradi.
     *
     * Qoida: izoh `create` faqat `signedIn() && data.userId == auth.uid` da o'tadi —
     * shuning uchun chaqiruvdan oldin Firebase sessiyasi ochilgan bo'lishi shart
     * (qarang [YuzGramAdsAuth.ensureSignedIn]). `commentCount` uchta ruxsat etilgan
     * maydondan biri (`views`, `likeCount`, `commentCount`), shuning uchun alohida
     * `update()` orqali ketadi — izoh yozuvi muvaffaqiyatli bo'lsagina oshiriladi.
     *
     * @return yozilgan izoh (id bilan)
     */
    suspend fun addComment(reelId: String, comment: ReelComment): ReelComment {
        if (reelId.isBlank() || comment.text.isBlank() || comment.userId.isBlank()) {
            throw IllegalArgumentException("Izoh matni bo'sh")
        }
        val docRef = db.collection(COLLECTION).document(reelId)
            .collection(COMMENTS).document()
        val saved = comment.copy(id = docRef.id, reelId = reelId)
        val data = hashMapOf<String, Any>(
            "id" to docRef.id,
            "reelId" to reelId,
            "text" to saved.text,
            "authorName" to saved.authorName,
            "authorPhotoUrl" to saved.authorPhotoUrl,
            "userId" to saved.userId,
            "deviceId" to saved.deviceId,
            "createdAt" to saved.createdAt,
            "replyToCommentId" to saved.replyToCommentId,
            "replyToUserId" to saved.replyToUserId,
            "replyToAuthorName" to saved.replyToAuthorName
        )
        try {
            docRef.set(data).await()
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (message.contains("PERMISSION_DENIED", true) || message.contains("permission", true)) {
                throw IllegalStateException(
                    "Izoh yozish ruxsati yo'q. Telegram orqali qayta kiring yoki biroz kutib urinib ko'ring."
                )
            }
            throw e
        }
        try {
            db.collection(COLLECTION).document(reelId)
                .update(mapOf("commentCount" to FieldValue.increment(1L))).await()
        } catch (e: Exception) {
            // Hisob yozuvi muvaffaqiyatli bo'lsa, izoh baribir ko'rinadi.
            Log.e(TAG, "commentCount $reelId: ${e.message}", e)
        }
        try {
            notifyCommentAdded(saved)
        } catch (e: Exception) {
            Log.e(TAG, "notifyCommentAdded $reelId: ${e.message}", e)
        }
        return saved
    }

    /**
     * Izoh fan-out (donor `notifyReelCommentAdded` mantig'i):
     *  - javob (replyToCommentId bor) → asosiy izoh egasiga TYPE_REPLY,
     *    reel egasi izohchidan va javob berilgan egadan boshqa bo'lsa TYPE_COMMENT;
     *  - oddiy izoh → reel egasiga TYPE_COMMENT (o'ziga yozsa — yo'q).
     */
    private suspend fun notifyCommentAdded(comment: ReelComment) {
        val reel = reelMeta(comment.reelId) ?: return
        val ownerUid = reel.uploadedByUid
        if (comment.replyToCommentId.isNotBlank()) {
            val parent = getReelComment(comment.reelId, comment.replyToCommentId)
            val parentOwner = parent?.userId?.takeIf { it.isNotBlank() } ?: comment.replyToUserId
            if (parentOwner.isNotBlank() && parentOwner != comment.userId) {
                ReelNotificationManager.pushReply(
                    commentOwnerUid = parentOwner,
                    actorUid = comment.userId,
                    actorName = comment.authorName,
                    actorPhoto = comment.authorPhotoUrl,
                    reelId = comment.reelId,
                    reelTitle = reel.title,
                    reelThumbUrl = reel.thumbnailUrl,
                    commentId = comment.replyToCommentId,
                    commentText = comment.text
                )
            }
            if (ownerUid.isNotBlank() && ownerUid != comment.userId && ownerUid != parentOwner) {
                ReelNotificationManager.pushComment(
                    ownerUid = ownerUid,
                    actorUid = comment.userId,
                    actorName = comment.authorName,
                    actorPhoto = comment.authorPhotoUrl,
                    reelId = comment.reelId,
                    reelTitle = reel.title,
                    reelThumbUrl = reel.thumbnailUrl,
                    commentId = comment.id,
                    commentText = comment.text
                )
            }
        } else if (ownerUid.isNotBlank() && ownerUid != comment.userId) {
            ReelNotificationManager.pushComment(
                ownerUid = ownerUid,
                actorUid = comment.userId,
                actorName = comment.authorName,
                actorPhoto = comment.authorPhotoUrl,
                reelId = comment.reelId,
                reelTitle = reel.title,
                reelThumbUrl = reel.thumbnailUrl,
                commentId = comment.id,
                commentText = comment.text
            )
        }
    }

    /** Bitta izoh hujjatini o'qiydi (reply'langan otani topish uchun). */
    suspend fun getReelComment(reelId: String, commentId: String): ReelComment? {
        if (reelId.isBlank() || commentId.isBlank()) return null
        return try {
            db.collection(COLLECTION).document(reelId)
                .collection(COMMENTS).document(commentId).get().await()
                .let { doc ->
                    if (!doc.exists()) return null
                    val c = doc.toObject(ReelComment::class.java) ?: return null
                    c.copy(id = c.id.ifBlank { doc.id }, reelId = c.reelId.ifBlank { reelId })
                }
        } catch (e: Exception) {
            Log.e(TAG, "getReelComment $commentId: ${e.message}", e)
            null
        }
    }

    /** Reel'ning bildirishnoma uchun kerakli maydonlari (title/thumb/ega). */
    private suspend fun reelMeta(reelId: String): Reel? {
        if (reelId.isBlank()) return null
        return try {
            db.collection(COLLECTION).document(reelId).get().await()
                .let { doc -> if (doc.exists()) doc.toObject(Reel::class.java) else null }
        } catch (e: Exception) {
            Log.e(TAG, "reelMeta $reelId: ${e.message}", e)
            null
        }
    }

    /**
     * Reel'ni Firestore'dan o'chiradi. Qoida: faqat `uploadedByUid == auth.uid`
     * (ega) yoki admin — shuning uchun chaqiruvchi tomon oldin
     * [YuzGramAdsAuth.ensureSignedIn] bilan sessiya yaratilgan bo'lishi kerak.
     */
    suspend fun deleteReel(reelId: String) {
        if (reelId.isBlank()) return
        try {
            db.collection(COLLECTION).document(reelId).delete().await()
            // O'chirilgan reel keshda qolib keyingi ochilishda "tirilib" qolmasin.
            cachedFeed = null
            synchronized(cachedByUploader) { cachedByUploader.clear() }
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (message.contains("PERMISSION_DENIED", true) || message.contains("permission", true)) {
                throw IllegalStateException("Bu reel'ni o'chirish faqat egasiga ruxsat.")
            }
            throw e
        }
    }
}
