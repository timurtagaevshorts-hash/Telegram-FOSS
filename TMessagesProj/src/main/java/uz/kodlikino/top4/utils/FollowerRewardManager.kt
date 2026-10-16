package uz.kodlikino.top4.utils

import uz.kodlikino.top4.models.FollowerRewardTier
import uz.kodlikino.top4.models.WithdrawalRequest

/** Obunachi soniga qarab mukofot bosqichlari (15/50/100/1000) holati. */
object FollowerRewardManager {

    enum class TierState {
        LOCKED,
        READY,
        PENDING,
        APPROVED,
        REJECTED
    }

    fun activeTiers(
        tiers: List<FollowerRewardTier>,
        programEnabled: Boolean
    ): List<FollowerRewardTier> {
        if (!programEnabled) return emptyList()
        return tiers
            .filter { it.enabled && it.subscribers > 0 && it.rewardSom > 0L }
            .sortedBy { it.subscribers }
    }

    fun tierState(
        followerCount: Int,
        tier: FollowerRewardTier,
        withdrawals: List<WithdrawalRequest>
    ): TierState {
        val forTier = withdrawals.filter { it.followerMilestone == tier.subscribers }
        if (forTier.any { it.status == WithdrawalRequest.STATUS_APPROVED }) {
            return TierState.APPROVED
        }
        if (forTier.any { it.status == WithdrawalRequest.STATUS_PENDING }) {
            return TierState.PENDING
        }
        if (forTier.any { it.status == WithdrawalRequest.STATUS_REJECTED }) {
            return if (followerCount >= tier.subscribers) TierState.REJECTED else TierState.LOCKED
        }
        return if (followerCount >= tier.subscribers) TierState.READY else TierState.LOCKED
    }

    fun canWithdraw(
        followerCount: Int,
        tier: FollowerRewardTier,
        withdrawals: List<WithdrawalRequest>
    ): Boolean = tierState(followerCount, tier, withdrawals) == TierState.READY

    fun progressLabel(followerCount: Int, tier: FollowerRewardTier): String {
        val current = followerCount.coerceAtLeast(0)
        val required = tier.subscribers.coerceAtLeast(1)
        return "$current / $required"
    }
}
