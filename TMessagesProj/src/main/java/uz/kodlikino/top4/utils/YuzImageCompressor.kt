package uz.kodlikino.top4.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Tanlangan rasmni yuklashdan oldin siqadi (o'lcham + JPEG sifati).
 *
 * Nima uchun kerak bo'ldi (2026-10-16, "elon yuklab bo'lmayapti" shikoyati):
 *  - zamonaviy telefon kamerasi 5–15 MB li JPEG beradi; 5 rasm ~40 MB qiladi.
 *    Mobil internetda bunday yuklash daqiqalab davom etadi va stall bo'ladi;
 *  - Firebase Storage qoidalari (`storage.rules`, `classified_ads/{uid}/{file}`)
 *    faqat 15 MB dan kichik rasmga ruxsat beradi — kattaroq fayl rad etiladi va
 *    xato "permission" ko'rinishida chiqqani uchun tarmoq/ruxsat muammosidek
 *    tuyuladi.
 *
 * Siqilgan rasm ~0.5–1.2 MB: yuklash bir necha soniyada tugaydi va 15 MB
 * chegarasiga hech qachon urilmaydi.
 *
 * EXIF muhimi: qayta kodlash EXIF'ni yo'qotadi, shuning uchun burilish
 * (rotation) avval Bitmap'ga qo'llanadi — aks holda portretdagi rasmlar yon
 * burchakda ko'rinardi.
 *
 * Har qanday xatoda `null` qaytadi — chaqiruvchi tomon avvalgi kabi original
 * Uri'ni yuklaydi. Ya'ni siqish yuklashni hech qachon buzmaydi.
 */
object YuzImageCompressor {

    private const val TAG = "YuzImageCompressor"

    /** Yuklashdan oldin saqlanadigan eng katta tomon (px). */
    private const val MAX_LONG_EDGE = 1600

    /** Maqsadli hajm (bayt); kattaroq bo'lsa JPEG sifati pasaytiriladi. */
    private const val TARGET_BYTES = 1_200_000

    /** Shundan kichik fayllarni qayta kodlamaymiz — tezroq va sifat buzilmaydi. */
    private const val PASS_THROUGH_BYTES = 900_000L

    private const val MIN_QUALITY = 46

    /**
     * @return siqilgan vaqtinchalik JPEG (context.cacheDir'da) yoki `null` —
     *         rasm allaqachon kichik/siqib bo'lmasa original ishlatiladi.
     */
    fun compressForUpload(context: Context, uri: Uri): File? {
        var decoded: Bitmap? = null
        var result: Bitmap? = null
        try {
            val resolver = context.contentResolver

            val sourceBytes = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (sourceBytes in 0L..PASS_THROUGH_BYTES) return null

            // 1) Faqat o'lchamni o'qib inSampleSize tanlaymiz (katta bitmapni
            //    butunlay decodelashdan oldin xotirani tejash uchun).
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            val rotation = readExifRotation(context, uri)
            // 90/270 gradusda kenglik/balandlik o'rin almashadi, lekin eng katta
            // tomon o'zgarmaydi — shuning uchun ikkala holatda ham bitta qiymat.
            val longEdge = maxOf(srcW, srcH)
            if (rotation == 0 && longEdge <= MAX_LONG_EDGE && sourceBytes <= PASS_THROUGH_BYTES * 3) {
                return null
            }

            var sample = 1
            while (longEdge / (sample * 2) >= MAX_LONG_EDGE) sample *= 2
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sample
                // RGB_565 = 2x kam xotira; e'lon rasmi uchun sifati yetarli.
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            decoded = resolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            val src = decoded ?: return null

            // 2) Burilish + sample yetmasa aniq masshtab (bitta Matrix'da).
            val scale = (MAX_LONG_EDGE.toFloat() * sample) / longEdge
            result = if (rotation == 0 && scale >= 0.98f) {
                src
            } else {
                val matrix = Matrix()
                if (rotation != 0) matrix.postRotate(rotation.toFloat())
                if (scale < 0.98f) {
                    val clamped = if (scale < 0.2f) 0.2f else scale
                    matrix.postScale(clamped, clamped)
                }
                val transformed =
                    Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
                if (transformed !== src) src.recycle()
                transformed
            }
            val bitmap = result ?: return null

            // 3) Maqsadli hajmgacha sifatni pasaytiramiz.
            var quality = 82
            var bytes = ByteArray(0)
            while (true) {
                val bos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                bytes = bos.toByteArray()
                if (bytes.size <= TARGET_BYTES || quality <= MIN_QUALITY) break
                quality -= 12
            }

            val file = File.createTempFile("yuz_upload_", ".jpg", context.cacheDir)
            FileOutputStream(file).use { it.write(bytes) }
            Log.i(
                TAG,
                "${sourceBytes / 1024}KB -> ${bytes.size / 1024}KB " +
                    "(${bitmap.width}x${bitmap.height}, q=$quality, rot=$rotation)"
            )
            return file
        } catch (e: Exception) {
            Log.w(TAG, "siqish yiqildi, original yuklanadi: ${e.message}")
            return null
        } finally {
            // result === decoded bo'lgan holatda ikki marta recycle qilamiz —
            // Bitmap.recycle() idempotent, shuning uchun xavfsiz.
            try {
                result?.recycle()
            } catch (_: Exception) {
            }
            try {
                decoded?.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /** Yuklash (muvaffaqiyatli yoki oxirgi urinish) tugagach vaqtinchalik faylni o'chiradi. */
    fun deleteQuietly(file: File?) {
        try {
            if (file != null && file.exists()) file.delete()
        } catch (_: Exception) {
        }
    }

    /**
     * EXIF orientatsiyasini gradusga aylantiradi. [ExifInterface]'ning
     * InputStream konstruktori API 24+ da bor — past versiyalarda 0 qaytamiz
     * (rasm burilishsiz yuklanadi, xato yuz bermaydi).
     */
    private fun readExifRotation(context: Context, uri: Uri): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return 0
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_TRANSPOSE -> 90
                    ExifInterface.ORIENTATION_ROTATE_180,
                    ExifInterface.ORIENTATION_TRANSVERSE -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
