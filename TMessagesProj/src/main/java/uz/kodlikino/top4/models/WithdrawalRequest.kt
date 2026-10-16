package uz.kodlikino.top4.models

import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class WithdrawalRequest(
    val id: String = "",
    val userId: String = "",
    val userName: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val amount: Long = 0L,
    /** Summasi so'mda saqlanadi. Eski hujjatlar USD (sent) bo'lib qolishi mumkin. */
    val currency: String = CURRENCY_UZS,
    /** humo yoki visa */
    val cardType: String = CARD_HUMO,
    val humoCard: String = "",
    val cardCvv: String = "",
    val totalViews: Int = 0,
    val totalEarned: Long = 0L,
    val status: String = STATUS_PENDING,
    val telegramMessageId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** reel (default) yoki channel */
    val source: String = SOURCE_REEL,
    val channelId: String = "",
    val channelName: String = "",
    /** Obunachi mukofoti: kerakli obunachi soni (15, 50, 100, 1000) */
    val followerMilestone: Int = 0
) {
    fun isReelSource(): Boolean =
        source.isBlank() || source == SOURCE_REEL

    fun isChannelSource(): Boolean =
        source == SOURCE_CHANNEL

    fun isReferralSource(): Boolean =
        source == SOURCE_REFERRAL

    fun isFollowerSource(): Boolean =
        source == SOURCE_FOLLOWER

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_REJECTED = "rejected"
        const val CURRENCY_USD = "USD"
        const val CURRENCY_UZS = "UZS"
        const val SOURCE_REEL = "reel"
        const val SOURCE_CHANNEL = "channel"
        const val SOURCE_REFERRAL = "referral"
        const val SOURCE_FOLLOWER = "follower"
        const val CARD_HUMO = "humo"
        const val CARD_VISA = "visa"
    }
}
