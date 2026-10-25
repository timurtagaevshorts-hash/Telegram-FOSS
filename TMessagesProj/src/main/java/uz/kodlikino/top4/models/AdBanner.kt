package uz.kodlikino.top4.models

/**
 * Admin paneldan boshqariladigan reklama banneri (`ad_banners` kolleksiyasi).
 *
 * [showInChats]/[showInFunctions] — eski KinoTOP4 hujjatlarida bu maydonlar
 * yo'q, shuning uchun standalone `isActive` bo'lsa ham YuzGram'da ko'rinmaydi
 * (default false) — faqat admin panel orqali aniq belgilangan banner chiqadi.
 */
data class AdBanner(
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val imageUrl: String = "",
    val linkUrl: String = "",
    val theme: String = "",
    val showInChats: Boolean = false,
    val showInFunctions: Boolean = false,
    val isActive: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)
