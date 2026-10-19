package uz.kodlikino.top4.utils

import java.util.Locale

/**
 * YuzGram Premium tariflari — KinoTOP4'dagi `PremiumPlans` + `PremiumMembership`
 * mexanizmining to'liq ko'chirmasi (narxlar donor bilan bir xil:
 * premium_kino 12 000 so'm, full_premium 220 000 so'm).
 *
 * Ijro darajasi (oldingi loyiha bilan bir xil): Google Play Billing orqali
 * sotib olingach holat mahalliy prefs'ga yoziladi ([YuzPremiumManager]).
 * Firestore `users` kolleksiyasi qoidalari bilan berkitilgan, `comment_profiles` da esa
 * premium maydonlarini client o'zgartira olmaydi — shuning uchun mirrors
 * faqat "best-effort" (jimgina rad etiladi), haqiqiy grant Play Purchase
 * tekshiruvida (Google imzosi).
 */
data class YuzPremiumPlan(
    val id: String,
    val title: String,
    val priceSom: Int,
    val playProductId: String,
    val upgradeNote: String?,
    val benefits: List<String>,
    val buttonText: String
) {
    fun formattedPrice(): String =
        String.format(Locale.US, "%,d so'm", priceSom).replace(',', ' ')
}

object YuzPremiumPlans {
    const val EXTRA_PLAN_ID = "premium_plan_id"
    const val VALIDITY_NOTE = "Tarif 1 yil muddatga amal qiladi"

    const val ID_KINO = "premium_kino"
    const val ID_FULL = "full_premium"

    val PREMIUM_KINO = YuzPremiumPlan(
        id = ID_KINO,
        title = "Premium Kino",
        priceSom = 12_000,
        playProductId = "premium_kino",
        upgradeNote = VALIDITY_NOTE,
        benefits = listOf(
            "Premium belgilangan kinolarni cheksiz tomosha",
            "Reels limiti oddiy foydalanuvchi kabi (3 ta/hafta · 50 MB)"
        ),
        buttonText = "Tarifga ulanish"
    )

    val FULL_PREMIUM = YuzPremiumPlan(
        id = ID_FULL,
        title = "To'liq Premium",
        priceSom = 220_000,
        playProductId = "full_premium",
        upgradeNote = "Premium Kino tarifidan o'tish narxi",
        benefits = listOf(
            "Barcha premium kinolar ochiq",
            "Haftasiga 30 tagacha reel joylash",
            "100 MB gacha video hajmi"
        ),
        buttonText = "To'liq Premium olish"
    )

    fun fromId(id: String?): YuzPremiumPlan = when (id) {
        ID_FULL -> FULL_PREMIUM
        ID_KINO -> PREMIUM_KINO
        else -> PREMIUM_KINO
    }

    fun allPlayProductIds(): List<String> =
        listOf(PREMIUM_KINO.playProductId, FULL_PREMIUM.playProductId)

    fun playProductIdFromPlanId(planId: String): String? = when (planId) {
        PREMIUM_KINO.id -> PREMIUM_KINO.playProductId
        FULL_PREMIUM.id -> FULL_PREMIUM.playProductId
        else -> null
    }

    fun planIdFromPlayProductId(playProductId: String): String? = when (playProductId) {
        PREMIUM_KINO.playProductId -> PREMIUM_KINO.id
        FULL_PREMIUM.playProductId -> FULL_PREMIUM.id
        else -> null
    }

    /** Xom saqlangan qiymatni faqat tanilgan tariflarga normalizatsiya qiladi. */
    fun normalizePlanType(raw: String?): String {
        val plan = raw?.trim().orEmpty()
        return when (plan) {
            ID_KINO, ID_FULL -> plan
            else -> ""
        }
    }

    fun isPremiumPlan(planType: String): Boolean = normalizePlanType(planType).isNotBlank()

    fun isFullPremiumPlan(planType: String): Boolean =
        normalizePlanType(planType) == ID_FULL
}
