package uz.kodlikino.top4.ai

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Ovoz yordamchisi: SpeechRecognizer (uz -> ru -> default retry) + Android TextToSpeech.
 * Gemini/Internet STT yo'q — faqat tizim mikrofoni va tizim TTS'i ishlatiladi.
 */
class YuzAiVoice(context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onListeningStarted()
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onListenError(message: String, retry: Boolean)
        fun onListeningStopped()
        fun onSpeakFinished()
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: String? = null
    private var listening = false
    private var speaking = false
    private var languageIndex = 0
    private var ignoreNextError = false
    private var released = false
    private var lastSpeakId = "yuz_ai_0"
    private var cloudPlayer: MediaPlayer? = null
    private var cloudFile: File? = null

    /**
     * Worker'dan kelgan WAV (inson ovozi) bilan gapirish. Xato bo'lsa — tizim TTS'ga tushadi.
     */
    fun playCloudAudio(bytes: ByteArray, fallback: String) {
        stopListening()
        stopSpeakingInternal()
        val spoken = speakable(fallback)
        if (bytes.size < 2000 || spoken.isBlank()) {
            if (bytes.size < 2000) speak(fallback) else callbacks.onSpeakFinished()
            return
        }
        speaking = true
        try {
            cloudFile?.delete()
            val f = File(appContext.cacheDir, "ai_tts_" + System.currentTimeMillis() + ".wav")
            f.writeBytes(bytes)
            cloudFile = f
            val p = MediaPlayer()
            cloudPlayer = p
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setOnCompletionListener { finishCloud(p, null) }
            p.setOnErrorListener { _, _, _ -> finishCloud(p, fallback); true }
            p.setDataSource(f.absolutePath)
            p.setOnPreparedListener { it.start() }
            p.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "playCloudAudio: " + e.message)
            finishCloud(cloudPlayer, fallback)
        }
    }

    private fun finishCloud(p: MediaPlayer?, fallback: String?) {
        try { p?.release() } catch (_: Exception) { }
        if (cloudPlayer === p) cloudPlayer = null
        cloudFile?.delete()
        cloudFile = null
        if (!speaking && fallback == null) return
        speaking = false
        mainHandler.post {
            if (!released) callbacks.onSpeakFinished()
        }
        if (!fallback.isNullOrBlank() && !released) {
            mainHandler.post { speak(fallback) }
        }
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            if (released || status != TextToSpeech.SUCCESS) return@TextToSpeech
            ttsReady = true
            applyVoiceLocale()
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking = true
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null && utteranceId != lastSpeakId) return
                    speaking = false
                    mainHandler.post {
                        if (!released) callbacks.onSpeakFinished()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    speaking = false
                    mainHandler.post {
                        if (!released) callbacks.onSpeakFinished()
                    }
                }
            })
            pendingSpeak?.let { queued ->
                pendingSpeak = null
                speak(queued)
            }
        }
    }

    fun isListening(): Boolean = listening

    fun canListen(): Boolean = try {
        SpeechRecognizer.isRecognitionAvailable(appContext)
    } catch (_: Exception) {
        false
    }

    fun startListening() {
        if (released || speaking || listening) return
        mainHandler.post {
            if (released || speaking || listening) return@post
            languageIndex = 0
            ignoreNextError = false
            playListenSignal()
            if (!canListen() || !ensureRecognizer()) {
                callbacks.onListenError("Mikrofon mavjud emas.", false)
                return@post
            }
            startRecognizer(languageTags().first())
        }
    }

    fun stopListening() {
        mainHandler.post {
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

    fun speak(text: String) {
        val spoken = speakable(text)
        if (spoken.isBlank()) {
            callbacks.onSpeakFinished()
            return
        }
        stopListening()
        speaking = true
        if (!ttsReady) {
            pendingSpeak = spoken
            return
        }
        val engine = tts
        if (engine == null) {
            speaking = false
            callbacks.onSpeakFinished()
            return
        }
        val chunks = spoken
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(spoken) }
        lastSpeakId = "yuz_ai_" + chunks.lastIndex
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = "yuz_ai_$index"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                engine.speak(humanize(chunk), mode, null, id)
            } else {
                @Suppress("DEPRECATION")
                engine.speak(humanize(chunk), mode, null)
            }
        }
    }

    fun stopSpeaking() {
        pendingSpeak = null
        stopSpeakingInternal()
    }

    private fun stopSpeakingInternal() {
        speaking = false
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
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
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ttsReady = false
        muteRecognizerCue(false)
    }

    private fun playListenSignal() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            mainHandler.postDelayed({
                try {
                    tone.release()
                } catch (_: Exception) {
                }
            }, 150)
        } catch (_: Exception) {
        }
    }

    private fun muteRecognizerCue(mute: Boolean) {
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.adjustStreamVolume(
                    AudioManager.STREAM_NOTIFICATION,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0
                )
            } else {
                @Suppress("DEPRECATION")
                am.setStreamMute(AudioManager.STREAM_NOTIFICATION, mute)
            }
        } catch (_: Exception) {
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
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
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
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600L)
        }
        listening = true
        callbacks.onListeningStarted()
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

    private fun applyVoiceLocale() {
        val engine = tts ?: return
        val picked = listOf(
            Locale("uz", "UZ"),
            Locale("uz"),
            Locale("tr", "TR"),
            Locale("ru", "RU"),
            Locale.getDefault()
        ).firstOrNull { locale ->
            try {
                engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
            } catch (_: Exception) {
                false
            }
        } ?: Locale.getDefault()
        try {
            engine.language = picked
            engine.setSpeechRate(1.05f)
            engine.setPitch(1.0f)
        } catch (_: Exception) {
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
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 13
        private const val ERROR_LANGUAGE_UNAVAILABLE = 14

        /** Matnli input yo'q — javoblar ovozga yaroqli, toza holda qoladi. */
        fun speakable(text: String): String = text
            .replace(Regex("[*_#`]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun humanize(text: String): String = text
            .replace("’", "'")
            .replace("‘", "'")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
