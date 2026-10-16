package uz.kodlikino.top4.utils

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await

/**
 * Reel videosini Cloud Storage'ga yuklaydi (`user_reels/{uid}/{fileName}`).
 * KinoTOP4'dagi `ReelStorageHelper` yo'li bilan bir xil — shuning uchun eski
 * foydalanuvchi reel'lariga mos keladi. Auth sessiyasi [YuzGramAdsAuth] orqali
 * `tg_<id>` uid'ni ta'minlaydi (Storage qoidasi `user_reels/{uid}` owner yozuvi).
 */
object ReelStorageHelper {

    private val storage = FirebaseStorage.getInstance()

    /**
     * @return yuklangan videoning olinadigan (download) URL'i.
     */
    suspend fun uploadVideo(uid: String, fileName: String, uri: Uri, onProgress: (Int) -> Unit = {}): String {
        if (uid.isBlank() || fileName.isBlank()) {
            throw IllegalStateException("Reel yuklash uchun uid/fileName kerak")
        }
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Avval Telegram orqali kiring")
        if (user.uid != uid) throw IllegalStateException("Foydalanuvchi tasdiqlanmadi")

        val ref = storage.reference.child("user_reels/$uid/$fileName")
        val metadata = StorageMetadata.Builder()
            .setContentType("video/mp4")
            .build()
        try {
            val task = ref.putFile(uri, metadata)
            task.addOnProgressListener { snap ->
                val total = snap.totalByteCount.coerceAtLeast(1L)
                onProgress(((snap.bytesTransferred * 100) / total).toInt().coerceIn(0, 100))
            }.await()
            return ref.downloadUrl.await().toString()
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (message.contains("permission", true) ||
                message.contains("unauthorized", true) ||
                message.contains("does not have permission", true)
            ) {
                throw IllegalStateException(
                    "Firebase Storage ruxsati yo'q. Firebase Console → Storage → Rules bo'limiga " +
                        "storage.rules qoidalarini Publish qiling."
                )
            }
            throw e
        }
    }
}
