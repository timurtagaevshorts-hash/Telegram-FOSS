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
 * Google tomonidan imzolanib tasdiqlangach holat shu qurilmada yoza­miladi.
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

    fun setPlanType(account: Int, planType: String) {
        val uid = YuzGramAdsAuth.expectedUid(account)
        if (uid.isBlank()) return
        prefs.edit()
            .putString(planKey(uid), YuzPremiumPlans.normalizePlanType(planType))
            .apply()
        mirrorBestEffort(uid, YuzPremiumPlans.normalizePlanType(planType))
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

    /** Reels haftalik limitining premium darajasi (Faza C: 30 ta/hafta). */
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

        @Volatile
        private var instance: YuzPremiumManager? = null

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
