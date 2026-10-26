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
data class PlanBenefit(
    val emoji: String,
    val title: String,
    val subtitle: String
)

data class YuzPremiumPlan(
    val id: String,
    val title: String,
    val priceSom: Int,
    val playProductId: String,
    val tag: String,
    val subtitle: String,
    val benefits: List<PlanBenefit>,
    val buttonText: String
) {
    fun formattedPrice(): String =
        String.format(Locale.US, "%,d so'm", priceSom).replace(',', ' ')
}

object YuzPremiumPlans {
    const val EXTRA_PLAN_ID = "premium_plan_id"

    const val ID_KINO = "premium_kino"
    const val ID_FULL = "full_premium"

    val PREMIUM_KINO = YuzPremiumPlan(
        id = ID_KINO,
        title = "Premium Kino",
        priceSom = 12_000,
        playProductId = "premium_kino",
        tag = "KINO",
        subtitle = "Faqat premium kinolar uchun",
        benefits = listOf(
            PlanBenefit(
                emoji = "🎬",
                title = "Premium kinolar to'liq ochiladi",
                subtitle = "Maxsus tanlangan filmlar kutubxonasi"
            ),
            PlanBenefit(
                emoji = "🍿",
                title = "Kinolar cheksiz",
                subtitle = "Kunlik chegara yo'q — xohlagancha tomosha"
            ),
            PlanBenefit(
                emoji = "📱",
                title = "1 yil — bitta to'lov",
                subtitle = "Obuna avtomatik uzaytirilmaydi"
            ),
            PlanBenefit(
                emoji = "ℹ️",
                title = "Reels va AI — To'liq Premium'da",
                subtitle = "Bu tarif faqat kinolar uchun"
            )
        ),
        buttonText = "Tarifga ulanish"
    )

    val FULL_PREMIUM = YuzPremiumPlan(
        id = ID_FULL,
        title = "To'liq Premium",
        priceSom = 220_000,
        playProductId = "full_premium",
        tag = "TOP TANLOV",
        subtitle = "Hammasi bitta tarifda",
        benefits = listOf(
            PlanBenefit(
                emoji = "👑",
                title = "Maxsus premium nishon",
                subtitle = "Profilingizda oltin yulduz belgisi"
            ),
            PlanBenefit(
                emoji = "🎬",
                title = "Reels videolarni xohlagancha yuklash",
                subtitle = "Premium uchun alohida, yuqori kunlik limit"
            ),
            PlanBenefit(
                emoji = "⚡",
                title = "Kuchaytirilgan xizmatlar",
                subtitle = "Tezkor yuklash va kattaroq video hajmi"
            ),
            PlanBenefit(
                emoji = "🤖",
                title = "AI yordamchi to'liq quvvatda",
                subtitle = "Ovozli buyruqlar va aqlli javoblar"
            ),
            PlanBenefit(
                emoji = "🍿",
                title = "Kinolar cheksiz",
                subtitle = "Barcha premium filmlar ochiq"
            )
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
