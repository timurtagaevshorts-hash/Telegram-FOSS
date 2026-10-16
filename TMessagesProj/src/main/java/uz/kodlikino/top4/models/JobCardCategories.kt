package uz.kodlikino.top4.models

/**
 * Firestore `classified_ads` kolleksiyasi ish kartalari (job board) bilan ham bo'lishadi.
 * Elonlar feed'i ularni chiqarib tashlashi uchun kategoriya tekshiruvi kerak;
 * Ish kartasi ekranlari esa listingType <-> category o'tishini shu yer oladi.
 */
object JobCardCategories {
    const val HIRING = "job_board_hiring"
    const val SEEKING = "job_board_seeking"

    fun fromListingType(listingType: String): String = when (listingType) {
        JobCardListingTypes.SEEKING_WORK -> SEEKING
        else -> HIRING
    }

    fun toListingType(category: String): String = when (category) {
        SEEKING -> JobCardListingTypes.SEEKING_WORK
        else -> JobCardListingTypes.HIRING
    }

    fun isJobBoardCategory(category: String): Boolean =
        category == HIRING || category == SEEKING
}
