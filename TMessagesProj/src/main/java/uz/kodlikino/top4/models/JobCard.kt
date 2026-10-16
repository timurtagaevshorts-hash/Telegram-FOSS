package uz.kodlikino.top4.models

import java.io.Serializable

/** Ish kartasi — ish qidirish yoki ishchi qidirish e'loni. */
data class JobCard(
    val id: String = "",
    /** @see JobCardListingTypes */
    val listingType: String = JobCardListingTypes.HIRING,
    /** Ish nomi yoki qila oladigan ish. */
    val title: String = "",
    val description: String = "",
    /** Manzil / qayerdan. */
    val location: String = "",
    val phone: String = "",
    val salaryAmount: Long = 0L,
    /** @see JobCardSalaryTypes */
    val salaryType: String = JobCardSalaryTypes.NEGOTIABLE,
    val imageUrl: String = "",
    val cardTheme: String = ClassifiedAdCardThemes.BLACK,
    val ownerUid: String = "",
    val ownerName: String = "",
    val ownerPhotoUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val isBlocked: Boolean = false,
    val region: String = ""
) : Serializable {

    fun isPubliclyVisible(): Boolean = isActive && !isBlocked

    fun formattedSalary(): String {
        if (listingType == JobCardListingTypes.SEEKING_WORK) return ""
        if (salaryAmount <= 0L) {
            return when (salaryType) {
                JobCardSalaryTypes.DAILY -> "Kunlik — kelishiladi"
                JobCardSalaryTypes.MONTHLY -> "Oylik — kelishiladi"
                else -> "Kelishilgan holda"
            }
        }
        val amount = "%,d".format(salaryAmount).replace(',', ' ')
        return when (salaryType) {
            JobCardSalaryTypes.DAILY -> "$amount so'm / kun"
            JobCardSalaryTypes.MONTHLY -> "$amount so'm / oy"
            else -> "$amount so'm"
        }
    }

    /** E'lon rasmi bo'lmasa — profil yoki boshqa ish kartasi rasmi. */
    fun displayImageUrl(): String {
        imageUrl.trim().takeIf { it.isNotBlank() }?.let { return it }
        ownerPhotoUrl.trim().takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }
}

object JobCardListingTypes {
    const val SEEKING_WORK = "seeking_work"
    const val HIRING = "hiring"

    fun label(type: String): String = when (type) {
        SEEKING_WORK -> "Ishlayman"
        HIRING -> "Ishchi kerak"
        else -> "Ish"
    }

    fun emoji(type: String): String = when (type) {
        SEEKING_WORK -> "👷"
        HIRING -> "💼"
        else -> "📋"
    }
}

object JobCardSalaryTypes {
    const val DAILY = "daily"
    const val MONTHLY = "monthly"
    const val NEGOTIABLE = "negotiable"

    /** Spinner tartibi: avvalo kelishilgan holda. */
    val spinnerOrder = listOf(NEGOTIABLE, DAILY, MONTHLY)

    val all = spinnerOrder

    fun label(type: String): String = when (type) {
        DAILY -> "Kunlik"
        MONTHLY -> "Oylik"
        else -> "Kelishilgan holda"
    }
}
