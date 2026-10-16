package uz.kodlikino.top4.utils

import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

/**
 * Reel videosini Cloud Storage'ga yuklaydi (`user_reels/{uid}/{file}`).
 * KinoTOP4'dagi `ReelStorageHelper` yo'li bilan bir xil — shuning uchun eski
 * foydalanuvchi reel'lariga mos keladi. Auth sessiyasi [YuzGramAdsAuth] orqali
 * `tg_<id>` uid'ni ta'minlaydi (Storage qoidasi `user_reels/{uid}` owner yozuvi).
 *
 * "Reel yuklab bo'lmayapti" shikoyatiga javob (2026-10-16):
 *  - [UPLOAD_ATTEMPTS] urinish + [STALL_MS] **to'xtab qolish kuzatuvchisi**.
 *    Oldin `await()` cheksiz kutardi: internet sekinlashsa yoki upload tutaqib
 *    qolsa ekran "yuklanmoqda"da qolib ketardi. Endi har [WATCHDOG_TICK_MS] ms da
 *    jo'natilgan baytlar tekshiriladi — [STALL_MS] davomida 1 bayt ham ketmasa
 *    task bekor qilinadi va qaytadan urinish boshlanadi.
 *  - [onProgress] foizdan tashqari jo'natilgan/umumiy baytlarni ham beradi,
 *    shunda ekranda "34% · 12.4 / 36.0 MB" ko'rinadi — jarayon tirikmi, aniq.
 *  - `contentType` fayl cho'zzisiga qarab tanlanadi (Storage qoidasi
 *    `contentType().split('/')[0] == 'video'` talab qiladi).
 */
object ReelStorageHelper {

    private const val TAG = "ReelStorageHelper"
    private const val UPLOAD_ATTEMPTS = 3

    private val storage = FirebaseStorage.getInstance()

    /**
     * @return yuklangan videoning olinadigan (download) URL'i.
     * @throws IllegalStateException matni foydalanuvchiga ko'rsatishga tayyor.
     */
    suspend fun uploadVideo(
        uid: String,
        fileName: String,
        uri: Uri,
        onProgress: (percent: Int, transferred: Long, total: Long) -> Unit = { _, _, _ -> }
    ): String {
        if (uid.isBlank() || fileName.isBlank()) {
            throw IllegalStateException("Reel yuklash uchun uid/fileName kerak")
        }
        val user = FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("Avval Telegram orqali kiring")
        if (user.uid != uid) throw IllegalStateException("Foydalanuvchi tasdiqlanmadi")

        val ref = storage.reference.child("user_reels/$uid/$fileName")
        val metadata = StorageMetadata.Builder()
            .setContentType(videoContentType(fileName))
            .build()

        var lastError: Exception? = null
        for (attempt in 1..UPLOAD_ATTEMPTS) {
            try {
                val task = ref.putFile(uri, metadata)
                awaitWithStallWatchdog(task, attempt) { snap ->
                    val total = snap.totalByteCount.coerceAtLeast(1L)
                    onProgress(
                        ((snap.bytesTransferred * 100) / total).toInt().coerceIn(0, 100),
                        snap.bytesTransferred,
                        snap.totalByteCount
                    )
                }
                return ref.downloadUrl.await().toString()
            } catch (e: Exception) {
                lastError = e
                val message = e.message.orEmpty()
                if (message.contains("permission", true) ||
                    message.contains("unauthorized", true)
                ) {
                    // Ruxsat xatosini takrorlab bo'lmaydi — darhol bildiramiz.
                    throw IllegalStateException(
                        "Firebase Storage ruxsati yo'q. Firebase Console → Storage → Rules " +
                            "bo'limiga storage.rules qoidalarini Publish qiling."
                    )
                }
                Log.w(TAG, "attempt $attempt/$UPLOAD_ATTEMPTS: ${e.message}")
                if (attempt < UPLOAD_ATTEMPTS) delay(1500L * attempt)
            }
        }
        throw IllegalStateException(
            "Video yuklanmadi (${UPLOAD_ATTEMPTS} marta urinish). Internet sekin yoki " +
                "uzilib qoldi — mobil ma'lumotga o'tib qayta urinib ko'ring." +
                lastError?.message?.let { " ($it)" }.orEmpty()
        )
    }

    /**
     * [task] tugaguncha kutadi; [STALL_MS] davomida birorta bayt jo'natilmasa
     * task'ni bekor qilib [IllegalStateException] bilan qaytadi.
     */
    private suspend fun awaitWithStallWatchdog(
        task: UploadTask,
        attempt: Int,
        onSnapshot: (UploadTask.TaskSnapshot) -> Unit
    ) = YuzUploadWatchdog.awaitWithStall(task, "Video (urinish $attempt)", onSnapshot)

    /**
     * Reel videosini Storage'dan o'chiradi (`user_reels/{uid}/{file}`).
     * Fayl nomi [videoUrl]'dan olinadi — eski reel'larda nom boshqacha bo'lsa
     * jim o'tamiz (Firestore yozuvi o'chirilgan bo'lsa ham ishlayveradi).
     */
    suspend fun deleteVideo(uid: String, videoUrl: String) {
        if (uid.isBlank() || videoUrl.isBlank()) return
        val name = try {
            Uri.decode(videoUrl.substringBefore('?').substringAfterLast('/'))
        } catch (_: Exception) {
            ""
        }
        if (name.isBlank() || !name.contains('.')) return
        try {
            storage.reference.child("user_reels/$uid/$name").delete().await()
        } catch (e: Exception) {
            Log.w(TAG, "deleteVideo $name: ${e.message}")
        }
    }

    /** Storage qoidasi contentType "video" turidan bo'lishini talab qiladi. */
    private fun videoContentType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "webm" -> "video/webm"
            else -> "video/mp4"
        }
    }
}
