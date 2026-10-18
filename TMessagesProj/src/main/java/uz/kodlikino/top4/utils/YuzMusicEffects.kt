package uz.kodlikino.top4.utils

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * YuzGram #62: pleyer ovozini sozlash — Ekvalayzer presetlari (Bass, Jazz, Rock,
 * Vocal) + qo'shimcha bas kuchi. ExoPlayer'ning audio session'iga ulanadi;
 * qurilmada effect bo'lmasa jim skip qilinadi.
 */
class YuzMusicEffects(private val context: Context) {

    companion object {
        const val PRESET_FLAT = 0
        const val PRESET_BASS = 1
        const val PRESET_JAZZ = 2
        const val PRESET_ROCK = 3
        const val PRESET_VOCAL = 4
        const val PRESET_COUNT = 5

        private const val PREFS = "yuz_music_eq"

        fun presetName(preset: Int): String = when (preset) {
            PRESET_FLAT -> "Flat"
            PRESET_BASS -> "Bass"
            PRESET_JAZZ -> "Jazz"
            PRESET_ROCK -> "Rock"
            PRESET_VOCAL -> "Vocal"
            else -> "Flat"
        }

        /**
         * Chastota (mHz) bo'yicha nisbiy kuchaytirish, dB. Har bir preset o'z
         * egri chizig'i bilan — band soni qanday bo'lishidan qat'i noldan ishlaydi.
         */
        private fun curveDb(preset: Int, freqMhz: Double): Double {
            val f = max(20.0, freqMhz / 1000.0) // Hz
            fun shelf(low: Double, high: Double, boost: Double): Double {
                // past chastota => +boost, yuqori => 0
                return if (f <= low) boost else if (f >= high) 0.0
                else boost * (high - f) / (high - low)
            }
            fun bell(center: Double, q: Double, boost: Double): Double {
                val d = (log10(f) - log10(center)) * q
                return boost / (1.0 + d * d)
            }
            return when (preset) {
                PRESET_BASS -> shelf(60.0, 250.0, 8.0) + bell(45.0, 1.2, 4.0) - bell(8000.0, 1.2, 2.0)
                PRESET_JAZZ -> bell(120.0, 1.1, 3.0) + bell(3500.0, 1.1, 3.0) - shelf(9000.0, 14000.0, 2.0)
                PRESET_ROCK -> shelf(50.0, 200.0, 6.0) + bell(300.0, 1.3, -4.0) + bell(4000.0, 1.2, 5.0) + shelf(8000.0, 12000.0, 3.0)
                PRESET_VOCAL -> shelf(40.0, 180.0, -5.0) + bell(2500.0, 1.0, 5.0) + bell(4000.0, 1.2, 3.0)
                else -> 0.0
            }
        }
    }

    var preset = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("preset", PRESET_FLAT)
        private set
    var bassStrength = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt("bass", 0) // 0..100
        private set

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var sessionId = -1

    val hasEqualizer get() = eq != null
    val hasBassBoost get() = bass != null

    /** Player session ID o'zgarganda qayta ulanadi; bir xil bo'lsa hech narsa qilmaydi. */
    fun ensureSession(newSessionId: Int) {
        if (newSessionId <= 0 || newSessionId == sessionId) return
        release()
        sessionId = newSessionId
        try {
            // API 31+ konstruktor priority parametri int (short versiyasi olib
            // tashlangan); eski qurilmalarda try/catch jim ushlaydi.
            val e = Equalizer(0, newSessionId)
            e.enabled = true
            eq = e
        } catch (_: Exception) {
        }
        try {
            val b = BassBoost(0, newSessionId)
            b.enabled = true
            applyBass(b)
            bass = b
        } catch (_: Exception) {
        }
        applyPreset()
    }

    fun setPreset(value: Int) {
        preset = value.coerceIn(0, PRESET_COUNT - 1)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("preset", preset).apply()
        applyPreset()
    }

    fun setBass(value: Int) {
        bassStrength = value.coerceIn(0, 100)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("bass", bassStrength).apply()
        bass?.let { applyBass(it) }
    }

    private fun applyBass(b: BassBoost) {
        // Compile stub'da eski setBassBoostStrength(short) yo'q, yangi
        //yangi setStrength(int) ham har xil SDK'da farq qiladi — ikkalasini
        //refleksiya bilan, ketma-ket uramiz (ximichan, compile-xatosiz).
        try {
            b.javaClass.getMethod("setStrength", Int::class.javaPrimitiveType)
                .invoke(b, bassStrength * 10) // 0..1000
        } catch (_: Exception) {
            try {
                b.javaClass.getMethod("setBassBoostStrength", Short::class.javaPrimitiveType)
                    .invoke(b, (bassStrength * 10).toShort())
            } catch (_: Exception) {
            }
        }
    }

    private fun applyPreset() {
        val e = eq ?: return
        try {
            val range = e.bandLevelRange // [min, max] millibel
            val lo = range[0].toInt()
            val hi = range[1].toInt()
            val mid = (lo + hi) / 2f
            val span = (hi - lo) / 2f
            for (band in 0 until e.numberOfBands.toInt()) {
                val fr = e.getBandFreqRange(band.toShort())
                val centerMhz = (fr[0] + fr[1]) / 2.0
                val db = curveDb(preset, centerMhz)
                // ±12 dB lik uchani diapazonning 80% igacha kuchamiz
                val level = (mid + span * 0.8 * (db / 12.0)).toInt()
                e.setBandLevel(band.toShort(), max(lo, min(hi, level)).toShort())
            }
        } catch (_: Exception) {
        }
    }

    fun release() {
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        eq = null
        bass = null
        sessionId = -1
    }
}
