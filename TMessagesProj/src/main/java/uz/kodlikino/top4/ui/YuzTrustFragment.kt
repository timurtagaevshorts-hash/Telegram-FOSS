package uz.kodlikino.top4.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.Components.AvatarDrawable
import org.telegram.ui.Components.BackupImageView
import uz.kodlikino.top4.trust.TrustSnapshotBuilder
import uz.kodlikino.top4.trust.TrustStore
import uz.kodlikino.top4.utils.YuzGramAdsAuth

private const val TAG = "YuzTrustFragment"

/**
 * Ishonchli kuzatuv — asosiy ekran.
 *
 * Ikki ro'yxat: kelgan so'rovlar (kimdir mening chatlarimni ko'rmoqchi) va
 * yuborilgan so'rovlar (men kimningdir chatlarini ko'rmoqchiman). Rozilik
 * ikki tomonlama: approver `shareMine`ni yoqmaguncha hech narsa ochilmaydi.
 */
class YuzTrustFragment : BaseFragment() {

    private var scope: CoroutineScope? = null
    private var themedContext: Context? = null

    private var uid: String = ""
    private var myName: String = "YuzGram"

    private var incoming: List<TrustStore.Incoming> = emptyList()
    private var sent: List<TrustStore.Outgoing> = emptyList()
    private var loading = true
    private var busy = false

    private var inboxListener: ListenerRegistration? = null
    private var sentListener: ListenerRegistration? = null
    private val approvalWatchers = HashMap<String, ListenerRegistration>()

    /** Kelgan so'rovlar uchun dialog holati — yangi so'rov kelganda modal oyna. */
    private val seenPending = HashSet<String>()
    private var inboxSeeded = false
    private var requestDialog: AlertDialog? = null
    private var checkingDialog: AlertDialog? = null

    /** Qulfni ochish so'rovlari uchun bir martalik dialog. */
    private val unlockPrompted = HashSet<String>()
    private var unlockDialog: AlertDialog? = null

    private var progress: ProgressBar? = null
    private var emptyTitle: TextView? = null
    private var emptyDesc: TextView? = null
    private var incomingHeader: TextView? = null
    private var incomingList: LinearLayout? = null
    private var activeHeader: TextView? = null
    private var activeList: LinearLayout? = null
    private var sentHeader: TextView? = null
    private var sentList: LinearLayout? = null

    override fun createView(context: Context): View {
        val themed = YuzTheme.wrap(context)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_yuz_trust, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        root.findViewById<View>(R.id.btnTrustBack)
            .setOnClickListener { finishFragment() }
        YuzTopInset.applyPadding(root.findViewById(R.id.trustTopBar))

        progress = root.findViewById(R.id.trustProgress)
        emptyTitle = root.findViewById(R.id.trustEmptyTitle)
        emptyDesc = root.findViewById(R.id.trustEmptyDesc)
        incomingHeader = root.findViewById(R.id.trustIncomingHeader)
        incomingList = root.findViewById(R.id.trustIncomingList)
        activeHeader = root.findViewById(R.id.trustActiveHeader)
        activeList = root.findViewById(R.id.trustActiveList)
        sentHeader = root.findViewById(R.id.trustSentHeader)
        sentList = root.findViewById(R.id.trustSentList)

        fillRules(root.findViewById(R.id.trustRules))
        root.findViewById<View>(R.id.btnTrustRequest).setOnClickListener { openPicker() }

        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
                // Ekran ichidagi har qanday kutilmagan xato ilovani yiqitmasin.
                Log.e(TAG, "trust coroutine", e)
                hideChecking()
                toast(R.string.yuz_trust_error)
            }
        )
        render()
        start()
        return root
    }

    private fun fillRules(container: LinearLayout?) {
        container?.removeAllViews()
        val themed = themedContext ?: return
        val rules = listOf(
            R.string.yuz_trust_rule_1_title to R.string.yuz_trust_rule_1_desc,
            R.string.yuz_trust_rule_2_title to R.string.yuz_trust_rule_2_desc,
            R.string.yuz_trust_rule_3_title to R.string.yuz_trust_rule_3_desc,
            R.string.yuz_trust_rule_4_title to R.string.yuz_trust_rule_4_desc
        )
        for ((titleRes, descRes) in rules) {
            val row = LayoutInflater.from(themed)
                .inflate(R.layout.item_yuz_trust_rule, container, false)
            row.findViewById<TextView>(R.id.ruleTitle).text = str(titleRes)
            row.findViewById<TextView>(R.id.ruleDesc).text = str(descRes)
            container?.addView(row)
        }
    }

    // ---------------------------------------------------------------- loading

    private fun start() {
        loading = true
        render()
        scope?.launch {
            val meUid = kotlinx.coroutines.withContext(Dispatchers.IO) {
                try {
                    YuzGramAdsAuth.ensureSignedIn(currentAccount)
                } catch (e: Exception) {
                    null
                }
            }
            if (meUid.isNullOrBlank()) {
                loading = false
                render()
                toast(R.string.yuz_trust_sign_in_error)
                return@launch
            }
            uid = meUid
            myName = TrustSnapshotBuilder.meName(currentAccount)
            if (inboxListener == null) {
                inboxListener = TrustStore.listenInbox(uid) { list ->
                    incoming = list
                    loading = false
                    safeRender()
                    try {
                        onInboxUpdate(list)
                    } catch (e: Exception) {
                        Log.e(TAG, "inbox update", e)
                    }
                }
            }
            if (sentListener == null) {
                sentListener = TrustStore.listenSent(uid) { list ->
                    sent = list
                    loading = false
                    safeRender()
                }
            }
            // Rozilik berilgan bo'lsa, o'z ro'yxatimni darhol nashr qilaman —
            // TrustSync davriy tickerini kutmasdan.
            try {
                TrustSnapshotBuilder.publish(currentAccount)
            } catch (e: Exception) {
                Log.e(TAG, "publish", e)
            }
        }
    }

    private fun stopListeners() {
        inboxListener?.remove()
        inboxListener = null
        sentListener?.remove()
        sentListener = null
        for (w in approvalWatchers.values) w.remove()
        approvalWatchers.clear()
    }

    override fun onFragmentDestroy() {
        stopListeners()
        try {
            requestDialog?.dismiss()
            unlockDialog?.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "dismiss dialogs: ${e.message}")
        }
        requestDialog = null
        unlockDialog = null
        hideChecking()
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }

    // --------------------------------------------------------------- rendering

    private fun render() {
        val themed = themedContext ?: return
        val inc = incoming.filter { it.isPending || it.isActive }
        val out = sent
            .filter { it.status != TrustStore.STATUS_CANCELLED }
            .sortedWith(
                compareBy<TrustStore.Outgoing> {
                    when {
                        it.isActive -> 0
                        it.isPending -> 1
                        else -> 2
                    }
                }.thenByDescending { it.createdAt }
            )

        progress?.visibility = if (loading) View.VISIBLE else View.GONE

        val pendingInc = inc.filter { it.isPending }.sortedByDescending { it.createdAt }
        val activeInc = inc.filter { it.isActive }.sortedByDescending { it.updatedAt }

        setHeader(incomingHeader, R.string.yuz_trust_section_incoming, pendingInc.size)
        setHeader(activeHeader, R.string.yuz_trust_section_active, activeInc.size)
        setHeader(sentHeader, R.string.yuz_trust_section_sent, out.size)

        incomingList?.removeAllViews()
        for (item in pendingInc) incomingList?.addView(buildIncomingRow(themed, item))
        activeList?.removeAllViews()
        for (item in activeInc) activeList?.addView(buildIncomingRow(themed, item))
        sentList?.removeAllViews()
        for (item in out) sentList?.addView(buildSentRow(themed, item))

        val empty = !loading && inc.isEmpty() && out.isEmpty()
        emptyTitle?.visibility = if (empty) View.VISIBLE else View.GONE
        emptyDesc?.visibility = if (empty) View.VISIBLE else View.GONE

        syncApprovalWatchers()
        // Qulfni ochish so'rovi hal qilinsa, keyingi so'rovda yana dialog chiqadi.
        unlockPrompted.retainAll(
            sent.filter { it.isActive && it.unlockRequested }.map { it.peerUid }.toSet()
        )
    }

    /** Firestore listener'lari main thread'da ishlaydi — render xatosi app'ni yiqitmasin. */
    private fun safeRender() {
        try {
            render()
        } catch (e: Exception) {
            Log.e(TAG, "render", e)
        }
    }

    private fun setHeader(view: TextView?, titleRes: Int, count: Int) {
        if (count <= 0) {
            view?.visibility = View.GONE
            return
        }
        view?.visibility = View.VISIBLE
        view?.text = str(
            R.string.yuz_trust_section_count,
            str(titleRes),
            count
        )
    }

    /** Yangi kelgan so'rovda darhol qabul/rad oynasini ko'rsatadi. */
    private fun onInboxUpdate(list: List<TrustStore.Incoming>) {
        val pendingNow = list.filter { it.isPending }
        if (!inboxSeeded) {
            inboxSeeded = true
            seenPending.clear()
            seenPending.addAll(pendingNow.map { it.peerUid })
            return
        }
        val fresh = pendingNow.filter { it.peerUid !in seenPending }
        seenPending.clear()
        seenPending.addAll(pendingNow.map { it.peerUid })
        if (fresh.isNotEmpty()) {
            showRequestDialog(fresh.maxByOrNull { it.createdAt } ?: fresh.first())
        }
    }

    private fun showRequestDialog(item: TrustStore.Incoming) {
        if (requestDialog?.isShowing == true) return
        val themed = dialogContext() ?: return
        val name = item.peerName.ifBlank { str(R.string.yuz_trust_you) }
        try {
            val dialog = AlertDialog.Builder(themed)
                .setTitle(str(R.string.yuz_trust_request_dialog_title))
                .setMessage(str(R.string.yuz_trust_request_dialog_desc, name))
                .setPositiveButton(str(R.string.yuz_trust_accept)) { _, _ -> accept(item.peerUid) }
                .setNegativeButton(str(R.string.yuz_trust_decline)) { _, _ -> decline(item.peerUid) }
                .create()
            dialog.setOnDismissListener { if (requestDialog === dialog) requestDialog = null }
            requestDialog = dialog
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "request dialog", e)
        }
    }

    private fun showChecking() {
        if (checkingDialog?.isShowing == true) return
        val themed = dialogContext() ?: return
        try {
            val dialog = AlertDialog.Builder(themed)
                .setMessage(str(R.string.yuz_trust_checking))
                .create()
            dialog.setCancelable(false)
            checkingDialog = dialog
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "checking dialog", e)
        }
    }

    private fun hideChecking() {
        val dialog = checkingDialog ?: return
        checkingDialog = null
        try {
            if (dialog.isShowing) dialog.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "hideChecking: ${e.message}")
        }
    }

    private fun syncApprovalWatchers() {
        if (uid.isBlank()) return
        val pendingPeers = sent.filter { it.isPending }.map { it.peerUid }.toSet()
        val stale = approvalWatchers.keys.filter { it !in pendingPeers }
        for (peer in stale) {
            approvalWatchers.remove(peer)?.remove()
        }
        for (peer in pendingPeers) {
            if (approvalWatchers.containsKey(peer)) continue
            approvalWatchers[peer] = TrustStore.watchApproval(peer, uid) { status, _ ->
                if (status != TrustStore.STATUS_PENDING) {
                    scope?.launch { TrustStore.markSentStatus(uid, peer, status) }
                    approvalWatchers.remove(peer)?.remove()
                }
            }
        }
    }

    private fun buildIncomingRow(themed: Context, item: TrustStore.Incoming): View {
        val row = LayoutInflater.from(themed)
            .inflate(R.layout.item_yuz_trust_person, null)
        val name = item.peerName.ifBlank { str(R.string.yuz_trust_you) }
        row.findViewById<TextView>(R.id.personInitials).text = initials(name)
        row.findViewById<TextView>(R.id.personName).text = name
        bindAvatar(row, item.peerUid)
        val sub = row.findViewById<TextView>(R.id.personSub)
        val primary = row.findViewById<TextView>(R.id.personPrimary)
        val secondary = row.findViewById<TextView>(R.id.personSecondary)
        val shareRow = row.findViewById<View>(R.id.personShareRow)
        val shareSwitch = row.findViewById<android.widget.Switch>(R.id.personShareSwitch)
        val shareLabel = row.findViewById<TextView>(R.id.personShareLabel)

        if (item.isPending) {
            sub.text = str(R.string.yuz_trust_incoming_desc, name)
            primary.text = str(R.string.yuz_trust_accept)
            primary.setOnClickListener { accept(item.peerUid) }
            secondary.text = str(R.string.yuz_trust_decline)
            secondary.visibility = View.VISIBLE
            secondary.setOnClickListener { decline(item.peerUid) }
            shareRow.visibility = View.GONE
        } else {
            val locked = item.locked
            val unlockRequested = item.unlockRequested
            sub.text = if (locked) {
                str(R.string.yuz_trust_locked_desc, name)
            } else {
                str(R.string.yuz_trust_active_desc, name)
            }
            when {
                unlockRequested -> {
                    primary.text = str(R.string.yuz_trust_unlock_requested)
                    primary.isEnabled = false
                    primary.setOnClickListener(null)
                }
                locked -> {
                    primary.text = str(R.string.yuz_trust_unlock_request)
                    primary.isEnabled = true
                    primary.setOnClickListener { askUnlockPermission(item.peerUid, name) }
                }
                else -> {
                    primary.text = str(R.string.yuz_trust_revoke)
                    primary.isEnabled = true
                    primary.setOnClickListener { revoke(item.peerUid) }
                }
            }
            secondary.visibility = View.GONE
            row.findViewById<View>(R.id.personMore).visibility = View.GONE
            shareRow.visibility = View.VISIBLE
            shareLabel.text = if (locked) {
                str(R.string.yuz_trust_locked_share)
            } else {
                str(R.string.yuz_trust_share_public_desc, name)
            }
            shareSwitch.isEnabled = !locked && !unlockRequested
            shareSwitch.isChecked = item.shareMine
            shareSwitch.setOnCheckedChangeListener { _, checked ->
                if (checked != item.shareMine) setShare(item.peerUid, checked)
            }
        }
        return row
    }

    private fun buildSentRow(themed: Context, item: TrustStore.Outgoing): View {
        val row = LayoutInflater.from(themed)
            .inflate(R.layout.item_yuz_trust_person, null)
        val name = item.peerName.ifBlank { str(R.string.yuz_trust_you) }
        row.findViewById<TextView>(R.id.personInitials).text = initials(name)
        row.findViewById<TextView>(R.id.personName).text = name
        bindAvatar(row, item.peerUid)
        val sub = row.findViewById<TextView>(R.id.personSub)
        val primary = row.findViewById<TextView>(R.id.personPrimary)
        val secondary = row.findViewById<TextView>(R.id.personSecondary)
        val more = row.findViewById<View>(R.id.personMore)
        row.findViewById<View>(R.id.personShareRow).visibility = View.GONE
        secondary.visibility = View.GONE

        when {
            item.isActive -> {
                sub.text = when {
                    item.unlockRequested ->
                        str(R.string.yuz_trust_unlock_incoming_desc, name)
                    item.locked -> str(R.string.yuz_trust_locked_desc, name)
                    else -> str(R.string.yuz_trust_active_desc, name)
                }
                // "Kuzatish" — asosiy va aniq ko'rinadigan amal.
                primary.visibility = View.VISIBLE
                primary.isEnabled = true
                primary.text = str(R.string.yuz_trust_watch)
                primary.setPadding(dp(16), dp(6), dp(16), dp(6))
                primary.minHeight = dp(40)
                primary.textSize = 14f
                primary.setOnClickListener {
                    presentFragment(YuzTrustViewerFragment(item.peerUid, name), false)
                }
                // Bekor qilish/qulf — faqat vertikal ⋮ menyusida.
                more.visibility = View.VISIBLE
                more.setOnClickListener { showOverflow(item, name) }
                // Dialog faqat oyna ulangan bo'lsa ko'rsatiladi (aks holda
                // BadTokenException) — keyingi render'da yana urinamiz.
                if (item.unlockRequested &&
                    row.isAttachedToWindow &&
                    unlockPrompted.add(item.peerUid)
                ) {
                    showResolveUnlock(item, name)
                }
            }
            item.isPending -> {
                sub.text = str(R.string.yuz_trust_sent_pending_desc, name)
                primary.visibility = View.VISIBLE
                primary.text = str(R.string.yuz_trust_waiting_title)
                primary.isEnabled = false
                primary.setOnClickListener(null)
                more.visibility = View.VISIBLE
                more.setOnClickListener { showOverflow(item, name) }
            }
            else -> {
                sub.text = str(R.string.yuz_trust_status_declined)
                primary.visibility = View.GONE
                more.visibility = View.VISIBLE
                more.setOnClickListener { showOverflow(item, name) }
            }
        }
        return row
    }

    // ---------------------------------------------------------------- actions

    private suspend fun publishNow() {
        try {
            TrustSnapshotBuilder.publish(currentAccount)
        } catch (e: Exception) {
            Log.e(TAG, "publish", e)
        }
    }

    private fun accept(peerUid: String) {
        if (busy || uid.isBlank()) return
        busy = true
        scope?.launch {
            try {
                TrustStore.accept(uid, peerUid, share = true)
                publishNow()
            } catch (e: Exception) {
                Log.e(TAG, "accept", e)
            }
            busy = false
            toast(R.string.yuz_trust_accepted_toast)
        }
    }

    private fun decline(peerUid: String) {
        if (busy || uid.isBlank()) return
        busy = true
        scope?.launch {
            try {
                TrustStore.decline(uid, peerUid)
                publishNow()
            } catch (e: Exception) {
                Log.e(TAG, "decline", e)
            }
            busy = false
            toast(R.string.yuz_trust_declined_toast)
        }
    }

    private fun revoke(peerUid: String) {
        if (busy || uid.isBlank()) return
        busy = true
        scope?.launch {
            try {
                TrustStore.revokeIncoming(uid, peerUid)
                publishNow()
            } catch (e: Exception) {
                Log.e(TAG, "revoke", e)
            }
            busy = false
            toast(R.string.yuz_trust_revoked_toast)
        }
    }

    private fun setShare(peerUid: String, share: Boolean) {
        if (uid.isBlank()) return
        scope?.launch {
            try {
                TrustStore.setShare(uid, peerUid, share)
                publishNow()
            } catch (e: Exception) {
                Log.e(TAG, "setShare", e)
            }
        }
    }

    private fun cancelSent(peerUid: String) {
        if (busy || uid.isBlank()) return
        busy = true
        scope?.launch {
            TrustStore.removeSent(uid, peerUid)
            busy = false
            toast(R.string.yuz_trust_revoked_note)
        }
    }

    // ------------------------------------------------------------------ lock

    /** Approver: mustahkamlangan ulanishni ochish uchun ruxsat so'raydi. */
    private fun askUnlockPermission(peerUid: String, name: String) {
        if (busy || uid.isBlank()) return
        val themed = dialogContext() ?: return
        try {
            AlertDialog.Builder(themed)
                .setTitle(str(R.string.yuz_trust_unlock_dialog_title))
                .setMessage(str(R.string.yuz_trust_unlock_request_desc, name))
                .setPositiveButton(str(R.string.yuz_trust_unlock_request)) { _, _ ->
                    busy = true
                    scope?.launch {
                        try {
                            TrustStore.requestUnlock(uid, peerUid)
                        } catch (e: Exception) {
                            Log.e(TAG, "requestUnlock", e)
                        }
                        busy = false
                        toast(R.string.yuz_trust_unlock_request_toast, name)
                    }
                }
                .setNegativeButton(str(android.R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "unlock dialog", e)
        }
    }

    /** Requester: approverning ochish so'rovini hal qiladi. */
    private fun showResolveUnlock(item: TrustStore.Outgoing, name: String) {
        if (unlockDialog?.isShowing == true) return
        val themed = dialogContext() ?: return
        try {
            val dialog = AlertDialog.Builder(themed)
                .setTitle(str(R.string.yuz_trust_unlock_dialog_title))
                .setMessage(str(R.string.yuz_trust_unlock_dialog_desc, name))
                .setPositiveButton(str(R.string.yuz_trust_unlock_allow)) { _, _ ->
                    resolveUnlock(item.peerUid, true)
                }
                .setNegativeButton(str(R.string.yuz_trust_unlock_deny)) { _, _ ->
                    resolveUnlock(item.peerUid, false)
                }
                .create()
            dialog.setOnDismissListener { if (unlockDialog === dialog) unlockDialog = null }
            unlockDialog = dialog
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "resolve unlock dialog", e)
        }
    }

    private fun resolveUnlock(peerUid: String, allow: Boolean) {
        if (busy || uid.isBlank()) return
        busy = true
        scope?.launch {
            try {
                TrustStore.resolveUnlock(uid, peerUid, allow)
            } catch (e: Exception) {
                Log.e(TAG, "resolveUnlock", e)
            }
            busy = false
            toast(
                if (allow) R.string.yuz_trust_unlock_allowed_toast
                else R.string.yuz_trust_unlock_denied_toast
            )
        }
    }

    /** Vertikal ⋮ menyusi — o'chirish va doimo ruxsatsiz kuzatish. */
    private fun showOverflow(item: TrustStore.Outgoing, name: String) {
        val themed = dialogContext() ?: return
        val labels = ArrayList<String>(3)
        val actions = ArrayList<() -> Unit>(3)
        labels.add(str(R.string.yuz_trust_remove))
        actions.add {
            if (item.isActive) confirmUnlink(item.peerUid, name) else cancelSent(item.peerUid)
        }
        if (item.isActive) {
            if (item.locked) {
                labels.add(str(R.string.yuz_trust_watch_free_on))
                actions.add { toast(R.string.yuz_trust_watch_free_active_toast) }
            } else {
                labels.add(str(R.string.yuz_trust_watch_free))
                actions.add { openWatchFree(item.peerUid, name) }
            }
            // Kuzatilayotgan odam qulfni ochishni so'rasa, javob berish yo'li.
            if (item.unlockRequested) {
                labels.add(str(R.string.yuz_trust_unlock_dialog_title))
                actions.add { showResolveUnlock(item, name) }
            }
        }
        try {
            AlertDialog.Builder(themed)
                .setTitle(name)
                .setItems(labels.toTypedArray()) { dialog, which ->
                    dialog.dismiss()
                    actions.getOrNull(which)?.invoke()
                }
                .setNegativeButton(str(android.R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "overflow dialog", e)
        }
    }

    /** "Doimo ruxsatsiz kuzatish" — faqat YuzGram Premium uchun. */
    private fun openWatchFree(peerUid: String, name: String) {
        if (busy || uid.isBlank()) return
        if (!YuzPremiumGate.isPremium(currentAccount, ctx())) {
            YuzPremiumGate.showWatchFreeRequired(this)
            return
        }
        val themed = dialogContext() ?: return
        try {
            AlertDialog.Builder(themed)
                .setTitle(str(R.string.yuz_trust_watch_free))
                .setMessage(str(R.string.yuz_trust_watch_free_confirm_desc, name))
                .setPositiveButton(str(R.string.yuz_trust_watch_free)) { _, _ -> enableWatchFree(peerUid) }
                .setNegativeButton(str(android.R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "watch-free dialog", e)
        }
    }

    private fun enableWatchFree(peerUid: String) {
        if (uid.isBlank()) return
        scope?.launch {
            try {
                TrustStore.setLocked(uid, peerUid, true)
            } catch (e: Exception) {
                Log.e(TAG, "setLocked", e)
            }
            toast(R.string.yuz_trust_watch_free_toast)
        }
    }

    private fun confirmUnlink(peerUid: String, name: String) {
        val themed = dialogContext() ?: return
        try {
            AlertDialog.Builder(themed)
                .setTitle(str(R.string.yuz_trust_remove))
                .setMessage(str(R.string.yuz_trust_unlink_confirm_desc, name))
                .setPositiveButton(str(R.string.yuz_trust_remove)) { _, _ -> cancelSent(peerUid) }
                .setNegativeButton(str(android.R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "unlink dialog", e)
        }
    }

    // ---------------------------------------------------------------- picker

    private fun openPicker() {
        presentFragment(YuzTrustPickerFragment { peerUid, peerName, telegramId ->
            onPicked(peerUid, peerName, telegramId)
        }, false)
    }

    private fun onPicked(peerUid: String, peerName: String, telegramId: Long) {
        if (uid.isBlank()) {
            toast(R.string.yuz_trust_sign_in_error)
            return
        }
        if (peerUid == uid) {
            toast(R.string.yuz_trust_self)
            return
        }
        if (sent.any { it.peerUid == peerUid && it.status == TrustStore.STATUS_PENDING }) {
            toast(R.string.yuz_trust_request_exists)
            return
        }
        if (sent.any { it.peerUid == peerUid && it.status == TrustStore.STATUS_ACCEPTED }) {
            return
        }
        scope?.launch {
            try {
                showChecking()
                val result = try {
                    TrustStore.usesYuzGram(peerUid)
                } catch (e: Exception) {
                    Log.e(TAG, "usesYuzGram", e)
                    null
                }
                hideChecking()
                when (result) {
                    null -> toast(R.string.yuz_trust_error)
                    false -> showNotYuzGram(peerName, telegramId)
                    true -> {
                        val ok = try {
                            TrustStore.sendRequest(uid, myName, peerUid, peerName)
                        } catch (e: Exception) {
                            Log.e(TAG, "sendRequest", e)
                            false
                        }
                        if (ok) toast(R.string.yuz_trust_request_sent, peerName)
                        else toast(R.string.yuz_trust_error)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "picked", e)
                hideChecking()
                toast(R.string.yuz_trust_error)
            }
        }
    }

    private fun showNotYuzGram(peerName: String, telegramId: Long) {
        val themed = dialogContext() ?: return
        val name = peerName.ifBlank { str(R.string.yuz_trust_you) }
        try {
            AlertDialog.Builder(themed)
                .setTitle(str(R.string.yuz_trust_not_yuzgram_title))
                .setMessage(str(R.string.yuz_trust_not_yuzgram_desc, name))
                .setPositiveButton(str(R.string.yuz_trust_not_yuzgram_invite)) { _, _ ->
                    org.telegram.messenger.AndroidUtilities.addToClipboard(
                        str(R.string.yuz_trust_invite_text)
                    )
                    toast(R.string.yuz_trust_copied)
                    if (telegramId > 0L) {
                        presentFragment(
                            org.telegram.ui.ChatActivity.of(telegramId),
                            false
                        )
                    }
                }
                .setNegativeButton(str(android.R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "not-yuzgram dialog", e)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun initials(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        val letters = parts.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
        return if (letters.isEmpty()) "?" else letters.joinToString("")
    }

    /** Ro'yxat qatoridagi avatar: Telegram profil surati, bo'lmasa bosh harflar. */
    private fun bindAvatar(row: View, peerUid: String) {
        val host = row.findViewById<FrameLayout>(R.id.personAvatarHost) ?: return
        val tgId = peerUid.removePrefix("tg_").toLongOrNull() ?: return
        try {
            val user = MessagesController.getInstance(currentAccount).getUser(tgId) ?: return
            val avatar = BackupImageView(row.context)
            avatar.setRoundRadius(dp(22))
            avatar.setForUserOrChat(user, AvatarDrawable(user))
            host.addView(avatar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            row.findViewById<TextView>(R.id.personInitials).visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "avatar", e)
        }
    }

    private fun dp(value: Int): Int =
        org.telegram.messenger.AndroidUtilities.dp(value.toFloat())

    /**
     * Dialoglar faqat faol Activity oynasiga tayangan kontekstda ochiladi —
     * `createConfigurationContext` asosli konteksda `show()` BadTokenException
     * beradi.
     */
    private fun dialogContext(): Context? {
        val activity = parentActivity ?: return null
        if (activity.isFinishing) return null
        val themed = themedContext ?: return activity
        var c: Context? = themed
        while (c is ContextWrapper) {
            if (c is Activity) return themed
            c = c.baseContext
        }
        return activity
    }

    private fun ctx(): Context =
        themedContext ?: context ?: ApplicationLoader.applicationContext

    private fun str(resId: Int): String = ctx().getString(resId)

    private fun str(resId: Int, vararg args: Any): String = ctx().getString(resId, *args)

    private fun toast(resId: Int) {
        try {
            Toast.makeText(ctx(), str(resId), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "toast: ${e.message}")
        }
    }

    private fun toast(resId: Int, vararg args: Any) {
        try {
            Toast.makeText(ctx(), str(resId, *args), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "toast: ${e.message}")
        }
    }
}