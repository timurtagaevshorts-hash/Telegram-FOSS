package uz.kodlikino.top4.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.animation.ValueAnimator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import org.telegram.ui.Components.voip.VoIPHelper
import uz.kodlikino.top4.ai.YuzAiCloud
import uz.kodlikino.top4.ai.YuzAiEngine
import uz.kodlikino.top4.ai.YuzAiRecorder
import uz.kodlikino.top4.ai.YuzAiVoice
import uz.kodlikino.top4.ai.YuzVoiceResult
import uz.kodlikino.top4.utils.YuzgramAiBrain

/**
 * AI Yordamchi — faqat OVOZLI yordamchi (yozmatni kiritish maydoni yo'q).
 * Katta mikrofon tugmasi + tezkor chiplar. "Sardor qalesan bugun kelasanmi"
 * deyilsa — Sardor ismli chat topiladi, xabar yuboriladi va "Xabar yubordim"
 * deyiladi. Boshqa holda savol javobga (soat/ob-havo/wikipedia) uzatiladi.
 */
class YuzAiActivity : BaseFragment() {

    private lateinit var ctx: Context
    private lateinit var logContainer: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var scroll: NestedScrollView
    private lateinit var micRing: View

    private var voice: YuzAiVoice? = null
    private var ringAnim: ValueAnimator? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingPeer: YuzAiEngine.Peer? = null
    private val recorder = YuzAiRecorder()
    private var cloudBusy = false

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
        micRing = root.findViewById(R.id.aiMicRing)

        val mic = root.findViewById<ImageButton>(R.id.btnAiMic)
        mic.setOnClickListener { onMicTapped() }

        buildChips()

        val v = YuzAiVoice(ctx, object : YuzAiVoice.Callbacks {
            override fun onListeningStarted() {
                statusView.text = ctx.getString(R.string.ai_listening)
                startRing()
            }

            override fun onPartial(text: String) {
                statusView.text = text
            }

            override fun onResult(text: String) {
                stopRing()
                handleUtterance(text)
            }

            override fun onListenError(message: String, retry: Boolean) {
                stopRing()
                val msg = when {
                    message.isNotBlank() -> message
                    retry -> ctx.getString(R.string.ai_network_voice)
                    else -> ctx.getString(R.string.ai_no_mic)
                }
                statusView.text = msg
                assistant(msg)
            }

            override fun onListeningStopped() {
                stopRing()
            }

            override fun onSpeakFinished() {
                statusView.text = ""
            }
        })
        voice = v

        // Salom — ko'rsatamiz va aytamiz.
        statusView.text = ctx.getString(R.string.ai_greeting)
        assistant(ctx.getString(R.string.ai_greeting))
        speakNice("Salom! Mikrofon tugmasini bosib gapiring.")
        // #79: AI hovuzi faqat yuklangan dialoglardan to'planadi — ro'yxatni
        // oldindan chuqurroq yuklab, uzoqroqdagi chatlardagi odamlarni ham topiladigan qilamiz.
        runCatching { MessagesController.getInstance(currentAccount).loadDialogs(0, 0, 200, false) }
        // #127 tezlik: ochilishda DNS+TLS+edge "isitamiz" — mikrofon bosilganda
        // handshake (~300-600 ms) foydalanuvchi kutmaydi.
        scope.launch { runCatching { YuzAiCloud.warmup() } }
        return root
    }

    private fun onMicTapped() {
        val activity = parentActivity ?: return
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.RECORD_AUDIO), 501
            )
            statusView.text = ctx.getString(R.string.ai_mic_permission)
            assistant(ctx.getString(R.string.ai_mic_permission))
            return
        }
        val v = voice ?: return
        if (cloudBusy) return
        if (recorder.isRecording()) {
            recorder.cancel()
            statusView.text = ctx.getString(R.string.ai_processing)
            return
        }
        if (v.isListening()) {
            v.stopListening()
            statusView.text = ctx.getString(R.string.ai_processing)
        } else {
            v.stopSpeaking()
            startCloudFlow()
        }
    }

    /**
     * Yangi ovozli oqim: AudioRecord+VAD -> worker /voice (Gemini STT+niyat+javob)
     * -> inson ovozi WAV playback. Tizim mikrofoni faqat zaxira (legacy handleUtterance).
     */
    private fun startCloudFlow() {
        val v = voice ?: return
        if (cloudBusy) return
        cloudBusy = true
        statusView.text = ctx.getString(R.string.ai_listening)
        startRing()
        val s = scope
        val acc = currentAccount
        // #127: nomlar hovuzi foydalanuvchi GAPIRGUNCHA IO'da quriladi — yozuv
        // tugagach /voice so'rovi hovuzni kutmaydi (asosiy oqim block bo'lmaydi).
        s.launch { withContext(Dispatchers.IO) { runCatching { YuzAiEngine.peers(acc) } } }
        s.launch {
            val wav = try {
                withContext(Dispatchers.IO) { recorder.recordBlocking() }
            } catch (_: Exception) {
                null
            }
            stopRing()
            if (wav == null) {
                cloudBusy = false
                if (parentActivity == null) return@launch
                statusView.text = ctx.getString(R.string.ai_heard_none)
                assistant(ctx.getString(R.string.ai_heard_none))
                return@launch
            }
            if (parentActivity == null) { cloudBusy = false; return@launch }
            statusView.text = ctx.getString(R.string.ai_processing)
            val res = try {
                // Hovuz + so'rov bitta IO blokada: main thread nom qurishda qotmaydi.
                withContext(Dispatchers.IO) {
                    val names = YuzAiEngine.peers(acc).map { it.name }.take(120)
                    YuzAiCloud.voice(wav, names)
                }
            } catch (_: Exception) {
                null
            }
            cloudBusy = false
            if (parentActivity == null) return@launch
            if (res == null) {
                // Muloqot uzildi — tizim mikrofoni (offline STT) zaxirasiga o'tamiz.
                val msg = ctx.getString(R.string.ai_cloud_fallback)
                statusView.text = msg
                assistant(msg)
                voice?.stopSpeaking()
                voice?.startListening()
                return@launch
            }
            if (res.transcript.isBlank()) {
                statusView.text = ctx.getString(R.string.ai_voice_slow)
                assistant(ctx.getString(R.string.ai_voice_slow))
                return@launch
            }
            handleCloudResult(res)
        }
    }

    private fun handleCloudResult(res: YuzVoiceResult) {
        if (res.transcript.isNotBlank()) user(res.transcript)
        val pending = pendingPeer
        if (pending != null) {
            pendingPeer = null
            sendTo(pending, res.transcript, res)
            return
        }
        val peer = if (res.peerName.isNotBlank()) findPeerByName(res.peerName) else null
        when (res.intent) {
            "send" -> when {
                peer == null -> say(ctx.getString(R.string.ai_not_found, res.peerName), res.audio)
                res.message.isBlank() -> {
                    pendingPeer = peer
                    say(ctx.getString(R.string.ai_need_body, peer.name), res.audio)
                }
                else -> sendTo(peer, res.message, res)
            }
            "call" -> when {
                peer == null -> say(ctx.getString(R.string.ai_not_found, res.peerName), res.audio)
                peer.userId == 0L -> say(ctx.getString(R.string.ai_call_no_user, peer.name), res.audio)
                else -> {
                    val video = res.message.trim().equals("video", ignoreCase = true) ||
                        res.transcript.contains("video")
                    makeCall(peer, video)
                    say(ctx.getString(R.string.ai_call_start, peer.name), res.audio)
                }
            }
            "open" -> if (peer != null) {
                say(ctx.getString(R.string.ai_open_chat, peer.name), res.audio)
                openChat(peer)
            } else {
                say(ctx.getString(R.string.ai_not_found, res.peerName), res.audio)
            }
            "goto" -> {
                val sec = mapGoto(res.goto)
                if (sec != null) {
                    say(ctx.getString(R.string.ai_goto, sectionName(sec)), res.audio)
                    if (sec == YuzAiEngine.Section.MUSIQA && res.message.isNotBlank()) {
                        presentFragment(YuzMusicActivity(res.message), false)
                    } else {
                        gotoSection(sec)
                    }
                } else {
                    cloudAsk(res)
                }
            }
            else -> cloudAsk(res)
        }
    }

    private fun cloudAsk(res: YuzVoiceResult) {
        val a = if (res.answer.isNotBlank()) res.answer else ctx.getString(R.string.ai_heard_none)
        say(a, res.audio)
    }

    private fun say(text: String, audio: ByteArray?) {
        statusView.text = text
        assistant(text)
        sayOut(text, audio)
    }

    private fun sayOut(text: String, audio: ByteArray?) {
        val v = voice ?: return
        if (audio != null && audio.size > 2000) v.playCloudAudio(audio, text) else speakNice(text)
    }

    /** Cloud inson ovozi bilan gapirish: /speak async; bo'lmasa darhol tizim TTS. */
    private fun speakNice(text: String) {
        val v = voice ?: return
        val s = scope
        s.launch {
            val b = try {
                withContext(Dispatchers.IO) { YuzAiCloud.speakText(text) }
            } catch (_: Exception) {
                null
            }
            if (parentActivity == null) return@launch
            if (b != null && b.size > 2000) v.playCloudAudio(b, text) else v.speak(text)
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

    /**
     * Gemini qaytargan nomni lokal hovuzdan topish. #105: endi yagona mantique
     * YuzAiEngine.findByName'ida — kontaktlar, tipografiya va familiyalar ham.
     */
    private fun findPeerByName(want: String): YuzAiEngine.Peer? =
        YuzAiEngine.findByName(want, currentAccount)

    private fun handleUtterance(raw: String) {
        if (raw.isBlank()) return
        user(raw)
        val pending = pendingPeer
        if (pending != null) {
            // Oldingi savol: "qaysi xabarni yuboray?" — bu javob endi matn.
            pendingPeer = null
            sendTo(pending, raw)
            return
        }
        val intent = YuzAiEngine.parse(raw, currentAccount)
        when (intent) {
            is YuzAiEngine.Intent.SendText -> sendTo(intent.peer, intent.text)
            is YuzAiEngine.Intent.NeedBody -> {
                pendingPeer = intent.peer
                val q = ctx.getString(R.string.ai_need_body, intent.peer.name)
                statusView.text = q
                assistant(q)
                voice?.speak(q)
            }
            is YuzAiEngine.Intent.NotFound -> {
                val q = ctx.getString(R.string.ai_not_found, intent.name)
                statusView.text = q
                assistant(q)
                voice?.speak(q)
            }
            is YuzAiEngine.Intent.OpenChat -> {
                assistant(ctx.getString(R.string.ai_open_chat, intent.peer.name))
                openChat(intent.peer)
            }
            is YuzAiEngine.Intent.Call -> {
                if (intent.peer.userId == 0L) {
                    val q = ctx.getString(R.string.ai_call_no_user, intent.peer.name)
                    statusView.text = q
                    assistant(q)
                    voice?.speak(q)
                } else {
                    val q = ctx.getString(R.string.ai_call_start, intent.peer.name)
                    statusView.text = q
                    assistant(q)
                    makeCall(intent.peer, intent.video)
                }
            }
            is YuzAiEngine.Intent.Goto -> {
                val name = sectionName(intent.section)
                assistant(ctx.getString(R.string.ai_goto, name))
                voice?.speak(ctx.getString(R.string.ai_goto, name))
                gotoSection(intent.section)
            }
            is YuzAiEngine.Intent.Ask -> askQuestion(intent.question)
        }
    }

    private fun askQuestion(question: String) {
        statusView.text = ctx.getString(R.string.ai_processing)
        val s = scope
        s.launch {
            val answer = try {
                YuzgramAiBrain.answer(question)
            } catch (e: Exception) {
                ctx.getString(R.string.ai_no_mic)
            }
            if (parentActivity == null) return@launch
            statusView.text = answer
            assistant(answer)
            speakNice(answer)
        }
    }

    private fun sendTo(peer: YuzAiEngine.Peer, text: String, res: YuzVoiceResult? = null) {
        val body = text.trim()
        if (body.isBlank()) {
            val q = ctx.getString(R.string.ai_need_body, peer.name)
            pendingPeer = peer
            statusView.text = q
            assistant(q)
            voice?.speak(q)
            return
        }
        val mid = try {
            SendMessagesHelper.getInstance(currentAccount).sendMessage(
                SendMessagesHelper.SendMessageParams.of(
                    body, peer.dialogId, null, null, null, false,
                    null, null, null, true, 0, null, false
                )
            )
        } catch (e: Exception) {
            0L
        }
        if (mid != 0L) {
            val msg = ctx.getString(R.string.ai_sent, peer.name, body)
            statusView.text = msg
            assistant(msg)
            sayOut(ctx.getString(R.string.ai_sent_short), res?.audio)
        } else {
            val msg = ctx.getString(R.string.ai_send_failed)
            statusView.text = msg
            assistant(msg)
            voice?.speak(msg)
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
            val msg = ctx.getString(R.string.ai_call_failed)
            statusView.text = msg
            assistant(msg)
            voice?.speak(msg)
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
            onMicTapped()
        }
        addChip(R.string.ai_chip_time) { askQuestion("hozir soat nechchi") }
        addChip(R.string.ai_chip_weather) { askQuestion("ob-havo qanday") }
        addChip(R.string.ai_chip_reels) { gotoSection(YuzAiEngine.Section.REELS) }
        addChip(R.string.ai_chip_musiqa) { gotoSection(YuzAiEngine.Section.MUSIQA) }
    }

    private fun addChip(labelRes: Int, onClick: () -> Unit) {
        val chip = YuzChips.pillText(ctx, ctx.getString(labelRes), false).apply {
            isClickable = true
            setOnClickListener { onClick() }
        }
        chipsRow.addView(chip)
    }

    private fun user(text: String) {
        addLine(
            ctx.getString(R.string.ai_utterance_prefix, text),
            androidx.core.content.ContextCompat.getColor(ctx, R.color.yuz_action), true
        )
    }

    private fun assistant(text: String) {
        addLine(text, androidx.core.content.ContextCompat.getColor(ctx, R.color.yuz_text), false)
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

    private fun startRing() {
        if (ringAnim != null) return
        ringAnim = ValueAnimator.ofFloat(0.15f, 0.9f).apply {
            duration = 700L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { micRing.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopRing() {
        ringAnim?.cancel()
        ringAnim = null
        micRing.alpha = 0f
    }

    private fun stopAll() {
        stopRing()
        try {
            recorder.cancel()
            voice?.stopListening()
            voice?.stopSpeaking()
        } catch (_: Exception) {
        }
    }

    private fun toast(text: String) {
        val activity = parentActivity ?: return
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    override fun onFragmentDestroy() {
        scope.cancel()
        stopAll()
        try {
            voice?.release()
        } catch (_: Exception) {
        }
        voice = null
        super.onFragmentDestroy()
    }
}
