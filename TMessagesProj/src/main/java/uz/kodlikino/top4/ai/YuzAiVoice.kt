package uz.kodlikino.top4.ai

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uz.kodlikino.top4.utils.NetworkReachability
import java.io.File
import java.util.Locale

/**
 * Ovoz yordamchisi: nutqni tanish — tizim SpeechRecognizer (uz -> ru -> default),
 * javob ovozi — worker'dagi Gemini TTS. Tizim TTS ataylab ishlatilmaydi:
 * robot ovoz o'rniga yordamchi internet bo'lmasa jim qoladi.
 */
class YuzAiVoice(context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onListeningStarted()
        fun onPartial(text: String)
        /** Mikrofon darajasi (0..1) — orb animatsiyasi uchun. */
        fun onLevel(level: Float) = Unit
        fun onResult(text: String)
        fun onListenError(message: String, retry: Boolean)
        fun onListeningStopped()
        fun onSpeakFinished()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var speaking = false
    private var languageIndex = 0
    private var ignoreNextError = false
    private var released = false
    private val speakScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speakJob: Job? = null
    private var speakToken = 0
    private val readyAudio = ArrayDeque<ByteArray>()
    private var fetchDone = true
    private var playerActive = false
    private var cloudPlayer: MediaPlayer? = null
    private var cloudFile: File? = null
    /** Takroriy javoblar (salomlashuv, tasdiqlar) qayta sintez qilinmaydi. */
    private val audioCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 24
    }

    /**
     * Tizim tanishi jim qotib qolsa (natija ham, xato ham kelmasa) —
     * 12 s dan keyin sessiya yopiladi. Aks holda "tinglayapman" holati
     * abadiy qolib, tugma javob bermayotgandek ko'rinardi.
     */
    private val listenTimeout = Runnable {
        if (released || !listening) return@Runnable
        ignoreNextError = true
        listening = false
        muteRecognizerCue(false)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        callbacks.onListeningStopped()
        callbacks.onListenError("", true)
    }

    /**
     * Bulut ovozi: javob jumlalarga bo'linadi, birinchi audio kelishi bilan
     * ijro boshlanadi, qolganlari shu paytda yuklanadi. Tizim TTS'ga
     * qaytish yo'q — internet yo'q bo'lsa yordamchi jim qoladi.
     */
    fun speakCloud(text: String) {
        val spoken = speakable(text)
        if (released || spoken.isBlank()) {
            callbacks.onSpeakFinished()
            return
        }
        stopListening()
        stopSpeakingInternal()
        if (!NetworkReachability.isOnline(appContext)) {
            callbacks.onSpeakFinished()
            return
        }
        val chunks = splitForSpeech(spoken)
        val token = ++speakToken
        speaking = true
        fetchDone = false
        speakJob = speakScope.launch {
            for (chunk in chunks) {
                if (token != speakToken) return@launch
                val bytes = fetchAudio(chunk)
                if (token != speakToken) return@launch
                if (bytes == null) break
                readyAudio.addLast(bytes)
                pumpSpeaker(token)
            }
            if (token == speakToken) {
                fetchDone = true
                pumpSpeaker(token)
            }
        }
    }

    private suspend fun fetchAudio(text: String): ByteArray? {
        audioCache[text]?.let { return it }
        val bytes = withContext(Dispatchers.IO) { YuzAiCloud.speakText(text) } ?: return null
        if (bytes.size < 2000) return null
        audioCache[text] = bytes
        return bytes
    }

    private fun pumpSpeaker(token: Int) {
        if (token != speakToken || playerActive) return
        val bytes = if (readyAudio.isEmpty()) null else readyAudio.removeFirst()
        if (bytes == null) {
            if (fetchDone) finishSpeak(token)
            return
        }
        playerActive = true
        val file = try {
            File(appContext.cacheDir, "ai_tts_" + System.nanoTime() + ".wav").also { it.writeBytes(bytes) }
        } catch (_: Exception) {
            playerActive = false
            pumpSpeaker(token)
            return
        }
        val player = MediaPlayer()
        cloudPlayer = player
        cloudFile = file
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener {
                releasePlayer(player, file)
                playerActive = false
                pumpSpeaker(token)
            }
            player.setOnErrorListener { _, _, _ ->
                releasePlayer(player, file)
                playerActive = false
                pumpSpeaker(token)
                true
            }
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        } catch (_: Exception) {
            releasePlayer(player, file)
            playerActive = false
            pumpSpeaker(token)
        }
    }

    private fun releasePlayer(player: MediaPlayer, file: File) {
        try {
            player.release()
        } catch (_: Exception) {
        }
        if (cloudPlayer === player) cloudPlayer = null
        if (cloudFile === file) cloudFile = null
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    private fun finishSpeak(token: Int) {
        if (token != speakToken || !speaking) return
        speaking = false
        if (!released) callbacks.onSpeakFinished()
    }

    fun isListening(): Boolean = listening

    fun isSpeaking(): Boolean = speaking

    /**
     * #134: "endi eshitaman" signali — qisqa beep + vibratsiya.
     * Kirishda va har bir yozuv boshida chaqiriladi; ovozli salom YO'Q.
     */
    fun playReadySignal() {
        playListenSignal()
        vibrateShort()
    }

    fun canListen(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(appContext)
    } catch (_: Exception) {
        false
    }

    /**
     * [announce] = false — beep+vibratsiya chaqiruvchi tomonda allaqachon
     * berilgan (takroriy signal mikrofonga tushmasin).
     */
    fun startListening(announce: Boolean = true) {
        if (released || speaking || listening) return
        mainHandler.post {
            if (released || speaking || listening) return@post
            languageIndex = 0
            ignoreNextError = false
            if (announce) playReadySignal()
            if (!canListen() || !ensureRecognizer()) {
                callbacks.onListenError("Mikrofon mavjud emas.", false)
                return@post
            }
            startRecognizer(languageTags().first())
        }
    }

    fun stopListening() {
        mainHandler.post {
            mainHandler.removeCallbacks(listenTimeout)
            ignoreNextError = true
            listening = false
            muteRecognizerCue(false)
            try {
                recognizer?.stopListening()
                recognizer?.cancel()
            } catch (_: Exception) {
            }
            callbacks.onListeningStopped()
        }
    }

    /** Push-to-talk: yozuvni yakunlaydi, natija saqlanadi (bekor qilish emas). */
    fun finishListening() {
        mainHandler.post {
            if (released || !listening) return@post
            try {
                recognizer?.stopListening()
            } catch (_: Exception) {
            }
        }
    }

    fun stopSpeaking() {
        stopSpeakingInternal()
    }

    private fun stopSpeakingInternal() {
        speakJob?.cancel()
        speakJob = null
        speakToken += 1
        readyAudio.clear()
        fetchDone = true
        playerActive = false
        speaking = false
        cloudPlayer?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        cloudPlayer = null
        try {
            cloudFile?.delete()
        } catch (_: Exception) {
        }
        cloudFile = null
    }

    fun release() {
        released = true
        listening = false
        stopSpeakingInternal()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            listenTone?.release()
        } catch (_: Exception) {
        }
        listenTone = null
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        speakScope.coroutineContext[Job]?.cancel()
        muteRecognizerCue(false)
    }

    // #126: postDelayed tone.release() mainHandler.removeCallbacksAndMessages
    // bilan o'chirib qo'yilmasi uchun aktiv ToneGenerator maydonida ushlanadi.
    private var listenTone: ToneGenerator? = null
    // #135: muteRecognizerCue(false) har speak'da chaqiriladi — holatni
    // eslab qolmasak, foydalanuvchining bildirishnoma ovozi majburan
    // yoqilib qoladi. Endi faqat haqiqatan o'zgartirilganda tegadi.
    private var cueMuted = false
    // Foydalanuvchi bildirishnoma ovozini o'zi o'chirib qo'ygan bo'lsa,
    // tinglashdan keyin uni "qayta yoqib" yubormasligimiz kerak.
    private var cueWasSilent = false

    private fun playListenSignal() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            listenTone = tone
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            mainHandler.postDelayed({
                try {
                    tone.release()
                } catch (_: Exception) {
                } finally {
                    if (listenTone === tone) listenTone = null
                }
            }, 150)
        } catch (_: Exception) {
        }
    }

    private fun vibrateShort() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(28L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(28L)
            }
        } catch (_: Exception) {
        }
    }

    private fun muteRecognizerCue(mute: Boolean) {
        if (mute == cueMuted) return
        cueMuted = mute
        val am = try {
            appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (_: Exception) {
            null
        } ?: return
        try {
            if (mute) {
                cueWasSilent = isStreamSilent(am, AudioManager.STREAM_NOTIFICATION)
                setStreamSilent(am, true)
                // Javob kelmasa ham (xizmat jim yopildi) ovoz abadiy
                // o'chib qolmasin — ehtiyot taymeri.
                mainHandler.removeCallbacks(cueRestore)
                mainHandler.postDelayed(cueRestore, CUE_RESTORE_MS)
            } else {
                mainHandler.removeCallbacks(cueRestore)
                if (!cueWasSilent) setStreamSilent(am, false)
                cueWasSilent = false
            }
        } catch (_: Exception) {
        }
    }

    private val cueRestore = Runnable {
        if (!cueMuted) return@Runnable
        cueMuted = false
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (!cueWasSilent) setStreamSilent(am, false)
        } catch (_: Exception) {
        }
        cueWasSilent = false
    }

    private fun isStreamSilent(am: AudioManager, stream: Int): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.isStreamMute(stream)
        } else {
            @Suppress("DEPRECATION")
            am.getStreamVolume(stream) == 0
        }
    } catch (_: Exception) {
        false
    }

    private fun setStreamSilent(am: AudioManager, mute: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.adjustStreamVolume(
                AudioManager.STREAM_NOTIFICATION,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0
            )
        } else {
            @Suppress("DEPRECATION")
            am.setStreamMute(AudioManager.STREAM_NOTIFICATION, mute)
        }
    }

    private fun ensureRecognizer(): Boolean {
        if (recognizer != null) return true
        return try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(listener)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "createSpeechRecognizer: " + e.message)
            false
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            listening = true
            callbacks.onListeningStarted()
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) {
            if (!listening) return
            // RMS dB odatda -2 (jim) .. 10 (baland) — orb uchun 0..1 ga keltiramiz.
            callbacks.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            mainHandler.removeCallbacks(listenTimeout)
            muteRecognizerCue(false)
            if (ignoreNextError || released) {
                ignoreNextError = false
                listening = false
                callbacks.onListeningStopped()
                return
            }
            if (shouldRetryLanguage(error) && languageIndex + 1 < languageTags().size) {
                languageIndex += 1
                startRecognizer(languageTags()[languageIndex])
                return
            }
            listening = false
            callbacks.onListeningStopped()
            val retry = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_CLIENT
            callbacks.onListenError(errorMessage(error), retry)
        }

        override fun onResults(results: Bundle?) {
            mainHandler.removeCallbacks(listenTimeout)
            muteRecognizerCue(false)
            listening = false
            callbacks.onListeningStopped()
            val text = firstResult(results)
            if (text.isBlank()) {
                callbacks.onListenError("", true)
            } else {
                callbacks.onResult(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults)
            if (text.isNotBlank()) callbacks.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun startRecognizer(language: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // 1300/1100 -> 1000/850 ms: qisqa buyruq ("salom", "Sardorga yoz")
            // tugagach natija ~0.3 s tezroq keladi; 350 ms minimal — juda
            // erta kesib yubormaydi.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 850L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 350L)
        }
        listening = true
        callbacks.onListeningStarted()
        mainHandler.removeCallbacks(listenTimeout)
        mainHandler.postDelayed(listenTimeout, LISTEN_TIMEOUT_MS)
        try {
            muteRecognizerCue(true)
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            muteRecognizerCue(false)
            Log.w(TAG, "startListening failed: " + e.message)
            listening = false
            callbacks.onListeningStopped()
            callbacks.onListenError("Mikrofon ochilmadi.", false)
        }
    }

    private fun firstResult(bundle: Bundle?): String {
        val list = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return list?.firstOrNull()?.trim().orEmpty()
    }

    private fun shouldRetryLanguage(error: Int): Boolean =
        error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO,
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_CLIENT -> ""
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon ruxsati kerak."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Ovoz uchun internet kerak."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> ""
        else -> ""
    }

    private fun languageTags(): List<String> =
        listOf("uz-UZ", "uz", "ru-RU", Locale.getDefault().toLanguageTag())

    companion object {
        private const val TAG = "YuzAiVoice"
        private const val CUE_RESTORE_MS = 60_000L
        private const val LISTEN_TIMEOUT_MS = 12_000L
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 13
        private const val ERROR_LANGUAGE_UNAVAILABLE = 14

        /** Matnli input yo'q — javoblar ovozga yaroqli, toza holda qoladi. */
        fun speakable(text: String): String = text
            .replace(Regex("[*_#`]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** Jumlalab bo'lish: birinchi audio tezroq tayyor bo'ladi. */
        private fun splitForSpeech(text: String): List<String> {
            val sentences = text.split(Regex("(?<=[.!?…])\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (sentences.isEmpty()) return listOf(text)
            val out = ArrayList<String>()
            var buf = StringBuilder()
            fun flush() {
                val s = buf.toString().trim()
                if (s.isNotEmpty()) out.add(s)
                buf = StringBuilder()
            }
            for (s in sentences) {
                if (buf.isNotEmpty() && buf.length + s.length > 180) flush()
                if (s.length <= 180) {
                    buf.append(s).append(' ')
                } else {
                    flush()
                    var i = 0
                    while (i < s.length) {
                        out.add(s.substring(i, minOf(i + 180, s.length)).trim())
                        i += 180
                    }
                }
            }
            flush()
            return out.ifEmpty { listOf(text) }
        }
    }
}
