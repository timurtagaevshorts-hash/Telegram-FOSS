package uz.kodlikino.top4.models

data class TaxiOrder(
    val id: String = "",
    val userId: String = "",
    val userName: String = "",
    val phone: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val addressLabel: String = "",
    /** Foydalanuvchi tanlagan boradigan manzil. */
    val destinationLabel: String = "",
    val status: String = STATUS_PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    /** Shu vaqtgacha yangi taksi buyurtmasi bloklangan (ms). */
    val callBlockedUntil: Long = 0L,
    val telegramMessageId: Long = 0L,
    val statusMessage: String = ""
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_ARRIVED = "arrived"
        const val CALL_COOLDOWN_MS = 0L
        /** Haydovchi yetib kelishi uchun ekrandagi taymer (5 daqiqa). */
        const val ETA_COUNTDOWN_MS = 5L * 60L * 1000L
    }
}
