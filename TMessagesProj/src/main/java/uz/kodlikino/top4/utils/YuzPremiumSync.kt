package uz.kodlikino.top4.utils

import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.UserConfig

/**
 * Fon rejimida Play inventarini tekshirish — premium faqat haqiqiy xaridga
 * tayangan holda qolishi uchun:
 *  - qurilmada xarid bor-u mahalliy yozuv yo'q bo'lsa (app data tozalangan,
 *    yangi qurilma) — premium avtomatik tiklanadi;
 *  - refund/void yoki boshqa Play akkaunt aniqlansa — mahalliy premium
 *    o'chiriladi;
 *  - tarmoq/billing xatosida hech narsa o'chirilmaydi (oflaynda premium
 *    yo'qolmaydi).
 *
 * Tekshiruv 6 soatdan tez-tez takrorlanmaydi; premium ekrani ochilganda
 * [YuzPlayBilling] darhol majburiy tekshiruv qiladi.
 */
object YuzPremiumSync {

    private const val THROTTLE_MS = 6 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 25_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    fun syncInBackground(account: Int = UserConfig.selectedAccount) {
        val ctx = ApplicationLoader.applicationContext ?: return
        val manager = YuzPremiumManager.from(ctx)
        if (System.currentTimeMillis() - manager.lastVerifiedAt(account) < THROTTLE_MS) return
        if (running) return
        val expectedUid = YuzGramAdsAuth.expectedUid(account)
        if (expectedUid.isBlank()) return
        running = true

        val client = try {
            BillingClient.newBuilder(ctx)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
                )
                .build()
        } catch (e: Exception) {
            FileLog.e(e)
            running = false
            return
        }

        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            running = false
            try {
                client.endConnection()
            } catch (_: Exception) {
            }
        }
        handler.postDelayed({ finish() }, TIMEOUT_MS)

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    finish()
                    return
                }
                val params = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
                client.queryPurchasesAsync(params) { queryResult, purchases ->
                    if (queryResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        try {
                            apply(account, expectedUid, manager, client, purchases)
                        } catch (e: Exception) {
                            FileLog.e(e)
                        }
                    }
                    finish()
                }
            }

            override fun onBillingServiceDisconnected() {
                finish()
            }
        })
    }

    private fun apply(
        account: Int,
        expectedUid: String,
        manager: YuzPremiumManager,
        client: BillingClient,
        purchases: List<Purchase>
    ) {
        val valid = ArrayList<YuzPurchaseCheck.Valid>()
        var rejected = false
        var pending = false
        purchases.forEach { purchase ->
            when (val check = YuzPurchaseValidator.check(purchase, expectedUid)) {
                is YuzPurchaseCheck.Valid -> valid.add(check)
                YuzPurchaseCheck.Pending -> pending = true
                is YuzPurchaseCheck.Rejected -> {
                    rejected = true
                    FileLog.d("YuzPremiumSync: xarid rad etildi — ${check.reason}")
                }
                YuzPurchaseCheck.NotPurchased -> Unit
            }
        }

        val best = YuzPurchaseValidator.bestPlan(valid.map { it.planId })
        if (best != null) {
            val purchase = valid.first { it.planId == best }.purchase
            manager.applyPurchase(
                account = account,
                planType = best,
                productId = purchase.products.firstOrNull().orEmpty(),
                purchaseToken = purchase.purchaseToken,
                purchaseTimeMs = purchase.purchaseTime
            )
            manager.markVerified(account)
            if (!purchase.isAcknowledged) {
                // Grant ekranda ham tasdiqlanadi; bu yerda — 3 kunlik
                // avtomatik refund oynasini yopish uchun qo'shimcha urinish.
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                client.acknowledgePurchase(params) { }
            }
            return
        }

        // Aniq bo'sh inventar — refund/void yoki boshqa Play akkaunt.
        // Imzo rad etilgan yoki pending bo'lsa keshni saqlaymiz.
        if (!rejected && !pending) {
            manager.markVerified(account)
            if (manager.isPremium(account)) {
                manager.clearPlan(account)
            }
        }
    }
}
