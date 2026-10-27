package uz.kodlikino.top4.utils

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.telegram.messenger.FileLog

/**
 * Google Play Billing — KinoTOP4'dagi `PlayBillingManager`ning to'liq
 * ko'chirmasi (INAPP mahsulotlar: premium_kino / full_premium).
 *
 * Grant qoidasi qat'iy: faqat Google `PURCHASED` deb qaytargan va imzosi
 * [YuzBillingSecurity] bilan tasdiqlangan xarid premium beradi. PENDING
 * to'lov hech qachon grant qilmaydi; acknowledge esa fonda qayta uriniladi.
 *
 * Har ulanishda `queryPurchasesAsync` butun inventarni qayta o'qiydi:
 *  - bir nechta xarid bo'lsa eng yuqori tarif tanlanadi (full > kino) —
 *    upgrade qilgan foydalanuvchi pastroq tarifga tushib qolmaydi;
 *  - inventar aniq bo'sh bo'lsa [Listener.onSyncFinished] `null` qaytaradi —
 *    refund/void aniqlanganda mahalliy premium o'chiriladi.
 *
 * `expectedUid` (`tg_<telegramId>`) xaridga obfuscatedAccountId sifatida
 * bog'lanadi — bir Play hisobidagi xarid boshqa Telegram akkauntga o'tmaydi.
 *
 * DIQQAT: to'lov faqat Google Play'ga yuklangan (signed, internal/open test)
 * build'larda ishlaydi. Sideload CI debug build'ida Play xizmati imzolashni
 * rad etadi — tugma "Mahsulot topilmadi" xabarini ko'rsatadi.
 */
class YuzPlayBilling(
    private val activity: Activity,
    private val account: Int,
    private val listener: Listener
) : PurchasesUpdatedListener {

    interface Listener {
        fun onBillingReady(available: Boolean)
        fun onProductDetails(productId: String, formattedPrice: String)
        fun onProductUnavailable(productId: String)
        fun onPurchaseCompleted(planId: String, purchase: Purchase)
        fun onPurchaseFailed(message: String)
        fun onExistingPurchaseRestored(planId: String, purchase: Purchase)

        /** Aniq natija: null = Play hisobida faol xarid yo'q (refund/void). */
        fun onSyncFinished(activePlanId: String?)
    }

    private var billingClient: BillingClient? = null
    private val productDetailsMap = HashMap<String, ProductDetails>()
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var destroyed = false
    private var restoreRequested = false

    /** To'lov oynasi yopilgandan keyingi so'rov — xarid shu sessiyada tugagan. */
    private var purchaseFlowActive = false

    private val expectedUid: String
        get() = YuzGramAdsAuth.expectedUid(account)

    fun start() {
        destroyed = false
        if (billingClient?.isReady == true) {
            listener.onBillingReady(true)
            queryAllProducts()
            queryExistingPurchases()
            return
        }
        billingClient = BillingClient.newBuilder(activity)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()
        connect()
    }

    private fun connect() {
        val client = billingClient ?: return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                listener.onBillingReady(ok)
                if (ok) {
                    reconnectAttempts = 0
                    queryAllProducts()
                    queryExistingPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                listener.onBillingReady(false)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (destroyed) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        reconnectAttempts++
        val delayMs = (1000L * (1 shl (reconnectAttempts - 1))).coerceAtMost(30_000L)
        reconnectHandler.postDelayed({
            if (!destroyed && billingClient?.isReady != true) connect()
        }, delayMs)
    }

    fun destroy() {
        destroyed = true
        reconnectHandler.removeCallbacksAndMessages(null)
        billingClient?.endConnection()
        billingClient = null
        productDetailsMap.clear()
    }

    fun getFormattedPrice(productId: String): String? =
        productDetailsMap[productId]?.oneTimePurchaseOfferDetails?.formattedPrice

    fun launchPurchase(productId: String) {
        val client = billingClient
        if (client == null || !client.isReady) {
            listener.onPurchaseFailed("Google Play Billing ulanmagan")
            return
        }
        val details = productDetailsMap[productId]
        if (details == null) {
            listener.onPurchaseFailed("Mahsulot Play Marketda topilmadi")
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val builder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
        val uid = expectedUid
        if (uid.isNotBlank()) {
            builder.setObfuscatedAccountId(uid)
        }
        val result = client.launchBillingFlow(activity, builder.build())
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            listener.onPurchaseFailed(
                result.debugMessage.takeIf { it.isNotBlank() } ?: "To'lov oynasi ochilmadi"
            )
        }
    }

    fun restorePurchases() {
        restoreRequested = true
        queryExistingPurchases()
    }

    /** Ekranga qaytganda inventarni jimgina yangilash. */
    fun refreshPurchases() {
        restoreRequested = false
        // purchaseFlowActive'ni bu yerda tozalamaymiz: to'lov oynasi yopilishi
        // bilan onResume poyga qilishi mumkin — bayroqni birinchi so'rov o'zi yeydi.
        queryExistingPurchases()
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                // Bu ro'yxat to'liq inventar emas — qayta so'raymiz, shunda
                // upgrade qilgan foydalanuvchi pastroq tarifga tushmaydi.
                // So'rov yiqilsa, callback ro'yxati zaxira bo'lib qoladi.
                purchaseFlowActive = true
                queryExistingPurchases(purchases)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                listener.onPurchaseFailed("To'lov bekor qilindi")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                listener.onPurchaseFailed("Xarid allaqachon mavjud — tiklanmoqda")
                restoreRequested = true
                queryExistingPurchases()
            }
            else -> {
                listener.onPurchaseFailed(
                    result.debugMessage.takeIf { it.isNotBlank() } ?: "To'lov xatosi"
                )
            }
        }
    }

    private fun queryAllProducts() {
        val client = billingClient ?: return
        val productList = YuzPremiumPlans.allPlayProductIds().map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
        // billing 8.0.0: callback QueryProductDetailsResult beradi.
        client.queryProductDetailsAsync(params) { result, detailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            productDetailsMap.clear()
            detailsResult.productDetailsList.forEach { details ->
                productDetailsMap[details.productId] = details
                val price = details.oneTimePurchaseOfferDetails?.formattedPrice ?: return@forEach
                listener.onProductDetails(details.productId, price)
            }
            YuzPremiumPlans.allPlayProductIds()
                .filter { it !in productDetailsMap.keys }
                .forEach { listener.onProductUnavailable(it) }
        }
    }

    private fun queryExistingPurchases(fallback: List<Purchase>? = null) {
        val client = billingClient ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                purchaseFlowActive = false
                // Play xaridni tasdiqladi, lekin to'liq inventar so'rovi yiqildi —
                // pul to'lagan foydalanuvchi premium'siz qolmasin.
                if (!fallback.isNullOrEmpty()) {
                    val restoredOnly = restoreRequested
                    restoreRequested = false
                    processQuery(fallback, restoredOnly, allowEmptyClear = false, fresh = false)
                    return@queryPurchasesAsync
                }
                if (restoreRequested) {
                    restoreRequested = false
                    listener.onPurchaseFailed("Xaridlar tekshirilmadi. Internetni sinab ko'ring.")
                }
                return@queryPurchasesAsync
            }
            val restoredOnly = restoreRequested
            val fresh = purchaseFlowActive
            restoreRequested = false
            purchaseFlowActive = false
            processQuery(purchases, restoredOnly, allowEmptyClear = true, fresh = fresh)
        }
    }

    private fun processQuery(
        purchases: List<Purchase>,
        restoredOnly: Boolean,
        allowEmptyClear: Boolean,
        fresh: Boolean
    ) {
        val valid = ArrayList<YuzPurchaseCheck.Valid>()
        var pending = false
        var rejected = false
        purchases.forEach { purchase ->
            when (val check = YuzPurchaseValidator.check(purchase, expectedUid)) {
                is YuzPurchaseCheck.Valid -> valid.add(check)
                YuzPurchaseCheck.Pending -> pending = true
                is YuzPurchaseCheck.Rejected -> {
                    rejected = true
                    FileLog.d("YuzPlayBilling: xarid rad etildi — ${check.reason}")
                }
                YuzPurchaseCheck.NotPurchased -> Unit
            }
        }

        if (valid.isEmpty()) {
            if (pending) {
                listener.onPurchaseFailed("To'lov tasdiqlanishi kutilmoqda...")
            }
            if (rejected) {
                // Imzo/akkaunt tekshiruvi yiqildi — inventar "bo'sh" degan
                // xulosa chiqarmaymiz, aks holda haqiqiy xarid o'chib ketadi.
                if (restoredOnly) listener.onPurchaseFailed("Xarid tekshiruvdan o'tmadi")
                return
            }
            // Faqat aniq bo'sh inventar — refund/void yoki boshqa akkaunt.
            if (allowEmptyClear) listener.onSyncFinished(null)
            return
        }
        processValid(valid, restoredOnly, fresh)
    }

    private fun processValid(
        valid: List<YuzPurchaseCheck.Valid>,
        restoredOnly: Boolean,
        fresh: Boolean
    ) {
        // To'lov Google tomonidan tasdiqlangan (PURCHASED + imzo tekshirildi) —
        // grant darhol beriladi. Acknowledge buxgalteriya qadami: u yiqilsa ham
        // xaridor premium'ni yo'qotmaydi, keyingi urinishlar davom etadi.
        finishSync(valid, restoredOnly, fresh)
        valid.filter { !it.purchase.isAcknowledged }.forEach { item ->
            acknowledge(item.purchase, attempt = 0) { ok ->
                if (!ok) FileLog.d("YuzPlayBilling: acknowledge bajarilmadi")
            }
        }
    }

    private fun acknowledge(purchase: Purchase, attempt: Int, onDone: (Boolean) -> Unit) {
        val client = billingClient
        if (client == null) {
            onDone(false)
            return
        }
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { ackResult ->
            when {
                ackResult.responseCode == BillingClient.BillingResponseCode.OK -> onDone(true)
                attempt < MAX_ACK_ATTEMPTS -> reconnectHandler.postDelayed({
                    if (!destroyed) acknowledge(purchase, attempt + 1, onDone) else onDone(false)
                }, 2000L * (attempt + 1))
                else -> onDone(false)
            }
        }
    }

    private fun finishSync(
        valid: List<YuzPurchaseCheck.Valid>,
        restoredOnly: Boolean,
        fresh: Boolean
    ) {
        val bestPlan = YuzPurchaseValidator.bestPlan(valid.map { it.planId }) ?: return
        val best = valid.first { it.planId == bestPlan }
        // "Faollashtirildi" faqat shu sessiyada Play oynasi orqali tugagan
        // xaridga tegishli; inventar tekshiruvi esa mavjud holatni ko'rsatadi.
        if (fresh && !restoredOnly) {
            listener.onPurchaseCompleted(bestPlan, best.purchase)
        } else {
            listener.onExistingPurchaseRestored(bestPlan, best.purchase)
        }
        listener.onSyncFinished(bestPlan)
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val MAX_ACK_ATTEMPTS = 3
    }
}
