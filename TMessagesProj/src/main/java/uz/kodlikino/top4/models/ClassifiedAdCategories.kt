package uz.kodlikino.top4.models

object ClassifiedAdCategories {
    const val PHONE = "phone"
    const val CAR = "car"
    const val TV = "tv"
    const val HOME_APPLIANCE = "home_appliance"
    const val OTHER = "other"

    data class CategoryOption(val id: String, val label: String, val emoji: String)

    val all: List<CategoryOption> = listOf(
        CategoryOption(PHONE, "Telefon", "📱"),
        CategoryOption(CAR, "Moshina", "🚗"),
        CategoryOption(TV, "Televizor", "📺"),
        CategoryOption(HOME_APPLIANCE, "Uy jihozlari", "🏠"),
        CategoryOption(OTHER, "Boshqa", "📦")
    )

    fun label(categoryId: String): String =
        all.firstOrNull { it.id == categoryId }?.label ?: "Boshqa"

    fun emoji(categoryId: String): String =
        all.firstOrNull { it.id == categoryId }?.emoji ?: "📦"
}
