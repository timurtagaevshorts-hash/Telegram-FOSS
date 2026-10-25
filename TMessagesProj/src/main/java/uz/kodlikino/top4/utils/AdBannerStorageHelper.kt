package uz.kodlikino.top4.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Admin reklama bannerining rasmini `ad_banners/` ga yuklaydi.
 *
 * `storage.rules`da bu yo'l faqat admin (`admins/{uid}`) uchun ochiq, shuning
 * uchun yuklashdan oldin Firebase Auth sessiyasi bo'lishi shart —
 * [KinoAdminAuth.claimSession] buni ta'minlaydi.
 *
 * Yuklash [ClassifiedAdStorageHelper] bilan bir xil naqshda: avval
 * [YuzImageCompressor] siqadi, keyin [YuzUploadWatchdog] bilan kuzatiladi va
 * 3 martagacha urinadi. Rasm URL'i Firestore'dagi `imageUrl` maydoniga yoziladi.
 */
object AdBannerStorageHelper {

    private const val TAG = "AdBannerStorage"
    private const val ATTEMPTS = 3
    private const val PATH = "ad_banners"

    private val storage = FirebaseStorage.getInstance()

    /**
     * @param onProgress yuklash foizi (0–100).
     * @return muvaffaqiyatda `downloadUrl`, aks holda foydalanuvchiga ko'rsatishga
     *         tayyor matnli [Result].
     */
    suspend fun uploadImage(
        context: Context,
        uri: Uri,
        onProgress: (Int) -> Unit = {}
    ): Result<String> {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return Result.failure(
                IllegalStateException("Admin sessiyasi yo'q — qayta kiring")
            )
        val appContext = context.applicationContext

        var compressed: File? = null
        try {
            compressed = withContext(Dispatchers.IO) {
                YuzImageCompressor.compressForUpload(appContext, uri)
            }
            val payload = compressed?.let { Uri.fromFile(it) } ?: uri
            val mime = if (compressed != null) "image/jpeg"
            else appContext.contentResolver.getType(uri)
                ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            val metadata = StorageMetadata.Builder()
                .setContentType(mime)
                .setCacheControl("public, max-age=31536000")
                .build()
            val ref = storage.reference
                .child("$PATH/ad_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg")

            var lastError: Exception? = null
            for (attempt in 1..ATTEMPTS) {
                try {
                    YuzUploadWatchdog.awaitWithStall(
                        ref.putFile(payload, metadata),
                        "Reklama rasmi"
                    ) { snap ->
                        val total = snap.totalByteCount.coerceAtLeast(1L)
                        val pct = ((snap.bytesTransferred * 100) / total).toInt()
                        onProgress(pct.coerceIn(0, 100))
                    }
                    val url = ref.downloadUrl.await().toString()
                    onProgress(100)
                    Log.i(TAG, "yuklandi: uid=${user.uid} ${compressed?.length() ?: -1L} B")
                    return Result.success(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    val message = e.message.orEmpty()
                    if (message.contains("permission", true) ||
                        message.contains("unauthorized", true)
                    ) {
                        return Result.failure(
                            IllegalStateException(
                                "Storage ruxsati rad etildi — admin sessiyasi faolligini tekshiring"
                            )
                        )
                    }
                    Log.w(TAG, "urinish $attempt: ${e.message}")
                    if (attempt < ATTEMPTS) delay(1500L * attempt)
                }
            }
            return Result.failure(lastError ?: IllegalStateException("Rasm yuklanmadi"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "uploadImage: ${e.message}", e)
            return Result.failure(e)
        } finally {
            YuzImageCompressor.deleteQuietly(compressed)
        }
    }

    /**
     * Banner o'chirilganda yoki rasmi almashtirilganda eski faylni Storage'dan
     * tozalaydi. Tashqi (bizga tegishli bo'lmagan) URL'lar e'tiborsiz qoldiriladi.
     */
    suspend fun deleteByUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        val isBannerFile = trimmed.contains("/$PATH/") || trimmed.contains("/$PATH%2F")
        if (!isBannerFile) return
        try {
            storage.getReferenceFromUrl(trimmed).delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "deleteByUrl: ${e.message}")
        }
    }
}
