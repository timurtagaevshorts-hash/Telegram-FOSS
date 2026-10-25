package uz.kodlikino.top4.utils

import com.android.billingclient.api.Purchase

/**
 * Bitta Play `Purchase`ni premium grant uchun tekshirish — [YuzPlayBilling]
 * (UI oqimi) va [YuzPremiumSync] (fon sinxroni) bir xil qoidalarni ishlatadi:
 * PURCHASED + Google imzosi + ma'lum mahsulot + akkaunt bog'lanishi.
 */
sealed class YuzPurchaseCheck {
    data class Valid(val planId: String, val purchase: Purchase) : YuzPurchaseCheck()
    object Pending : YuzPurchaseCheck()
    object NotPurchased : YuzPurchaseCheck()
    data class Rejected(val reason: String) : YuzPurchaseCheck()
}

object YuzPurchaseValidator {

    fun check(purchase: Purchase, expectedUid: String): YuzPurchaseCheck {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            return YuzPurchaseCheck.Pending
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            return YuzPurchaseCheck.NotPurchased
        }
        if (!YuzBillingSecurity.isSignatureValid(purchase)) {
            return YuzPurchaseCheck.Rejected("Xarid imzosi tasdiqlanmadi")
        }
        val productId = purchase.products.firstOrNull()
            ?: return YuzPurchaseCheck.NotPurchased
        val planId = YuzPremiumPlans.planIdFromPlayProductId(productId)
            ?: return YuzPurchaseCheck.Rejected("Noma'lum mahsulot: $productId")
        val boundUid = purchase.accountIdentifiers?.obfuscatedAccountId
        if (!boundUid.isNullOrBlank() && expectedUid.isNotBlank() && boundUid != expectedUid) {
            return YuzPurchaseCheck.Rejected("Xarid boshqa akkauntga bog'langan")
        }
        return YuzPurchaseCheck.Valid(planId, purchase)
    }

    /** Bir nechta xarid bo'lsa eng yuqori tarif qaytadi (full > kino). */
    fun bestPlan(plans: Collection<String>): String? = when {
        plans.contains(YuzPremiumPlans.ID_FULL) -> YuzPremiumPlans.ID_FULL
        plans.contains(YuzPremiumPlans.ID_KINO) -> YuzPremiumPlans.ID_KINO
        else -> null
    }
}
