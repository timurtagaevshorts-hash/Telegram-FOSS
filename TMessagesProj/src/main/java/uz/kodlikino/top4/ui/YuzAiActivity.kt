package uz.kodlikino.top4.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.voip.VoIPHelper
import uz.kodlikino.top4.ai.YuzAiChatPickerActivity
import uz.kodlikino.top4.ai.YuzAiCloud
import uz.kodlikino.top4.ai.YuzAiEngine
import uz.kodlikino.top4.ai.YuzAiOverlayService
import uz.kodlikino.top4.ai.YuzAiRecorder
import uz.kodlikino.top4.ai.YuzAiVoice
import uz.kodlikino.top4.ai.YuzVoiceResult
import uz.kodlikino.top4.utils.YuzMusicEngine
import uz.kodlikino.top4.utils.YuzMusicRepository
import uz.kodlikino.top4.utils.YuzgramAiBrain

/**
 * AI Yordamchi — faqat OVOZLI yordamchi (yozmatni kiritish maydoni yo'q).
 *
 * #134: kirishda salom aytmaydi — qisqa beep + vibratsiya ("eshitaman") va
 * markazda Gemini uslubidagi jonli orb. Transkript kelgach buyruq AVVAL lokal
 * qoidalar bilan bajariladi (ism gapning istalgan joyida bo'lsa ham), musiqa
 * buyrug'i darhol qidirilib ijro etiladi; suhbat javoblari Gemini'dan olinadi.
 */
class YuzAiActivity : BaseFragment() {

    private lateinit var ctx: Context
    private lateinit var logContainer: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var orb: YuzAiOrbView

    private var voice: YuzAiVoice? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingPeer: YuzAiEngine.Peer? = null
    // Tasdiq kartasi ochiq: kimga va qanday matn yuborilishi kutilmoqda.
    private var pendingSend: Pair<YuzAiEngine.Peer, String>? = null
    private val recorder = YuzAiRecorder { level ->
        if (::orb.isInitialized) orb.post { orb.level = level }
    }
    private var cloudBusy = false
    private var listeningPending = false
    // Tizimli SpeechRecognizer sessiyasi ketmoqda (bulut oqimi emas).
    private var recognizerPending = false
    private var cloudTtsBlockedUntil = 0L
    // Har yangi muloqot eski asinxron javob/TTS'ni bekor qiladi — kechikkan
    // javob yangi buyruq ustidan yozib yubormasin.
    private var interactionSeq = 0
    private var musicBusy = false
    private var autoListenArmed = false
    private var screenActive = false
    private var entryPending = true
    private var quickButton: ImageButton? = null
    private var quickPending = false
    private var choiceHolder: android.widget.HorizontalScrollView? = null
    // Markazdagi avatarli tanlov ochiq bo'lsa — uning natijasi shu yerda.
    private var pendingChoice: YuzAiEngine.Intent.NeedChoice? = null

    override fun createView(context: Context): View {
        ctx = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        val root = LayoutInflater.from(ctx)
            .inflate(R.layout.fragment_yuz_ai, null) as ViewGroup
        fragmentView = root
        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        YuzTopInset.applyPadding(root.findViewById<View>(R.id.aiTopBar))
        root.findViewById<View>(R.id.btnAiBack)?.setOnClickListener {
            stopAll()
            removeSelfFromStack()
        }

        logContainer = root.findViewById(R.id.aiLog)
        statusView = root.findViewById(R.id.tvAiStatus)
        chipsRow = root.findViewById(R.id.aiChips)
        scroll = root.findViewById(R.id.aiScroll)
        orb = root.findViewById(R.id.aiOrb)

        root.findViewById<ImageButton>(R.id.btnAiMic).setOnClickListener { onMicTapped() }
        root.findViewById<View>(R.id.aiOrbWrap)?.setOnClickListener { onMicTapped() }
        quickButton = root.findViewById<ImageButton>(R.id.btnAiQuick).apply {
            setOnClickListener { onQuickTapped() }
        }
        updateQuickButton()

        buildChips()

        voice = YuzAiVoice(ctx, object : YuzAiVoice.Callbacks {
            override fun onListeningStarted() {
                statusView.text = ctx.getString(R.string.ai_listening)
                setOrb(YuzAiOrbView.Mode.LISTENING)
            }

            override fun onPartial(text: String) {
                statusView.text = text
            }

            override fun onLevel(level: Float) {
                if (::orb.isInitialized) orb.level = level
            }

            override fun onResult(text: String) {
                recognizerPending = false
                cloudBusy = false
                handleUtterance(text)
            }

            override fun onListenError(message: String, retry: Boolean) {
                setOrb(YuzAiOrbView.Mode.IDLE)
                if (recognizerPending) {
                    recognizerPending = false
                    cloudBusy = false
                    if (message.isNotBlank()) {
                        statusView.text = message
                        assistant(message)
                        return
                    }
                    // Tizim nutq tanishi ishlamadi — AudioRecord + /voice zaxirasi.
                    startCloudFlow()
                    return
                }
                val msg = when {
                    message.isNotBlank() -> message
                    retry -> ctx.getString(R.string.ai_network_voice)
                    else -> ctx.getString(R.string.ai_no_mic)
                }
                statusView.text = msg
                assistant(msg)
            }

            override fun onListeningStopped() {
                // TTS/cloud oqimi davom etayotgan bo'lsa orbni "jim" rejimga
                // qaytarmaymiz — faqat haqiqiy tinglash tugaganda.
                if (!cloudBusy && ::orb.isInitialized &&
                    orb.mode == YuzAiOrbView.Mode.LISTENING
                ) {
                    setOrb(YuzAiOrbView.Mode.IDLE)
                }
            }

            override fun onSpeakFinished() {
                setOrb(YuzAiOrbView.Mode.IDLE)
                val armed = autoListenArmed
                autoListenArmed = false
                if (armed) scheduleListen(150L)
            }
        })

        // #79: AI hovuzi faqat yuklangan dialoglardan to'planadi — ro'yxatni
        // oldindan chuqurroq yuklab, uzoqroqdagi chatlardagi odamlarni ham topiladigan qilamiz.
        runCatching { MessagesController.getInstance(currentAccount).loadDialogs(0, 0, 200, false) }
        // #127 tezlik: ochilishda DNS+TLS+edge "isitamiz".
        scope.launch { runCatching { YuzAiCloud.warmup() } }
        // Ism hovuzini ham oldindan qurib qo'yamiz — birinchi buyruq kutmaydi.
        scope.launch(Dispatchers.IO) { runCatching { YuzAiEngine.peers(currentAccount) } }
        return root
    }

    override fun onResume() {
        super.onResume()
        screenActive = true
        // #135: "Ustidan chizish" sozlamasidan qaytgach — tezkor tugmani yoqamiz.
        if (quickPending) {
            quickPending = false
            if (Settings.canDrawOverlays(ctx)) startQuick()
        }
        updateQuickButton()
        if (entryPending) {
            entryPending = false
            // Ekranga kirganda: ovozli salom YO'Q — faqat signal va tinglash.
            fragmentView?.postDelayed({
                if (screenActive && !cloudBusy && parentActivity != null) beginListening()
            }, 120L)
        }
    }

    override fun onPause() {
        screenActive = false
        autoListenArmed = false
        stopAll()
        super.onPause()
    }

    private fun onMicTapped() {
        val activity = parentActivity ?: return
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC
            )
            statusView.text = ctx.getString(R.string.ai_mic_permission)
            return
        }
        if (cloudBusy) {
            when {
                // Yozuv ketmoqda — yakunlab, aytilganini yuboramiz (2-bosish = to'xta).
                recorder.isRecording() -> recorder.finish()
                // Hali signal/kechikish bosqichi — yozuvni bekor qilamiz.
                listeningPending -> {
                    recorder.cancel()
                    recognizerPending = false
                    voice?.stopListening()
                    interactionSeq++
                    listeningPending = false
                    cloudBusy = false
                    setOrb(YuzAiOrbView.Mode.IDLE)
                    statusView.text = ctx.getString(R.string.ai_cancelled)
                }
                // Javob kutilmoqda — bekor qilamiz; kechikkan javob aytilmaydi.
                else -> {
                    recognizerPending = false
                    voice?.stopListening()
                    interactionSeq++
                    cloudBusy = false
                    setOrb(YuzAiOrbView.Mode.IDLE)
                    statusView.text = ctx.getString(R.string.ai_cancelled)
                }
            }
            return
        }
        voice?.stopSpeaking()
        beginListening()
    }

    /**
     * #135: tezkor (overlay) tugma — yoqilganda boshqa ilovalar ustida
     * ekran burchagida suzuvchi orb qoladi. Qayta bosish — o'chirish.
     */
    private fun onQuickTapped() {
        if (YuzAiOverlayService.running) {
            YuzAiOverlayService.stop(ctx)
            statusView.text = ctx.getString(R.string.ai_quick_off)
            fragmentView?.postDelayed({ updateQuickButton() }, 250L)
            return
        }
        val activity = parentActivity ?: return
        val micGranted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_QUICK
            )
            statusView.text = ctx.getString(R.string.ai_mic_permission)
            return
        }
        if (!Settings.canDrawOverlays(ctx)) {
            quickPending = true
            statusView.text = ctx.getString(R.string.ai_quick_permission)
            try {
                activity.startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + ctx.packageName)
                    ),
                    REQ_OVERLAY
                )
            } catch (_: Exception) {
                try {
                    activity.startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), REQ_OVERLAY
                    )
                } catch (_: Exception) {
                    quickPending = false
                    statusView.text = ctx.getString(R.string.ai_quick_permission)
                }
            }
            return
        }
        startQuick()
    }

    private fun startQuick() {
        YuzAiOverlayService.start(ctx)
        statusView.text = ctx.getString(R.string.ai_quick_on)
        fragmentView?.postDelayed({ updateQuickButton() }, 450L)
    }

    private fun updateQuickButton() {
        val button = quickButton ?: return
        val active = YuzAiOverlayService.running
        button.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (active) R.color.yuz_action else R.color.yuz_text)
        )
    }

    private fun beginListening() {
        if (!screenActive || cloudBusy || parentActivity == null) return
        val activity = parentActivity ?: return
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            statusView.text = ctx.getString(R.string.ai_mic_permission)
            return
        }
        if (voice?.isListening() == true) voice?.stopListening()
        autoListenArmed = false
        val v = voice
        // TEZKOR YO'L: tizim nutq tanishi — audio yuklanmaydi va buyruq
        // transkript kelishi bilanoq lokal bajariladi. Mavjud bo'lmasa — bulut.
        if (v != null && v.canListen()) startRecognizerFlow(v) else startCloudFlow()
    }

    /**
     * Tizimli SpeechRecognizer bilan tinglash. Natija matni kelishi bilan
     * YuzAiEngine lokal buyruqni darhol bajaradi; savol /chat'ga ketadi.
     * Xato bo'lsa — AudioRecord + /voice zaxirasiga o'tiladi.
     */
    private fun startRecognizerFlow(v: YuzAiVoice) {
        if (cloudBusy) return
        clearChoices()
        cloudBusy = true
        listeningPending = false
        recognizerPending = true
        val seq = ++interactionSeq
        statusView.text = ctx.getString(R.string.ai_listening)
        setOrb(YuzAiOrbView.Mode.LISTENING)
        v.playReadySignal()
        val acc = currentAccount
        // Ism hovuzi va TLS bir vaqtda qizisin — transkript kelganda kutish yo'q.
        scope.launch(Dispatchers.IO) { runCatching { YuzAiEngine.peers(acc) } }
        scope.launch(Dispatchers.IO) { runCatching { YuzAiCloud.warmup() } }
        fragmentView?.postDelayed({
            if (!screenActive || parentActivity == null || seq != interactionSeq ||
                !recognizerPending
            ) return@postDelayed
            v.startListening(announce = false)
        }, 90L)
    }

    private fun scheduleListen(delayMs: Long) {
        val root = fragmentView ?: return
        root.postDelayed({
            if (parentActivity == null || !screenActive || cloudBusy) return@postDelayed
            if (voice?.isSpeaking() == true) {
                scheduleListen(400L)
                return@postDelayed
            }
            beginListening()
        }, delayMs)
    }

    /**
     * Ovozli oqim: AudioRecord+VAD -> worker /voice (STT+niyat+javob)
     * -> lokal buyruq yoki Gemini javobi. Tizim mikrofoni — zaxira.
     */
    private fun startCloudFlow() {
        val v = voice ?: return
        if (cloudBusy) return
        clearChoices()
        cloudBusy = true
        listeningPending = true
        recorder.prepare()
        val seq = ++interactionSeq
        statusView.text = ctx.getString(R.string.ai_listening)
        setOrb(YuzAiOrbView.Mode.LISTENING)
        v.playReadySignal()
        val acc = currentAccount
        // Hovuz qurish disk/contacts bilan ishlaydi — UI thread'ni band qilmaymiz.
        scope.launch(Dispatchers.IO) { runCatching { YuzAiEngine.peers(acc) } }
        // Uzoq jim turgandan keyin TLS qayta qurilmasin.
        scope.launch(Dispatchers.IO) { runCatching { YuzAiCloud.warmup() } }
        scope.launch {
            // Beep (80 ms) tugashi uchun qisqa pauza — signal mikrofonga tushmasin.
            delay(90L)
            if (seq != interactionSeq) return@launch
            if (!screenActive || parentActivity == null) {
                listeningPending = false
                cloudBusy = false
                return@launch
            }
            val wav = try {
                withContext(Dispatchers.IO) { recorder.recordBlocking() }
            } catch (_: Exception) {
                null
            } finally {
                // Yangi muloqot boshlangan bo'lsa — uning holatini buzmaymiz.
                if (seq == interactionSeq) listeningPending = false
            }
            if (::orb.isInitialized) orb.level = 0f
            if (seq != interactionSeq) return@launch
            if (parentActivity == null) {
                cloudBusy = false
                return@launch
            }
            if (recorder.wasCancelled()) {
                cloudBusy = false
                setOrb(YuzAiOrbView.Mode.IDLE)
                if (screenActive) statusView.text = ctx.getString(R.string.ai_cancelled)
                return@launch
            }
            if (wav == null) {
                cloudBusy = false
                setOrb(YuzAiOrbView.Mode.IDLE)
                if (screenActive) {
                    val m = ctx.getString(R.string.ai_heard_none)
                    statusView.text = m
                    assistant(m)
                }
                return@launch
            }
            setOrb(YuzAiOrbView.Mode.THINKING)
            statusView.text = ctx.getString(R.string.ai_processing)
            val names = try {
                withContext(Dispatchers.IO) {
                    YuzAiEngine.peers(acc).map { it.name }.take(120)
                }
            } catch (_: Exception) {
                emptyList()
            }
            val res = try {
                withContext(Dispatchers.IO) { YuzAiCloud.voice(wav, names) }
            } catch (_: Exception) {
                null
            }
            // Bekor qilingan/yangi muloqot — bayroqlarni uning ustidan yozmaymiz.
            if (seq != interactionSeq) return@launch
            cloudBusy = false
            if (!screenActive || parentActivity == null) {
                setOrb(YuzAiOrbView.Mode.IDLE)
                return@launch
            }
            if (res == null) {
                // #157: worker limit (429) bergan bo'lsa — sababni o'zi aytadi,
                // offline STT'ga o'tishning foydasi yo'q (u ham /chat'ga boradi).
                YuzAiCloud.lastRateMessage?.takeIf { it.isNotBlank() }?.let { rate ->
                    statusView.text = rate
                    assistant(rate)
                    setOrb(YuzAiOrbView.Mode.IDLE)
                    return@launch
                }
                // Muloqot uzildi — tizim mikrofoni (offline STT) zaxirasiga o'tamiz.
                val m = ctx.getString(R.string.ai_cloud_fallback)
                statusView.text = m
                assistant(m)
                setOrb(YuzAiOrbView.Mode.IDLE)
                voice?.startListening()
                return@launch
            }
            if (res.transcript.isBlank()) {
                setOrb(YuzAiOrbView.Mode.IDLE)
                val m = ctx.getString(R.string.ai_voice_slow)
                statusView.text = m
                assistant(m)
                return@launch
            }
            handleCloudResult(res)
        }
    }

    private fun handleCloudResult(res: YuzVoiceResult) {
        val text = res.transcript.trim()
        if (text.isNotBlank()) user(text)
        val pending = pendingPeer
        if (pending != null) {
            pendingPeer = null
            sendTo(pending, text)
            return
        }
        // Juda qisqa / tushunarsiz transkript: taxmin qilib noto'g'ri amal
        // bajarmaymiz — qayta gapirishni so'raymiz.
        if (YuzAiEngine.isUnclear(text)) {
            fastSay(ctx.getString(R.string.ai_unclear))
            return
        }
        // #134: lokal qoida birinchi — ism/nik gapning istalgan joyida bo'lsa
        // ham topiladi va buyruq darhol bajariladi (worker niyatini kutmaydi).
        val intent = try {
            YuzAiEngine.parse(text, currentAccount)
        } catch (_: Exception) {
            YuzAiEngine.Intent.Ask(text)
        }
        if (intent !is YuzAiEngine.Intent.Ask) {
            executeIntent(intent)
            return
        }
        // "Salom/rahmat" kabi sof suhbat: worker uni "send" deb talqin qilsa
        // ham kontaktga xabar yuborilmaydi — darhol lokal javob.
        if (YuzAiEngine.isSmallTalk(text)) {
            askQuestion(text, res.answer)
            return
        }
        // Lokal qoida topolmadi — worker niyati hali ham foydali bo'lishi mumkin.
        // Nomi bo'yicha BARCHA yaqin chatlar olinadi: bir nechta "Sardor"
        // bo'lsa yolg'on "yubordim" o'rniga variantlar ko'rsatiladi.
        val named = try {
            if (res.peerName.isNotBlank()) YuzAiEngine.findByNames(res.peerName, currentAccount)
            else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val video = res.message.trim().equals("video", ignoreCase = true) ||
            text.contains("video", ignoreCase = true)
        when (res.intent) {
            "send" -> when {
                named.isEmpty() -> notFound(res.peerName)
                named.size > 1 -> askWhich(
                    YuzAiEngine.Intent.NeedChoice(
                        res.peerName.trim(),
                        named.map { YuzAiEngine.ChoiceOption(it, res.message.trim()) },
                        YuzAiEngine.ChoiceKind.SEND
                    )
                )
                res.message.isBlank() -> askBody(named[0])
                else -> sendTo(named[0], res.message)
            }
            "call" -> when {
                named.isEmpty() -> notFound(res.peerName)
                named.size > 1 -> askWhich(
                    YuzAiEngine.Intent.NeedChoice(
                        res.peerName.trim(),
                        named.map { YuzAiEngine.ChoiceOption(it, "") },
                        YuzAiEngine.ChoiceKind.CALL,
                        video
                    )
                )
                named[0].userId == 0L ->
                    fastSay(ctx.getString(R.string.ai_call_no_user, named[0].name), false)
                else -> executeCall(named[0], video)
            }
            "open" -> when {
                named.isEmpty() -> notFound(res.peerName)
                named.size > 1 -> askWhich(
                    YuzAiEngine.Intent.NeedChoice(
                        res.peerName.trim(),
                        named.map { YuzAiEngine.ChoiceOption(it, "") },
                        YuzAiEngine.ChoiceKind.OPEN
                    )
                )
                else -> openChatWithLine(named[0])
            }
            "goto" -> {
                val sec = mapGoto(res.goto)
                when {
                    sec == YuzAiEngine.Section.MUSIQA && res.message.isNotBlank() ->
                        playMusic(res.message)
                    sec != null -> gotoWithLine(sec)
                    else -> askQuestion(text, res.answer)
                }
            }
            else -> askQuestion(text, res.answer)
        }
    }

    private fun executeIntent(intent: YuzAiEngine.Intent) {
        when (intent) {
            is YuzAiEngine.Intent.SendText -> sendTo(intent.peer, intent.text)
            is YuzAiEngine.Intent.NeedBody -> askBody(intent.peer)
            is YuzAiEngine.Intent.NotFound ->
                if (intent.name.isBlank()) fastSay(ctx.getString(R.string.ai_need_peer))
                else fastSay(ctx.getString(R.string.ai_not_found, intent.name))
            is YuzAiEngine.Intent.OpenChat -> openChatWithLine(intent.peer)
            is YuzAiEngine.Intent.Call ->
                if (intent.peer.userId == 0L) {
                    fastSay(ctx.getString(R.string.ai_call_no_user, intent.peer.name), false)
                } else {
                    executeCall(intent.peer, intent.video)
                }
            is YuzAiEngine.Intent.Goto -> gotoWithLine(intent.section)
            is YuzAiEngine.Intent.PlayMusic -> playMusic(intent.query)
            is YuzAiEngine.Intent.NeedChoice -> askWhich(intent)
            is YuzAiEngine.Intent.Ask -> askQuestion(intent.question, null)
        }
    }

    /**
     * Lokal tizim mikrofoni (SpeechRecognizer) zaxirasi shu yerga keladi.
     */
    private fun handleUtterance(raw: String) {
        if (raw.isBlank()) return
        clearChoices()
        user(raw)
        val pending = pendingPeer
        if (pending != null) {
            pendingPeer = null
            sendTo(pending, raw)
            return
        }
        if (YuzAiEngine.isUnclear(raw)) {
            fastSay(ctx.getString(R.string.ai_unclear))
            return
        }
        val intent = try {
            YuzAiEngine.parse(raw, currentAccount)
        } catch (_: Exception) {
            YuzAiEngine.Intent.Ask(raw)
        }
        executeIntent(intent)
    }

    private fun askQuestion(question: String, cloudAnswer: String?) {
        val q = question.trim()
        if (q.isBlank()) return
        // Soat/sana/salomlashuv — tarmoqsiz, bir zumda.
        YuzgramAiBrain.quickAnswer(q)?.let { fastSay(it); return }
        if (!cloudAnswer.isNullOrBlank()) {
            say(cloudAnswer)
            return
        }
        statusView.text = ctx.getString(R.string.ai_processing)
        setOrb(YuzAiOrbView.Mode.THINKING)
        val seq = ++interactionSeq
        scope.launch {
            val answer = try {
                YuzgramAiBrain.answer(q)
            } catch (_: Exception) {
                ctx.getString(R.string.ai_no_network)
            }
            if (!screenActive || parentActivity == null || seq != interactionSeq) return@launch
            say(answer)
        }
    }

    /**
     * Yuborishdan OLDIN tasdiq kartasi: kimga va qanday matn ketadi, matnni
     * qalam bilan tahrirlash mumkin. Kartani ochib bo'lmasa — to'g'ridan-to'g'ri.
     */
    private fun sendTo(peer: YuzAiEngine.Peer, text: String) {
        val body = text.trim()
        if (body.isBlank()) {
            askBody(peer)
            return
        }
        clearChoices()
        autoListenArmed = false
        setOrb(YuzAiOrbView.Mode.IDLE)
        pendingSend = peer to body
        val started = parentActivity != null && runCatching {
            startActivityForResult(
                YuzAiChatPickerActivity.intent(
                    ctx, body, listOf(peer), currentAccount, send = true
                ),
                REQ_CONFIRM
            )
        }.isSuccess
        if (!started) {
            pendingSend = null
            sendNow(peer, body)
        }
    }

    private fun sendNow(peer: YuzAiEngine.Peer, text: String) {
        val body = text.trim()
        if (body.isBlank()) {
            askBody(peer)
            return
        }
        val mid = try {
            SendMessagesHelper.getInstance(currentAccount).sendMessage(
                SendMessagesHelper.SendMessageParams.of(
                    body, peer.dialogId, null, null, null, false,
                    null, null, null, true, 0, null, false
                )
            )
        } catch (_: Exception) {
            0L
        }
        if (mid != 0L) {
            val long = ctx.getString(R.string.ai_sent, peer.name, body)
            statusView.text = long
            assistant(long)
            speakAndResume(ctx.getString(R.string.ai_sent_short))
        } else {
            fastSay(ctx.getString(R.string.ai_send_failed), false)
        }
    }

    private fun askBody(peer: YuzAiEngine.Peer) {
        pendingPeer = peer
        fastSay(ctx.getString(R.string.ai_need_body, peer.name))
    }

    private fun openChatWithLine(peer: YuzAiEngine.Peer) {
        val m = ctx.getString(R.string.ai_open_chat, peer.name)
        statusView.text = m
        assistant(m)
        openChat(peer)
    }

    private fun gotoWithLine(section: YuzAiEngine.Section) {
        val m = ctx.getString(R.string.ai_goto, sectionName(section))
        statusView.text = m
        assistant(m)
        gotoSection(section)
    }

    private fun executeCall(peer: YuzAiEngine.Peer, video: Boolean) {
        val m = ctx.getString(R.string.ai_call_start, peer.name)
        statusView.text = m
        assistant(m)
        setOrb(YuzAiOrbView.Mode.IDLE)
        makeCall(peer, video)
    }

    /**
     * #134: musiqa buyrug'i — qidiruv natijasini darhol ijro etadi.
     * Kayfiyat so'rovi ("sevgi haqida dardli") tegishli seedga aylanadi.
     */
    private fun playMusic(rawQuery: String) {
        if (musicBusy) return
        musicBusy = true
        autoListenArmed = false
        val query = YuzAiEngine.refineMusicQuery(rawQuery)
        val label = if (query.isBlank()) ctx.getString(R.string.ai_music_chart_label) else query
        val searching = ctx.getString(R.string.ai_music_searching, label)
        statusView.text = searching
        assistant(searching)
        setOrb(YuzAiOrbView.Mode.THINKING)
        val seq = ++interactionSeq
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
            if (!screenActive || parentActivity == null || seq != interactionSeq) return@launch
            if (list.isEmpty()) {
                fastSay(ctx.getString(R.string.ai_music_empty), false)
                return@launch
            }
            val started = runCatching {
                YuzMusicEngine.playQueue(ctx.applicationContext, list, 0)
            }.isSuccess
            if (!started) {
                fastSay(ctx.getString(R.string.ai_music_empty), false)
                return@launch
            }
            val first = list[0]
            val msg = ctx.getString(R.string.ai_music_playing, first.title, first.artist)
            statusView.text = msg
            assistant(msg)
            // Qo'shiq yangraydi — mikrofon darhol qayta ochilmaydi (qo'shiq
            // ovozi buyruq bo'lib ketmasin). Keyingi buyruq — orbni bosib.
            setOrb(YuzAiOrbView.Mode.IDLE)
        }
    }

    /** Qisqa tasdiq: tizim TTS — bir zumda, tarmoq kutmasdan. */
    private fun fastSay(text: String, resume: Boolean = true) {
        statusView.text = text
        assistant(text)
        setOrb(YuzAiOrbView.Mode.SPEAKING)
        autoListenArmed = resume
        voice?.speak(text)
    }

    private fun speakAndResume(text: String) {
        setOrb(YuzAiOrbView.Mode.SPEAKING)
        autoListenArmed = true
        voice?.speak(text)
    }

    /** Suhbat javobi — Gemini ovozi (1.5 s ichida kelmasa tizim TTS). */
    private fun say(text: String) {
        statusView.text = text
        assistant(text)
        setOrb(YuzAiOrbView.Mode.SPEAKING)
        autoListenArmed = true
        speakNice(text)
    }

    private fun speakNice(text: String) {
        val v = voice ?: return
        // /speak bir marta ishlamasa, 5 daqiqa tizim TTS bilan darhol gapiramiz.
        if (SystemClock.elapsedRealtime() < cloudTtsBlockedUntil) {
            v.speak(text)
            return
        }
        val seq = interactionSeq
        scope.launch {
            val b = try {
                withTimeoutOrNull(400L) { YuzAiCloud.speakText(text) }
            } catch (_: Exception) {
                null
            }
            if (!screenActive || parentActivity == null || cloudBusy || seq != interactionSeq) return@launch
            if (b != null && b.size > 2000) {
                v.playCloudAudio(b, text)
            } else {
                cloudTtsBlockedUntil = SystemClock.elapsedRealtime() + 5 * 60_000L
                v.speak(text)
            }
        }
    }

    private fun mapGoto(tag: String): YuzAiEngine.Section? = when (tag) {
        "reels" -> YuzAiEngine.Section.REELS
        "kinolar" -> YuzAiEngine.Section.KINOLAR
        "musiqa" -> YuzAiEngine.Section.MUSIQA
        "elonlar" -> YuzAiEngine.Section.ELONLAR
        "profil" -> YuzAiEngine.Section.PROFIL
        "chats" -> YuzAiEngine.Section.CHATS
        else -> null
    }

    private fun notFound(name: String) {
        val n = name.trim()
        if (n.isBlank()) fastSay(ctx.getString(R.string.ai_need_peer))
        else fastSay(ctx.getString(R.string.ai_not_found, n))
    }

    /**
     * Bir xil ismli bir nechta chat topilganda — ekran markazida avatarli
     * ro'yxat. Ovozli savol aytilmaydi: tanlov darhol ochiladi (tezlik).
     * Oyna ochilmasa — eski chip usuli zaxira bo'lib qoladi.
     */
    private fun askWhich(intent: YuzAiEngine.Intent.NeedChoice) {
        val m = ctx.getString(R.string.ai_choose_peer, intent.label)
        statusView.text = m
        assistant(m)
        setOrb(YuzAiOrbView.Mode.IDLE)
        autoListenArmed = false
        clearChoices()
        pendingChoice = intent
        val message = if (intent.kind == YuzAiEngine.ChoiceKind.SEND) {
            intent.options.firstOrNull()?.text.orEmpty()
        } else {
            ""
        }
        val started = parentActivity != null && runCatching {
            startActivityForResult(
                YuzAiChatPickerActivity.intent(
                    ctx, message, intent.options.map { it.peer }, currentAccount,
                    send = intent.kind == YuzAiEngine.ChoiceKind.SEND
                ),
                REQ_PICK
            )
        }.isSuccess
        if (!started) {
            pendingChoice = null
            showChoiceChips(intent)
        }
    }

    /**
     * Zaxira: markazdagi oyna ochilmasa — bosiladigan variant chiplari.
     * Chiplar yangi buyruq boshlanishida olib tashlanadi, shunda eskirgan
     * variant bosilib, noto'g'ri odamga xabar ketmaydi.
     */
    private fun showChoiceChips(intent: YuzAiEngine.Intent.NeedChoice) {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (option in intent.options) {
            val duplicate = intent.options.count { it.peer.name == option.peer.name } > 1
            val label = if (duplicate && !option.peer.username.isNullOrBlank()) {
                "${option.peer.name} (@${option.peer.username})"
            } else {
                option.peer.name
            }
            val chip = YuzChips.pillText(ctx, label, false).apply {
                isClickable = true
                minimumHeight = AndroidUtilities.dp(48f)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setOnClickListener {
                    clearChoices()
                    executeChoice(intent, option)
                }
            }
            row.addView(chip)
        }
        val holder = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = AndroidUtilities.dp(6f)
            bottomMargin = AndroidUtilities.dp(4f)
        }
        logContainer.addView(holder, lp)
        choiceHolder = holder
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun executeChoice(
        intent: YuzAiEngine.Intent.NeedChoice,
        option: YuzAiEngine.ChoiceOption
    ) {
        when (intent.kind) {
            YuzAiEngine.ChoiceKind.SEND ->
                if (option.text.isBlank()) askBody(option.peer)
                else sendTo(option.peer, option.text)
            YuzAiEngine.ChoiceKind.OPEN -> openChatWithLine(option.peer)
            YuzAiEngine.ChoiceKind.CALL ->
                if (option.peer.userId == 0L) {
                    fastSay(ctx.getString(R.string.ai_call_no_user, option.peer.name), false)
                } else {
                    executeCall(option.peer, intent.video)
                }
        }
    }

    private fun clearChoices() {
        val holder = choiceHolder ?: return
        choiceHolder = null
        try {
            logContainer.removeView(holder)
        } catch (_: Exception) {
        }
    }

    private fun openChat(peer: YuzAiEngine.Peer) {
        if (parentActivity == null) return
        val args = Bundle()
        args.putLong("dialog_id", peer.dialogId)
        presentFragment(ChatActivity(args), false)
    }

    /** Telefon qo'ng'irog'i — faqat shaxsiy odam chatlari uchun ishlaydi. */
    private fun makeCall(peer: YuzAiEngine.Peer, video: Boolean) {
        val activity = parentActivity ?: return
        if (peer.userId == 0L) return
        try {
            val mc = MessagesController.getInstance(currentAccount)
            val user = mc.getUser(peer.userId) ?: return
            val userFull = mc.getUserFull(peer.userId)
            VoIPHelper.startCall(
                user, video, userFull != null && userFull.video_calls_available, activity,
                userFull, accountInstance
            )
        } catch (_: Exception) {
            fastSay(ctx.getString(R.string.ai_call_failed), false)
        }
    }

    private fun gotoSection(section: YuzAiEngine.Section) {
        when (section) {
            YuzAiEngine.Section.REELS ->
                YuzGramNav.select(this, YuzGramBottomBar.TAB_REELS)
            YuzAiEngine.Section.CHATS ->
                YuzGramNav.select(this, YuzGramBottomBar.TAB_ASOSIY)
            YuzAiEngine.Section.PROFIL ->
                YuzGramNav.select(this, YuzGramBottomBar.TAB_PROFIL)
            YuzAiEngine.Section.ELONLAR ->
                presentFragment(ElonlarActivity(), false)
            YuzAiEngine.Section.KINOLAR ->
                presentFragment(KinolarActivity(), false)
            YuzAiEngine.Section.MUSIQA ->
                presentFragment(YuzMusicActivity(), false)
        }
    }

    private fun sectionName(section: YuzAiEngine.Section): String = when (section) {
        YuzAiEngine.Section.REELS -> "Reels"
        YuzAiEngine.Section.ELONLAR -> ctx.getString(R.string.func_ai_elon_name)
        YuzAiEngine.Section.KINOLAR -> ctx.getString(R.string.func_ai_kino_name)
        YuzAiEngine.Section.MUSIQA -> ctx.getString(R.string.func_ai_musiqa_name)
        YuzAiEngine.Section.PROFIL -> ctx.getString(R.string.func_ai_profil_name)
        YuzAiEngine.Section.CHATS -> ctx.getString(R.string.func_ai_chats_name)
    }

    private fun buildChips() {
        addChip(R.string.ai_chip_send) {
            statusView.text = ctx.getString(R.string.ai_hint_send)
            if (!cloudBusy) onMicTapped()
        }
        addChip(R.string.ai_chip_reels) { gotoSection(YuzAiEngine.Section.REELS) }
        addChip(R.string.ai_chip_musiqa) { playMusic("") }
    }

    private fun addChip(labelRes: Int, onClick: () -> Unit) {
        val chip = YuzChips.pillText(ctx, ctx.getString(labelRes), false).apply {
            isClickable = true
            minimumHeight = AndroidUtilities.dp(48f)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }
        chipsRow.addView(chip)
    }

    private fun user(text: String) {
        addLine(
            ctx.getString(R.string.ai_utterance_prefix, text),
            ContextCompat.getColor(ctx, R.color.yuz_action), true
        )
    }

    private fun assistant(text: String) {
        addLine(text, ContextCompat.getColor(ctx, R.color.yuz_text), false)
    }

    private fun addLine(text: String, color: Int, alignRight: Boolean) {
        val tv = TextView(ctx)
        tv.text = text
        tv.setTextColor(color)
        tv.textSize = 15f
        if (alignRight) tv.gravity = android.view.Gravity.END
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = AndroidUtilities.dp(8f)
        lp.bottomMargin = AndroidUtilities.dp(4f)
        logContainer.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun setOrb(mode: YuzAiOrbView.Mode) {
        if (::orb.isInitialized) orb.mode = mode
    }

    private fun stopAll() {
        autoListenArmed = false
        clearChoices()
        recognizerPending = false
        interactionSeq++
        // Bekor qilingan oqim endi bayroqlarni o'zi tiklamaydi (seq tekshiruvi) —
        // shu yerda tozalaymiz, aks holda tugma "band" bo'lib qoladi.
        cloudBusy = false
        listeningPending = false
        try {
            recorder.cancel()
            voice?.stopListening()
            voice?.stopSpeaking()
        } catch (_: Exception) {
        }
        setOrb(YuzAiOrbView.Mode.IDLE)
        if (::orb.isInitialized) orb.level = 0f
    }

    override fun onRequestPermissionsResultFragment(
        requestCode: Int,
        permissions: Array<out String>?,
        grantResults: IntArray?
    ) {
        if (requestCode == REQ_MIC_QUICK) {
            if (grantResults?.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                onQuickTapped()
            } else {
                statusView.text = ctx.getString(R.string.ai_mic_permission)
            }
            return
        }
        if (requestCode != REQ_MIC) return
        if (grantResults?.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            fragmentView?.postDelayed({
                if (screenActive && !cloudBusy) beginListening()
            }, 150L)
        } else {
            statusView.text = ctx.getString(R.string.ai_mic_permission)
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_CONFIRM) {
            val pending = pendingSend
            pendingSend = null
            if (resultCode == Activity.RESULT_OK && pending != null) {
                val text = data?.getStringExtra(YuzAiChatPickerActivity.EXTRA_TEXT)
                    .orEmpty().ifBlank { pending.second }
                sendNow(pending.first, text)
            } else {
                // Bekor qilindi — mikrofon qayta ochilsin, qaytadan aytish mumkin.
                scheduleListen(200L)
            }
            return
        }
        if (requestCode == REQ_PICK) {
            val choice = pendingChoice
            pendingChoice = null
            if (resultCode == Activity.RESULT_OK && choice != null) {
                val index = data?.getIntExtra(YuzAiChatPickerActivity.EXTRA_INDEX, -1) ?: -1
                val option = choice.options.getOrNull(index)
                if (option != null) {
                    if (choice.kind == YuzAiEngine.ChoiceKind.SEND) {
                        // Karta chatni va matnni tasdiqlab qaytardi — qayta so'ramaymiz.
                        val body = data?.getStringExtra(YuzAiChatPickerActivity.EXTRA_TEXT)
                            .orEmpty().ifBlank { option.text }
                        sendNow(option.peer, body)
                    } else {
                        executeChoice(choice, option)
                    }
                }
            }
            return
        }
        if (requestCode != REQ_OVERLAY) return
        quickPending = false
        if (Settings.canDrawOverlays(ctx)) {
            startQuick()
        } else {
            statusView.text = ctx.getString(R.string.ai_quick_permission)
        }
    }

    override fun onFragmentDestroy() {
        pendingChoice = null
        scope.cancel()
        stopAll()
        try {
            voice?.release()
        } catch (_: Exception) {
        }
        voice = null
        super.onFragmentDestroy()
    }

    private companion object {
        private const val REQ_MIC = 501
        private const val REQ_OVERLAY = 502
        private const val REQ_MIC_QUICK = 503
        private const val REQ_PICK = 504
        private const val REQ_CONFIRM = 505
    }
}
