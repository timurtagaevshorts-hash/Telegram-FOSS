package uz.kodlikino.top4.models

import androidx.annotation.DrawableRes
import org.telegram.messenger.R

object ClassifiedAdCategories {
    const val PHONE = "phone"
    const val CAR = "car"
    const val TV = "tv"
    const val HOME_APPLIANCE = "home_appliance"
    const val OTHER = "other"

    data class CategoryOption(val id: String, val label: String, @DrawableRes val icon: Int)

    val all: List<CategoryOption> = listOf(
        CategoryOption(PHONE, "Telefon", R.drawable.ic_yuz_phone),
        CategoryOption(CAR, "Moshina", R.drawable.ic_yuz_car),
        CategoryOption(TV, "Televizor", R.drawable.ic_yuz_tv),
        CategoryOption(HOME_APPLIANCE, "Uy jihozlari", R.drawable.ic_yuz_home),
        CategoryOption(OTHER, "Boshqa", R.drawable.ic_yuz_box)
    )

    fun label(categoryId: String): String =
        all.firstOrNull { it.id == categoryId }?.label ?: "Boshqa"

    @DrawableRes
    fun icon(categoryId: String): Int =
        all.firstOrNull { it.id == categoryId }?.icon ?: R.drawable.ic_yuz_box
}
