package uz.kodlikino.top4.models

import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName

/**
 * Firestore `comment_profiles` hujjati — obuna tizimi va profil ko'rsatkichlari
 * uchun minimal maydonlar to'plami. KinoTOP4'dagi kengroq modelning fork'da
 * kerakli qismi (PremiumManager/checkers maydonlari olib tashlandi).
 */
@IgnoreExtraProperties
data class CommentUserProfile(
    val uid: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val photoUrl: String = "",
    val bio: String = "",
    @field:PropertyName("isPremium")
    val isPremium: Boolean = false,
    val premiumPlanType: String = "",
    val monetizationVerified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val followerCount: Int = 0,
    val followingCount: Int = 0
) {
    val displayName: String
        get() = listOf(firstName.trim(), lastName.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    val isComplete: Boolean
        get() = firstName.isNotBlank()
}
