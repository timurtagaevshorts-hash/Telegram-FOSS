package uz.kodlikino.top4.models

import java.io.Serializable

/**
 * "Reels" video posti modeli — KinoTOP4'dagi `Reel` bilan bir xil maydonlar
 * (Firestore `reels` kolleksiyasi bilan mos keladi).
 */
data class Reel(
    val id: String = "",
    val videoUrl: String = "",
    /** Firebase Storage poster — darhol ko'rinish uchun */
    val thumbnailUrl: String = "",
    var views: Int = 0,
    val title: String = "",
    val hashtags: String = "",
    /** false bo'lsa ko'rishlar daromadga kirmaydi */
    val monetizationEnabled: Boolean = true,
    val movieUrl: String = "",
    val adTitle: String = "",
    val adUrl: String = "",
    val commentCount: Int = 0,
    val likeCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val uploadedByUid: String = "",
    val uploadedByName: String = "",
    val uploadedByPhotoUrl: String = "",
    val isUserUpload: Boolean = false,
    /** Faqat UI — Firestore'da saqlanmaydi */
    var isLikedByMe: Boolean = false
) : Serializable {
    /** Sarlavha + hashtag'lardan iborat caption. */
    fun caption(): String {
        val t = title.trim()
        val tags = hashtags.trim()
        return when {
            t.isNotBlank() && tags.isNotBlank() -> "$t\n$tags"
            t.isNotBlank() -> t
            else -> tags
        }
    }
}
