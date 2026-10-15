package uz.kodlikino.top4.models

import java.io.Serializable

data class ClassifiedAd(
    val id: String = "",
    val title: String = "",
    val model: String = "",
    val description: String = "",
    val price: Long = 0L,
    val category: String = ClassifiedAdCategories.OTHER,
    val imageUrls: List<String> = emptyList(),
    val address: String = "",
    val phone: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val ownerPhotoUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    /** Admin tomonidan bloklangan — faqat egasiga ko'rinadi. */
    val isBlocked: Boolean = false,
    /** Karta fon rangi: white, black, premium_red, premium_blue */
    val cardTheme: String = ClassifiedAdCardThemes.BLACK,
    /** Viloyat/shahar nomi — filtrlash uchun. */
    val region: String = "",
    /** JobCard uchun maxsus: hiring, seeking_work. */
    val listingType: String = ""
) : Serializable {

    fun isPubliclyVisible(): Boolean = isActive && !isBlocked

    fun primaryImage(): String = imageUrls.firstOrNull().orEmpty()

    fun formattedPrice(): String {
        if (price <= 0L) return "Kelishiladi"
        return "%,d so'm".format(price).replace(',', ' ')
    }
}
