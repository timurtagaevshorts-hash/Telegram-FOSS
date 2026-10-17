package uz.kodlikino.top4.ui

import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.ActionBar

/**
 * Yuz ekranlari uchun tepadagi status-bar bo'shlig'i.
 *
 * Avval har bir ekran `AndroidUtilities.statusBarHeight` ni QATTI qo'shardi.
 * Agar oynaning kontent maydoni allaqachon status bar OSTIDA joylashgan
 * bo'lsa (edge-to-edge bo'lmagan holat), bu ortiqcha "bosh qora joy"
 * paydo bo'lar edi. Yordamchi ko'rinishning haqiqiy ekran koordinatasini
 * o'lchaydi: kerakli tepa surish = status bar pastki chegarasi − view yuqorisi
 * (manfiy bo'lsa 0). Shunda:
 *  - edge-to-edge oynada (view yuqorisi = 0) → avvalgidek to'liq offset;
 *  - kontent status bar ostida boshlansa → qo'shimcha bo'shliq 0 (joy yopiladi).
 *
 * Birinchi layout'tan keyin hisoblanadi; shu sababli daf'a optimistik ravishda
 * avvalgi (to'liq) qiymatni qo'yadi — edge-to-edge holatda bir kadrlik
 * sakrash ham bo'lmaydi.
 */
object YuzTopInset {

    /**
     * "Qora boshliq"ning ASOSIY sababi: Yuz sahifalari ActionBar'ni
     * `visibility = GONE` qiladi, lekin ActionBarLayout GONE bo'lgan
     * ActionBar'ning o'lchangan balandligini (status bar + 56dp) baribir
     * fragment tepasida zaxiralaydi — ustda bo'sh qora lenta qoladi.
     * Har fragment o'z ActionBar nusxasiga ega, shuning uchun GLOBAL
     * o'zgartirishsiz mahalliy yopamiz: o'lchan balandlik
     * 56dp + 0 (occupyStatusBar=false) + (-56dp) = 0 → fragment y=0 dan
     * (edge-to-edge) boshlanadi, fon status bar ostiga ham yoyiladi;
     * [applyPadding] keyin haqiqiy status-bar offset'ni o'zi hisoblaydi.
     */
    fun collapseActionBar(bar: ActionBar?) {
        val b = bar ?: return
        b.setOccupyStatusBar(false)
        b.setExtraHeight(-ActionBar.getCurrentActionBarHeight())
    }

    fun applyPadding(view: View?) {
        val v = view ?: return
        val base = v.paddingTop
        v.setPadding(v.paddingLeft, base + AndroidUtilities.statusBarHeight,
            v.paddingRight, v.paddingBottom)
        v.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(target: View, left: Int, top: Int, right: Int,
                                        bottom: Int, oldLeft: Int, oldTop: Int,
                                        oldRight: Int, oldBottom: Int) {
                target.removeOnLayoutChangeListener(this)
                val loc = IntArray(2)
                target.getLocationOnScreen(loc)
                val need = (AndroidUtilities.statusBarHeight - loc[1]).coerceAtLeast(0)
                target.setPadding(target.paddingLeft, base + need,
                    target.paddingRight, target.paddingBottom)
            }
        })
    }
}
