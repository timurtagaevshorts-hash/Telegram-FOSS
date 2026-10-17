package uz.kodlikino.top4.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment

/**
 * Kino pleyeri — WebView ichida HTML5 `<video>`. Donor `VideoPlayerActivity`
 * mantiqini port qiladi: Google Drive → preview, instagram → ddinstagram,
 * to'g'ridan-to'g'ri mp4/webm/m3u8/... → <video> sahifa, boshqasi → loadUrl.
 * Landshaft fullscreen; chiqishda orientatsiya tiklanadi.
 */
class KinoPlayerActivity(
    private val videoUrl: String,
    private val videoTitle: String
) : BaseFragment() {

    private var webView: WebView? = null
    private var progress: ProgressBar? = null
    private var prevOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.fragment_kino_player, null) as ViewGroup
        fragmentView = root
        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        webView = root.findViewById(R.id.kinoWebView)
        progress = root.findViewById(R.id.kinoPlayerProgress)
        root.findViewById<View>(R.id.btnKinoPlayerBack)?.setOnClickListener { removeSelfFromStack() }

        val activity = parentActivity
        if (activity != null) {
            prevOrientation = activity.requestedOrientation
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        setupWebView()
        loadVideo(videoUrl)
        return root
    }

    private fun setupWebView() {
        val wv = webView ?: return
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        wv.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, reload: Boolean) {
                hideProgress()
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 80) hideProgress()
            }
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                try { request?.grant(request.resources) } catch (_: Exception) {}
            }
        }
    }

    private fun hideProgress() {
        progress?.visibility = View.GONE
    }

    private fun loadVideo(url: String) {
        val wv = webView ?: return
        val trimmed = url.trim()
        if (trimmed.isBlank()) return

        val driveId = extractGoogleDriveId(trimmed)
        if (driveId != null) {
            wv.loadUrl("https://drive.google.com/file/d/$driveId/preview")
            return
        }
        if (trimmed.contains("instagram.com")) {
            wv.loadUrl(trimmed.replace("instagram.com", "ddinstagram.com"))
            return
        }
        val lower = trimmed.substringBefore('?').toLowerCase()
        val ext = lower.substringAfterLast('.', "")
        if (ext in setOf("mp4", "m4v", "webm", "ogg", "mov", "mkv", "m3u8")) {
            val mime = when (ext) {
                "mp4", "m4v", "mov" -> "video/mp4"
                "webm" -> "video/webm"
                "ogg" -> "video/ogg"
                else -> "video/mp4"
            }
            val html = buildVideoHtml(trimmed, mime)
            wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            return
        }
        wv.loadUrl(trimmed)
    }

    private fun buildVideoHtml(src: String, mime: String): String {
        val safeSrc = src.replace("\"", "&quot;")
        return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,\" />" +
            "<style>html,body{margin:0;padding:0;background:#000;height:100%}" +
            "video{width:100%;height:100vh;background:#000;display:block}</style></head>" +
            "<body><video controls autoplay playsinline><source src=\"$safeSrc\" type=\"$mime\"></video></body></html>"
    }

    private fun extractGoogleDriveId(url: String): String? {
        Regex("file/d/([a-zA-Z0-9_-]+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("[?&]id=([a-zA-Z0-9_-]+)").find(url)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        webView?.onResume()
    }

    override fun onFragmentDestroy() {
        val activity = parentActivity
        if (activity != null) {
            activity.requestedOrientation = prevOrientation
        }
        try {
            webView?.let {
                it.stopLoading()
                it.onPause()
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
        } catch (_: Exception) {
        }
        webView = null
        super.onFragmentDestroy()
    }
}
