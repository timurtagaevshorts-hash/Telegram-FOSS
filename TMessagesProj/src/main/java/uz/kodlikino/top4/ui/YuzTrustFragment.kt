package uz.kodlikino.top4.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.trust.TrustSnapshotBuilder
import uz.kodlikino.top4.trust.TrustStore
import uz.kodlikino.top4.utils.YuzGramAdsAuth

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
        root.findViewById<View>(R.id.btnTrustPremium).setOnClickListener {
            presentFragment(YuzPremiumActivity(), false)
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
                    render()
                    onInboxUpdate(list)
                }
            }
            if (sentListener == null) {
                sentListener = TrustStore.listenSent(uid) { list ->
                    sent = list
                    loading = false
                    render()
                }
            }
            // Rozilik berilgan bo'lsa, o'z ro'yxatimni darhol nashr qilaman —
            // TrustSync davriy tickerini kutmasdan.
            TrustSnapshotBuilder.publish(currentAccount)
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
        requestDialog?.dismiss()
        requestDialog = null
        unlockDialog?.dismiss()
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
        val themed = themedContext ?: parentActivity ?: return
        val name = item.peerName.ifBlank { str(R.string.yuz_trust_you) }
        val dialog = AlertDialog.Builder(themed)
            .setTitle(str(R.string.yuz_trust_request_dialog_title))
            .setMessage(str(R.string.yuz_trust_request_dialog_desc, name))
            .setPositiveButton(str(R.string.yuz_trust_accept)) { _, _ -> accept(item.peerUid) }
            .setNegativeButton(str(R.string.yuz_trust_decline)) { _, _ -> decline(item.peerUid) }
            .create()
        dialog.setOnDismissListener { if (requestDialog === dialog) requestDialog = null }
        requestDialog = dialog
        dialog.show()
    }

    private fun showChecking() {
        val themed = themedContext ?: return
        val dialog = AlertDialog.Builder(themed)
            .setMessage(str(R.string.yuz_trust_checking))
            .create()
        dialog.setCancelable(false)
        checkingDialog = dialog
        dialog.show()
    }

    private fun hideChecking() {
        checkingDialog?.dismiss()
        checkingDialog = null
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
                // "Ko'rish" — asosiy va aniq ko'rinadigan amal.
                primary.visibility = View.VISIBLE
                primary.isEnabled = true
                primary.text = str(R.string.yuz_trust_view)
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

    private fun accept(peerUid: String) {
        if (busy || uid.isBlank()) return
        busy = true
        scope?.launch {
            TrustStore.accept(uid, peerUid, share = true)
            TrustSnapshotBuilder.publish(currentAccount)
            busy = false
            toast(R.string.yuz_trust_accepted_toast)
        }
    }

    private fun decline(peerUid: String) {
        if (busy || uid.isBlank()) return
        busy = true
        scope?.launch {
            TrustStore.decline(uid, peerUid)
            TrustSnapshotBuilder.publish(currentAccount)
            busy = false
            toast(R.string.yuz_trust_declined_toast)
        }
    }

    private fun revoke(peerUid: String) {
        if (busy || uid.isBlank()) return
        busy = true
        scope?.launch {
            TrustStore.revokeIncoming(uid, peerUid)
            TrustSnapshotBuilder.publish(currentAccount)
            busy = false
            toast(R.string.yuz_trust_revoked_toast)
        }
    }

    private fun setShare(peerUid: String, share: Boolean) {
        if (uid.isBlank()) return
        scope?.launch {
            TrustStore.setShare(uid, peerUid, share)
            TrustSnapshotBuilder.publish(currentAccount)
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
        val themed = themedContext ?: return
        AlertDialog.Builder(themed)
            .setTitle(str(R.string.yuz_trust_unlock_dialog_title))
            .setMessage(str(R.string.yuz_trust_unlock_request_desc, name))
            .setPositiveButton(str(R.string.yuz_trust_unlock_request)) { _, _ ->
                busy = true
                scope?.launch {
                    TrustStore.requestUnlock(uid, peerUid)
                    busy = false
                    toast(R.string.yuz_trust_unlock_request_toast, name)
                }
            }
            .setNegativeButton(str(android.R.string.cancel), null)
            .show()
    }

    /** Requester: approverning ochish so'rovini hal qiladi. */
    private fun showResolveUnlock(item: TrustStore.Outgoing, name: String) {
        if (unlockDialog?.isShowing == true) return
        val themed = themedContext ?: return
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
    }

    private fun resolveUnlock(peerUid: String, allow: Boolean) {
        if (busy || uid.isBlank()) return
        busy = true
        scope?.launch {
            TrustStore.resolveUnlock(uid, peerUid, allow)
            busy = false
            toast(
                if (allow) R.string.yuz_trust_unlock_allowed_toast
                else R.string.yuz_trust_unlock_denied_toast
            )
        }
    }

    /** Vertikal ⋮ menyusi — bekor qilish va qulf faqat shu yerda. */
    private fun showOverflow(item: TrustStore.Outgoing, name: String) {
        val themed = themedContext ?: return
        val labels = ArrayList<String>(4)
        val actions = ArrayList<() -> Unit>(4)
        labels.add(str(R.string.yuz_trust_more_view))
        actions.add { presentFragment(YuzTrustViewerFragment(item.peerUid, name), false) }
        if (item.isActive) {
            when {
                item.unlockRequested -> {
                    labels.add(str(R.string.yuz_trust_unlock_request))
                    actions.add { showResolveUnlock(item, name) }
                }
                item.locked -> {
                    labels.add(str(R.string.yuz_trust_unlock))
                    actions.add { confirmSetLock(item.peerUid, name, false) }
                }
                else -> {
                    labels.add(str(R.string.yuz_trust_lock))
                    actions.add { confirmSetLock(item.peerUid, name, true) }
                }
            }
            labels.add(str(R.string.yuz_trust_revoke))
            actions.add { confirmUnlink(item.peerUid, name) }
        } else {
            labels.add(str(R.string.yuz_trust_remove))
            actions.add { cancelSent(item.peerUid) }
        }
        AlertDialog.Builder(themed)
            .setTitle(name)
            .setItems(labels.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(str(android.R.string.cancel), null)
            .show()
    }

    private fun confirmSetLock(peerUid: String, name: String, locked: Boolean) {
        val themed = themedContext ?: return
        AlertDialog.Builder(themed)
            .setTitle(str(if (locked) R.string.yuz_trust_lock_dialog_title else R.string.yuz_trust_unlock))
            .setMessage(
                if (locked) str(R.string.yuz_trust_lock_dialog_desc, name)
                else str(R.string.yuz_trust_unlock_confirm_desc, name)
            )
            .setPositiveButton(str(if (locked) R.string.yuz_trust_lock else R.string.yuz_trust_unlock)) { _, _ ->
                setLock(peerUid, locked)
            }
            .setNegativeButton(str(android.R.string.cancel), null)
            .show()
    }

    private fun setLock(peerUid: String, locked: Boolean) {
        if (busy || uid.isBlank()) return
        busy = true
        scope?.launch {
            TrustStore.setLocked(uid, peerUid, locked)
            busy = false
            toast(
                if (locked) R.string.yuz_trust_lock_on_toast
                else R.string.yuz_trust_lock_off_toast
            )
        }
    }

    private fun confirmUnlink(peerUid: String, name: String) {
        val themed = themedContext ?: return
        AlertDialog.Builder(themed)
            .setTitle(str(R.string.yuz_trust_revoke))
            .setMessage(str(R.string.yuz_trust_unlink_confirm_desc, name))
            .setPositiveButton(str(R.string.yuz_trust_revoke)) { _, _ -> cancelSent(peerUid) }
            .setNegativeButton(str(android.R.string.cancel), null)
            .show()
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
            showChecking()
            val result = try {
                TrustStore.usesYuzGram(peerUid)
            } catch (e: Exception) {
                null
            }
            hideChecking()
            when (result) {
                null -> toast(R.string.yuz_trust_error)
                false -> showNotYuzGram(peerName, telegramId)
                true -> {
                    val ok = TrustStore.sendRequest(uid, myName, peerUid, peerName)
                    if (ok) toast(R.string.yuz_trust_request_sent, peerName)
                    else toast(R.string.yuz_trust_error)
                }
            }
        }
    }

    private fun showNotYuzGram(peerName: String, telegramId: Long) {
        val activity = parentActivity ?: return
        val name = peerName.ifBlank { str(R.string.yuz_trust_you) }
        val themed = themedContext ?: activity
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
    }

    // ---------------------------------------------------------------- helpers

    private fun initials(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotBlank() }
        val letters = parts.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
        return if (letters.isEmpty()) "?" else letters.joinToString("")
    }

    private fun dp(value: Int): Int =
        org.telegram.messenger.AndroidUtilities.dp(value.toFloat())

    private fun ctx(): Context = themedContext ?: context ?: throw IllegalStateException()

    private fun str(resId: Int): String = ctx().getString(resId)

    private fun str(resId: Int, vararg args: Any): String = ctx().getString(resId, *args)

    private fun toast(resId: Int) {
        Toast.makeText(ctx(), str(resId), Toast.LENGTH_SHORT).show()
    }

    private fun toast(resId: Int, vararg args: Any) {
        Toast.makeText(ctx(), str(resId, *args), Toast.LENGTH_LONG).show()
    }
}