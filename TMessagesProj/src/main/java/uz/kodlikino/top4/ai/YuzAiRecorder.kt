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
 *
 * Push-to-talk: tugma bosilganda [prepare], qo'yib yuborilganda [finish] —
 * yozuv jimlikni kutmasdan darhol yakunlanadi.
 */
class YuzAiRecorder(
    /** Xom RMS darajasi (0..1), orb animatsiyasi uchun — IO thread'idan keladi. */
    private val onLevel: ((Float) -> Unit)? = null
) {

    @Volatile private var cancelled = false
    @Volatile private var cancelRequested = false
    @Volatile private var finishRequested = false
    @Volatile private var recording = false

    fun isRecording(): Boolean = recording

    /** Foydalanuvchi mikrofonni o'zi to'xtatganmi (jimlikdan farqlash uchun). */
    fun wasCancelled(): Boolean = cancelRequested

    /** Yangi sessiya boshlanishidan OLDIN chaqiriladi — eski bayroqlar qolmaydi. */
    fun prepare() {
        cancelled = false
        cancelRequested = false
        finishRequested = false
    }

    /** Push-to-talk: qo'yib yuborilganda yozuvni bekor qilmasdan yakunlaydi. */
    fun finish() {
        finishRequested = true
    }

    fun cancel() {
        cancelled = true
        cancelRequested = true
    }

    /** Blocking — faqat IO thread'ida chaqiriladi. */
    fun recordBlocking(): ByteArray? {
        if (cancelled || finishRequested) return null
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
        val filtered = ShortArray(320)
        var voiceSeen = false
        var levelTick = 0
        // 80 Hz high-pass: musiqa basi va xona g'uvillashi nutqni bosmasin.
        val hpR = 0.968f
        var hpPrevIn = 0f
        var hpPrevOut = 0f
        // Shovqin polasi asta moslashadi (pastga tez, yuqoriga sekin).
        var noiseFloor = -1.0
        try {
            rec.startRecording()
            recording = true
            var startedAt = 0L
            var lastVoiceAt = 0L
            while (!cancelled && !finishRequested) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) break
                val now = System.currentTimeMillis()
                if (startedAt == 0L) {
                    startedAt = now
                    lastVoiceAt = now
                }
                var acc = 0.0
                for (i in 0 until n) {
                    val x = chunk[i].toFloat()
                    val y = hpR * (hpPrevOut + x - hpPrevIn)
                    hpPrevIn = x
                    hpPrevOut = y
                    var s = y.toInt()
                    if (s > 32767) s = 32767 else if (s < -32768) s = -32768
                    filtered[i] = s.toShort()
                    acc += (s.toDouble() * s)
                }
                val rms = sqrt(acc / n)
                if (noiseFloor < 0) noiseFloor = minOf(rms, 220.0)
                val level = ((rms - noiseFloor) / 5000.0).coerceIn(0.0, 1.0)
                if (++levelTick % 3 == 0) {
                    onLevel?.invoke(level.toFloat())
                }
                val totalMs = now - startedAt
                // Polga NISBATAN chegara: shovqinli/musiqali joyda ham
                // "salom" kabi past ovozli so'z nutq sifatida qabul qilinadi.
                val threshold = maxOf(280.0, noiseFloor * 2.2)
                if (rms > threshold) {
                    voiceSeen = true
                    lastVoiceAt = now
                } else {
                    noiseFloor = if (rms < noiseFloor) {
                        noiseFloor + (rms - noiseFloor) * 0.35
                    } else {
                        noiseFloor + (rms - noiseFloor) * 0.02
                    }.coerceAtMost(2500.0)
                }
                // 4500 -> 3800 ms: signaldan keyin o'ylanib gapirsa ham
                // yozuv yopilib qolmaydi, lekin jim bosish tezroq tugaydi.
                if (!voiceSeen && totalMs > 3800L) break
                // 550 -> 400 ms: so'z orasidagi pauzalar saqlanadi, buyruq
                // tugagach javob ~0.15 s tezroq boshlanadi.
                if (voiceSeen && now - lastVoiceAt > 400L) break
                if (totalMs > 6500L) break
                for (i in 0 until n) {
                    val v = filtered[i].toInt()
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
        val bytes = normalize(pcm.toByteArray())
        if (bytes.size < 3200) return null // <0.1 s — keraksiz
        return wavOf(bytes, rate)
    }

    /**
     * Jimroq gapirilgan yozuvni ko'taradi (faqat kuchaytirish, bosilmaydi) —
     * shovqinli joyda STT'ga balandroq va toza PCM boradi.
     */
    private fun normalize(pcm: ByteArray): ByteArray {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i].toInt() and 0xFF) or
                ((pcm[i + 1].toInt() and 0xFF) shl 8)).toShort().toInt()
            val abs = if (s < 0) -s else s
            if (abs > peak) peak = abs
            i += 2
        }
        if (peak <= 0 || peak >= 18000) return pcm
        val gain = minOf(3.5, 16000.0 / peak).toFloat()
        if (gain < 1.25f) return pcm
        val out = ByteArray(pcm.size)
        i = 0
        while (i + 1 < pcm.size) {
            var s = (((pcm[i].toInt() and 0xFF) or
                ((pcm[i + 1].toInt() and 0xFF) shl 8)).toShort().toInt() * gain).toInt()
            if (s > 32767) s = 32767 else if (s < -32768) s = -32768
            out[i] = (s and 0xFF).toByte()
            out[i + 1] = ((s shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
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
