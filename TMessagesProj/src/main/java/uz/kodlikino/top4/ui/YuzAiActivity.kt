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
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ChatActivity
import uz.kodlikino.top4.ai.YuzAiEngine
import uz.kodlikino.top4.ai.YuzAiVoice
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

    override fun createView(context: Context): View {
        ctx = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        val root = LayoutInflater.from(ctx)
            .inflate(R.layout.fragment_yuz_ai, null) as ViewGroup
        fragmentView = root
        actionBar?.visibility = View.GONE

        root.findViewById<View>(R.id.aiTopBar)?.setPadding(
            0, AndroidUtilities.statusBarHeight, 0, 0
        )
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
        v.speak("Salom! Mikrofon tugmasini bosib gapiring.")
        return root
    }

    private fun onMicTapped() {
        val activity = parentActivity
        if (activity == null) {
            voice?.stopListening()
            voice?.startListening()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.RECORD_AUDIO), 501
            )
            toast(ctx.getString(R.string.ai_mic_permission))
            return
        }
        val v = voice ?: return
        if (v.isListening()) {
            v.stopListening()
            statusView.text = ctx.getString(R.string.ai_processing)
        } else {
            voice?.stopSpeaking()
            v.startListening()
        }
    }

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
            voice?.speak(answer)
        }
    }

    private fun sendTo(peer: YuzAiEngine.Peer, text: String) {
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
            voice?.speak(ctx.getString(R.string.ai_sent_short))
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
            YuzAiEngine.Section.TAKSI ->
                presentFragment(TaxiCallActivity(), false)
        }
    }

    private fun sectionName(section: YuzAiEngine.Section): String = when (section) {
        YuzAiEngine.Section.REELS -> "Reels"
        YuzAiEngine.Section.ELONLAR -> ctx.getString(R.string.func_ai_elon_name)
        YuzAiEngine.Section.KINOLAR -> ctx.getString(R.string.func_ai_kino_name)
        YuzAiEngine.Section.TAKSI -> ctx.getString(R.string.func_ai_taxi_name)
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
        addChip(R.string.ai_chip_taxi) { gotoSection(YuzAiEngine.Section.TAKSI) }
    }

    private fun addChip(labelRes: Int, onClick: () -> Unit) {
        val chip = TextView(ctx)
        chip.text = ctx.getString(labelRes)
        chip.setTextColor(0xFFDCE6F5.toInt())
        chip.textSize = 13f
        chip.setBackgroundResource(R.drawable.bg_ai_chip)
        chip.isClickable = true
        chip.setOnClickListener { onClick() }
        val hPad = AndroidUtilities.dp(14f)
        chip.setPadding(hPad, AndroidUtilities.dp(8f), hPad, AndroidUtilities.dp(8f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = AndroidUtilities.dp(8f)
        chipsRow.addView(chip, lp)
    }

    private fun user(text: String) {
        addLine(ctx.getString(R.string.ai_utterance_prefix, text), 0xFF2AABEE.toInt(), true)
    }

    private fun assistant(text: String) {
        addLine(text, 0xFFE6EEF9.toInt(), false)
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
