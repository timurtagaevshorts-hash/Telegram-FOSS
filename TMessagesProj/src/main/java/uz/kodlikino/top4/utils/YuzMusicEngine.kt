package uz.kodlikino.top4.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.R
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * YuzGram #67: musiqa dvigatel'i — ijroning yagona egalari (activity'dan
 * ko'chirildi). YuzMusicService (MediaSessionService) bilan FONDA ham
 * ishlaydi: ekran bloklandsa yoki ilovadan chiqsangiz ham qo'shiq tugamaydi.
 *
 * #67 403 tuzatishi (telefon logcat + media3 manba kodi bilan isbotlangan):
 *  - media3 DefaultHttpDataSource sukut bo'yicha "Accept-Encoding: gzip"
 *    yuborardi -> googlevideo 403 berardi. Endi barcha HTTP so'rovlar
 *    YuzStreamDataSource orqali: "Accept-Encoding: identity" + to'g'ri UA.
 *  - audio URL telefonning O'Z IP'SIDA resolve bo'ladi (googlevideo URL'lari
 *    IP-ga bog'langan; Cloudflare IP'larini YouTube "bot" deb bloklaydi).
 *
 * Arxitektura:
 *  - navbat: qator bosilganda BUTUN ro'yxat ExoPlayer navbati bo'ladi —
 *    qo'shiq tugagach keyingisi avtomatik boshlanadi (marker URI
 *    "https://yuz.stream/v/<id>": resolve media3 loader ipida, kechikishsiz);
 *  - radio: navbat oxiriga yaqlashsa — kuylagan qo'shiq bo'yicha o'xshashlar
 *    topilib navbat uzaytiriladi;
 *  - chidamlilik: 15s buffering yoki oqim xatosi — qo'shiq FONDA keshga
 *    yuklab olinadi (keyingi safar local ochiladi) va navbat keyingisiga
 *    o'tadi;
 *  - har qadam Log.i("YuzMusic") — logcat'dan masofaviy tashxis mumkin.
 */
object YuzMusicEngine {

    interface Observer {
        fun onItemChanged(item: MediaItem?) {}
        fun onPlayingChanged(playing: Boolean) {}
        fun onStatus(text: String?) {}
    }

    private const val TAG = "YuzMusic"
    private val ui = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()
    private val bufferingWatchdog = Runnable { onBufferingStall() }

    @Volatile
    private var player: ExoPlayer? = null
    private lateinit var appCtx: Context

    var queueTracks: List<YuzTrack> = emptyList()
        private set

    @Volatile
    var radio = true

    private val seenIds = HashSet<Long>()
    private var extending = false
    private var dlInFlight = false
    private var lastStatus: String? = null

    // ---------------------------------------------------------------- setup

    fun addObserver(o: Observer) = observers.add(o)
    fun removeObserver(o: Observer) = observers.remove(o)

    fun playerOrNull(): ExoPlayer? = player

    fun currentTrack(): YuzTrack? = tagOf(player?.currentMediaItem)

    fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        appCtx = context.applicationContext
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val http = YuzStreamDataSource.Factory(okHttp)
        val p = ExoPlayer.Builder(appCtx)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(http))
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .build(),
            true
        )
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                ui.removeCallbacks(bufferingWatchdog)
                postStatus(null)
                Log.i(TAG, "transition idx=${p.currentMediaItemIndex} vid=${tagOf(item)?.videoId}")
                notifyItemChanged(item)
                if (radio && p.currentMediaItemIndex >= queueTracks.size - 2) extendQueue()
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        ui.removeCallbacks(bufferingWatchdog)
                        postStatus(null)
                        notifyItemChanged(p.currentMediaItem)
                    }
                    Player.STATE_BUFFERING -> {
                        ui.removeCallbacks(bufferingWatchdog)
                        ui.postDelayed(bufferingWatchdog, 15000)
                    }
                    Player.STATE_ENDED -> postStatus(null)
                }
                notifyPlaying(p.isPlaying)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = notifyPlaying(isPlaying)

            override fun onPlayerError(error: PlaybackException) {
                ui.removeCallbacks(bufferingWatchdog)
                Log.i(TAG, "playerError code=${error.errorCode} vid=${tagOf(p.currentMediaItem)?.videoId}")
                handleItemFailure(p.currentMediaItem)
            }
        })
        player = p
        return p
    }

    private fun tagOf(item: MediaItem?): YuzTrack? =
        item?.localConfiguration?.tag as? YuzTrack

    // ---------------------------------------------------------------- queue

    /** Butun ro'yxatni navbat qilib [startIndex]'dan boshlab ijro etadi. */
    fun playQueue(context: Context, list: List<YuzTrack>, startIndex: Int) {
        val p = ensurePlayer(context)
        val bounded = list.take(150)
        val idx = startIndex.coerceIn(0, (bounded.size - 1).coerceAtLeast(0))
        queueTracks = bounded
        seenIds.clear()
        for (t in bounded) seenIds.add(t.id)
        Log.i(TAG, "playQueue n=${bounded.size} start=$idx vid=${bounded.getOrNull(idx)?.videoId}")
        p.setMediaItems(bounded.map { buildItem(it) }, idx, C.TIME_UNSET)
        p.prepare()
        p.playWhenReady = true
        notifyItemChanged(p.currentMediaItem)
    }

    /** Qo'shiqni navbat oxiriga qo'shadi; hozir bo'sh bo'lsa — shu bilan boshlaydi. */
    fun enqueue(context: Context, track: YuzTrack) {
        val p = ensurePlayer(context)
        if (!seenIds.add(track.id)) return
        val item = buildItem(track)
        if (p.currentMediaItem == null) {
            queueTracks = listOf(track)
            p.setMediaItem(item)
            p.prepare()
            p.playWhenReady = true
        } else {
            queueTracks = queueTracks + track
            p.addMediaItem(item)
        }
        notifyItemChanged(p.currentMediaItem)
    }

    // ---------------------------------------------------------------- items

    /** URI: local kesh > "yuz://" marker (loader ipida resolve) > Deezer preview. */
    private fun buildItem(t: YuzTrack): MediaItem {
        val local = if (::appCtx.isInitialized) YuzMusicCache.localFile(appCtx, t.id) else null
        val uri = when {
            local != null -> local.toURI().toString()
            t.videoId.isNotBlank() -> YuzStreamDataSource.URI_PREFIX + t.videoId
            else -> t.previewUrl
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setTag(t)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).build()
            )
            .build()
    }

    // ------------------------------------------------------- failure paths

    /** 15s buffering = jim 403/timeout deb hisoblaymiz. */
    private fun onBufferingStall() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_BUFFERING) {
            Log.i(TAG, "buffering stall vid=${tagOf(p.currentMediaItem)?.videoId}")
            handleItemFailure(p.currentMediaItem)
        }
    }

    private fun handleItemFailure(item: MediaItem?) {
        val t = tagOf(item) ?: return
        val p = player ?: return
        Log.i(TAG, "give-up vid=${t.videoId} -> silent download + skip")
        p.playWhenReady = false
        silentDownload(t)
        if (p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
            p.prepare()
            p.playWhenReady = true
        } else if (radio) {
            postStatus(appCtx.getString(R.string.musiqa_st_extend))
            extendQueue(retryStart = true)
        } else {
            postStatus(null)
        }
    }

    /** Oxirgi chora emas — qo'shiqni fonida yuklab, keyingi urinishda local ochilsin. */
    private fun silentDownload(t: YuzTrack) {
        if (dlInFlight) return
        if (YuzMusicCache.isDownloaded(appCtx, t.id)) return
        dlInFlight = true
        Thread {
            val ok = runCatching { YuzMusicCache.downloadBlocking(appCtx, t) }.getOrDefault(false)
            Log.i(TAG, "silentDownload vid=${t.videoId} ok=$ok")
            dlInFlight = false
            if (ok) ui.post { notifyItemChanged(player?.currentMediaItem) }
        }.start()
    }

    // ---------------------------------------------------------------- radio

    private fun extendQueue(retryStart: Boolean = false) {
        if (extending || queueTracks.isEmpty()) return
        extending = true
        val anchor = queueTracks.lastOrNull() ?: return
        val query = (anchor.artist + " " + anchor.title).trim()
        Log.i(TAG, "extendQueue q='$query'")
        Thread {
            val more = YuzMusicGateway.search(query, 15)
                .ifEmpty { runCatching { YuzYouTubeApi.search(query, 15) }.getOrDefault(emptyList()) }
            val fresh = more.filter { it.videoId.isNotBlank() && seenIds.add(it.id) }.take(8)
            ui.post {
                extending = false
                if (fresh.isEmpty()) return@post
                val p = player ?: return@post
                queueTracks = queueTracks + fresh
                runCatching { p.addMediaItems(fresh.map { buildItem(it) }) }
                if (retryStart) {
                    postStatus(null)
                    p.prepare()
                    p.playWhenReady = true
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- notify

    private fun notifyItemChanged(item: MediaItem?) {
        for (o in observers) runCatching { o.onItemChanged(item) }
    }

    private fun notifyPlaying(playing: Boolean) {
        for (o in observers) runCatching { o.onPlayingChanged(playing) }
    }

    private fun postStatus(text: String?) {
        if (text == lastStatus) return
        lastStatus = text
        for (o in observers) runCatching { o.onStatus(text) }
    }

    /** UI uchun: joriy status xabari (null = oddiy ijro). */
    fun lastStatusText(): String? = lastStatus

    // ------------------------------------------------------------- playlists

    data class Playlist(val name: String, val tracks: List<YuzTrack>)

    private fun plFile(context: Context) = File(context.filesDir, "yuzplaylists.json")

    fun playlists(context: Context): MutableList<Playlist> {
        val out = ArrayList<Playlist>()
        try {
            val f = plFile(context)
            if (!f.exists()) return out
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isBlank()) continue
                val tr = o.optJSONArray("tracks") ?: JSONArray()
                val list = ArrayList<YuzTrack>()
                for (j in 0 until tr.length()) {
                    val t = tr.optJSONObject(j) ?: continue
                    val title = t.optString("title")
                    if (title.isBlank()) continue
                    list.add(
                        YuzTrack(
                            id = t.optLong("id"),
                            title = title,
                            artist = t.optString("artist"),
                            album = t.optString("album"),
                            durationSec = t.optInt("dur"),
                            coverUrl = t.optString("cover"),
                            previewUrl = t.optString("preview"),
                            link = t.optString("link"),
                            videoId = t.optString("yt")
                        )
                    )
                }
                out.add(Playlist(name, list))
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Bor playlist'ga qo'shadi; yo'q bo'lsa — yangi yaratadi. */
    fun playlistAdd(context: Context, name: String, track: YuzTrack): Boolean {
        val all = playlists(context)
        val existing = all.indexOfFirst { it.name == name }
        val tracks =
            if (existing >= 0) all[existing].tracks.filterNot { it.id == track.id } + track
            else listOf(track)
        if (existing >= 0) all[existing] = Playlist(name, tracks) else all.add(Playlist(name, tracks))
        return savePlaylists(context, all)
    }

    fun playlistRemove(context: Context, name: String): Boolean =
        savePlaylists(context, playlists(context).filter { it.name != name })

    private fun savePlaylists(context: Context, list: List<Playlist>): Boolean {
        return try {
            val arr = JSONArray()
            for (pl in list) {
                val o = JSONObject()
                o.put("name", pl.name)
                val tr = JSONArray()
                for (t in pl.tracks) {
                    val j = JSONObject()
                    j.put("id", t.id)
                    j.put("title", t.title)
                    j.put("artist", t.artist)
                    j.put("album", t.album)
                    j.put("dur", t.durationSec)
                    j.put("cover", t.coverUrl)
                    j.put("preview", t.previewUrl)
                    j.put("link", t.link)
                    j.put("yt", t.videoId)
                    tr.put(j)
                }
                o.put("tracks", tr)
                arr.put(o)
            }
            plFile(context).writeText(arr.toString())
            true
        } catch (_: Exception) {
            false
        }
    }
}
