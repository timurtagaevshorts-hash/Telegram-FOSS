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
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.utils.PlanBenefit
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
 *    bir xil (Firestore `users` kolleksiyasi qoidasi bilan berkitilgan, `comment_profiles`
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
    private var tvPremiumStatus: TextView? = null

    /** Play tasdiqlagan faol tarif — tarif kartalari shunga mos ko'rinadi. */
    private var currentPlanId: String? = null

    private var billing: YuzPlayBilling? = null
    private var billingReady = false
    private var restoreRequested = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_yuz_premium, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        scroll = root.findViewById(R.id.premiumScroll)
        overlay = root.findViewById(R.id.premiumApprovedOverlay)
        tvApprovedTitle = root.findViewById(R.id.tvApprovedTitle)
        tvApprovedSubtitle = root.findViewById(R.id.tvApprovedSubtitle)
        tvKinoPlayPrice = root.findViewById(R.id.tvKinoPlayPrice)
        tvFullPlayPrice = root.findViewById(R.id.tvFullPlayPrice)
        tvPremiumStatus = root.findViewById(R.id.tvPremiumStatus)
        btnPayKino = root.findViewById(R.id.btnPayKino)
        btnPayFull = root.findViewById(R.id.btnPayFull)

        // Status bari bilan "bosh qora joy" qolmasin — haqiqiy inset bo'yicha.
        YuzTopInset.applyPadding(root.findViewById<View>(R.id.premiumTopBar))
        root.findViewById<View>(R.id.btnPremiumBack)?.setOnClickListener { removeSelfFromStack() }
        root.findViewById<View>(R.id.btnApprovedClose)?.setOnClickListener { removeSelfFromStack() }
        // Faol tarif ekranidan ham tarif kartalariga qaytish yo'li bo'lsin:
        // aks holda "a'zo bo'lish" ekrani umuman ochilmagandek tuyuladi.
        root.findViewById<View>(R.id.btnApprovedPlans)?.setOnClickListener {
            showPlanChooser()
        }
        root.findViewById<View>(R.id.btnRestorePurchases)?.setOnClickListener {
            restoreRequested = true
            val existing = billing
            if (existing != null) {
                existing.restorePurchases()
            } else {
                // start() o'zi inventarni so'raydi.
                ensureBilling()
            }
            toast(getString(R.string.premium_checking_purchases))
        }

        fillPlanTexts()

        // Ekran ochilishida har doim tariflar ko'rinadi; faol tarif yuqoridagi
        // bannerda aytiladi — "a'zo bo'lish" ekrani yopilib qolmasin.
        val plan = YuzPremiumManager.from(themed).planType(currentAccount)
        if (YuzPremiumPlans.isPremiumPlan(plan)) {
            currentPlanId = plan
        }
        showPlanChooser()
        return root
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        if (themedContext == null) return
        // Har qaytishda inventar qayta o'qiladi — refund/void darhol aks etadi.
        ensureBilling()
    }

    /** Ikkala tarif kartasi imtiyozlarini emoji nishonli qatorlar qilib chiqaradi. */
    private fun fillBenefits(
        container: android.widget.LinearLayout?,
        benefits: List<PlanBenefit>,
        gold: Boolean
    ) {
        val themed = themedContext ?: return
        container ?: return
        container.removeAllViews()
        val d = themed.resources.displayMetrics.density
        val emojiBg = if (gold) R.drawable.bg_yuz_emoji_badge_gold else R.drawable.bg_yuz_emoji_badge
        benefits.forEachIndexed { index, benefit ->
            val row = android.widget.LinearLayout(themed).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else (10f * d).toInt() }
            }
            val emoji = TextView(themed).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (30f * d).toInt(), (30f * d).toInt()
                )
                gravity = android.view.Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                includeFontPadding = false
                text = benefit.emoji
                setBackgroundResource(emojiBg)
            }
            val texts = android.widget.LinearLayout(themed).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = (10f * d).toInt() }
            }
            val title = TextView(themed).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(themed, R.color.yuz_text_bright)
                )
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
                )
                includeFontPadding = false
                text = benefit.title
            }
            val subtitle = TextView(themed).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2f * d).toInt() }
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(themed, R.color.yuz_text_secondary)
                )
                includeFontPadding = false
                text = benefit.subtitle
            }
            texts.addView(title)
            texts.addView(subtitle)
            row.addView(emoji)
            row.addView(texts)
            container.addView(row)
        }
    }

    /** Ikkala tarif kartasini to'ldiradi va tugmalarni ulaydi. */
    private fun fillPlanTexts() {
        // ContextThemeWrapper.findViewById ishonchli emas — fragment View daraxtini
        // ishlatamiz (createView'da chaqirilganda hali hierarxiyaga ulanmagan).
        val root = fragmentView ?: return
        for (plan in listOf(YuzPremiumPlans.PREMIUM_KINO, YuzPremiumPlans.FULL_PREMIUM)) {
            val isKino = plan.id == YuzPremiumPlans.PREMIUM_KINO.id
            val tvTitle = root.findViewById<TextView>(
                if (isKino) R.id.tvKinoTitle else R.id.tvFullTitle
            )
            val tvSubtitle = root.findViewById<TextView>(
                if (isKino) R.id.tvKinoSubtitle else R.id.tvFullSubtitle
            )
            val tvTag = root.findViewById<TextView>(
                if (isKino) R.id.tvKinoTag else R.id.tvFullTag
            )
            val tvPrice = root.findViewById<TextView>(
                if (isKino) R.id.tvKinoPrice else R.id.tvFullPrice
            )
            val btn = root.findViewById<MaterialButton>(
                if (isKino) R.id.btnPayKino else R.id.btnPayFull
            )
            tvTitle?.text = plan.title
            tvSubtitle?.text = plan.subtitle
            tvTag?.text = plan.tag
            tvPrice?.text = plan.formattedPrice()
            fillBenefits(
                root.findViewById(if (isKino) R.id.llKinoBenefits else R.id.llFullBenefits),
                plan.benefits,
                gold = !isKino
            )
            btn?.text = plan.buttonText
            btn?.setOnClickListener { launchPurchase(plan) }
        }
    }

    private fun ensureBilling() {
        val existing = billing
        if (existing != null) {
            existing.refreshPurchases()
            return
        }
        val activity = parentActivity ?: return
        billing = YuzPlayBilling(activity, currentAccount, billingListener).also { it.start() }
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
            // Grant faqat Google PURCHASED + imzo tekshiruvidan keyin keladi
            // (qarang YuzPurchaseValidator). #120: callback destroy'dan keyin ham
            // kelishi mumkin — themedContext bo'lmasa ham grant to'xtatilmaydi.
            val ctx = themedContext ?: org.telegram.messenger.ApplicationLoader.applicationContext
            YuzPremiumManager.from(ctx).applyPurchase(
                currentAccount,
                planId,
                purchase.products.firstOrNull().orEmpty(),
                purchase.purchaseToken,
                purchase.purchaseTime
            )
            mainHandler.post {
                if (fragmentView == null) return@post
                showApproved(planId)
            }
        }

        override fun onPurchaseFailed(message: String) {
            mainHandler.post {
                if (fragmentView == null) return@post
                // Restore so'rovi aniq natija bermadi — keyingi jimgina
                // yangilanishda "xarid topilmadi" degan yolg'on xabar chiqmasin.
                restoreRequested = false
                refreshPriceLabels()
                toast(message)
            }
        }

        override fun onExistingPurchaseRestored(planId: String, purchase: Purchase) {
            // #120: restore ham destroy-keyin kelishi mumkin — app context fallback.
            val themed = themedContext ?: org.telegram.messenger.ApplicationLoader.applicationContext
            YuzPremiumManager.from(themed).applyPurchase(
                currentAccount,
                planId,
                purchase.products.firstOrNull().orEmpty(),
                purchase.purchaseToken,
                purchase.purchaseTime
            )
            mainHandler.post {
                if (fragmentView == null) return@post
                // Tiklangan xarid — "faollashtirildi" deb aytmaymiz, faqat mavjud
                // holat ko'rsatiladi; tariflar ekrani ochiq qoladi.
                currentPlanId = planId
                refreshPriceLabels()
            }
        }

        override fun onSyncFinished(activePlanId: String?) {
            mainHandler.post {
                if (fragmentView == null) return@post
                val ctx = themedContext
                    ?: org.telegram.messenger.ApplicationLoader.applicationContext
                if (activePlanId == null) {
                    // Play aniq "xarid yo'q" dedi — refund/void yoki boshqa akkaunt.
                    val manager = YuzPremiumManager.from(ctx)
                    if (manager.isPremium(currentAccount)) {
                        manager.clearPlan(currentAccount)
                    }
                    currentPlanId = null
                    showPlanChooser()
                    if (restoreRequested) {
                        toast(getString(R.string.premium_restore_not_found))
                    }
                } else {
                    currentPlanId = activePlanId
                }
                restoreRequested = false
                refreshPriceLabels()
            }
        }
    }

    private fun refreshPriceLabels() {
        // Tarif holati (faol / ichida) billing holatidan mustaqil ko'rinadi.
        applyActivePlanLabels()
        updateStatusBanner()
        val b = billing ?: return
        val root = fragmentView ?: return
        for (plan in listOf(YuzPremiumPlans.PREMIUM_KINO, YuzPremiumPlans.FULL_PREMIUM)) {
            val isKino = plan.id == YuzPremiumPlans.PREMIUM_KINO.id
            val label = if (isKino) tvKinoPlayPrice else tvFullPlayPrice
            val btn = if (isKino) btnPayKino else btnPayFull
            val price = b.getFormattedPrice(plan.playProductId)
            // Karta sarlavhasidagi summa ilova taxmini emas, Play haqiqatan
            // yechadigan narx bo'lishi shart (Play siyosati + foydalanuvchi).
            if (!price.isNullOrBlank()) {
                root.findViewById<TextView>(
                    if (isKino) R.id.tvKinoPrice else R.id.tvFullPrice
                )?.text = price
            }
            label?.visibility = View.VISIBLE
            label?.text = if (price.isNullOrBlank()) {
                getString(R.string.premium_play_unavailable)
            } else {
                getString(R.string.premium_play_price, price)
            }
            btn?.isEnabled = isPlanPurchasable(plan.id) && billingReady && !price.isNullOrBlank()
        }
    }

    /** Faol tarif mavjud bo'lsa, o'sha tarifni (yoki unga kirgan tarifni) sotmaymiz. */
    private fun isPlanPurchasable(planId: String): Boolean = when (currentPlanId) {
        YuzPremiumPlans.ID_FULL -> false
        YuzPremiumPlans.ID_KINO -> planId == YuzPremiumPlans.ID_FULL
        else -> true
    }

    private fun applyActivePlanLabels() {
        val fullActive = currentPlanId == YuzPremiumPlans.ID_FULL
        btnPayKino?.text = when {
            currentPlanId == YuzPremiumPlans.ID_KINO -> getString(R.string.premium_plan_current)
            fullActive -> getString(R.string.premium_plan_included)
            else -> YuzPremiumPlans.PREMIUM_KINO.buttonText
        }
        btnPayFull?.text = if (fullActive) {
            getString(R.string.premium_plan_current)
        } else {
            YuzPremiumPlans.FULL_PREMIUM.buttonText
        }
    }

    private fun launchPurchase(plan: YuzPremiumPlan) {
        if (YuzGramAdsAuth.expectedUid(currentAccount).isBlank()) {
            toast(getString(R.string.premium_need_sign_in))
            return
        }
        val manager = themedContext?.let { YuzPremiumManager.from(it) }
        if (plan.id == YuzPremiumPlans.PREMIUM_KINO.id &&
            manager?.isFullPremium(currentAccount) == true
        ) {
            // To'liq Premium egalari uchun pastroq tarif sotilmaydi.
            toast(getString(R.string.premium_full_already_active))
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
        // Play oynasi yopilib qaytgan bo'lsa, tugmalar qotib qolmasin.
        mainHandler.postDelayed({
            if (fragmentView != null) refreshPriceLabels()
        }, 1500L)
    }

    /** Tarif tanlash ekraniga qaytish (faol tarif / xarid topilmadi / refund). */
    private fun showPlanChooser() {
        overlay?.visibility = View.GONE
        scroll?.visibility = View.VISIBLE
        refreshPriceLabels()
    }

    private fun showApproved(planId: String) {
        currentPlanId = planId
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

    /** Faol tarif tariflar ekranining tepasida qisqa banner bo'lib turadi. */
    private fun updateStatusBanner() {
        val status = tvPremiumStatus ?: return
        val planId = currentPlanId
        if (planId == null) {
            status.visibility = View.GONE
            return
        }
        status.visibility = View.VISIBLE
        status.text = getString(
            if (YuzPremiumPlans.isFullPremiumPlan(planId)) {
                R.string.premium_active_full
            } else {
                R.string.premium_active_kino
            }
        )
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
        tvPremiumStatus = null
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
