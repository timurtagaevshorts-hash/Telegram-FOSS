package uz.kodlikino.top4.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Galereyaga saqlash — KinoTOP4`dagi ChatMediaSaveHelper'ning minimal,
 * modellik bog'liqsiz varianti. Faqat mahalliy video faylni MediaStore'ga
 * Movies/YuzGram papkasiga nusxalaydi.
 */
object InstagramMediaSaver {

    suspend fun saveLocalVideoFile(
        context: Context,
        file: File,
        fileName: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists() || file.length() <= 0L) {
                throw IllegalArgumentException("empty file")
            }
            val name = displayName(fileName)
            insertVideo(
                context = context,
                displayName = name,
                mimeType = "video/mp4"
            ) { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            }
        }
    }

    private fun displayName(fileName: String): String {
        val cleaned = fileName.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        // #120: non-latin nomlar tozalanaganda bo'sh qoladi — eski tekshiruv
        // ".mp4" qo'shilgandan KEYINGI qiymatga qaragani uchun hech qachon
        // bo'sh bo'lmadi va fayl shunchaki ".mp4" deb saqlanardi.
        if (cleaned.isEmpty()) return "yuzgram_video.mp4"
        val withExt = if (cleaned.endsWith(".mp4", ignoreCase = true)) cleaned else "$cleaned.mp4"
        return if (withExt.equals(".mp4", ignoreCase = true)) "yuzgram_video.mp4" else withExt
    }

    private fun insertVideo(
        context: Context,
        displayName: String,
        mimeType: String,
        write: (java.io.OutputStream) -> Unit
    ): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/YuzGram"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { write(it) }
                ?: throw IllegalStateException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val folder = File(dir, "YuzGram").apply { mkdirs() }
            val target = File(folder, displayName)
            target.outputStream().use { write(it) }
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DATA, target.absolutePath)
                put(MediaStore.Video.Media.TITLE, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            }
            val uri = resolver.insert(collection, values)
            return uri ?: Uri.fromFile(target)
        }
    }
}
