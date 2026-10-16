package uz.kodlikino.top4.utils.instagram

import android.content.Context
import android.webkit.CookieManager

/** Instagram WebView orqali olingan cookie lar — yuklab olish ishonchliligini oshiradi. */
object InstagramCookieStore {

    private const val PREFS = "instagram_cookie_store"
    private const val KEY_COOKIE = "cookie_header"

    fun save(context: Context, cookieHeader: String) {
        val trimmed = cookieHeader.trim()
        if (trimmed.isBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COOKIE, trimmed)
            .apply()
    }

    fun syncFromWebView(context: Context) {
        val cookies = CookieManager.getInstance()
            .getCookie("https://www.instagram.com")
            ?.trim()
            .orEmpty()
        if (cookies.isNotBlank()) {
            save(context, cookies)
        }
    }

    fun load(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COOKIE, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    fun hasCookies(context: Context): Boolean = !load(context).isNullOrBlank()

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_COOKIE)
            .apply()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    fun csrfToken(context: Context): String? = csrfFromHeader(load(context).orEmpty())

    fun csrfFromHeader(cookieHeader: String): String? =
        cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("csrftoken=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
