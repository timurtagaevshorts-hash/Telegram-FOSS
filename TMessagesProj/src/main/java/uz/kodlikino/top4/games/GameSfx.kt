package uz.kodlikino.top4.games

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import org.telegram.messenger.R

/**
 * O‘yin tovushlari — qisqa, sintez qilingan SFX (res/raw).
 *
 * SoundPool tanlandi: taxta bosishlarida kechikish bo‘lmasligi uchun barcha
 * namunalar xotiraga bir marta yuklanadi. Ovoz sathi mavjud bo‘lgan ringer
 * rejimiga bo‘ysunadi (jim rejimda o‘yin ham jim).
 */
object GameSfx {

    const val TAP = 1
    const val MOVE = 2
    const val CAPTURE = 3
    const val BAD = 4
    const val TURN = 5
    const val WIN = 6
    const val LOSE = 7
    const val START = 8
    const val NIGHT = 9
    const val VOTE = 10
    const val DEATH = 11
    const val JOIN = 12

    private var pool: SoundPool? = null
    private val loadedIds = java.util.Collections.synchronizedSet(HashSet<Int>())
    private val ids = HashMap<Int, Int>()
    private var volume = 0.85f

    @Synchronized
    fun prepare(context: Context) {
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val created = if (Build.VERSION.SDK_INT >= 21) {
            SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build()
        } else {
            @Suppress("DEPRECATION")
            SoundPool(4, AudioManager.STREAM_MUSIC, 0)
        }
        created.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedIds.add(sampleId)
        }
        val app = context.applicationContext
        ids[TAP] = created.load(app, R.raw.sfx_tap, 1)
        ids[MOVE] = created.load(app, R.raw.sfx_move, 1)
        ids[CAPTURE] = created.load(app, R.raw.sfx_capture, 1)
        ids[BAD] = created.load(app, R.raw.sfx_bad, 1)
        ids[TURN] = created.load(app, R.raw.sfx_turn, 1)
        ids[WIN] = created.load(app, R.raw.sfx_win, 1)
        ids[LOSE] = created.load(app, R.raw.sfx_lose, 1)
        ids[START] = created.load(app, R.raw.sfx_start, 1)
        ids[NIGHT] = created.load(app, R.raw.sfx_night, 1)
        ids[VOTE] = created.load(app, R.raw.sfx_vote, 1)
        ids[DEATH] = created.load(app, R.raw.sfx_death, 1)
        ids[JOIN] = created.load(app, R.raw.sfx_join, 1)
        pool = created
    }

    fun play(context: Context, sound: Int, volumeScale: Float = 1f) {
        prepare(context)
        val soundId = ids[sound] ?: return
        if (soundId == 0 || !loadedIds.contains(soundId)) return
        try {
            pool?.play(soundId, volume * volumeScale, volume * volumeScale, 1, 0, 1f)
        } catch (_: Exception) {
        }
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
    }

    @Synchronized
    fun release() {
        try {
            pool?.release()
        } catch (_: Exception) {
        }
        pool = null
        loadedIds.clear()
        ids.clear()
    }
}
