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
import uz.kodlikino.top4.ui.YuzMusicNotification
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
 *  - chidamlilik: buffering tugasa — qo'shiq FONDA keshga yuklab olinadi
 *    (keyingi safar local ochiladi) va navbat keyingisiga o'tadi;
 *  - #86: har bir chekara (buffer vaqti, oyna hajmi, nomzod tanlash)
 *    YuzNetPulse o'lchagan TAROQ TEZLIGIDAN hisoblanadi — past internetda
 *    ham sifatli va tez ishlaydi; ijro boshlangach keyingi trek to'liq
 *    keshga yuklanadi (ijro xohlasa yo'l beradi).
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
            // buffer'i sekin ham bo'lsa o'saver ekan to'xtatmaymiz.
            // #86: chegaralar QATIY SON EMAS — YuzNetPulse tezligidan
            // hisoblanadi. 8 kB/s'lik taroqda 20s ichida buffer o'sishi
            // uchun keyingi Range oynasi yetib kelmasligi mumkin edi va
            // 120s'lik umumiy limit ishlayotgan yuklashni o'ldirardi;
            // endi sekin internetda ruxsat etilgan vaqt ham katta.
            if (now - stallMark > YuzNetPulse.stallLimitMs() ||
                now - bufferingStarted > YuzNetPulse.bufferingCapMs()
            ) {
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

    private const val PREFS = "yuz_music_prefs"
    private const val KEY_RADIO = "radio_extend"

    // #133: radio = "keyingi musiqalarni avto topish". Holat endi saqlanadi
    // va faqat [setRadio] orqali o'zgaradi — ilova qayta ochilganda
    // foydalanuvchi tanlovi saqlanib qoladi.
    @Volatile
    var radio = true
        private set

    /** #133: radio kaliti — holatni saqlaydi; yoqilganda navbat oxiriga yaqin bo'lsa darhol uzaytiradi. */
    fun setRadio(context: Context, enabled: Boolean) {
        radio = enabled
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_RADIO, enabled).apply()
        }
        if (!enabled) return
        val p = player ?: return
        if (queueTracks.isEmpty()) return
        if (p.currentMediaItemIndex >= queueTracks.size - 2) {
            extendQueue(retryStart = p.playbackState == Player.STATE_ENDED)
        }
    }

    // YuzGram #115: main IP (playQueue/enqueue) VA extendQueue ipi bir vaqtda
    // tegadi — oddiy HashSet ConcurrentModificationException bilan crash
    // berishi mumkin edi; concurrent set shu poygani yopadi.
    private val seenIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    )
    private var extending = false
    // #120: navbat generatsiyasi — playQueue/enqueue-swap'ida o'sadi.
    // extendQueue fon qidiruvi tugaydigacha navbat butunlay almashtirilsa,
    // eski ro'yxat urug'laridan yig'ilgan qo'shiqlar YANGI navbat oxiriga
    // ilinib qolardi — generaatsiya farqlansa natija tashlab yuboriladi.
    @Volatile
    private var queueGen = 0
    // YuzGram #98: radio navbatni cheksiz davom ettiradi — urinishlar soni
    // va qaysi "hit" so'rovi bilan davom etgani shu yerda yuritiladi.
    private var extendAttempts = 0
    private var seedRotate = 0
    // YuzGram #117: tavsiya hovuzi — o'zbekcha eng ko'p eshitilgan va rus
    // xitlari almashinib aylanadi (radio navbati shu manbalardan yig'iladi).
    private val hitSeeds = arrayOf(
        "eng kop eshitilgan ozbek qoshiklari",
        "russkie hity 2026",
        "ozbek xit qoshiklar 2026",
        "top russian hits pop",
        "trend musiqi 2026",
        "russkaya pop muzyka novinki 2026"
    )
    @Volatile
    private var dlInFlight = false
    private var lastStatus: String? = null
    private var itemRetries = 0
    // #86: ketma-ket boshlanmagan qo'shiqlar — 4 tadan keyin navbatni
    // aylantirishni to'xtatamiz (tarmoq o'lgan joyda cheksiz urinish
    // batareyani ham, taroqni ham yeydi).
    private var consecutiveSkips = 0
    @Volatile private var prebufferId = -1L
    @Volatile private var prebufferTryAt = 0L

    // #86: [backgroundGate] FON YUKLASH ipidan chaqiriladi. ExoPlayer'ning
    // holatini shu ipdan o'qish MUMKIN EMAS (player faqat application
    // thread'da ruxsat beradi), shuning uchun holatni asosiy ipdan
    // ko'z-nukta (volatile) maydonlarga ko'chirib turamiz.
    @Volatile private var gateBuffering = false
    @Volatile private var gatePlaying = false
    @Volatile private var gateAhead = 0L
    // #155: eshik holatining YANGILIK vaqti. Ticker faqat ijro paytida
    // yuradi — pauza/ekran o'chishida to'xtaydi va gateBuffering eskirgan
    // holda qolar edi. Stale "buffering" foydalanuvchi yuklashini ABADIY
    // muzlatib qo'yardi ("ozroq yuklab to'xtab qolyabdi" sabablaridan).
    @Volatile private var gateAt = 0L
    // #152: "qo'shiq HAQIQATAN tugadimi" nazorati — so'nggi tick'dagi
    // indeks/pozitsiya/davomiylik. Oqim muddatidan oldin uzilsa (truncated
    // EOF) media3 buni oddiy AUTO o'tish deb hisoblaydi; shunda orqaga
    // qaytib oqim nomzodini almashtiramiz.
    private var tickIndex = -1
    private var tickPos = 0L
    private var tickDur = 0L
    private var tickItem: MediaItem? = null
    // #122: YuzMusicService'ni har "playing" signalida EMAS, faqat trek
    // haqiqatan o'ynala boshlaganda (STATE_READY) va bir marta ko'taramiz.
    // startForegroundService chaqirilib, media3 5s ichida startForeground()
    // qila olmasa (player BUFFERING/IDLE — bildirishnoma hali yo'q), Android
    // 14 main thread'da ForegroundServiceDidNotStartInTimeException otadi.
    @Volatile private var musicServiceStarted = false
    private var gateTickerOn = false
    private val gateTicker = object : Runnable {
        override fun run() {
            val p = player
            if (p == null) {
                gateTickerOn = false
                return
            }
            gateBuffering = p.playbackState == Player.STATE_BUFFERING
            gatePlaying = p.playWhenReady
            gateAhead = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
            gateAt = android.os.SystemClock.elapsedRealtime()
            tickIndex = p.currentMediaItemIndex
            tickPos = p.currentPosition
            tickDur = p.duration
            tickItem = p.currentMediaItem
            if (gatePlaying) ui.postDelayed(this, 2000L) else gateTickerOn = false
        }
    }

    /** Ijro boshlanganda eshik holatini yangilashni davom ettiramiz. */
    private fun ensureGateTicker() {
        if (gateTickerOn) return
        gateTickerOn = true
        ui.post(gateTicker)
    }

    // ---------------------------------------------------------------- setup

    fun addObserver(o: Observer) = observers.add(o)
    fun removeObserver(o: Observer) = observers.remove(o)

    fun playerOrNull(): ExoPlayer? = player

    fun currentTrack(): YuzTrack? = tagOf(player?.currentMediaItem)

    /**
     * Ijro/pauza tugmasi (mini-pleyer, to'liq pleyer, bildirishnoma).
     * Ilgari chaqiruvchilar faqat `playWhenReady` almashtirardi — IDLE yoki
     * ENDED holatdagi pleyerda bu hech narsa qilmasdi (qo'shiq "o'lik"
     * qolardi). Bu yerda holat to'g'irlanadi va xizmat qayta ko'tariladi.
     */
    fun togglePlay(context: Context) {
        val p = player ?: return
        if (p.currentMediaItem == null) return
        if (p.isPlaying) {
            p.pause()
            return
        }
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(p.currentMediaItemIndex, 0L)
        }
        if (p.playbackState == Player.STATE_IDLE) {
            p.prepare()
        }
        p.playWhenReady = true
        startMusicServiceOnce()
    }

    fun ensurePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        appCtx = context.applicationContext
        radio = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RADIO, true)
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
            // #86 sekin internet: buffer maqsadi kengaytirildi — 20s'lik
            // zaxira (sukut 15s) va 60s'gacha oldindan yuklash taroq
            // uzilib qolsa ham ijro davom etishini ta'minlaydi. Boshlanish
            // uchun 2s yetadi (eski 0.9s juda erta edi: o'yin boshlanib,
            // birinchi oyna tugamay tinch holatga qaytardi).
            .setBufferDurationsMs(20000, 60000, 2000, 4000)
            // #157 orqaga seek: back buffer 0 edi — har bir orqaga siljish
            // oqimni qayta ochardi (0.3-1.5s jimlik + format almashish xavfi).
            // 60s ~1MB (128kbps) — seek buffer ichida bo'lsa darhol bajariladi.
            .setBackBuffer(60_000, true)
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
        // Quloqchin uzilganda pauza (AudioManager ACTION_AUDIO_BECOMING_NOISY)
        // va ekran o'chganda tarmoq oqimini ushlab turish uchun wake lock.
        p.setHandleAudioBecomingNoisy(true)
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // #152: AUTO o'tish — media3 "qo'shiq tugadi" degani. Lekin
                // oqim muddatidan oldin uzilganda (truncated EOF) ham AUTO
                // keladi: pozitsiya davomiylikdan 20+ sekund orqada bo'lsa,
                // o'tishni BEKOR qilib oqim nomzodini almashtiramiz.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    tickItem !== item &&
                    retryEarlyEnd(p, tickItem, tickIndex)
                ) return
                ui.removeCallbacks(bufferingWatchdog)
                postStatus(null)
                itemRetries = 0
                Log.i(TAG, "transition idx=${p.currentMediaItemIndex} vid=${tagOf(item)?.videoId}")
                notifyItemChanged(item)
                // #157: give-up yo'li (seekToNextMediaItem + prepare) BUFFERING
                // ichida yangi transition holatini O'ZGARTIRMAYDI, shuning uchun
                // watchdog qayta qurollanmasdi — ikkinchi tiqilgan qo'shiq
                // pleyerni abadiy BUFFERING'da qoldirardi ("pleyer o'ldi").
                if (p.playbackState == Player.STATE_BUFFERING) {
                    val rearm = android.os.SystemClock.elapsedRealtime()
                    bufferingStarted = rearm
                    stallMark = rearm
                    lastBuffered = p.bufferedPosition
                    ui.postDelayed(bufferingWatchdog, 2000L)
                }
                prefetchNext(p, p.currentMediaItemIndex)
                markPlayed(item)
                prebufferNext(p, p.currentMediaItemIndex)
                extendAttempts = 0
                if (radio && p.currentMediaItemIndex >= queueTracks.size - 3) extendQueue()
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        ui.removeCallbacks(bufferingWatchdog)
                        postStatus(null)
                        // #86: qo'shiq o'ynala boshladi demak tarmoq tirik —
                        // skip-bo'ron tozaladi, keyingisini yuklashni boshlaydi.
                        consecutiveSkips = 0
                        // #122: foreground xizmatni aynan SHU yerda ko'taramiz:
                        // player READY => media3 bildirishnomasi bor va
                        // startForeground() 5s ichida albatta chaqiriladi.
                        if (p.isPlaying) startMusicServiceOnce()
                        notifyItemChanged(p.currentMediaItem)
                        prefetchNext(p, p.currentMediaItemIndex)
                        prebufferNext(p, p.currentMediaItemIndex)
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
                    Player.STATE_ENDED -> {
                        // #152: oxirgi qo'shiq ham muddatidan oldin "tugasa"
                        // — radio-uzaytirish o'rniga avval oqimni tiklaymiz.
                        if (retryEarlyEnd(p, p.currentMediaItem, p.currentMediaItemIndex)) {
                            return
                        }
                        musicServiceStarted = false // #122: keyingi READY'da qayta ko'tariladi
                        // YuzGram #98: navbat tugashi — tugash emas. Radio
                        // yoqilgan bo'lsa o'xshash/ommabop qo'shiqlar
                        // qo'shib ijro davom etadi.
                        if (radio && queueTracks.isNotEmpty()) {
                            postStatus(appCtx.getString(R.string.musiqa_st_extend))
                            extendQueue(retryStart = true)
                        } else {
                            postStatus(null)
                        }
                    }
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
        if (startIndex !in list.indices) return
        val p = ensurePlayer(context)
        val first = (startIndex - 75).coerceAtLeast(0)
        val bounded = list.drop(first).take(150)
        val idx = startIndex - first
        queueTracks = bounded
        queueGen++
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
    fun enqueue(context: Context, track: YuzTrack): Boolean {
        val p = ensurePlayer(context)
        // #133: takroriy qator "qo'shildi" deb yolg'on xabar bermasin.
        if (!seenIds.add(track.id)) return false
        val item = buildItem(track)
        if (p.currentMediaItem == null) {
            queueTracks = listOf(track)
            queueGen++
            p.setMediaItem(item)
            p.prepare()
            p.playWhenReady = true
        } else {
            queueTracks = queueTracks + track
            p.addMediaItem(item)
        }
        notifyItemChanged(p.currentMediaItem)
        return true
    }

    // ---------------------------------------------------------------- items

    /** URI: local kesh > "yuz://" marker (loader ipida resolve) > Deezer preview. */
    private fun buildItem(t: YuzTrack): MediaItem {
        val local = if (::appCtx.isInitialized) YuzMusicCache.localFile(appCtx, t.id) else null
        val uri = when {
            // #133: File.toURI() "file:/..." (bir slash) beradi — media3
            // DataSource buni avval HTTP manba deb ochib, ijro etolmasdi.
            // Uri.fromFile() to'g'ri "file:///..." shaklini beradi.
            local != null -> Uri.fromFile(local).toString()
            t.videoId.isNotBlank() -> YuzStreamDataSource.URI_PREFIX + t.videoId
            else -> t.previewUrl
        }
        val md = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
            .apply {
                // #87: 1.0.2'da metadata'da bitmap maydoni yo'q — muqova URI
                // orqali beriladi va uni YuzMusicService'ning BitmapLoader'i
                // yuklaydi (bildirishnoma + qulf ekrani shu rasmni ko'rsatadi).
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
        // #87: muqovalarni ham isitamiz — bildirishnoma/qulf ekrani birinchi
        // qurilishdayoq rasmga ega bo'lsin (tarmoqqa chiqish vaqti bor).
        if (::appCtx.isInitialized) {
            for (i in 0..2) {
                YuzArtwork.prefetch(appCtx, queueTracks.getOrNull(from + i)?.coverUrl)
            }
        }
    }

    /** #86: ijro etila boshlagan qo'shiq — LRU "ishlatilgan" vaqti. */
    private fun markPlayed(item: MediaItem?) {
        val t = tagOf(item) ?: return
        if (::appCtx.isInitialized) {
            YuzMusicCache.touch(appCtx, t.id)
            // #117: tinglov tarixi — radio tavsiyalari shu profilga qaraydi.
            YuzListenStats.record(appCtx, t)
        }
    }

    /**
     * YuzGram #86: qo'shiq O'YNALAYOTGAN paytning o'zida keyingisini to'liq
     * keshga yuklaymiz.
     *
     * Nima uchun: past internetda muammo — har yangi trek avval YouTube
     * URL'ini ochib, sekin taroqdan 3-4 MB'ni oqim bo'yicha tortadi;
     * biror oyna kechiksa qo'shiq boshlanmaydi. Fayl oldindan to'liq
     * tushib qolsa, navbatdagi qo'shiq LOCAL ochiladi: tarmoq bilan
     * aloqa umuman bo'lmaydi, 403/throttle bu qo'shiqqa tegizilmaydi.
     * Yuklash [backgroundGate] orqali ijroga yo'l beradi — buffering
     * boshlansa darhol to'xtaydi.
     */
    private fun prebufferNext(p: ExoPlayer, from: Int) {
        if (!::appCtx.isInitialized || dlInFlight) return
        val t = queueTracks.getOrNull(from + 1) ?: return
        if (t.videoId.isBlank()) return
        if (YuzMusicCache.isDownloaded(appCtx, t.id)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (prebufferId == t.id && now - prebufferTryAt < 30_000L) return
        prebufferId = t.id
        prebufferTryAt = now
        dlInFlight = true
        val index = from + 1
        Thread {
            val ok = runCatching {
                YuzMusicCache.downloadBlockingExclusive(appCtx, t, ::backgroundGate)
            }.getOrDefault(false)
            Log.i(
                TAG, "prebuffer vid=${t.videoId} ok=$ok " +
                    "rate=${YuzNetPulse.rate().toInt()}B/s"
            )
            dlInFlight = false
            if (!ok) {
                prebufferId = -1L
            } else {
                ui.post { swapToLocal(index, t) }
            }
        }.start()
    }

    /** Yuklangan fayl tayyor — navbatdagi ITEM'ni lokal URI'ga almashtiramiz. */
    private fun swapToLocal(index: Int, t: YuzTrack) {
        val p = player ?: return
        // media3 1.0.2'da replaceMediaItem yo'q — o'chir + joyiga qo'yish.
        // Ikkalasi ham UI ipida ketma-ket: orada hech qanday callback
        // ishga tushmaydi, shuning uchun navbat bir lahza "tushib" qoladi.
        if (index <= p.currentMediaItemIndex || index >= p.mediaItemCount) return
        if (queueTracks.getOrNull(index)?.id != t.id) return
        if (YuzMusicCache.localFile(appCtx, t.id) == null) return
        runCatching {
            p.removeMediaItem(index)
            p.addMediaItem(index, buildItem(t))
        }
        Log.i(TAG, "prebuffer swap idx=$index -> local ${t.title.take(24)}")
    }

    /**
     * #86: fon yuklashi uchun "yo'l berish" qarori — FONDAN chaqiriladi,
     * shuning uchun faqat [gateTicker] ko'chirgan maydonlarni o'qiydi.
     *  -1: bekor qil (ijro tarmoqni kerak qildi);
     *   0: davom et;   >0: shuncha ms dam ol.
     */
    private fun backgroundGate(): Long {
        // #155: ticker to'xtagan bo'lsa (pauza, ekran o'chishi) holat
        // eskirgan — 4 sekunddan eski signal inobatga olinmaydi.
        val fresh = android.os.SystemClock.elapsedRealtime() - gateAt < 4_000L
        if (fresh && gateBuffering) return -1L
        if (!fresh || !gatePlaying) return 0L
        // Ijro zaxirasi 15s'dan kam bo'lsa — tarmoqni pleyerga qoldiramiz.
        return if (gateAhead < 15_000L) 1200L else 0L
    }

    /**
     * #133: foydalanuvchi boshlagan yuklash uchun YUMSHOQ eshik. Fon
     * eshigidan farqi — hech qachon bekor qilmaydi; faqat pleyer
     * buffering'da bo'lsa yoki zaxirasi 10s'dan kam bo'lsa qisqa dam
     * beradi. Shu bilan sekin tarmoqda yuklash ijroni bo'g'maydi va
     * ikkalasi ham bir-birini o'ldirmaydi.
     */
    fun softDownloadGate(): Long {
        // #155: stale holat yuklashni abadiy muzlatib qo'ymasligi uchun —
        // 4 sekunddan eski signal "ijro yo'q" deb hisoblanadi.
        val fresh = android.os.SystemClock.elapsedRealtime() - gateAt < 4_000L
        if (fresh && gateBuffering) return 1500L
        if (!fresh || !gatePlaying) return 0L
        return if (gateAhead < 10_000L) 800L else 0L
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

    /** Buffer o'smay qolsa (tezlikdan kelib chiqqan vaqtda) — nomzodni almashtiramiz. */
    private fun onBufferingStall() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_BUFFERING) {
            Log.i(TAG, "buffering stall vid=${tagOf(p.currentMediaItem)?.videoId}")
            handleItemFailure(p.currentMediaItem)
        }
    }

    /**
     * #152: qo'shiq HAQIQATAN tugamagan bo'lsa (oxirgi tick pozitsiyasi
     * davomiylikdan 20+ sekund orqada) — o'tishni bekor qilib shu joydan
     * boshqa oqim nomzodi bilan davom etamiz. [itemRetries] chegarasi (2)
     * cheksiz qaytish halqasini oldini oladi: uchinchi erta tugashda
     * qo'shiq oddiy o'tadi. true = qaytish bajarildi.
     */
    private fun retryEarlyEnd(p: ExoPlayer, item: MediaItem?, index: Int): Boolean {
        if (item == null || tickDur <= 0L || tickPos <= 0L) return false
        if (tickPos >= tickDur - 20_000L) return false
        if (index !in 0 until p.mediaItemCount) return false
        if (itemRetries >= 2) return false
        itemRetries++
        Log.i(
            TAG,
            "early end pos=$tickPos/$tickDur vid=${tagOf(item)?.videoId} " +
                "-> retry#$itemRetries (oqim almashtirish)"
        )
        tagOf(item)?.videoId?.let { YuzYouTubeApi.noteBadStream(it) }
        tickItem = null
        tickDur = 0L
        runCatching {
            p.seekTo(index, tickPos)
            p.prepare()
            p.playWhenReady = true
        }
        stallMark = android.os.SystemClock.elapsedRealtime()
        ui.removeCallbacks(bufferingWatchdog)
        ui.postDelayed(bufferingWatchdog, 2000L)
        return true
    }

    private fun handleItemFailure(item: MediaItem?) {
        val mediaItem = item ?: return
        val t = tagOf(mediaItem) ?: return
        val p = player ?: return
        // #133: yuklangan fayl buzilgan/yarim bo'lsa pleyer uni qayta-qayta
        // ochishga urinardi va hech qachon ijro etmasdi. Bunday faylni
        // keshdan olib tashlab, SHU index'da oqim manbasini quramiz.
        if (mediaItem.localConfiguration?.uri?.scheme == "file" &&
            (t.videoId.isNotBlank() || t.previewUrl.isNotBlank()) &&
            YuzMusicCache.localFile(appCtx, t.id) != null
        ) {
            Log.i(TAG, "local corrupt -> re-stream id=${t.id} vid=${t.videoId}")
            YuzMusicCache.remove(appCtx, t.id)
            itemRetries = 0
            val idx = p.currentMediaItemIndex
            if (idx in 0 until p.mediaItemCount) {
                runCatching {
                    p.removeMediaItem(idx)
                    p.addMediaItem(idx, buildItem(t))
                    p.seekTo(idx, 0L)
                    p.prepare()
                    p.playWhenReady = true
                }
                stallMark = android.os.SystemClock.elapsedRealtime()
                ui.removeCallbacks(bufferingWatchdog)
                ui.postDelayed(bufferingWatchdog, 2000L)
                return
            }
        }
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
            // #120: STATE_BUFFERING ichida seekTo/prepare qayta
            // onPlaybackStateChanged UYGMAYDI — watchdog qayta qurollansa
            // yangi nomzod ham tiqilsa stall aniqlanadi, aks holda pleyer
            // abadiy BUFFERING'da qolardi (give-up yo'li esa hech qachon
            // ishlamasdi).
            stallMark = android.os.SystemClock.elapsedRealtime()
            ui.removeCallbacks(bufferingWatchdog)
            ui.postDelayed(bufferingWatchdog, 2000L)
            return
        }
        itemRetries = 0
        consecutiveSkips++
        Log.i(TAG, "give-up vid=${t.videoId} skips=$consecutiveSkips -> bg download + skip")
        p.playWhenReady = false
        silentDownload(t)
        // #86 bo'ron bekori: ketma-ket 4 qo'shiq boshlanmasa tarmoq
        // o'lgan belgi — navbatni aylib urinish taroqni ham, batareyani
        // ham yeydi. To'xtaymiz va foydalanuvchiga ochiq aytamiz.
        if (consecutiveSkips >= 4) {
            postStatus(appCtx.getString(R.string.musiqa_st_weaknet))
            Log.i(TAG, "skip storm -> parked")
            return
        }
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
            // #86: shu yuklash KEYINGI qo'shiqni ijrosi bilan raqobatlashadi
            // — eshik ijroga birinchi navbat beradi.
            val ok = runCatching {
                YuzMusicCache.downloadBlockingExclusive(appCtx, t, ::backgroundGate)
            }.getOrDefault(false)
            Log.i(TAG, "silentDownload vid=${t.videoId} ok=$ok")
            dlInFlight = false
            if (ok) ui.post { notifyItemChanged(player?.currentMediaItem) }
        }.start()
    }

    // ---------------------------------------------------------------- radio

    /**
     * YuzGram #98: navbatni uzluksiz to'ldirish. Zanjir (#117):
     *  1) ijrochining boshqa qo'shiqlari (o'xshash),
     *  2) ijrochi + qo'shiq nomi (mospot),
     *  3) aylanadigan hit hovizi — o'zbekcha eng ko'p eshitilgan va
     *     rus xitlari almashinib,
     *  4) tinglov tarixi — foydalanuvchi eng ko'p eshitgan ijrochi va
     *     qo'shiqlar seed'lari.
     * Har seeddan en ko'pi bilan 5 qator olinadi — navbat aralash bo'ladi.
     * Hech biri natija bermasa 3 s dan keyin bir marta qayta uriniladi
     * (sust tarmoqda cheksiz urinish bo'lmasligi uchun 4 marta bilan cheklangan).
     */
    private fun extendQueue(retryStart: Boolean = false) {
        if (extending || queueTracks.isEmpty()) return
        if (extendAttempts >= 4) {
            if (retryStart) postStatus(appCtx.getString(R.string.musiqa_st_weaknet))
            return
        }
        extending = true
        extendAttempts++
        val anchor = currentTrack() ?: queueTracks.lastOrNull()
        val artist = anchor?.artist?.trim().orEmpty()
        val anchorLc = artist.lowercase()
        val baseSeeds = ArrayList<String>()
        if (artist.isNotEmpty()) baseSeeds.add(artist)
        if (artist.isNotEmpty() && anchor != null) baseSeeds.add((artist + " " + anchor.title).trim())
        // #115: seedRotate'ni modlusida aylantiramiz — cheksiz o'sib Int
        // chegarasidan o'tsa manfiy qoldiq (negative index) crash berar edi.
        baseSeeds.add(hitSeeds[seedRotate])
        seedRotate = (seedRotate + 1) % hitSeeds.size
        val appended = ArrayList<YuzTrack>()
        val gen = queueGen
        Thread {
            // #117: tinglov profili seed'lari — #126: bu qism SharedPreferences
            // JSON o'qishini qiladi, shuning uchun UI emas, fon ipida quriladi
            // (iyova qotib qolmasligi uchun).
            val seeds = ArrayList(baseSeeds)
            runCatching {
                for (a in YuzListenStats.topArtists(appCtx, 2, setOf(anchorLc))) {
                    val s = a + " qoshiklari"
                    if (s !in seeds) seeds.add(s)
                }
                YuzListenStats.topTracks(appCtx, 1).firstOrNull { !it.lowercase().startsWith(anchorLc) }
                    ?.let { if (it !in seeds) seeds.add(it) }
            }
            Log.i(TAG, "extendQueue seeds=$seeds attempt=$extendAttempts")
            var i = 0
            while (appended.size < 10 && i < seeds.size) {
                val q = seeds[i++]
                // #115: gateway izlanuvlisi istisno otsa butun ip crash
                // bo'lardi — har urinish alohida qalq ostida.
                val more = runCatching { YuzMusicGateway.search(q, 20) }
                    .getOrDefault(emptyList())
                    .ifEmpty { runCatching { YuzYouTubeApi.search(q, 20) }.getOrDefault(emptyList()) }
                // #117: bitta seeddan ko'pi bilan 5 qator — shunda har
                // kengaytirishda o'xshash + o'zbek xit + rus xiti aralash
                // keladi, bir manba navbatni butun egallab qoymaydi.
                var taken = 0
                for (t in more) {
                    if (taken >= 5 || appended.size >= 10) break
                    if (t.videoId.isNotBlank() && seenIds.add(t.id)) {
                        appended.add(t)
                        taken++
                    }
                }
            }
            ui.post {
                // #115: post tanasidagi istisno bevosita process crash bo'ladi —
                // butun holatlashuv qalq ostida.
                runCatching {
                    extending = false
                    // #120: qidiruv ketayotganda navbat almashtirilsa (yangi
                    // playQueue) — eski urug' natijalari yangi navbatga
                    // ilinmasin.
                    if (gen != queueGen) return@post
                    if (appended.isEmpty()) {
                        // navbat bo'sh qolmasin — bir marta kechikib qayta urinadi
                        val p = player
                        if (radio && p != null && p.playbackState == Player.STATE_ENDED) {
                            ui.postDelayed({
                                val pp = player ?: return@postDelayed
                                if (pp.playbackState == Player.STATE_ENDED) extendQueue(retryStart = true)
                            }, 3000L)
                        }
                        return@post
                    }
                    val p = player ?: return@post
                    extendAttempts = 0
                    queueTracks = queueTracks + appended
                    val startIndex = p.mediaItemCount
                    p.addMediaItems(appended.map { buildItem(it) })
                    if (retryStart) {
                        postStatus(null)
                        // #133: STATE_ENDED'dan keyin prepare() joriy (oxirgi)
                        // eski elementda qolib, yangi qatorlar boshlanmasdi.
                        // Endi birinchi YANGI qatorga aniq o'tamiz.
                        p.seekTo(startIndex, 0L)
                        p.prepare()
                        p.playWhenReady = true
                    }
                    for (o in observers) runCatching { o.onItemChanged(p.currentMediaItem) }
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- notify

    private fun notifyItemChanged(item: MediaItem?) {
        for (o in observers) runCatching { o.onItemChanged(item) }
    }

    private fun notifyPlaying(playing: Boolean) {
        // #78: ijro holati bildirishnomaga ham yoziladi. #122: xizmatni
        // ko'tarish ENDI SHU YERDA EMAS — "playing" BUFFERING paytda ham
        // true bo'lib qolsa, media3 startForeground()ni 5s ichida qila
        // olmay ForegroundServiceDidNotStartInTimeException crash bo'ladi.
        // Ko'tarish STATE_READY'da: startMusicServiceOnce().
        if (playing) ensureGateTicker() else {
            gatePlaying = false
            gateBuffering = false
            musicServiceStarted = false // #122: to'xtagach qayta ko'tarish mumkin
        }
        for (o in observers) runCatching { o.onPlayingChanged(playing) }
    }

    /** #122: YuzMusicService'ni faqat bir marta, player READY bo'lganda ko'taradi. */
    private fun startMusicServiceOnce() {
        if (musicServiceStarted || !::appCtx.isInitialized) return
        // #122b: bildirishnoma yo'q bo'lsa media3 startForeground()ni
        // HECH QACHON chaqirmaydi (DefaultMediaNotificationProvider
        // areNotificationsEnabled()==false da null qaytadi) — 5s'dan
        // so'ng ForegroundServiceDidNotStartInTimeException crash.
        // Shuning uchun kanal bloklangan/permatsiya yo'q holda xizmatni
        // umuman ko'tarmaymiz (ijro ilova ichida davom etaveradi).
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        val chanOk = nm?.getNotificationChannel(YuzMusicNotification.CHANNEL_ID)?.let {
            it.importance != android.app.NotificationManager.IMPORTANCE_NONE
        } ?: true
        if (!androidx.core.app.NotificationManagerCompat.from(appCtx).areNotificationsEnabled() || !chanOk) {
            Log.i(TAG, "music service skipped: notifications disabled/blocked")
            return
        }
        musicServiceStarted = true
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                appCtx,
                android.content.Intent(appCtx, uz.kodlikino.top4.ui.YuzMusicService::class.java)
            )
        }.onFailure {
            // Ko'tarilmasa flag "yopishib" qolmasin — keyingi READY'da qayta urinamiz.
            musicServiceStarted = false
            Log.e(TAG, "music service start failed: ${it.message}", it)
        }
    }

    /** Xizmat o'chganda chaqiriladi — keyingi READY'da qayta ko'tarish mumkin bo'lsin. */
    fun onMusicServiceStopped() {
        musicServiceStarted = false
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

    // #149: playlist fayliga UI (o'qish), "Sevimli" tugmasi (fon ipida qayta
    // yozish) va playlist qo'shish (IO) bir vaqtda tegar edi — yozuvlar
    // aralashib JSON buzilib, butun ro'yxat yo'qolishi mumkin.
    private val plLock = Any()

    fun playlists(context: Context): MutableList<Playlist> = synchronized(plLock) {
        val out = ArrayList<Playlist>()
        try {
            val f = plFile(context)
            if (!f.exists()) return@synchronized out
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
        out
    }

    /** Bor playlist'ga qo'shadi; yo'q bo'lsa — yangi yaratadi. */
    fun playlistAdd(context: Context, name: String, track: YuzTrack): Boolean = synchronized(plLock) {
        val all = playlists(context)
        val existing = all.indexOfFirst { it.name == name }
        val tracks =
            if (existing >= 0) all[existing].tracks.filterNot { it.id == track.id } + track
            else listOf(track)
        if (existing >= 0) all[existing] = Playlist(name, tracks) else all.add(Playlist(name, tracks))
        savePlaylists(context, all)
    }

    fun playlistRemove(context: Context, name: String): Boolean = synchronized(plLock) {
        savePlaylists(context, playlists(context).filter { it.name != name })
    }

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
