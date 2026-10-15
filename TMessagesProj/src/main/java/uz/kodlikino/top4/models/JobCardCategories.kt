package uz.kodlikino.top4.models

/**
 * Firestore `classified_ads` kolleksiyasi ish kartalari (job board) bilan ham bo'lishadi.
 * Elonlar feed'i ularni chiqarib tashlashi uchun kategoriya tekshiruvi kerak.
 * KinoTOP4'dagi to'liq JobCard tizimi YuzGram'ga hozircha ko'chirilmagan —
 * feed uchun faqat shu ajratish funksiyasi yetarli.
 */
object JobCardCategories {
    const val HIRING = "job_board_hiring"
    const val SEEKING = "job_board_seeking"

    fun isJobBoardCategory(category: String): Boolean =
        category == HIRING || category == SEEKING
}
