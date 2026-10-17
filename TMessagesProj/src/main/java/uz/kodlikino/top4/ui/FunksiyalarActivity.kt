package uz.kodlikino.top4.ui

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment

/**
 * "Funksiyalar" hub — YuzGram pastki navigatsiyasining 3-tab'i (sobiq "Elonlar").
 * Bir ekrandan ichki bo'limlarga kiradi: Elonlar, Ish kartasi, Taksi, Instagram video.
 * Har bir bo'lim alohida [BaseFragment] sifatida ustga qo'yiladi (presentFragment),
 * orqaga qaytish hub'ga olib keladi. Pastki bar LaunchActivity darajasida doimiy.
 */
class FunksiyalarActivity : BaseFragment() {

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_funksiyalar, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE

        // Status bari ostiga tushmasin — ichki ustun tepasiga bo'shliq.
        (root.getChildAt(0) as? ViewGroup)?.setPadding(
            0, AndroidUtilities.statusBarHeight, 0, 0
        )

        root.findViewById<View>(R.id.cardElonlar)?.setOnClickListener {
            presentFragment(ElonlarActivity(), false)
        }
        root.findViewById<View>(R.id.cardIsh)?.setOnClickListener {
            presentFragment(JobCardsActivity(), false)
        }
        root.findViewById<View>(R.id.cardTaksi)?.setOnClickListener {
            presentFragment(TaxiCallActivity(), false)
        }
        root.findViewById<View>(R.id.cardInstagram)?.setOnClickListener {
            presentFragment(InstagramSaveActivity(), false)
        }
        root.findViewById<View>(R.id.cardMonet)?.setOnClickListener {
            presentFragment(MonetizationActivity(), false)
        }
        root.findViewById<View>(R.id.cardKinolar)?.setOnClickListener {
            presentFragment(KinolarActivity(), false)
        }

        return root
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
    }
}
