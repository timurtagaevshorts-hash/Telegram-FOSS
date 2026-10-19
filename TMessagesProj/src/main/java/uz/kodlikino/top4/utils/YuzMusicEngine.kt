package uz.kodlikino.top4.utils

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
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
    private val bufferingWatchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState != Player.STATE_BUFFERING) return
            val now = android.os.SystemClock.elapsedRealtime()
            val bp = p.bufferedPosition
            if (bp != lastBuffered) {
                lastBuffered = bp
                stallMark = now
            }
            // #84: yumaloq 30s o'rniga PROGRESS kuzatuvi — uzun qo'shiq
            // buffer'i sekin ham bo'lsa o'saver ekan to'xtatmaymiz, lekin
            // 20s umuma jilmimasa yoki 2 daqiqa davomida tayyor bo'lmasa
            // qo'shiq nomzodini almashtiramiz.
            if (now - stallMark > 20000L || now - bufferingStarted > 120000L) {
                onBufferingStall()
            } else {
                ui.postDelayed(this, 2000L)
            }
        }
    }
    private var bufferingStarted = 0L
    private var stallMark = 0L
    private var lastBuffered = -1L

    @Volatile
    private var player: ExoPlayer? = null
    private lateinit var appCtx: Context
    private var miniInstalled = false
    private var sleepRunnable: Runnable? = null

    var queueTracks: List<YuzTrack> = emptyList()
        private set

    @Volatile
    var radio = true

    private val seenIds = HashSet<Long>()
    private var extending = false
    private var dlInFlight = false
    private var lastStatus: String? = null
    private var itemRetries = 0

    // ---------------------------------------------------------------- setup

    fun addObserver(o: Observer) = observers.add(o)
    fun removeObserver(o: Observer) = observers.remove(o)

    fun playerOrNull(): ExoPlayer? = player

    fun currentTrack(): YuzTrack? = tagOf(player?.currentMediaItem)

    fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        appCtx = context.applicationContext
        // #69: mini-pleyer butun ilova bo'ylab ko'rinishi uchun LaunchActivity
        // lifecycle'ini kuzatamiz (bir marta).
        if (!miniInstalled) {
            miniInstalled = true
            (appCtx as? Application)?.registerActivityLifecycleCallbacks(
                uz.kodlikino.top4.ui.YuzMiniPlayer
            )
        }
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val http = YuzStreamDataSource.Factory(okHttp)
        val load = DefaultLoadControl.Builder()
            // #69 tezlik: audio uchun kichik buffer — ijro deyarli darhol
            // boshlanadi (sukut 2.5s o'rniga 0.9s yetadi).
            .setBufferDurationsMs(15000, 45000, 900, 1500)
            .build()
        val p = ExoPlayer.Builder(appCtx)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(http))
            .setLoadControl(load)
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
                itemRetries = 0
                Log.i(TAG, "transition idx=${p.currentMediaItemIndex} vid=${tagOf(item)?.videoId}")
                notifyItemChanged(item)
                prefetchNext(p, p.currentMediaItemIndex)
                if (radio && p.currentMediaItemIndex >= queueTracks.size - 2) extendQueue()
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        ui.removeCallbacks(bufferingWatchdog)
                        postStatus(null)
                        notifyItemChanged(p.currentMediaItem)
                        prefetchNext(p, p.currentMediaItemIndex)
                    }
                    Player.STATE_BUFFERING -> {
                        ui.removeCallbacks(bufferingWatchdog)
                        // #84: progress kuzatuvi — har 2s'da buffered o'sishini
                        // tekshiramiz (birinchi ochilishda NPE JS parse ~15s
                        // olishi mumkin, shuning uchun darhol sanamaymiz).
                        val now = android.os.SystemClock.elapsedRealtime()
                        bufferingStarted = now
                        stallMark = now
                        lastBuffered = p.bufferedPosition
                        ui.postDelayed(bufferingWatchdog, 2000L)
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
        YuzYouTubeApi.resetRotation()
        Log.i(TAG, "playQueue n=${bounded.size} start=$idx vid=${bounded.getOrNull(idx)?.videoId}")
        p.setMediaItems(bounded.map { buildItem(it) }, idx, C.TIME_UNSET)
        p.prepare()
        p.playWhenReady = true
        notifyItemChanged(p.currentMediaItem)
        prefetchNext(p, idx)
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
        val md = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
            .apply {
                // #69: rasm endi media3'ning O'ZI yuklaydi — bildirishnoma va
                // qulf ekranidagi pleyer muqovani ko'rsatadi.
                if (t.coverUrl.isNotBlank()) setArtworkUri(Uri.parse(t.coverUrl))
            }
            .build()
        return MediaItem.Builder().setUri(uri).setTag(t).setMediaMetadata(md).build()
    }

    /** #69: ijro boshlanishi bilan 1-2 keyingi qo'shiq URL'ini oldindan ochamiz. */
    private fun prefetchNext(p: ExoPlayer, from: Int) {
        for (i in 1..2) {
            queueTracks.getOrNull(from + i)?.videoId?.let { YuzYouTubeApi.prefetch(it) }
        }
    }

    /** Navbatdagi [index]-qo'shiqqa sakrash (keyingi o'tishda prefetch bo'ladi). */
    fun playAt(index: Int) {
        val p = player ?: return
        if (index in 0 until p.mediaItemCount) {
            p.seekTo(index, 0L)
            p.playWhenReady = true
            prefetchNext(p, index)
        }
    }

    /** #69 uyqu taymeri: ms'dan keyin ijroni to'xtatadi; 0 = bekor qilish. */
    fun scheduleSleep(ms: Long) {
        sleepRunnable?.let { ui.removeCallbacks(it) }
        sleepRunnable = null
        if (ms <= 0L) {
            postStatus(null)
            return
        }
        val r = Runnable {
            player?.pause()
            postStatus(null)
            Log.i(TAG, "sleep timer fired")
        }
        sleepRunnable = r
        ui.postDelayed(r, ms)
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
        // #78: bir qo'shiq uchun avval 2 marta boshqa oqim nomzodini aylantir
        // (npe/ANDROID/IOS zanjirlari) — darrov keyingi qo'shiqqa o'tmaymiz.
        if (t.videoId.isNotBlank() && itemRetries < 2) {
            itemRetries++
            Log.i(TAG, "retry#$itemRetries vid=${t.videoId} -> rotate stream candidate")
            YuzYouTubeApi.noteBadStream(t.videoId)
            runCatching {
                // +1ms: media3 bir xil seekTo'ni "o'rin yo'q" deb oqimni
                // qayta ochmaydi — pozitsiyani buzib loader'ni majburlaymiz.
                p.seekTo(p.currentMediaItemIndex, p.currentPosition + 1)
                p.prepare()
                p.playWhenReady = true
            }
            return
        }
        itemRetries = 0
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
        // #78: ijro boshlanishi bilan MediaSessionService'ni ham yoqamiz —
        // shunda ilovadan chiqib ketganda bildirishnomadagi MediaStyle
        // pleyer (sarlavha/bajarguvchi/rasm + tugmalar) har doim chiqadi,
        // qaysi oynadan boshlanganiga qaramay (mini pleyer, pler, ro'yxat).
        if (playing && ::appCtx.isInitialized) {
            runCatching {
                appCtx.startService(
                    android.content.Intent(appCtx, uz.kodlikino.top4.ui.YuzMusicService::class.java)
                )
            }
        }
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
