package uz.kodlikino.top4.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.telegram.messenger.MediaController
import org.telegram.messenger.VideoEditedInfo
import org.telegram.messenger.video.MediaCodecVideoConvertor
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reel videosini yuklashdan OLDIN 720p H.264 ga siqadi.
 *
 * Muammo (2026-10-22): telefon videosi 40–50 MB bo'lsa, sekin mobil internetda
 * (75 KB/s) Firebase Storage'ga yuklash 10+ daqiqa davom etadi va foydalanuvchi
 * "yuklanmayapti" deb qabul qiladi. Telegram'ning o'z native konvertori
 * ([MediaCodecVideoConvertor], hardware MediaCodec) bilan fayl ~3–5 barobar
 * kichrayadi — yuklash shuncha tez tugaydi, sifat esa reel uchun yetarli.
 *
 * Siqish faqat [SKIP_BELOW_BYTES] dan katta fayllarda bajariladi. Har qanday
 * xato (noto'g'ri codec, metadata yo'q, bekor qilish) — `null`: chaqiruvchi
 * asl faylni yuklaydi, ya'ni publish hech qachon siqish tufayli buzilmaydi.
 */
object YuzReelCompressor {

    private const val TAG = "YuzReelCompressor"

    /** Bundan kichik fayl darhol yuklanadi — siqish vaqti tejaladi. */
    const val SKIP_BELOW_BYTES = 9L * 1024L * 1024L

    private const val MAX_LONG_SIDE = 1280
    private const val MAX_SHORT_SIDE = 720
    private const val DEFAULT_FPS = 30

    /**
     * @param sizeBytes tanlangan video hajmi (<=0 bo'lsa siqishga urinamiz).
     * @param targetBytes maqsad hajm — bitrate shundan hisoblanadi (12/24 MB).
     * @param onProgress 0..100, konvertor thread'idan chaqiriladi — UI'ga
     *   o'tkazish chaqiruvchi zimmasida.
     * @param isCanceled ekran yopilganda true — konvertor xavfsiz to'xtaydi.
     * @return siqilgan cache fayli yoki `null` (asl faylni yuklash kerak).
     */
    suspend fun compress(
        context: Context,
        uri: Uri,
        sizeBytes: Long,
        targetBytes: Long,
        onProgress: (Int) -> Unit,
        isCanceled: () -> Boolean
    ): File? = withContext(Dispatchers.IO) {
        if (sizeBytes in 1 until SKIP_BELOW_BYTES) return@withContext null
        var source: File? = null
        var dest: File? = null
        try {
            source = copyToCache(context, uri)
            val meta = readMetadata(source) ?: return@withContext null
            if (meta.width <= 0 || meta.height <= 0 || meta.durationMs <= 0) {
                return@withContext null
            }
            dest = File(context.cacheDir, "reel_out_${System.currentTimeMillis()}.mp4")
            val (outWidth, outHeight) = targetSize(meta)
            val outBitrate = targetBitrate(meta, targetBytes)
            val info = VideoEditedInfo().apply {
                originalWidth = meta.width
                originalHeight = meta.height
                rotationValue = meta.rotation
                originalBitrate = meta.bitrate
                resultWidth = outWidth
                resultHeight = outHeight
                bitrate = outBitrate
                framerate = meta.fps.coerceIn(15, DEFAULT_FPS)
                originalDuration = meta.durationMs
                startTime = -1
                endTime = -1
                avatarStartTime = -1
                isPhoto = false
                // isStory=true bo'lsa konvertor HEVC tanlaydi — H.264 kerak.
                isStory = false
                muted = false
                volume = 1f
            }
            var lastPercent = -1
            val listener = object : MediaController.VideoConvertorListener {
                override fun checkConversionCanceled(): Boolean = isCanceled()

                override fun didWriteData(availableSize: Long, progress: Float) {
                    val pct = (progress * 100f).roundToInt().coerceIn(0, 100)
                    if (pct != lastPercent) {
                        lastPercent = pct
                        onProgress(pct)
                    }
                }
            }
            val params = MediaCodecVideoConvertor.ConvertVideoParams.of(
                source.absolutePath, dest,
                meta.rotation, false,
                meta.width, meta.height,
                outWidth, outHeight,
                info.framerate, outBitrate, meta.bitrate,
                -1L, -1L, -1L,
                true, meta.durationMs,
                listener, info
            )
            val failed = MediaCodecVideoConvertor().convertVideo(params)
            if (failed || isCanceled() || dest == null || !dest.isFile || dest.length() <= 0L) {
                try {
                    dest?.delete()
                } catch (_: Exception) {
                }
                return@withContext null
            }
            Log.i(TAG, "compressed ${source.length()} -> ${dest.length()} bytes")
            dest
        } catch (e: Exception) {
            Log.w(TAG, "compress: ${e.message}")
            try {
                dest?.delete()
            } catch (_: Exception) {
            }
            null
        } finally {
            try {
                source?.delete()
            } catch (_: Exception) {
            }
        }
    }

    private class Meta(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val durationMs: Long,
        val bitrate: Int,
        val fps: Int
    )

    private fun readMetadata(file: File): Meta? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull() ?: 0
            val fps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()?.toInt()?.takeIf { it in 15..120 } ?: DEFAULT_FPS
            Meta(width, height, rotation, durationMs, bitrate, fps)
        } catch (e: Exception) {
            Log.w(TAG, "metadata: ${e.message}")
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Natija o'lchami: ko'rsatiladigan (rotation qo'llangan) tomonda qisqa
     * tomon <= 720, uzun tomon <= 1280; kichik videoni kattalashtirmaymiz.
     * Parametrlar esa ENCODED orientatsiyada bo'lishi kerak (Telegram ham
     * 90/270 da qaytarib almashtiradi).
     */
    private fun targetSize(meta: Meta): Pair<Int, Int> {
        val swap = meta.rotation == 90 || meta.rotation == 270
        val displayW = if (swap) meta.height else meta.width
        val displayH = if (swap) meta.width else meta.height
        if (displayW <= 0 || displayH <= 0) return meta.width to meta.height
        val longSide = maxOf(displayW, displayH)
        val shortSide = minOf(displayW, displayH)
        val scale = min(
            1f,
            min(MAX_LONG_SIDE.toFloat() / longSide, MAX_SHORT_SIDE.toFloat() / shortSide)
        )
        var w = (displayW * scale).roundToInt().coerceAtLeast(2)
        var h = (displayH * scale).roundToInt().coerceAtLeast(2)
        w -= w % 2
        h -= h % 2
        if (swap) {
            val t = w
            w = h
            h = t
        }
        return w.coerceAtLeast(2) to h.coerceAtLeast(2)
    }

    /** Maqsad hajmdan kelib chiqqan bitrate; manba bitrate'idan oshirmaymiz. */
    private fun targetBitrate(meta: Meta, targetBytes: Long): Int {
        val durationSec = (meta.durationMs / 1000.0).coerceAtLeast(1.0)
        val targetBps = (targetBytes * 8.0 / durationSec).toInt()
            .coerceIn(500_000, 2_500_000)
        return if (meta.bitrate > 0) min(targetBps, meta.bitrate.coerceAtLeast(600_000)) else targetBps
    }

    private fun copyToCache(context: Context, uri: Uri): File {
        val ext = try {
            context.contentResolver.getType(uri)?.substringAfterLast('/', "")
                ?.takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } } ?: "mp4"
        } catch (_: Exception) {
            "mp4"
        }
        val file = File(context.cacheDir, "reel_src_${System.currentTimeMillis()}.$ext")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Video ochilmadi")
        input.use { ins ->
            file.outputStream().use { out ->
                ins.copyTo(out, 256 * 1024)
            }
        }
        return file
    }
}
