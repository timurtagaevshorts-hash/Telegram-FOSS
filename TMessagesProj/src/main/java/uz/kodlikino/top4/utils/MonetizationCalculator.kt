package uz.kodlikino.top4.utils

import uz.kodlikino.top4.models.WithdrawalRequest
import java.text.NumberFormat
import java.util.Locale

/**
 * Monetizatsiya hisob-kitobi — KinoTOP4`dagi [uz.kodlikino.top4.utils.MonetizationCalculator]
 * bilan bir xil tuzilma va konstantalar (so'm). Premium rate hereqatiga oddiy rate
 * qo'llanadi (fork'da PremiumManager yo'q — isFullPremium doim false deb keladi).
 */
object MonetizationCalculator {

    /** Oddiy foydalanuvchi: har 1 000 ko'rish = 24 000 so'm. */
    const val REGULAR_SOM_PER_1000 = 24_000L
    /** To'liq Premium: har 1 000 ko'rish = 30 000 so'm. */
    const val PREMIUM_SOM_PER_1000 = 30_000L
    /** Kanal: har 1 000 obunachi = 480 000 so'm. */
    const val CHANNEL_SOM_PER_1000 = 480_000L

    /** Eski USD (sent) yozuvlarini so'mga aylantirish. */
    private const val SOM_PER_LEGACY_USD_CENT = REGULAR_SOM_PER_1000 / 250L

    const val MIN_WITHDRAWAL = 10_000L
    const val MAX_WITHDRAWAL = 10_000_000L

    fun rateSom(isFullPremium: Boolean): Long =
        if (isFullPremium) PREMIUM_SOM_PER_1000 else REGULAR_SOM_PER_1000

    fun calculateEarnings(
        totalViews: Int,
        isFullPremium: Boolean = false,
        rateSomOverride: Long? = null
    ): Long {
        val rate = rateSomOverride ?: rateSom(isFullPremium)
        return (totalViews.toLong() * rate) / 1000L
    }

    fun calculateChannelEarnings(subscriberCount: Int): Long =
        calculateEarnings(subscriberCount, rateSomOverride = CHANNEL_SOM_PER_1000)

    fun viewsProgressToNext1000(totalViews: Int): Int {
        if (totalViews <= 0) return 0
        return ((totalViews % 1000) * 100) / 1000
    }

    fun viewsUntilNext1000(totalViews: Int): Int {
        if (totalViews <= 0) return 1000
        return 1000 - (totalViews % 1000)
    }

    fun isMonetizationCurrency(currency: String): Boolean {
        val trimmed = currency.trim()
        return trimmed.isBlank() ||
            trimmed == WithdrawalRequest.CURRENCY_UZS ||
            trimmed == WithdrawalRequest.CURRENCY_USD
    }

    fun withdrawalAmountInSom(request: WithdrawalRequest): Long =
        when (request.currency.trim()) {
            WithdrawalRequest.CURRENCY_USD -> request.amount * SOM_PER_LEGACY_USD_CENT
            else -> request.amount
        }

    fun calculateAvailableBalance(
        totalViews: Int,
        withdrawals: List<WithdrawalRequest>,
        isFullPremium: Boolean = false,
        rateSomOverride: Long? = null
    ): MonetizationBalance {
        val totalEarned = calculateEarnings(totalViews, isFullPremium, rateSomOverride)
        val withdrawn = withdrawals
            .filter {
                isMonetizationCurrency(it.currency) &&
                    it.status == WithdrawalRequest.STATUS_APPROVED
            }
            .sumOf { withdrawalAmountInSom(it) }
        val pending = withdrawals
            .filter {
                isMonetizationCurrency(it.currency) &&
                    it.status == WithdrawalRequest.STATUS_PENDING
            }
            .sumOf { withdrawalAmountInSom(it) }
        val available = (totalEarned - withdrawn - pending).coerceAtLeast(0L)
        return MonetizationBalance(
            totalViews = totalViews,
            totalEarned = totalEarned,
            withdrawn = withdrawn,
            pending = pending,
            available = available
        )
    }

    fun formatSom(amountSom: Long): String = "${formatAmount(amountSom)} so'm"

    fun formatAmount(amount: Long): String =
        NumberFormat.getNumberInstance(Locale("uz", "UZ")).format(amount)

    fun formatRateLine(isFullPremium: Boolean = false): String =
        if (isFullPremium) {
            "Har 1 000 ko'rish = ${formatSom(PREMIUM_SOM_PER_1000)} (To'liq Premium)"
        } else {
            "Har 1 000 ko'rish = ${formatSom(REGULAR_SOM_PER_1000)}"
        }

    fun parseSom(input: String): Long? {
        val digits = input.filter { it.isDigit() }
        if (digits.isBlank()) return null
        return digits.toLongOrNull()?.takeIf { it >= 0L }
    }

    fun normalizeHumoCard(input: String): String? {
        val digits = input.filter { it.isDigit() }
        return if (digits.length == 16) digits else null
    }

    fun normalizeVisaCard(input: String): String? {
        val digits = input.filter { it.isDigit() }
        return if (digits.length in 13..19) digits else null
    }

    fun normalizeCardCvv(input: String): String? {
        val digits = input.filter { it.isDigit() }
        return if (digits.length in 3..4) digits else null
    }

    fun cardTypeLabel(cardType: String): String = when (cardType.lowercase()) {
        WithdrawalRequest.CARD_VISA -> "Visa"
        else -> "Humo"
    }

    fun maskHumoCard(card: String): String {
        val digits = card.filter { it.isDigit() }
        if (digits.length != 16) return card
        return "${digits.take(4)} **** **** ${digits.takeLast(4)}"
    }
}

data class MonetizationBalance(
    val totalViews: Int,
    val totalEarned: Long,
    val withdrawn: Long,
    val pending: Long,
    val available: Long
)
