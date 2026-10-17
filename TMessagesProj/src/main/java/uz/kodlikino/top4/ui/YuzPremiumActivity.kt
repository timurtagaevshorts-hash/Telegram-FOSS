package uz.kodlikino.top4.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.android.billingclient.api.Purchase
import com.google.android.material.button.MaterialButton
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzPlayBilling
import uz.kodlikino.top4.utils.YuzPremiumManager
import uz.kodlikino.top4.utils.YuzPremiumPlan
import uz.kodlikino.top4.utils.YuzPremiumPlans

/**
 * YuzGram Premium ekrani — KinoTOP4'dagi `PremiumActivity` mexanizmining
 * to'liq ko'chirmasi, fork'ning [BaseFragment] + Theme.YuzGramAds (qora-neon)
 * uslubiga moslashtirilgan:
 *  - ikki tarif kartasi (Premium Kino 12 000 / To'liq Premium 220 000 so'm);
 *  - Google Play Billing (INAPP) orqali xarid + acknowledge + restore;
 *  - grant mahalliy prefs'ga ([YuzPremiumManager]) yoziladi — donor bilan
 *    bir xil (Firestore `users/*` qoida bilan berkitilgan, `comment_profiles`
 *    premium maydonlari client'ga taqiqlangan — mirror best-effort);
 *  - sotib olingach muvaffaqiyat overlay'i ko'rsatiladi.
 *
 * Sozlamalardagi "Telegram Premium" qatori shu ekranni ochadi
 * (qarang ProfileActivity premiumRow).
 */
class YuzPremiumActivity : BaseFragment() {

    private var themedContext: Context? = null
    private var scroll: View? = null
    private var overlay: View? = null
    private var tvApprovedTitle: TextView? = null
    private var tvApprovedSubtitle: TextView? = null
    private var btnPayKino: MaterialButton? = null
    private var btnPayFull: MaterialButton? = null
    private var tvKinoPlayPrice: TextView? = null
    private var tvFullPlayPrice: TextView? = null

    private var billing: YuzPlayBilling? = null
    private var billingReady = false
    private var handledFinalStatus = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_yuz_premium, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE

        scroll = root.findViewById(R.id.premiumScroll)
        overlay = root.findViewById(R.id.premiumApprovedOverlay)
        tvApprovedTitle = root.findViewById(R.id.tvApprovedTitle)
        tvApprovedSubtitle = root.findViewById(R.id.tvApprovedSubtitle)
        tvKinoPlayPrice = root.findViewById(R.id.tvKinoPlayPrice)
        tvFullPlayPrice = root.findViewById(R.id.tvFullPlayPrice)
        btnPayKino = root.findViewById(R.id.btnPayKino)
        btnPayFull = root.findViewById(R.id.btnPayFull)

        // Status bari ostiga tushmaslik uchun tepa bo'shliq (boshqa YuzGram
        // ekranlari bilan bir xil usul).
        root.findViewById<View>(R.id.premiumTopBar)?.let { bar ->
            bar.setPadding(
                bar.paddingLeft,
                AndroidUtilities.statusBarHeight,
                bar.paddingRight,
                bar.paddingBottom
            )
        }
        root.findViewById<View>(R.id.btnPremiumBack)?.setOnClickListener { removeSelfFromStack() }
        root.findViewById<View>(R.id.btnApprovedClose)?.setOnClickListener { removeSelfFromStack() }
        root.findViewById<View>(R.id.btnRestorePurchases)?.setOnClickListener {
            billing?.restorePurchases()
            toast(getString(R.string.premium_checking_purchases))
        }

        fillPlanTexts()

        // Allab: faol tarifi bor foydalanuvchi darhol holatini ko'radi.
        val plan = YuzPremiumManager.from(themed).planType(currentAccount)
        if (YuzPremiumPlans.isPremiumPlan(plan)) {
            showActivePlan(plan)
        }
        return root
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        val themed = themedContext ?: return
        if (!YuzPremiumManager.from(themed).isPremium(currentAccount)) {
            ensureBilling()
        }
    }

    /** Ikkala tarif kartasini donor matnlari bilan to'ldiradi va tugmalarni ulaydi. */
    private fun fillPlanTexts() {
        // ContextThemeWrapper.findViewById ishonchli emas — fragment View daraxtini
        // ishlatamiz (createView'da chaqirilganda hali hierarxiyaga ulanmagan).
        val root = fragmentView ?: return
        for (plan in listOf(YuzPremiumPlans.PREMIUM_KINO, YuzPremiumPlans.FULL_PREMIUM)) {
            val isKino = plan.id == YuzPremiumPlans.PREMIUM_KINO.id
            val tvTitle = root.findViewById<TextView>(
                if (isKino) R.id.tvKinoTitle else R.id.tvFullTitle
            )
            val tvPrice = root.findViewById<TextView>(
                if (isKino) R.id.tvKinoPrice else R.id.tvFullPrice
            )
            val tvBenefits = root.findViewById<TextView>(
                if (isKino) R.id.tvKinoBenefits else R.id.tvFullBenefits
            )
            val btn = root.findViewById<MaterialButton>(
                if (isKino) R.id.btnPayKino else R.id.btnPayFull
            )
            tvTitle?.text = (if (isKino) "💎 " else "👑 ") + plan.title
            tvPrice?.text = plan.formattedPrice()
            tvBenefits?.text = plan.benefits.joinToString("\n")
            btn?.text = plan.buttonText
            btn?.setOnClickListener { launchPurchase(plan) }
        }
    }

    private fun ensureBilling() {
        if (billing != null) return
        val activity = parentActivity ?: return
        billing = YuzPlayBilling(activity, billingListener).also { it.start() }
    }

    private val billingListener = object : YuzPlayBilling.Listener {
        override fun onBillingReady(available: Boolean) {
            mainHandler.post {
                if (fragmentView == null) return@post
                billingReady = available
                refreshPriceLabels()
            }
        }

        override fun onProductDetails(productId: String, formattedPrice: String) {
            mainHandler.post {
                if (fragmentView == null) return@post
                refreshPriceLabels()
            }
        }

        override fun onProductUnavailable(productId: String) {
            mainHandler.post {
                if (fragmentView == null) return@post
                refreshPriceLabels()
            }
        }

        override fun onPurchaseCompleted(planId: String, purchase: Purchase) {
            if (handledFinalStatus) return
            handledFinalStatus = true
            val themed = themedContext ?: return
            YuzPremiumManager.from(themed).setPlanType(currentAccount, planId)
            mainHandler.post {
                if (fragmentView == null) return@post
                showApproved(planId)
            }
        }

        override fun onPurchaseFailed(message: String) {
            mainHandler.post {
                if (fragmentView == null) return@post
                refreshPriceLabels()
                toast(message)
            }
        }

        override fun onExistingPurchaseRestored(planId: String) {
            val themed = themedContext ?: return
            val manager = YuzPremiumManager.from(themed)
            // Premium Kino ustuniga To'liq Premium yetar — pastga tushirmaymiz.
            if (manager.isFullPremium(currentAccount) &&
                planId == YuzPremiumPlans.PREMIUM_KINO.id
            ) {
                return
            }
            manager.setPlanType(currentAccount, planId)
            mainHandler.post {
                if (fragmentView == null) return@post
                showApproved(planId)
            }
        }
    }

    private fun refreshPriceLabels() {
        val b = billing ?: return
        for (plan in listOf(YuzPremiumPlans.PREMIUM_KINO, YuzPremiumPlans.FULL_PREMIUM)) {
            val isKino = plan.id == YuzPremiumPlans.PREMIUM_KINO.id
            val label = if (isKino) tvKinoPlayPrice else tvFullPlayPrice
            val btn = if (isKino) btnPayKino else btnPayFull
            val price = b.getFormattedPrice(plan.playProductId)
            label?.visibility = View.VISIBLE
            label?.text = if (price.isNullOrBlank()) {
                getString(R.string.premium_play_unavailable)
            } else {
                getString(R.string.premium_play_price, price)
            }
            btn?.isEnabled = billingReady && !price.isNullOrBlank()
        }
    }

    private fun launchPurchase(plan: YuzPremiumPlan) {
        if (YuzGramAdsAuth.expectedUid(currentAccount).isBlank()) {
            toast(getString(R.string.premium_need_sign_in))
            return
        }
        val b = billing
        if (b == null || !billingReady) {
            toast(getString(R.string.premium_billing_off))
            ensureBilling()
            return
        }
        btnPayKino?.isEnabled = false
        btnPayFull?.isEnabled = false
        b.launchPurchase(plan.playProductId)
        mainHandler.postDelayed({
            if (fragmentView != null && !handledFinalStatus) refreshPriceLabels()
        }, 1500L)
    }

    private fun showApproved(planId: String) {
        val isKinoOnly = planId == YuzPremiumPlans.PREMIUM_KINO.id
        tvApprovedTitle?.text = getString(
            if (isKinoOnly) R.string.premium_approved_kino else R.string.premium_approved_full
        )
        tvApprovedSubtitle?.text = getString(
            if (isKinoOnly) R.string.premium_approved_kino_sub else R.string.premium_approved_full_sub
        )
        overlay?.visibility = View.VISIBLE
        scroll?.visibility = View.GONE
    }

    private fun showActivePlan(plan: String) {
        val isFull = YuzPremiumPlans.isFullPremiumPlan(plan)
        tvApprovedTitle?.text = getString(
            if (isFull) R.string.premium_active_full else R.string.premium_active_kino
        )
        tvApprovedSubtitle?.text = getString(
            if (isFull) R.string.premium_active_full_sub else R.string.premium_active_kino_sub
        )
        overlay?.visibility = View.VISIBLE
        scroll?.visibility = View.GONE
    }

    override fun onFragmentDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        billing?.destroy()
        billing = null
        themedContext = null
        scroll = null
        overlay = null
        tvApprovedTitle = null
        tvApprovedSubtitle = null
        btnPayKino = null
        btnPayFull = null
        tvKinoPlayPrice = null
        tvFullPlayPrice = null
        super.onFragmentDestroy()
    }

    // ---- BaseFragment'da getString/toast yo'q — o'z helperlarimiz ----

    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }

    private fun getString(resId: Int, vararg args: Any): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId, *args)
    }

    private fun toast(text: String) {
        val ctx = parentActivity ?: context ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }
}
