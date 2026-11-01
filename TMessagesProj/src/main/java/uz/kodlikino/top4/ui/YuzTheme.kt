package uz.kodlikino.top4.ui

import android.content.Context
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
        val wanted = if (isNight()) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        val current = context.resources.configuration
        if (current.uiMode and Configuration.UI_MODE_NIGHT_MASK == wanted) {
            return context
        }
        val config = Configuration(current)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or wanted
        return context.createConfigurationContext(config)
    }

    /** YuzGram ekranlari uchun Material DayNight tema + moslangan uiMode. */
    @JvmStatic
    fun wrap(context: Context): Context {
        return ContextThemeWrapper(themeContext(context), R.style.Theme_YuzGramAds)
    }
}
