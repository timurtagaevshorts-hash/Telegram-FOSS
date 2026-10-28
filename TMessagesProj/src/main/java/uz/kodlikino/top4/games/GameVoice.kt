package uz.kodlikino.top4.games

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * O‘yin ichidagi ovozli aloqa — "bosib gapir" (push-to-talk).
 *
 * Klip qurilmada yoziladi (AAC/m4a), Firebase Storage'ga yuklanadi, so‘ng xona
 * hujjatidagi `voice` maydoniga havola yoziladi. Ikkinchi o‘yinchi maydon
 * o‘zgarishini ko‘radi va klipni avtomatik ijro etadi — bu walkie-talkie kabi
 * ishlaydi va mobil tarmoqda ham ishonchli.
 *
 * Tarmoq real-vaqt oqimi (WebRTC) ataylab ishlatilmadi: TURN server va doimiy
 * ulanish talab qiladi, o‘yin esa Firestore orqali allaqachon sinxron.
 */
object GameVoice {

    private const val TAG = "GameVoice"
    const val MAX_MS = 20_000L
    const val MIN_MS = 400L

    data class Clip(val file: File, val durationMs: Long)

    data class Message(
        val uid: String,
        val name: String,
        val url: String,
        val ms: Long,
        val at: Long
    )

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L
    private var player: MediaPlayer? = null

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isRecording(): Boolean = recorder != null

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun elapsedMs(): Long = if (recorder == null) 0L else GamesCore.now() - startedAt

    @Suppress("DEPRECATION")
    fun start(context: Context): Boolean {
        if (recorder != null) return false
        if (!hasPermission(context)) return false
        val file = File(context.cacheDir, "gamevoice_${GamesCore.now()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(16000)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(32000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            target = file
            startedAt = GamesCore.now()
            true
        } catch (e: Exception) {
            Log.e(TAG, "start: ${e.message}", e)
            try {
                rec.release()
            } catch (_: Exception) {
            }
            file.delete()
            recorder = null
            target = null
            false
        }
    }

    /** Yozishni to‘xtatadi; juda qisqa bo‘lsa null (tasodifiy bosish). */
    fun stop(): Clip? {
        val rec = recorder ?: return null
        val file = target
        val duration = GamesCore.now() - startedAt
        recorder = null
        target = null
        startedAt = 0L
        try {
            rec.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop: ${e.message}")
            try {
                rec.release()
            } catch (_: Exception) {
            }
            file?.delete()
            return null
        }
        try {
            rec.release()
        } catch (_: Exception) {
        }
        if (file == null || !file.exists() || duration < MIN_MS || file.length() < 600L) {
            file?.delete()
            return null
        }
        return Clip(file, duration.coerceAtMost(MAX_MS))
    }

    fun cancel() {
        val rec = recorder
        recorder = null
        startedAt = 0L
        try {
            rec?.stop()
        } catch (_: Exception) {
        }
        try {
            rec?.release()
        } catch (_: Exception) {
        }
        target?.delete()
        target = null
    }

    /** Yuklaydi va xona hujjatiga `voice` havolasini yozadi. */
    suspend fun publish(
        roomId: String,
        uid: String,
        name: String,
        clip: Clip
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val safeUid = uid.trim().ifBlank { "anon" }
            // chat_media — allaqachon ruxsat berilgan audio yo'li (yangi qoida deploy talab qilmaydi).
            val storagePath =
                "chat_media/yuzgame_${roomId.trim()}/${safeUid}_${clip.file.nameWithoutExtension.hashCode()}.m4a"
            val ref = FirebaseStorage.getInstance().reference.child(storagePath)
            val meta = StorageMetadata.Builder()
                .setContentType("audio/mp4")
                .build()
            ref.putFile(android.net.Uri.fromFile(clip.file), meta).await()
            val url = ref.downloadUrl.await().toString()
            val at = GamesCore.now()
            val updates = HashMap<String, Any>(2)
            updates["voice"] = mapOf(
                "uid" to uid,
                "name" to name.take(40),
                "url" to url,
                "ms" to clip.durationMs,
                "at" to at
            )
            updates["voiceAt"] = at
            GamesCore.rooms().document(roomId.trim()).update(updates).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "publish: ${e.message}", e)
            false
        } finally {
            clip.file.delete()
        }
    }

    fun parse(raw: Any?): Message? {
        val map = raw as? Map<*, *> ?: return null
        val url = map["url"]?.toString().orEmpty()
        if (url.isBlank()) return null
        return Message(
            uid = map["uid"]?.toString().orEmpty(),
            name = map["name"]?.toString().orEmpty(),
            url = url,
            ms = (map["ms"] as? Number)?.toLong() ?: 0L,
            at = (map["at"] as? Number)?.toLong() ?: 0L
        )
    }

    fun play(url: String, onDone: (() -> Unit)? = null): Boolean {
        stopPlayback()
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { done ->
                try {
                    done.release()
                } catch (_: Exception) {
                }
                if (player === done) player = null
                onDone?.invoke()
            }
            mp.setOnErrorListener { failed, _, _ ->
                try {
                    failed.release()
                } catch (_: Exception) {
                }
                if (player === failed) player = null
                onDone?.invoke()
                true
            }
            mp.prepareAsync()
            player = mp
            true
        } catch (e: Exception) {
            Log.e(TAG, "play: ${e.message}", e)
            false
        }
    }

    fun stopPlayback() {
        val mp = player ?: return
        player = null
        try {
            mp.stop()
        } catch (_: Exception) {
        }
        try {
            mp.release()
        } catch (_: Exception) {
        }
    }

    fun release() {
        cancel()
        stopPlayback()
    }
}
