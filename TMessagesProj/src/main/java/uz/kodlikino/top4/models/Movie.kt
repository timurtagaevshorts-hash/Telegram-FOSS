package uz.kodlikino.top4.models

/**
 * Kino (film) — Firestore `movies` kolleksiyasi hujjati.
 * Donor KinoTOP4 `models/Movie.kt` tuzilmasi bilan bir xil (backend shu schema'ni
 * ishlatadi: id == document id, admin `claimAdminSession` orqali yozadi).
 */
data class Movie(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val genre: String = "",
    val category: String = "",
    val imageUrl: String = "",
    val bannerUrl: String = "",
    val videoUrl: String = "",
    val year: String = "",
    val duration: String = "",
    val rating: Double = 0.0,
    val views: Int = 0,
    val likes: Int = 0,
    val searchCount: Int = 0,
    val adult: Boolean = true,
    val isMultfilm: Boolean = false,
    val isPremium: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val adNotificationText: String = "",
    val adNotificationSent: Boolean = false
)
