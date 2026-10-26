package uz.kodlikino.top4.utils

import android.content.Context
import android.util.Log

/**
 * YuzGram Premium holati — KinoTOP4'dagi `PremiumManager`ning fork moslamasi.
 *
 * Saqlash: mahalliy `SharedPreferences` ("premium_prefs"), kalit tarif
 * `premium_plan_<uid>` — bu yerda uid = `tg_<clientUserId>`
 * ([YuzGramAdsAuth.expectedUid]). Donor bilan bir xil sabab: Firestore
 * qoidalarida `users` hujjatlari berkitilgan va `comment_profiles` premium
 * maydonlarini client'ga o'zgartirish taqiqlangan — Play Billing'da xarid
 * Google tomonidan imzolanib tasdiqlangach holat shu qurilmada yoziladi.
 *
 * Faqat [applyPurchase] (tekshirilgan Play `Purchase` bilan) premium beradi;
 * [clearPlan] refund/void yoki boshqa Play akkaunt aniqlanganda chaqiriladi.
 * Xarid tokeni/sanasi ham saqlanadi — kelajakda server tekshiruvi va
 * qo'llab-quvvatlash uchun iz qoladi.
 *
 * [mirrorBestEffort] donor'dagi `refreshUserPremium` urinishining o'rniga
 * qo'yilgan: qoida rad etsa jimgina o'tamiz (iltimos xatolik UI'ga chiqmaydi).
 */
class YuzPremiumManager private constructor(private val context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun planType(account: Int): String {
        val uid = YuzGramAdsAuth.expectedUid(account)
        if (uid.isBlank()) return ""
        return YuzPremiumPlans.normalizePlanType(prefs.getString(planKey(uid), ""))
    }

    /** Faqat tasdiqlangan xarid natijasidan chaqiriladi. */
    fun applyPurchase(
        account: Int,
        planType: String,
        productId: String,
        purchaseToken: String,
        purchaseTimeMs: Long
    ) {
        val uid = YuzGramAdsAuth.expectedUid(account)
        if (uid.isBlank()) return
        val plan = YuzPremiumPlans.normalizePlanType(planType)
        if (plan.isBlank()) return
        prefs.edit()
            .putString(planKey(uid), plan)
            .putString(productKey(uid), productId)
            .putString(tokenKey(uid), purchaseToken)
            .putLong(purchaseTimeKey(uid), purchaseTimeMs)
            .putLong(verifiedKey(uid), System.currentTimeMillis())
            .apply()
        mirrorBestEffort(uid, plan)
    }

    /** Refund/void yoki Play hisobida faol xarid yo'qligi aniqlandi. */
    fun clearPlan(account: Int) {
        val uid = YuzGramAdsAuth.expectedUid(account)
        if (uid.isBlank()) return
        prefs.edit()
            .remove(planKey(uid))
            .remove(productKey(uid))
            .remove(tokenKey(uid))
            .remove(purchaseTimeKey(uid))
            .apply()
        mirrorBestEffort(uid, "")
    }

    fun lastVerifiedAt(account: Int): Long {
        val uid = YuzGramAdsAuth.expectedUid(account)
        if (uid.isBlank()) return 0L
        return prefs.getLong(verifiedKey(uid), 0L)
    }

    fun markVerified(account: Int) {
        val uid = YuzGramAdsAuth.expectedUid(account)
        if (uid.isBlank()) return
        prefs.edit().putLong(verifiedKey(uid), System.currentTimeMillis()).apply()
    }

    fun purchaseToken(account: Int): String {
        val uid = YuzGramAdsAuth.expectedUid(account)
        if (uid.isBlank()) return ""
        return prefs.getString(tokenKey(uid), "").orEmpty()
    }

    /** Har qanday faol tarif (Premium Kino yoki To'liq Premium). */
    fun isPremium(account: Int): Boolean =
        YuzPremiumPlans.isPremiumPlan(planType(account))

    fun hasPremiumKino(account: Int): Boolean =
        planType(account) == YuzPremiumPlans.ID_KINO

    fun isFullPremium(account: Int): Boolean =
        YuzPremiumPlans.isFullPremiumPlan(planType(account))

    /** Premium kinolarni ko'rish — Premium Kino yoki To'liq Premium. */
    fun hasPremiumMovies(account: Int): Boolean =
        hasPremiumKino(account) || isFullPremium(account)

    /** Reels uchun premium darajasi — admin panel belgilagan premium kunlik limit. */
    fun hasReelsBoost(account: Int): Boolean = isFullPremium(account)

    /**
     * Donor'dagi `syncCommentProfilePremium`/`refreshUserPremium` muqobili —
     * ko'rinadigan premium belgisi Firestore'da ham paydo bo'lishi uchun
     * urinish. Qoida rad etsa ([Log]) bilan yozib, UI'ga chiqarmaydi.
     */
    private fun mirrorBestEffort(uid: String, plan: String) {
        if (uid.isBlank()) return
        try {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            if (auth.currentUser?.uid != uid) return // sessiya yo'q — keyingi saqlashda urinadi
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val active = plan.isNotBlank()
            val payload = hashMapOf<String, Any>(
                "isPremium" to active,
                "premiumPlanType" to plan
            )
            db.collection("comment_profiles").document(uid)
                .set(payload, com.google.firebase.firestore.SetOptions.merge())
                .addOnFailureListener { e ->
                    // Qoida premium maydonlarni client'ga taqiqlaydi — bu
                    // urinishning "normal" yakuni: jimgina o'tamiz.
                    Log.d(TAG, "premium mirror rad: ${e.message}")
                }
        } catch (e: Exception) {
            Log.d(TAG, "premium mirror (kutilgan rad): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "YuzPremiumManager"
        private const val PREFS = "premium_prefs"

        private fun planKey(uid: String): String = "premium_plan_$uid"
        private fun productKey(uid: String): String = "premium_product_$uid"
        private fun tokenKey(uid: String): String = "premium_token_$uid"
        private fun purchaseTimeKey(uid: String): String = "premium_purchase_time_$uid"
        private fun verifiedKey(uid: String): String = "premium_verified_at_$uid"

        @Volatile
        private var instance: YuzPremiumManager? = null

        @JvmStatic
        fun from(context: Context): YuzPremiumManager {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val created = YuzPremiumManager(context.applicationContext)
                instance = created
                return created
            }
        }
    }
}
