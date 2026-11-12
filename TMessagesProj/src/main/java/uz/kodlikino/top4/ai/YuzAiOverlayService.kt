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

    /**
     * Aniq fazalar: "band" degan yagona bayroq ikkinchi bosishni yutib yuborardi
     * (oyna javob kutayotganda bekor qilib bo'lmasdi). Endi har bosish fazaga
     * qarab ishlaydi: IDLE -> tinglash, LISTENING -> yakunlash, THINKING -> bekor,
     * SPEAKING -> ovozni to'xtatish.
     */
    private enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

    /**
     * Tinglash yo'li: tizimli SpeechRecognizer (tez — audio yuklanmaydi) yoki
     * zaxira bulut oqimi (AudioRecord + /voice). Tizim tanishi mavjud bo'lmasa
     * yoki xato bersa — bulutga tushamiz.
     */
    private enum class ListenMode { NONE, RECOGNIZER, CLOUD }

    @Volatile private var phase = Phase.IDLE
    @Volatile private var listenMode = ListenMode.NONE
    @Volatile private var listenStartedAt = 0L
    private var sessionToken = 0
    private var sessionAccount = 0
    private var interactionSeq = 0
    @Volatile private var peerNamesCache: List<String> = emptyList()
    private var voice: YuzAiVoice? = null
    private var musicBusy = false
    private var pushToTalk = false
    private var pendingPeer: YuzAiEngine.Peer? = null
    @Volatile private var destroyed = false

    private val recorder = YuzAiRecorder { level ->
        mainHandler.post {
            if (!destroyed && phase == Phase.LISTENING) orb?.level = level
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
        // Ism hovuzi ham tayyor turisin: birinchi buyruqda qurish kutilmaydi.
        scope.launch(Dispatchers.IO) {
            runCatching { YuzAiEngine.peers(UserConfig.selectedAccount) }
        }
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
                setColor(0xE61C1026.toInt())
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
                    when (phase) {
                        Phase.IDLE -> beginListening()
                        Phase.LISTENING ->
                            // Ikkinchi bosish — yozuvni yakunlab, aytilganini yuboramiz.
                            when (listenMode) {
                                ListenMode.RECOGNIZER ->
                                    if (listenStartedAt != 0L &&
                                        SystemClock.elapsedRealtime() - listenStartedAt > 350L
                                    ) voice?.finishListening()
                                    else abortListen()
                                ListenMode.CLOUD ->
                                    if (recorder.isRecording()) recorder.finish()
                                    else { recorder.cancel(); resetIdle() }
                                ListenMode.NONE -> resetIdle()
                            }
                        Phase.THINKING -> cancelInteraction()
                        Phase.SPEAKING -> {
                            voice?.stopSpeaking()
                            resetIdle()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragged && (abs(dx) > slop || abs(dy) > slop)) {
                        dragged = true
                        // Sudrab ko'chirish — tasodifiy yozuvni to'xtatadi.
                        when (phase) {
                            Phase.LISTENING -> abortListen()
                            Phase.THINKING -> cancelInteraction()
                            else -> Unit
                        }
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
                    } else if (phase == Phase.LISTENING &&
                        SystemClock.elapsedRealtime() - downAt >= HOLD_MS
                    ) {
                        // Ushlab turilgan — qo'yib yuborilishi bilan yakunlaymiz.
                        pushToTalk = true
                        when (listenMode) {
                            ListenMode.RECOGNIZER -> voice?.finishListening()
                            ListenMode.CLOUD -> recorder.finish()
                            ListenMode.NONE -> Unit
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    when (phase) {
                        Phase.LISTENING -> abortListen()
                        Phase.THINKING -> cancelInteraction()
                        else -> Unit
                    }
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

        override fun onLevel(level: Float) {
            if (listenMode == ListenMode.RECOGNIZER && phase == Phase.LISTENING) {
                orb?.level = level
            }
        }

        override fun onResult(text: String) {
            if (listenMode != ListenMode.RECOGNIZER) return
            listenMode = ListenMode.NONE
            listenStartedAt = 0L
            val token = sessionToken
            if (!isCurrent(token)) return
            orb?.level = 0f
            val said = text.trim()
            if (said.isBlank()) {
                // Tizim tanimadi — bulut zaxirasi (foydalanuvchi qayta gapiradi).
                cloudListen(token, sessionAccount)
                return
            }
            phase = Phase.THINKING
            setOrb(YuzAiOrbView.Mode.THINKING)
            handleUtterance(said, sessionAccount)
        }

        override fun onListenError(message: String, retry: Boolean) {
            if (listenMode != ListenMode.RECOGNIZER) return
            listenMode = ListenMode.NONE
            listenStartedAt = 0L
            val token = sessionToken
            if (!isCurrent(token)) return
            if (message.isNotBlank()) {
                // Ruxsat/tarmoq kabi qat'iy xato — qayta tinglashning foydasi yo'q.
                speak(message)
                return
            }
            // Tanilmadi / jim qoldi — AudioRecord + /voice zaxirasiga o'tamiz.
            cloudListen(token, sessionAccount)
        }

        override fun onListeningStopped() = Unit

        override fun onSpeakFinished() {
            if (phase == Phase.SPEAKING) resetIdle()
        }
    }

    private fun isCurrent(token: Int): Boolean = !destroyed && token == interactionSeq

    private fun resetIdle() {
        phase = Phase.IDLE
        listenMode = ListenMode.NONE
        listenStartedAt = 0L
        setOrb(YuzAiOrbView.Mode.IDLE)
    }

    /** Javob kutish/xabar yuborishni bekor qiladi — kechikkan natija aytilmaydi. */
    private fun cancelInteraction() {
        interactionSeq++
        voice?.stopSpeaking()
        resetIdle()
    }

    private fun beginListening() {
        if (phase != Phase.IDLE) return
        val v = voice ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            speak(getString(R.string.ai_mic_permission))
            return
        }
        val account = UserConfig.selectedAccount
        val token = ++interactionSeq
        sessionToken = token
        sessionAccount = account
        phase = Phase.LISTENING
        pushToTalk = false
        listenMode = ListenMode.NONE
        listenStartedAt = 0L
        recorder.prepare()
        setOrb(YuzAiOrbView.Mode.LISTENING)
        orb?.level = 0f
        // Ism hovuzini yozuv davomida qurib qo'yamiz — transkript kelganda kutish yo'q.
        scope.launch(Dispatchers.IO) {
            runCatching { YuzAiEngine.peers(account) }.getOrNull()?.let { peers ->
                peerNamesCache = peers.map { it.name }.take(120)
            }
        }
        // Uzoq jim turgandan keyin TLS qayta qurilmasin — fon rejimida isitamiz.
        scope.launch(Dispatchers.IO) { runCatching { YuzAiCloud.warmup() } }
        // TEZKOR YO'L: tizimli nutq tanishi — audio yuklanmaydi, matn kelishi
        // bilan buyruq lokal bajariladi. Mavjud bo'lmasa — bulut zaxirasi.
        if (v.canListen()) {
            listenMode = ListenMode.RECOGNIZER
            v.playReadySignal()
            mainHandler.postDelayed({
                if (!isCurrent(token) || phase != Phase.LISTENING ||
                    listenMode != ListenMode.RECOGNIZER
                ) return@postDelayed
                listenStartedAt = SystemClock.elapsedRealtime()
                v.startListening(announce = false)
            }, 60L)
        } else {
            cloudListen(token, account)
        }
    }

    /**
     * Zaxira yo'l: AudioRecord+VAD -> worker /voice (STT+niyat+javob).
     * Tizim tanishi mavjud bo'lmagan yoki xato bergan holatda ishlatiladi.
     */
    private fun cloudListen(token: Int, account: Int) {
        if (!isCurrent(token)) return
        listenMode = ListenMode.CLOUD
        phase = Phase.LISTENING
        pushToTalk = false
        listenStartedAt = 0L
        setOrb(YuzAiOrbView.Mode.LISTENING)
        orb?.level = 0f
        recorder.prepare()
        voice?.playReadySignal()
        scope.launch {
            // Signal tugashi bilan yozishni boshlaymiz — beep mikrofonga
            // buyruq bo'lib tushmasin (80 ms signal + qisqa quyruq).
            delay(70L)
            if (!isCurrent(token) || phase != Phase.LISTENING ||
                listenMode != ListenMode.CLOUD
            ) return@launch
            if (recorder.wasCancelled()) {
                resetIdle()
                return@launch
            }
            listenStartedAt = SystemClock.elapsedRealtime()
            val wav = try {
                withContext(Dispatchers.IO) { recorder.recordBlocking() }
            } catch (_: Exception) {
                null
            }
            orb?.level = 0f
            if (!isCurrent(token)) return@launch
            if (recorder.wasCancelled()) {
                resetIdle()
                return@launch
            }
            if (wav == null) {
                resetIdle()
                // Push-to-talk'da jim qo'yib yuborilgan bo'lsa — uzun uzr emas.
                if (!pushToTalk) speak(getString(R.string.ai_heard_none))
                return@launch
            }
            phase = Phase.THINKING
            listenMode = ListenMode.NONE
            listenStartedAt = 0L
            setOrb(YuzAiOrbView.Mode.THINKING)
            var names = peerNamesCache
            if (names.isEmpty()) {
                names = try {
                    withContext(Dispatchers.IO) {
                        YuzAiEngine.peers(account).map { it.name }.take(120)
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (!isCurrent(token)) return@launch
            val res = try {
                withContext(Dispatchers.IO) { YuzAiCloud.voice(wav, names) }
            } catch (_: Exception) {
                null
            }
            if (!isCurrent(token)) return@launch
            if (res == null) {
                resetIdle()
                // #157: limit (429) sababini aytamiz — "aloqa uzildi" emas.
                val rate = YuzAiCloud.lastRateMessage
                speak(if (!rate.isNullOrBlank()) rate else getString(R.string.ai_cloud_fallback))
                return@launch
            }
            if (res.transcript.isBlank()) {
                resetIdle()
                speak(getString(R.string.ai_voice_slow))
                return@launch
            }
            resetIdle()
            handleResult(res, account)
        }
    }

    /** Tizim tanishidan kelgan matn: lokal buyruq darhol, savol — miyaga. */
    private fun handleUtterance(text: String, account: Int) {
        val pending = pendingPeer
        if (pending != null) {
            pendingPeer = null
            sendTo(pending, text, account)
            return
        }
        // Juda qisqa yoki tushunarsiz — taxmin qilmaymiz, qayta gapirishni so'raymiz.
        if (YuzAiEngine.isUnclear(text)) {
            speak(getString(R.string.ai_unclear))
            return
        }
        val intent = try {
            YuzAiEngine.parse(text, account)
        } catch (_: Exception) {
            YuzAiEngine.Intent.Ask(text)
        }
        if (intent is YuzAiEngine.Intent.Ask) {
            answer(text, null)
            return
        }
        execute(intent, account)
    }

    /** Dragging/bekor: joriy tinglashni to'xtatib, orbni tinch holatga qaytaradi. */
    private fun abortListen() {
        listenMode = ListenMode.NONE
        listenStartedAt = 0L
        interactionSeq++
        runCatching { recorder.cancel() }
        runCatching { voice?.stopListening() }
        resetIdle()
    }

    private fun handleResult(res: YuzVoiceResult, account: Int) {
        val text = res.transcript.trim()
        val pending = pendingPeer
        if (pending != null) {
            pendingPeer = null
            sendTo(pending, text, account)
            return
        }
        if (YuzAiEngine.isUnclear(text)) {
            speak(getString(R.string.ai_unclear))
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
        resetIdle()
        val peers = choice.options.map { it.peer }
        val message = if (choice.kind == YuzAiEngine.ChoiceKind.SEND) {
            choice.options.firstOrNull()?.text.orEmpty()
        } else {
            ""
        }
        val started = runCatching {
            YuzAiChatPickerActivity.listener = listener@{ index, edited ->
                YuzAiChatPickerActivity.listener = null
                val option = choice.options.getOrNull(index) ?: return@listener
                when (choice.kind) {
                    YuzAiEngine.ChoiceKind.SEND -> {
                        // Karta matnni ham tasdiqlab qaytardi.
                        val body = edited.ifBlank { option.text }
                        if (body.isBlank()) askBody(option.peer)
                        else sendNow(option.peer, body, account)
                    }
                    YuzAiEngine.ChoiceKind.OPEN,
                    YuzAiEngine.ChoiceKind.CALL -> openApp()
                }
            }
            startActivity(
                YuzAiChatPickerActivity.intent(
                    this, message, peers, account, overlay = true,
                    send = choice.kind == YuzAiEngine.ChoiceKind.SEND
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
    }

    /**
     * Yuborishdan oldin tasdiq kartasi: kimga va qanday matn ketadi (matn
     * qalam bilan tahrirlanadi). Karta ochilmasa — to'g'ridan-to'g'ri yuboramiz.
     */
    private fun sendTo(peer: YuzAiEngine.Peer, text: String, account: Int) {
        val body = text.trim()
        if (body.isBlank()) {
            askBody(peer)
            return
        }
        resetIdle()
        val started = runCatching {
            YuzAiChatPickerActivity.listener = listener@{ index, edited ->
                YuzAiChatPickerActivity.listener = null
                if (index == 0) sendNow(peer, edited.ifBlank { body }, account)
            }
            startActivity(
                YuzAiChatPickerActivity.intent(
                    this, body, listOf(peer), account, overlay = true, send = true
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!started) {
            YuzAiChatPickerActivity.listener = null
            sendNow(peer, body, account)
        }
    }

    private fun sendNow(peer: YuzAiEngine.Peer, text: String, account: Int) {
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
        val token = interactionSeq
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
            if (!isCurrent(token)) return@launch
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
        if (q.isBlank()) {
            resetIdle()
            return
        }
        YuzgramAiBrain.quickAnswer(q)?.let {
            speak(it)
            return
        }
        if (!cloudAnswer.isNullOrBlank()) {
            speak(cloudAnswer)
            return
        }
        val token = interactionSeq
        phase = Phase.THINKING
        setOrb(YuzAiOrbView.Mode.THINKING)
        scope.launch {
            val text = try {
                YuzgramAiBrain.answer(q)
            } catch (_: Exception) {
                getString(R.string.ai_no_network)
            }
            if (isCurrent(token)) speak(text)
        }
    }

    private fun speak(text: String) {
        val spoken = YuzAiVoice.speakable(text)
        val v = voice
        if (spoken.isBlank() || v == null) {
            resetIdle()
            return
        }
        phase = Phase.SPEAKING
        setOrb(YuzAiOrbView.Mode.SPEAKING)
        v.speakCloud(spoken)
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
