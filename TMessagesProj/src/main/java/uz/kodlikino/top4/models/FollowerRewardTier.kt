package uz.kodlikino.top4.models

data class FollowerRewardTier(
    val subscribers: Int = 0,
    val rewardSom: Long = 0L,
    val enabled: Boolean = true
) {
    companion object {
        fun defaults(): List<FollowerRewardTier> = listOf(
            FollowerRewardTier(subscribers = 15, rewardSom = 20_000L),
            FollowerRewardTier(subscribers = 50, rewardSom = 65_000L),
            FollowerRewardTier(subscribers = 100, rewardSom = 140_000L),
            FollowerRewardTier(subscribers = 1000, rewardSom = 2_000_000L)
        )
    }
}
