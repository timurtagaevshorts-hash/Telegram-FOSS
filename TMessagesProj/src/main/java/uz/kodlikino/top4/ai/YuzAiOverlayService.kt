package uz.kodlikino.top4.ai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import org.telegram.ui.LaunchActivity
import uz.kodlikino.top4.ui.YuzAiOrbView
import uz.kodlikino.top4.utils.YuzMusicEngine
import uz.kodlikino.top4.utils.YuzMusicRepository
import uz.kodlikino.top4.utils.YuzgramAiBrain
import kotlin.math.abs

/**
 * #135: tezkor AI tugmasi — ilovadan chiqqan holda ham ekran burchagida
 * turadigan suzuvchi orb. Tinch holatda kichik; bosib ushlab turilganda
 * kattalashadi va mikrofonni ochadi, qo'yib yuborilganda buyruq darhol
 * yuboriladi (/voice: xabar, musiqa, savol). Qisqa bosish ham ishlaydi —
 * u holda yozuv jimlik aniqlamaguncha davom etadi. Oyna fokus olmaydi va
 * faqat o'z maydonidagi teginishlarni ushlaydi.
 */
class YuzAiOverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var bubble: FrameLayout? = null
    private var bubbleButton: FrameLayout? = null
    private var orb: YuzAiOrbView? = null

    private var voice: YuzAiVoice? = null
    private var busy = false
    private var musicBusy = false
    private var pushToTalk = false
    private var pendingPeer: YuzAiEngine.Peer? = null
    @Volatile private var destroyed = false

    private val recorder = YuzAiRecorder { level ->
        mainHandler.post {
            if (!destroyed && busy) orb?.level = level
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        destroyed = false
        running = true
        if (!Settings.canDrawOverlays(this)) {
            running = false
            stopSelf()
            return
        }
        createChannel()
        if (!startForegroundSafely()) {
            running = false
            stopSelf()
            return
        }
        voice = YuzAiVoice(this, voiceCallbacks)
        addBubble()
        // DNS+TLS oldindan isitiladi — birinchi buyruq handshake'ni kutmaydi.
        scope.launch(Dispatchers.IO) { runCatching { YuzAiCloud.warmup() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        running = false
        YuzAiChatPickerActivity.listener = null
        scope.cancel()
        runCatching { recorder.cancel() }
        runCatching { voice?.release() }
        voice = null
        bubble?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        bubble = null
        bubbleButton = null
        orb = null
        super.onDestroy()
    }

    // ------------------------------------------------------------- bubble

    private fun addBubble() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm
        val windowSize = dp(WINDOW_DP)
        val idleSize = dp(IDLE_DP)

        val orbView = YuzAiOrbView(this)
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_yuz_mic)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        val button = FrameLayout(this).apply {
            contentDescription = getString(R.string.ai_quick_a11y)
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6101A26.toInt())
                setStroke(dp(1), 0x59FFFFFF)
            }
            elevation = dp(6).toFloat()
            addView(
                orbView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }

        // Oyna kattalashgan holatga yetadi; ko'rinadigan tugma tinch holatda kichik.
        val holder = FrameLayout(this).apply {
            addView(button, FrameLayout.LayoutParams(idleSize, idleSize, Gravity.CENTER))
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            windowSize, windowSize, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - windowSize - dp(8)
            y = (screenHeight() * 0.6f).toInt().coerceAtMost(maxY(windowSize))
        }
        attachTouch(button, lp)

        val added = runCatching { wm.addView(holder, lp) }.isSuccess
        if (!added) {
            stopSelf()
            return
        }
        bubble = holder
        bubbleButton = button
        orb = orbView
        setOrb(YuzAiOrbView.Mode.IDLE)
    }

    private fun attachTouch(view: View, lp: WindowManager.LayoutParams) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        var downAt = 0L
        val slop = dp(8)

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragged = false
                    downAt = SystemClock.elapsedRealtime()
                    if (busy) {
                        // Yozuv ketayotganda yana bosish — bekor qilish.
                        recorder.cancel()
                    } else {
                        if (voice?.isSpeaking() == true) voice?.stopSpeaking()
                        beginListening()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragged && (abs(dx) > slop || abs(dy) > slop)) {
                        dragged = true
                        // Sudrab ko'chirish — tasodifiy yozuvni to'xtatadi.
                        if (busy) recorder.cancel()
                    }
                    if (dragged) {
                        lp.x = (startX + dx).toInt()
                        lp.y = (startY + dy).toInt()
                        clampPosition(lp)
                        bubble?.let { runCatching { windowManager?.updateViewLayout(it, lp) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        bubble?.let { snapToEdge(it, lp) }
                    } else if (busy && SystemClock.elapsedRealtime() - downAt >= HOLD_MS) {
                        // Ushlab turilgan — qo'yib yuborilishi bilan yakunlaymiz.
                        pushToTalk = true
                        recorder.finish()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (busy) recorder.cancel()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, lp: WindowManager.LayoutParams) {
        val size = lp.width
        lp.x = if (lp.x + size / 2 < screenWidth() / 2) 0 else screenWidth() - size
        clampPosition(lp)
        runCatching { windowManager?.updateViewLayout(view, lp) }
    }

    private fun clampPosition(lp: WindowManager.LayoutParams) {
        val size = lp.width
        lp.x = lp.x.coerceIn(0, (screenWidth() - size).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(dp(8), maxY(size))
    }

    private fun maxY(size: Int): Int =
        (screenHeight() - size - dp(8)).coerceAtLeast(dp(8))

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- voice

    private val voiceCallbacks = object : YuzAiVoice.Callbacks {
        override fun onListeningStarted() = Unit
        override fun onPartial(text: String) = Unit
        override fun onResult(text: String) = Unit
        override fun onListenError(message: String, retry: Boolean) = Unit
        override fun onListeningStopped() = Unit
        override fun onSpeakFinished() {
            if (!busy) setOrb(YuzAiOrbView.Mode.IDLE)
        }
    }

    private fun beginListening() {
        if (busy) return
        val v = voice ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            speak(getString(R.string.ai_mic_permission))
            return
        }
        busy = true
        pushToTalk = false
        recorder.prepare()
        setOrb(YuzAiOrbView.Mode.LISTENING)
        orb?.level = 0f
        v.playReadySignal()
        val account = UserConfig.selectedAccount
        // Ism hovuzini yozuv davomida qurib qo'yamiz — transkript kelganda kutish yo'q.
        scope.launch(Dispatchers.IO) { runCatching { YuzAiEngine.peers(account) } }
        scope.launch {
            // Signal tugashi bilan yozishni boshlaymiz — beep mikrofonga
            // buyruq bo'lib tushmasin (80 ms signal + qisqa quyruq).
            delay(90L)
            if (destroyed) return@launch
            if (recorder.wasCancelled()) {
                busy = false
                setOrb(YuzAiOrbView.Mode.IDLE)
                return@launch
            }
            val wav = try {
                withContext(Dispatchers.IO) { recorder.recordBlocking() }
            } catch (_: Exception) {
                null
            }
            orb?.level = 0f
            if (destroyed) return@launch
            if (recorder.wasCancelled()) {
                busy = false
                setOrb(YuzAiOrbView.Mode.IDLE)
                return@launch
            }
            if (wav == null) {
                busy = false
                setOrb(YuzAiOrbView.Mode.IDLE)
                // Push-to-talk'da jim qo'yib yuborilgan bo'lsa — uzun uzr emas.
                if (!pushToTalk) speak(getString(R.string.ai_heard_none))
                return@launch
            }
            setOrb(YuzAiOrbView.Mode.THINKING)
            val names = try {
                withContext(Dispatchers.IO) {
                    YuzAiEngine.peers(account).map { it.name }.take(120)
                }
            } catch (_: Exception) {
                emptyList()
            }
            val res = try {
                withContext(Dispatchers.IO) { YuzAiCloud.voice(wav, names) }
            } catch (_: Exception) {
                null
            }
            busy = false
            if (destroyed) return@launch
            if (res == null) {
                setOrb(YuzAiOrbView.Mode.IDLE)
                // #157: limit (429) sababini aytamiz — "aloqa uzildi" emas.
                val rate = YuzAiCloud.lastRateMessage
                speak(if (!rate.isNullOrBlank()) rate else getString(R.string.ai_cloud_fallback))
                return@launch
            }
            if (res.transcript.isBlank()) {
                setOrb(YuzAiOrbView.Mode.IDLE)
                speak(getString(R.string.ai_voice_slow))
                return@launch
            }
            handleResult(res, account)
        }
    }

    private fun handleResult(res: YuzVoiceResult, account: Int) {
        val text = res.transcript.trim()
        val pending = pendingPeer
        if (pending != null) {
            pendingPeer = null
            sendTo(pending, text, account)
            return
        }
        val intent = try {
            YuzAiEngine.parse(text, account)
        } catch (_: Exception) {
            YuzAiEngine.Intent.Ask(text)
        }
        if (intent !is YuzAiEngine.Intent.Ask) {
            execute(intent, account)
            return
        }
        val named = if (res.peerName.isNotBlank()) {
            try {
                YuzAiEngine.findByNames(res.peerName, account)
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()
        if (named.size > 1 && res.intent in MULTI_PEER_INTENTS) {
            val kind = when (res.intent) {
                "send" -> YuzAiEngine.ChoiceKind.SEND
                "call" -> YuzAiEngine.ChoiceKind.CALL
                else -> YuzAiEngine.ChoiceKind.OPEN
            }
            showPicker(
                YuzAiEngine.Intent.NeedChoice(
                    res.peerName.trim(),
                    named.map {
                        YuzAiEngine.ChoiceOption(
                            it,
                            if (kind == YuzAiEngine.ChoiceKind.SEND) res.message.trim() else ""
                        )
                    },
                    kind
                ),
                account
            )
            return
        }
        val peer = named.firstOrNull()
        when (res.intent) {
            "send" -> when {
                peer == null -> answer(text, res.answer)
                res.message.isBlank() -> askBody(peer)
                else -> sendTo(peer, res.message, account)
            }
            "goto" -> {
                if (res.goto == "musiqa" && res.message.isNotBlank()) {
                    playMusic(res.message)
                } else {
                    openApp()
                }
            }
            "call", "open" -> openApp()
            else -> answer(text, res.answer)
        }
    }

    private fun execute(intent: YuzAiEngine.Intent, account: Int) {
        when (intent) {
            is YuzAiEngine.Intent.SendText -> sendTo(intent.peer, intent.text, account)
            is YuzAiEngine.Intent.NeedBody -> askBody(intent.peer)
            is YuzAiEngine.Intent.NotFound ->
                if (intent.name.isBlank()) speak(getString(R.string.ai_need_peer))
                else speak(getString(R.string.ai_not_found, intent.name))
            is YuzAiEngine.Intent.OpenChat -> openApp()
            is YuzAiEngine.Intent.Call -> openApp()
            is YuzAiEngine.Intent.Goto -> openApp()
            is YuzAiEngine.Intent.PlayMusic -> playMusic(intent.query)
            is YuzAiEngine.Intent.NeedChoice -> showPicker(intent, account)
            is YuzAiEngine.Intent.Ask -> answer(intent.question, null)
        }
    }

    /**
     * Bir xil ismli bir nechta chat: ekran markazida avatarli ro'yxat.
     * Tanlov Activity callback'i orqali qaytadi (xizmatda ActivityResult yo'q).
     * Oyna ochilmasa — ishonchli zaxira: variantlarni ovoz bilan aytamiz.
     */
    private fun showPicker(choice: YuzAiEngine.Intent.NeedChoice, account: Int) {
        pendingPeer = null
        setOrb(YuzAiOrbView.Mode.IDLE)
        val peers = choice.options.map { it.peer }
        val message = if (choice.kind == YuzAiEngine.ChoiceKind.SEND) {
            choice.options.firstOrNull()?.text.orEmpty()
        } else {
            ""
        }
        val started = runCatching {
            YuzAiChatPickerActivity.listener = { index ->
                YuzAiChatPickerActivity.listener = null
                val option = choice.options.getOrNull(index)
                if (option != null) {
                    when (choice.kind) {
                        YuzAiEngine.ChoiceKind.SEND ->
                            if (option.text.isBlank()) askBody(option.peer)
                            else sendTo(option.peer, option.text, account)
                        YuzAiEngine.ChoiceKind.OPEN,
                        YuzAiEngine.ChoiceKind.CALL -> openApp()
                    }
                }
            }
            startActivity(
                YuzAiChatPickerActivity.intent(this, message, peers, account, overlay = true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!started) {
            YuzAiChatPickerActivity.listener = null
            speakWhich(choice.label, peers)
        }
    }

    /** Zaxira: tanlov oynasi ochilmasa — variantlarni ovoz bilan aytamiz. */
    private fun speakWhich(label: String, peers: List<YuzAiEngine.Peer>) {
        pendingPeer = null
        val names = peers.take(3).joinToString(", ") { p ->
            val uname = p.username
            if (uname.isNullOrBlank()) p.name else "${p.name} (@$uname)"
        }
        speak(getString(R.string.ai_choose_peer, label) + " " + names)
        setOrb(YuzAiOrbView.Mode.IDLE)
    }

    private fun sendTo(peer: YuzAiEngine.Peer, text: String, account: Int) {
        val body = text.trim()
        if (body.isBlank()) {
            askBody(peer)
            return
        }
        val mid = try {
            SendMessagesHelper.getInstance(account).sendMessage(
                SendMessagesHelper.SendMessageParams.of(
                    body, peer.dialogId, null, null, null, false,
                    null, null, null, true, 0, null, false
                )
            )
        } catch (_: Exception) {
            0L
        }
        if (mid != 0L) {
            speak(getString(R.string.ai_sent_short))
        } else {
            speak(getString(R.string.ai_send_failed))
        }
    }

    private fun askBody(peer: YuzAiEngine.Peer) {
        pendingPeer = peer
        speak(getString(R.string.ai_need_body, peer.name))
    }

    private fun playMusic(rawQuery: String) {
        if (musicBusy) return
        musicBusy = true
        val query = YuzAiEngine.refineMusicQuery(rawQuery)
        val label = if (query.isBlank()) getString(R.string.ai_music_chart_label) else query
        speak(getString(R.string.ai_music_searching, label))
        scope.launch {
            val list = try {
                withContext(Dispatchers.IO) {
                    if (query.isBlank()) {
                        YuzMusicRepository.chart()
                    } else {
                        YuzMusicRepository.search(query).ifEmpty { YuzMusicRepository.chart() }
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
            musicBusy = false
            if (destroyed) return@launch
            if (list.isEmpty()) {
                speak(getString(R.string.ai_music_empty))
                return@launch
            }
            val started = runCatching {
                YuzMusicEngine.playQueue(applicationContext, list, 0)
            }.isSuccess
            if (!started) {
                speak(getString(R.string.ai_music_empty))
                return@launch
            }
            speak(getString(R.string.ai_music_playing, list[0].title, list[0].artist))
        }
    }

    private fun answer(question: String, cloudAnswer: String?) {
        val q = question.trim()
        if (q.isBlank()) return
        YuzgramAiBrain.quickAnswer(q)?.let {
            speak(it)
            return
        }
        if (!cloudAnswer.isNullOrBlank()) {
            speak(cloudAnswer)
            return
        }
        setOrb(YuzAiOrbView.Mode.THINKING)
        scope.launch {
            val text = try {
                YuzgramAiBrain.answer(q)
            } catch (_: Exception) {
                getString(R.string.ai_no_network)
            }
            if (!destroyed) speak(text)
        }
    }

    private fun speak(text: String) {
        val spoken = YuzAiVoice.speakable(text)
        if (spoken.isBlank()) return
        setOrb(YuzAiOrbView.Mode.SPEAKING)
        voice?.speak(spoken)
    }

    private fun openApp() {
        speak(getString(R.string.ai_quick_open_app))
        runCatching {
            startActivity(
                Intent(this, LaunchActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            )
        }
    }

    private fun setOrb(mode: YuzAiOrbView.Mode) {
        val view = orb ?: return
        view.mode = mode
        // Tinch holatda animatsiya to'xtaydi (overlay soatlab turadi —
        // doimiy kadr batareyani yeydi). Faqat ish vaqtida ko'rinadi.
        view.visibility = if (mode == YuzAiOrbView.Mode.IDLE) View.INVISIBLE else View.VISIBLE
        // Tinch: kichik tugma; eshitish/o'ylash/gapirish: kattalashadi.
        val target = if (mode == YuzAiOrbView.Mode.IDLE) 1f else ACTIVE_SCALE
        bubbleButton?.animate()
            ?.scaleX(target)?.scaleY(target)
            ?.setDuration(if (mode == YuzAiOrbView.Mode.IDLE) 150L else 110L)
            ?.start()
    }

    // --------------------------------------------------------- foreground

    private fun startForegroundSafely(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.player)
        .setContentTitle(getString(R.string.ai_quick_notif_title))
        .setContentText(getString(R.string.ai_quick_notif_text))
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setColor(ContextCompat.getColor(this, R.color.yuz_action))
        .setContentIntent(
            PendingIntent.getActivity(
                this, 41,
                Intent(this, LaunchActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ai_quick_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            setSound(null, null)
        }
        runCatching { nm.createNotificationChannel(channel) }
    }

    companion object {
        private const val CHANNEL_ID = "yuz_ai_quick"
        private const val NOTIF_ID = 23071
        private const val WINDOW_DP = 64   // oyna: kattalashgan orb ham sig'adi
        private const val IDLE_DP = 48     // tinch holatdagi ko'rinadigan tugma
        private const val HOLD_MS = 450L   // shundan uzoq ushlansa — qo'yishda yakun
        private const val ACTIVE_SCALE = 64f / 48f
        private val MULTI_PEER_INTENTS = setOf("send", "call", "open")

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, YuzAiOverlayService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, YuzAiOverlayService::class.java))
        }
    }
}
