package uz.kodlikino.top4.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.FollowerRewardTier
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.models.WithdrawalRequest
import uz.kodlikino.top4.utils.FollowManager
import uz.kodlikino.top4.utils.FollowerRewardManager
import uz.kodlikino.top4.utils.MonetizationBalance
import uz.kodlikino.top4.utils.MonetizationCalculator
import uz.kodlikino.top4.utils.MonetizationRepository
import uz.kodlikino.top4.utils.ReelsRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth

/**
 * Monetizatsiya ekrani — ko'rishlar daromadi, obunachi mukofotlari,
 * hisobdan pul yechish va "mening reel'larim"ni boshqarish.
 * KinoTOP4 tuzilmasidagi [MonetizationCalculator]/[FollowerRewardManager]/
 * [MonetizationRepository] ustida qurilgan; auth — [YuzGramAdsAuth] (`tg_<id>`).
 */
class MonetizationActivity : BaseFragment() {

    private var themedContext: Context? = null
    private var progress: ProgressBar? = null
    private var content: LinearLayout? = null
    private var tvAvailable: TextView? = null
    private var tvEarned: TextView? = null
    private var tvViews: TextView? = null
    private var tvPending: TextView? = null
    private var tvRate: TextView? = null
    private var btnWithdraw: MaterialButton? = null
    private var tvFollowStats: TextView? = null
    private var rewardsContainer: LinearLayout? = null
    private var reelsContainer: LinearLayout? = null
    private var tvNoReels: TextView? = null

    private var scope: CoroutineScope? = null
    private var uid: String = ""
    private var balance: MonetizationBalance = MonetizationBalance(0, 0, 0, 0, 0)
    private var followerCount: Int = 0
    private var reelWithdrawals: List<WithdrawalRequest> = emptyList()

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_monetization, null) as ViewGroup
        fragmentView = root
        actionBar?.visibility = View.GONE

        val toolbar = root.findViewById<Toolbar>(R.id.monetToolbar)
        toolbar.setPadding(
            toolbar.paddingLeft, AndroidUtilities.statusBarHeight,
            toolbar.paddingRight, toolbar.paddingBottom
        )
        toolbar.setNavigationOnClickListener { removeSelfFromStack() }

        progress = root.findViewById(R.id.monetProgress)
        content = root.findViewById(R.id.monetContent)
        tvAvailable = root.findViewById(R.id.tvAvailable)
        tvEarned = root.findViewById(R.id.tvEarned)
        tvViews = root.findViewById(R.id.tvViews)
        tvPending = root.findViewById(R.id.tvPending)
        tvRate = root.findViewById(R.id.tvRate)
        btnWithdraw = root.findViewById(R.id.btnWithdraw)
        tvFollowStats = root.findViewById(R.id.tvFollowStats)
        rewardsContainer = root.findViewById(R.id.rewardsContainer)
        reelsContainer = root.findViewById(R.id.reelsContainer)
        tvNoReels = root.findViewById(R.id.tvNoReels)

        btnWithdraw?.setOnClickListener { openWithdrawDialog() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        loadData()
        return root
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
    }

    override fun onFragmentDestroy() {
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }

    private fun loadData() {
        val s = scope ?: return
        val account = currentAccount
        progress?.visibility = View.VISIBLE
        content?.visibility = View.GONE
        s.launch {
            uid = YuzGramAdsAuth.expectedUid(account)
            if (uid.isBlank()) {
                progress?.visibility = View.GONE
                toast(R.string.monet_sign_in_required)
                return@launch
            }
            val reels = ReelsRepository.getReelsByUploader(uid)
            val monetizableViews = reels
                .filter { it.monetizationEnabled }
                .sumOf { it.views }
            reelWithdrawals = MonetizationRepository.getUserWithdrawalRequests(uid)
                .filter { it.isReelSource() }
            balance = MonetizationCalculator.calculateAvailableBalance(monetizableViews, reelWithdrawals)

            val counts = FollowManager.fetchFollowCounts(uid)
            followerCount = counts.followers
            val followerWithdrawals = MonetizationRepository.getUserWithdrawalRequests(uid)
                .filter { it.isFollowerSource() }

            progress?.visibility = View.GONE
            content?.visibility = View.VISIBLE
            bindBalance()
            bindRewers(counts.following, followerWithdrawals)
            bindReels(reels)
        }
    }

    private fun bindBalance() {
        tvAvailable?.text = MonetizationCalculator.formatSom(balance.available)
        tvEarned?.text = MonetizationCalculator.formatSom(balance.totalEarned)
        tvViews?.text = MonetizationCalculator.formatAmount(balance.totalViews.toLong())
        tvPending?.text = MonetizationCalculator.formatSom(balance.pending)
        tvRate?.text = MonetizationCalculator.formatRateLine(isFullPremium = false)
        btnWithdraw?.isEnabled = balance.available >= MonetizationCalculator.MIN_WITHDRAWAL
    }

    private fun bindRewers(followingCount: Int, followerWithdrawals: List<WithdrawalRequest>) {
        tvFollowStats?.text = "${getString(R.string.monet_followers)}: $followerCount   " +
            "${getString(R.string.monet_following)}: $followingCount"

        rewardsContainer?.removeAllViews()
        val tiers = FollowerRewardManager.activeTiers(FollowerRewardTier.defaults(), programEnabled = true)
        if (tiers.isEmpty()) {
            val tv = TextView(themedContext ?: return).apply {
                text = getString(R.string.monet_no_rewards)
                setTextColor(resources.getColor(R.color.text_secondary, null))
                textSize = 13f
            }
            rewardsContainer?.addView(tv)
            return
        }
        for (tier in tiers) {
            val state = FollowerRewardManager.tierState(followerCount, tier, followerWithdrawals)
            rewardsContainer?.addView(buildTierRow(tier, state))
        }
    }

    private fun buildTierRow(tier: FollowerRewardTier, state: FollowerRewardManager.TierState): View {
        val ctx = themedContext ?: return View(context)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10))
        }
        val label = TextView(ctx).apply {
            text = "${tier.subscribers} → ${MonetizationCalculator.formatSom(tier.rewardSom)}"
            setTextColor(resources.getColor(R.color.text_primary, null))
            textSize = 14f
        }
        val stateTv = TextView(ctx).apply {
            text = stateLabel(state)
            setTextColor(
                resources.getColor(
                    when (state) {
                        FollowerRewardManager.TierState.READY -> R.color.classified_price_gold
                        FollowerRewardManager.TierState.APPROVED -> R.color.primary
                        else -> R.color.text_secondary
                    }, null
                )
            )
            textSize = 12f
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(stateTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        if (state == FollowerRewardManager.TierState.READY) {
            val btn = MaterialButton(ctx, null, 0).apply {
                text = getString(R.string.monet_withdraw)
                textSize = 11f
                setOnClickListener { withdrawForTier(tier) }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(36)
            ).apply { marginStart = AndroidUtilities.dp(8) }
            row.addView(btn, lp)
        }
        return row
    }

    private fun stateLabel(state: FollowerRewardManager.TierState): String = when (state) {
        FollowerRewardManager.TierState.READY -> getString(R.string.monet_tier_ready)
        FollowerRewardManager.TierState.LOCKED -> getString(R.string.monet_tier_locked)
        FollowerRewardManager.TierState.PENDING -> getString(R.string.monet_tier_pending)
        FollowerRewardManager.TierState.APPROVED -> getString(R.string.monet_tier_approved)
        FollowerRewardManager.TierState.REJECTED -> getString(R.string.monet_tier_rejected)
    }

    private fun bindReels(reels: List<Reel>) {
        reelsContainer?.removeAllViews()
        if (reels.isEmpty()) {
            tvNoReels?.visibility = View.VISIBLE
            return
        }
        tvNoReels?.visibility = View.GONE
        for (reel in reels) {
            reelsContainer?.addView(buildReelRow(reel))
        }
    }

    private fun buildReelRow(reel: Reel): View {
        val ctx = themedContext ?: return View(context)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10))
        }
        val title = TextView(ctx).apply {
            text = reel.title.ifBlank { reel.caption() }.ifBlank { reel.id }
            setTextColor(resources.getColor(R.color.text_primary, null))
            textSize = 14f
            maxLines = 2
        }
        val views = TextView(ctx).apply {
            text = getString(R.string.monet_reel_views, reel.views)
            setTextColor(resources.getColor(R.color.text_secondary, null))
            textSize = 12f
        }
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        left.addView(title)
        left.addView(views)
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val del = MaterialButton(ctx, null, 0).apply {
            text = getString(R.string.classified_delete)
            textSize = 11f
            setOnClickListener { deleteReel(reel) }
        }
        row.addView(del, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(36)
        ))
        return row
    }

    private fun deleteReel(reel: Reel) {
        val s = scope ?: return
        s.launch {
            try {
                YuzGramAdsAuth.ensureSignedIn(currentAccount)
                ReelsRepository.deleteReel(reel.id)
                toast(R.string.monet_reel_deleted)
                loadData()
            } catch (e: Exception) {
                toast(getString(R.string.instagram_save_error_failed))
            }
        }
    }

    private fun withdrawForTier(tier: FollowerRewardTier) {
        val s = scope ?: return
        s.launch {
            try {
                YuzGramAdsAuth.ensureSignedIn(currentAccount)
                val user = UserConfig.getInstance(currentAccount).currentUser
                val req = WithdrawalRequest(
                    id = System.currentTimeMillis().toString(),
                    userId = uid,
                    firstName = user?.first_name.orEmpty(),
                    lastName = user?.last_name.orEmpty(),
                    amount = tier.rewardSom,
                    currency = WithdrawalRequest.CURRENCY_UZS,
                    source = WithdrawalRequest.SOURCE_FOLLOWER,
                    followerMilestone = tier.subscribers
                )
                MonetizationRepository.createWithdrawalRequest(req)
                toast(R.string.withdraw_created)
                loadData()
            } catch (e: Exception) {
                toast(getString(R.string.withdraw_failed))
            }
        }
    }

    private fun openWithdrawDialog() {
        val ctx = themedContext ?: return
        val available = balance.available
        if (available < MonetizationCalculator.MIN_WITHDRAWAL) {
            toast(R.string.withdraw_no_balance)
            return
        }
        val pad = AndroidUtilities.dp(20)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val etAmount = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.withdraw_amount_hint)
            setText(MonetizationCalculator.parseSom(available.toString()).toString())
        }
        val etFirst = EditText(ctx).apply { hint = getString(R.string.withdraw_first_hint) }
        val etLast = EditText(ctx).apply { hint = getString(R.string.withdraw_last_hint) }
        val etCard = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.withdraw_card_hint)
        }
        container.addView(etAmount)
        container.addView(etFirst)
        container.addView(etLast)
        container.addView(etCard)

        AlertDialog.Builder(ctx)
            .setTitle(R.string.withdraw_title)
            .setView(container)
            .setNegativeButton(R.string.withdraw_cancel, null)
            .setPositiveButton(R.string.withdraw_submit) { _, _ ->
                submitWithdraw(etAmount, etFirst, etLast, etCard)
            }
            .show()
    }

    private fun submitWithdraw(
        etAmount: EditText,
        etFirst: EditText,
        etLast: EditText,
        etCard: EditText
    ) {
        val firstName = etFirst.text.toString().trim()
        val lastName = etLast.text.toString().trim()
        val card = MonetizationCalculator.normalizeHumoCard(etCard.text.toString())
        val amount = MonetizationCalculator.parseSom(etAmount.text.toString())
        when {
            firstName.isBlank() || lastName.isBlank() || card == null -> {
                toast(R.string.withdraw_fill_fields); return
            }
            amount == null || amount <= 0 -> { toast(R.string.withdraw_fill_fields); return }
            amount < MonetizationCalculator.MIN_WITHDRAWAL -> {
                toast(getString(R.string.withdraw_min_amount,
                    MonetizationCalculator.formatSom(MonetizationCalculator.MIN_WITHDRAWAL))); return
            }
            amount > MonetizationCalculator.MAX_WITHDRAWAL -> {
                toast(getString(R.string.withdraw_max_amount,
                    MonetizationCalculator.formatSom(MonetizationCalculator.MAX_WITHDRAWAL))); return
            }
            amount > balance.available -> {
                toast(getString(R.string.withdraw_balance_insufficient,
                    MonetizationCalculator.formatSom(balance.available))); return
            }
        }
        val s = scope ?: return
        s.launch {
            try {
                YuzGramAdsAuth.ensureSignedIn(currentAccount)
                val req = WithdrawalRequest(
                    id = System.currentTimeMillis().toString(),
                    userId = uid,
                    firstName = firstName,
                    lastName = lastName,
                    amount = amount,
                    currency = WithdrawalRequest.CURRENCY_UZS,
                    cardType = WithdrawalRequest.CARD_HUMO,
                    humoCard = card,
                    totalViews = balance.totalViews,
                    totalEarned = balance.totalEarned,
                    source = WithdrawalRequest.SOURCE_REEL
                )
                MonetizationRepository.createWithdrawalRequest(req)
                toast(R.string.withdraw_created)
                loadData()
            } catch (e: Exception) {
                toast(getString(R.string.withdraw_failed))
            }
        }
    }

    private fun toast(resId: Int) {
        val ctx = themedContext ?: context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }

    private fun getString(resId: Int, vararg args: Any): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId, *args)
    }
}
