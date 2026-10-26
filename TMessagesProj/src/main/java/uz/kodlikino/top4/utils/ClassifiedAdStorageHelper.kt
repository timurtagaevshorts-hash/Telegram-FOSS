package uz.kodlikino.top4.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Elon rasmlarini Cloud Storage'ga yuklaydi (`classified_ads/{uid}/{adId}_{i}.jpg`).
 *
 * Tezlik (2026-10-26 "juda sekin" shikoyati): avval rasmlar KETMA-KET
 * siqilib, keyin ketma-ket yuklanardi — 5 rasm sekin tarmoqda daqiqalab
 * cho'zilardi. Endi siqish (2 tadan) va yuklash (3 tadan) parallel: siqilgan
 * rasm darhol yuklashga ketadi, keyingisi shu payt siqiladi. Umumiy vaqt
 * deyarli rasm soniga bo'linadi, xotira esa chegara bilan himoyalangan.
 *
 * [YuzUploadWatchdog] + urinishlar saqlanadi: bitta rasm tutaqib qolsa butun
 * e'lon abadiy "yuklanmoqda" holatida qolmaydi. Ruxsat xatosi (qoidalar/15 MB
 * chegara) darhol foydalanuvchi tilidagi xabarga aylanadi.
 */
object ClassifiedAdStorageHelper {

    private const val TAG = "ClassifiedAdStorage"
    private const val UPLOAD_ATTEMPTS = 3
    private const val MAX_PARALLEL_COMPRESS = 2
    private const val MAX_PARALLEL_UPLOAD = 3

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
        val total = files.size
        val results = arrayOfNulls<String>(total)
        val fractions = AtomicIntegerArray(total)
        val completed = AtomicInteger(0)

        fun report(index: Int, percent: Int) {
            fractions.set(index, percent.coerceIn(0, 100))
            var sum = 0
            for (i in 0 until total) sum += fractions.get(i)
            onProgress(sum / total, completed.get(), total)
        }

        coroutineScope {
            val compressSem = Semaphore(MAX_PARALLEL_COMPRESS)
            val uploadSem = Semaphore(MAX_PARALLEL_UPLOAD)
            files.forEachIndexed { index, uri ->
                launch(Dispatchers.IO) {
                    val compressed: File? = compressSem.withPermit {
                        YuzImageCompressor.compressForUpload(appContext, uri)
                    }
                    try {
                        val ref = storage.reference
                            .child("classified_ads/$uid/${adId}_$index.jpg")
                        val metadata = StorageMetadata.Builder()
                            .setContentType("image/jpeg")
                            .setCacheControl("public, max-age=31536000")
                            .build()
                        val payload = compressed?.let { Uri.fromFile(it) } ?: uri
                        var lastError: Exception? = null
                        var uploaded = false
                        for (attempt in 1..UPLOAD_ATTEMPTS) {
                            try {
                                uploadSem.withPermit {
                                    YuzUploadWatchdog.awaitWithStall(
                                        ref.putFile(payload, metadata),
                                        "Rasm ${index + 1}"
                                    ) { snap ->
                                        val bytes = snap.totalByteCount.coerceAtLeast(1L)
                                        val fraction =
                                            (snap.bytesTransferred.toFloat() / bytes)
                                                .coerceIn(0f, 1f)
                                        report(index, (fraction * 100).toInt())
                                    }
                                }
                                results[index] = ref.downloadUrl.await().toString()
                                uploaded = true
                                break
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                lastError = e
                                val message = e.message.orEmpty()
                                if (message.contains("permission", true) ||
                                    message.contains("unauthorized", true)
                                ) {
                                    throw IllegalStateException(
                                        "Firebase Storage ruxsati rad etildi. Sabab: " +
                                            "storage.rules yoyilmagan yoki rasm 15 MB dan " +
                                            "katta (qoida chegarasi)."
                                    )
                                }
                                Log.w(TAG, "rasm $index urinish $attempt: ${e.message}")
                                if (attempt < UPLOAD_ATTEMPTS) delay(1500L * attempt)
                            }
                        }
                        if (!uploaded) {
                            throw IllegalStateException(
                                "${index + 1}-rasm yuklanmadi ($UPLOAD_ATTEMPTS marta " +
                                    "urinish). Internet sekin — mobil ma'lumotga o'tib " +
                                    "qayta urinib ko'ring." +
                                    lastError?.message?.let { " ($it)" }.orEmpty()
                            )
                        }
                        completed.incrementAndGet()
                        report(index, 100)
                    } finally {
                        YuzImageCompressor.deleteQuietly(compressed)
                    }
                }
            }
        }
        return results.filterNotNull()
    }

    suspend fun deleteImages(urls: List<String>) {
        for (url in urls) {
            val trimmed = url.trim()
            if (trimmed.isBlank()) continue
            try {
                storage.getReferenceFromUrl(trimmed).delete().await()
            } catch (_: Exception) {
            }
        }
    }
}
