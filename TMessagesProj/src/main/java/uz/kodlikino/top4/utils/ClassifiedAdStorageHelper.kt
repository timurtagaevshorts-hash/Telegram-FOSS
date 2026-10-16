package uz.kodlikino.top4.utils

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await

/**
 * Elon rasmlarini Cloud Storage'ga yuklaydi (`classified_ads/{uid}/{adId}_{i}.jpg`).
 * KinoTOP4'dan ko'chirildi; Firebase Auth endi [YuzGramAdsAuth] orqali `tg_<id>`
 * sessiyasini ta'minlagani uchun `user.uid == uid` tekshiruvi o'rinli.
 */
object ClassifiedAdStorageHelper {

    private val storage = FirebaseStorage.getInstance()

    suspend fun uploadImages(
        uid: String,
        adId: String,
        uris: List<Uri>,
        onProgress: (Int) -> Unit = {}
    ): List<String> {
        if (uid.isBlank() || adId.isBlank() || uris.isEmpty()) return emptyList()
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Avval Telegram orqali kiring")
        if (user.uid != uid) throw IllegalStateException("Foydalanuvchi tasdiqlanmadi")

        val urls = mutableListOf<String>()
        uris.take(5).forEachIndexed { index, uri ->
            val ref = storage.reference.child("classified_ads/$uid/${adId}_$index.jpg")
            val metadata = StorageMetadata.Builder()
                .setContentType("image/jpeg")
                .setCacheControl("public, max-age=31536000")
                .build()
            try {
                ref.putFile(uri, metadata).await()
                urls.add(ref.downloadUrl.await().toString())
            } catch (e: Exception) {
                val message = e.message.orEmpty()
                if (message.contains("permission", ignoreCase = true) ||
                    message.contains("unauthorized", ignoreCase = true) ||
                    message.contains("does not have permission", ignoreCase = true)
                ) {
                    throw IllegalStateException(
                        "Firebase Storage ruxsati yo'q. Firebase Console → Storage → Rules " +
                            "bo'limiga storage.rules qoidalarini Publish qiling."
                    )
                }
                throw e
            }
            onProgress(((index + 1) * 100) / uris.size.coerceAtMost(5))
        }
        return urls
    }

    suspend fun deleteImages(urls: List<String>) {
        urls.forEach { url ->
            val trimmed = url.trim()
            if (trimmed.isBlank()) return@forEach
            try {
                storage.getReferenceFromUrl(trimmed).delete().await()
            } catch (_: Exception) {
            }
        }
    }
}
