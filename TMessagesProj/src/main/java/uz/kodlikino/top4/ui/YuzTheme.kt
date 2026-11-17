package uz.kodlikino.top4.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.Theme

/**
 * YuzGram ekranlarini joriy Telegram mavzusiga bog'lovchi ko'prik.
 *
 * Telegram ranglarni kod orqali (`Theme.getColor`) beradi, YuzGram ekranlari esa
 * XML `@color/yuz_*` resurslaridan foydalanadi (values / values-night juftligi).
 * Android bu juftlikdan qaysi birini olishini kontekstning uiMode'idan hal qiladi,
 * shuning uchun [wrap] kontekst uiMode'ini joriy Telegram mavzusining
 * qorong'i/kunduzgi holatiga moslab qaytaradi.
 */
object YuzTheme {

    private fun isNight(): Boolean {
        val theme = Theme.getActiveTheme()
        return theme?.isDark ?: Theme.isCurrentThemeNight()
    }

    /**
     * [context] asosida uiMode'i joriy Telegram mavzusiga moslangan kontekst
     * qaytaradi. Activity'larda `attachBaseContext` ichida ishlatiladi.
     */
    @JvmStatic
    fun themeContext(context: Context): Context {
        val config = uiModeOverride(context) ?: return context
        return context.createConfigurationContext(config)
    }

    /**
     * YuzGram ekranlari uchun Material DayNight tema + moslangan uiMode.
     *
     * Muhim: `ContextThemeWrapper` asosida haqiqiy [Activity] turishi shart.
     * Avval uiMode uchun `createConfigurationContext()` konteksti o'ralardi —
     * uning ichida Activity yo'q, shuning uchun undan yaratilgan
     * `MaterialAlertDialogBuilder(...).show()` `BadTokenException` bilan
     * yiqilardi (masalan, "hudud tanlash" dialogi). Endi uiMode
     * `applyOverrideConfiguration()` orqali qo'llanadi — tema va tungi rejim
     * o'sha-o'sha, lekin oyna tokeni (Activity) zanjirda saqlanadi.
     */
    @JvmStatic
    fun wrap(context: Context): Context {
        val host = findActivity(context)
            ?: return ContextThemeWrapper(themeContext(context), R.style.Theme_YuzGramAds)
        val wrapper = ContextThemeWrapper(host, R.style.Theme_YuzGramAds)
        val config = uiModeOverride(host) ?: return wrapper
        try {
            wrapper.applyOverrideConfiguration(config)
        } catch (_: IllegalStateException) {
            // Resurslar allaqachon ochilgan bo'lsa — faqat tema bilan qanoatlanamiz.
        }
        return wrapper
    }

    /** Kontekst zanjiridagi haqiqiy Activity (dialog oyna tokeni manbasi). */
    @JvmStatic
    fun findActivity(context: Context): Activity? {
        var current: Context? = context
        var depth = 0
        while (current != null && depth++ < 12) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    private fun uiModeOverride(context: Context): Configuration? {
        val wanted = if (isNight()) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        val current = context.resources.configuration
        if (current.uiMode and Configuration.UI_MODE_NIGHT_MASK == wanted) {
            return null
        }
        val config = Configuration(current)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or wanted
        return config
    }
}
