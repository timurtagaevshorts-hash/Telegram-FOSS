package uz.kodlikino.top4.models

/** Reels faoliyat bildirishnomasi: like | comment | reply. */
data class ReelNotification(
    val id: String = "",
    val type: String = TYPE_LIKE,
    val reelId: String = "",
    val commentId: String = "",
    val commentText: String = "",
    val actorUid: String = "",
    val actorName: String = "",
    val actorPhotoUrl: String = "",
    val reelTitle: String = "",
    val reelThumbUrl: String = "",
    val createdAt: Long = 0L,
    val read: Boolean = false
) {
    companion object {
        const val TYPE_LIKE = "like"
        const val TYPE_COMMENT = "comment"
        const val TYPE_REPLY = "reply"
    }
}
