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

/**
 * Google Play Billing — KinoTOP4'dagi `PlayBillingManager`ning to'liq
 * ko'chirmasi (INAPP mahsulotlar: premium_kino / full_premium).
 *
 * Farqlar (compileSdk 33 chegarasi):
 *  - billing kutubxonasi 5.2.1 (donor 8.0.0 — SDK 34 talab qiladi); 5.2.1'da
 *    `queryProductDetailsAsync` javobi `ProductDetailsResult` orqali keladi;
 *  - `enableAutoServiceReconnection()` ataylab ishlatilmadi — qo'lda
 *    eksponensial qayta ulanish ([scheduleReconnect]) donor'da ham bor,
 *    version riskiga tushmaslik uchun faqat shu yo'l qoldirildi.
 *
 * DIQQAT: to'lov faqat Google Play'ga yuklangan (signed, internal test)
 * build'larda to'liq ishlaydi. Sideload CI debug build'ida Play xizmati
 * imzolashni rad etadi — tugma "Mahsulot topilmadi" xabisini ko'rsatadi.
 */
class YuzPlayBilling(
    private val activity: Activity,
    private val listener: Listener
) : PurchasesUpdatedListener {

    interface Listener {
        fun onBillingReady(available: Boolean)
        fun onProductDetails(productId: String, formattedPrice: String)
        fun onProductUnavailable(productId: String)
        fun onPurchaseCompleted(planId: String, purchase: Purchase)
        fun onPurchaseFailed(message: String)
        fun onExistingPurchaseRestored(planId: String)
    }

    private var billingClient: BillingClient? = null
    private val productDetailsMap = HashMap<String, ProductDetails>()
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var destroyed = false

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
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
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
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = client.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            listener.onPurchaseFailed(result.debugMessage ?: "To'lov oynasi ochilmadi")
        }
    }

    fun restorePurchases() {
        queryExistingPurchases(restoredOnly = true)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handlePurchase(it, restoredOnly = false) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                listener.onPurchaseFailed("To'lov bekor qilindi")
            }
            else -> {
                listener.onPurchaseFailed(result.debugMessage ?: "To'lov xatosi")
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
        client.queryProductDetailsAsync(params) { result, detailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            productDetailsMap.clear()
            val detailsList = detailsResult.productDetailsList ?: mutableListOf()
            detailsList.forEach { details ->
                productDetailsMap[details.productId] = details
                val price = details.oneTimePurchaseOfferDetails?.formattedPrice ?: return@forEach
                listener.onProductDetails(details.productId, price)
            }
            YuzPremiumPlans.allPlayProductIds()
                .filter { it !in productDetailsMap.keys }
                .forEach { listener.onProductUnavailable(it) }
        }
    }

    private fun queryExistingPurchases(restoredOnly: Boolean = false) {
        val client = billingClient ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            purchases.forEach { purchase ->
                handlePurchase(purchase, restoredOnly = restoredOnly)
            }
        }
    }

    private fun handlePurchase(purchase: Purchase, restoredOnly: Boolean) {
        if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            // To'lov hali yakunlanmagan (masalan, naqd/keyinroq). qayta so'raladi.
            if (!restoredOnly) listener.onPurchaseFailed("To'lov tasdiqlanishi kutilmoqda")
            return
        }
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val productId = purchase.products.firstOrNull() ?: return
        val planId = YuzPremiumPlans.planIdFromPlayProductId(productId) ?: return

        if (!purchase.isAcknowledged) {
            acknowledgeWithRetry(purchase, planId, restoredOnly, attempt = 0)
        } else {
            notifyPurchase(planId, purchase, restoredOnly)
        }
    }

    /**
     * Xaridni tasdiqlash (acknowledge) — 3 kun ichida tasdiqlanmasa Google
     * pulni qaytaradi. Shuning uchun xatolikda bir necha marta qayta urinamiz.
     */
    private fun acknowledgeWithRetry(
        purchase: Purchase,
        planId: String,
        restoredOnly: Boolean,
        attempt: Int
    ) {
        val client = billingClient ?: return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { ackResult ->
            when {
                ackResult.responseCode == BillingClient.BillingResponseCode.OK ->
                    notifyPurchase(planId, purchase, restoredOnly)
                attempt < MAX_ACK_ATTEMPTS -> reconnectHandler.postDelayed({
                    if (!destroyed) acknowledgeWithRetry(purchase, planId, restoredOnly, attempt + 1)
                }, 2000L * (attempt + 1))
                else -> listener.onPurchaseFailed("Xarid tasdiqlanmadi")
            }
        }
    }

    private fun notifyPurchase(planId: String, purchase: Purchase, restoredOnly: Boolean) {
        if (restoredOnly) {
            listener.onExistingPurchaseRestored(planId)
        } else {
            listener.onPurchaseCompleted(planId, purchase)
        }
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val MAX_ACK_ATTEMPTS = 3
    }
}
