package uz.kodlikino.top4.utils

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import uz.kodlikino.top4.models.Reel

/**
 * "Reels" ma'lumotlar ombori — Firestore `reels` kolleksiyasi.
 * KinoTOP4 bilan bir xil kollekciya/field nomlari, shuning uchun mavjud
 * reels ma'lumotlari ham ko'rinadi va yangi postlar umumiy tasмага qo'shiladi.
 */
object ReelsRepository {

    private const val TAG = "ReelsRepository"
    const val COLLECTION = "reels"
    private const val LIKES = "likes"
    private const val FEED_LIMIT = 100L

    private val db = FirebaseFirestore.getInstance()

    /** Feed — oxirgi qo'shilgan reelslar (createdAt bo'yicha kamayish tartibida). */
    suspend fun getReels(): List<Reel> {
        return try {
            val snapshot = db.collection(COLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(FEED_LIMIT)
                .get()
                .await()
            snapshot.documents.mapNotNull { doc ->
                try {
                    val reel = doc.toObject(Reel::class.java) ?: return@mapNotNull null
                    reel.copy(id = reel.id.ifBlank { doc.id })
                        .takeIf { it.videoUrl.isNotBlank() }
                } catch (e: Exception) {
                    Log.e(TAG, "parse ${doc.id}: ${e.message}", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getReels: ${e.message}", e)
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
     */
    suspend fun setLiked(reelId: String, uid: String, liked: Boolean) {
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
}
