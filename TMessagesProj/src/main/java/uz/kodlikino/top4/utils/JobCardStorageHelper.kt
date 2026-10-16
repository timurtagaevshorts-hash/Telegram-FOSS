package uz.kodlikino.top4.utils

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await

object JobCardStorageHelper {

    private val storage = FirebaseStorage.getInstance()

    suspend fun uploadImage(uid: String, cardId: String, uri: Uri): String {
        if (uid.isBlank() || cardId.isBlank()) return ""
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Avval tizimga kiring")
        if (user.uid != uid) throw IllegalStateException("Foydalanuvchi tasdiqlanmadi")
        val ref = storage.reference.child("classified_ads/$uid/${cardId}_0.jpg")
        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .setCacheControl("public, max-age=31536000")
            .build()
        try {
            ref.putFile(uri, metadata).await()
            return ref.downloadUrl.await().toString()
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (message.contains("permission", ignoreCase = true) ||
                message.contains("unauthorized", ignoreCase = true) ||
                message.contains("does not have permission", ignoreCase = true)
            ) {
                throw IllegalStateException(
                    "Rasm yuklash ruxsati yo'q. Telegram orqali qayta kiring yoki rasmni olib tashlab qayta urinib ko'ring."
                )
            }
            throw e
        }
    }

    suspend fun deleteImage(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        runCatching { storage.getReferenceFromUrl(trimmed).delete().await() }
    }
}
