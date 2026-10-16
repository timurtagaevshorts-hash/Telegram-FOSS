package uz.kodlikino.top4.models

import java.io.Serializable

/**
 * Reel izohi — Firestore `reels/{reelId}/comments/{id}` hujjati.
 *
 * Maydon nomlari KinoTOP4'dagi `ReelComment` bilan bir xil (umumiy tasma),
 * shuning uchun eski ilovada yozilgan izohlar YuzGram'da, yangilari esa
 * KinoTOP4'da ko'rinadi. `userId` Firebase Auth uid'i (`tg_<telegramId>`) —
 * qoida `request.resource.data.userId == request.auth.uid` ni talab qiladi.
 */
data class ReelComment(
    val id: String = "",
    val reelId: String = "",
    val text: String = "",
    val authorName: String = "",
    val authorPhotoUrl: String = "",
    val userId: String = "",
    val deviceId: String = "",
    val createdAt: Long = 0L,
    /** Javob berilayotgan asosiy izoh (bo'sh = oddiy izoh). */
    val replyToCommentId: String = "",
    val replyToUserId: String = "",
    val replyToAuthorName: String = ""
) : Serializable {
    val isReply: Boolean get() = replyToCommentId.isNotBlank()
}
