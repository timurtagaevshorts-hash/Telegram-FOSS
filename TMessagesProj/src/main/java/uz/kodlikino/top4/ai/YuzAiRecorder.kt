package uz.kodlikino.top4.ai

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Mikrofonni oddiy WAV (16 kHz mono PCM16) fayliga yozadi — Gemini STT uchun.
 * RMS-based VAD: gap boshlanib ~0.75 s jimlik bo'lsa to'xtaydi, maksimum 8 s.
 * Umuman gap bo'lmasa null qaytadi (birinchi 4 s ichida) — kvota tejash uchun.
 */
class YuzAiRecorder {

    @Volatile private var cancelled = false
    @Volatile private var recording = false

    fun isRecording(): Boolean = recording

    fun cancel() {
        cancelled = true
    }

    /** Blocking — faqat IO thread'ida chaqiriladi. */
    fun recordBlocking(): ByteArray? {
        val rate = 16000
        val minBuf = try {
            AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        } catch (_: Exception) {
            0
        }
        if (minBuf <= 0) return null
        val rec = try {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 3200)
            )
        } catch (_: Exception) {
            null
        } ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Exception) { }
            return null
        }
        val pcm = ByteArrayOutputStream()
        val chunk = ShortArray(320) // 20 ms
        var voiceSeen = false
        try {
            cancelled = false
            rec.startRecording()
            recording = true
            var startedAt = 0L
            var lastVoiceAt = 0L
            while (!cancelled) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) break
                val now = System.currentTimeMillis()
                if (startedAt == 0L) {
                    startedAt = now
                    lastVoiceAt = now
                }
                var acc = 0.0
                for (i in 0 until n) {
                    val s = chunk[i].toInt()
                    acc += (s * s).toDouble()
                }
                val rms = sqrt(acc / n)
                val totalMs = now - startedAt
                if (rms > 500.0) {
                    voiceSeen = true
                    lastVoiceAt = now
                }
                if (!voiceSeen && totalMs > 4000L) break
                // 1100 -> 750 ms: gap tugashini kutish qisqardi (so'z orasi
                // pauzalar 500 ms'dan kam, xavfsiz; ~0.35 s tezkor his).
                if (voiceSeen && now - lastVoiceAt > 750L) break
                if (totalMs > 8000L) break
                for (i in 0 until n) {
                    val v = chunk[i].toInt()
                    pcm.write((v and 0xFF))
                    pcm.write((v shr 8) and 0xFF)
                }
            }
        } catch (_: Exception) {
            // ok stays null
        } finally {
            recording = false
            try { rec.stop() } catch (_: Exception) { }
            try { rec.release() } catch (_: Exception) { }
        }
        if (!voiceSeen) return null
        val bytes = pcm.toByteArray()
        if (bytes.size < 3200) return null // <0.1 s — keraksiz
        return wavOf(bytes, rate)
    }

    private fun wavOf(pcm: ByteArray, rate: Int): ByteArray {
        val total = pcm.size
        val out = ByteArrayOutputStream(44 + total)
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray()); le32(36 + total); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(rate); le32(rate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(total)
        out.write(pcm)
        return out.toByteArray()
    }
}
