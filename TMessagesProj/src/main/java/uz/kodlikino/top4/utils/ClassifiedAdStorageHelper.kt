package uz.kodlikino.top4.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Elon rasmlarini Cloud Storage'ga yuklaydi (`classified_ads/{uid}/{adId}_{i}.jpg`).
 * KinoTOP4'dan ko'chirildi; Firebase Auth endi [YuzGramAdsAuth] orqali `tg_<id>`
 * sessiyasini ta'minlagani uchun `user.uid == uid` tekshiruvi o'rinli.
 *
 * [YuzUploadWatchdog] + urinishlar: oldin bitta rasm "tutaqib" qolsa butun e'lon
 * abadiy yuklanayotgan holatda qolardi. Endi har bir rasm uchun to'xtab qolish
 * kuzatiladi va kerak bo'lsa qaytadan urinish boradi.
 *
 * Rasm avval [YuzImageCompressor] bilan siqiladi — telefon kamerasining 8–15 MB li
 * fayllari mobil internetda yuklab bo'lmas edi va storage.rules 15 MB chegarasiga
 * urilardi.
 */
object ClassifiedAdStorageHelper {

    private const val TAG = "ClassifiedAdStorage"
    private const val UPLOAD_ATTEMPTS = 3

    private val storage = FirebaseStorage.getInstance()

    /**
     * @param onProgress (umumiy foiz, tugagan rasm soni, jami rasm soni)
     * @throws IllegalStateException matni foydalanuvchiga ko'rsatishga tayyor.
     */
    suspend fun uploadImages(
        context: Context,
        uid: String,
        adId: String,
        uris: List<Uri>,
        onProgress: (percent: Int, done: Int, total: Int) -> Unit = { _, _, _ -> }
    ): List<String> {
        if (uid.isBlank() || adId.isBlank() || uris.isEmpty()) return emptyList()
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Avval Telegram orqali kiring")
        if (user.uid != uid) throw IllegalStateException("Foydalanuvchi tasdiqlanmadi")

        val appContext = context.applicationContext
        val files = uris.take(5)
        val urls = mutableListOf<String>()
        files.forEachIndexed { index, uri ->
            val ref = storage.reference.child("classified_ads/$uid/${adId}_$index.jpg")
            val metadata = StorageMetadata.Builder()
                .setContentType("image/jpeg")
                .setCacheControl("public, max-age=31536000")
                .build()
            // Siqilgan fayl barcha urinishlarda qayta ishlatiladi (bir marta
            // kodlash yetarli), oxirida o'chiriladi.
            var lastError: Exception? = null
            var uploaded = false
            val compressed: File? = YuzImageCompressor.compressForUpload(appContext, uri)
            val payload = compressed?.let { Uri.fromFile(it) } ?: uri
            try {
                for (attempt in 1..UPLOAD_ATTEMPTS) {
                    try {
                        YuzUploadWatchdog.awaitWithStall(
                            ref.putFile(payload, metadata),
                            "Rasm ${index + 1}"
                        )
                        urls.add(ref.downloadUrl.await().toString())
                        uploaded = true
                        break
                    } catch (e: Exception) {
                        lastError = e
                        val message = e.message.orEmpty()
                        if (message.contains("permission", true) ||
                            message.contains("unauthorized", true)
                        ) {
                            throw IllegalStateException(
                                "Firebase Storage ruxsati rad etildi. Sabab: storage.rules " +
                                    "yoyilmagan yoki rasm 15 MB dan katta (qoida chegarasi)."
                            )
                        }
                        Log.w(TAG, "rasm $index urinish $attempt: ${e.message}")
                        if (attempt < UPLOAD_ATTEMPTS) delay(1500L * attempt)
                    }
                }
            } finally {
                YuzImageCompressor.deleteQuietly(compressed)
            }
            if (!uploaded) {
                throw IllegalStateException(
                    "${index + 1}-rasm yuklanmadi (${UPLOAD_ATTEMPTS} marta urinish). " +
                        "Internet sekin — mobil ma'lumotga o'tib qayta urinib ko'ring." +
                        lastError?.message?.let { " ($it)" }.orEmpty()
                )
            }
            onProgress(((index + 1) * 100) / files.size, index + 1, files.size)
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
