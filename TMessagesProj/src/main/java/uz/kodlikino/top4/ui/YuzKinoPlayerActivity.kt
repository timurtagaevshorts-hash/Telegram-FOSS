package uz.kodlikino.top4.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.telegram.messenger.R

/**
 * YuzGram #72: kino ko'rish — donor KinoTOP4 `VideoPlayerActivity`'sining
 * 1:1 porti: alohida landshaft immersive fullscreen Activity. Fragment ichida
 * (LaunchActivity oynasida) WebView fullscreen/orientatsiya holatini to'g'ri
 * ololmadi, shuning uchun tizim avvalgi ko'rinishiga qaytarildi.
 */
class YuzKinoPlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: ImageView
    private lateinit var tvTitle: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yuz_kino_player)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        applyImmersive()

        webView = findViewById(R.id.yuzKinoWebView)
        progressBar = findViewById(R.id.yuzKinoProgress)
        btnBack = findViewById(R.id.btnYuzKinoBack)
        tvTitle = findViewById(R.id.tvYuzKinoTitle)

        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.kino_title)
        tvTitle.text = title

        if (url.isEmpty()) {
            Toast.makeText(this, R.string.kino_no_url, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupWebView()
        loadVideo(url)

        btnBack.setOnClickListener { finish() }

        handler.postDelayed({ hideControls() }, 3000)
        webView.setOnClickListener { showControls() }
    }

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    private fun hideControls() {
        btnBack.animate().alpha(0f).setDuration(500).withEndAction {
            btnBack.visibility = View.GONE
        }.start()
        tvTitle.animate().alpha(0f).setDuration(500).withEndAction {
            tvTitle.visibility = View.GONE
        }.start()
    }

    private fun showControls() {
        btnBack.visibility = View.VISIBLE
        btnBack.alpha = 0f
        btnBack.animate().alpha(1f).setDuration(300).start()

        tvTitle.visibility = View.VISIBLE
        tvTitle.alpha = 0f
        tvTitle.animate().alpha(1f).setDuration(300).start()

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ hideControls() }, 3000)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }
    }

    private fun loadVideo(url: String) {
        progressBar.visibility = View.VISIBLE

        when {
            url.contains("drive.google.com") -> {
                val fileId = extractGoogleDriveId(url)
                if (fileId != null) {
                    webView.loadUrl("https://drive.google.com/file/d/$fileId/preview")
                } else {
                    webView.loadUrl(url)
                }
            }

            url.contains("instagram.com") -> {
                webView.loadUrl(url.replace("instagram.com", "ddinstagram.com"))
            }

            url.contains(".mp4") || url.contains(".m3u8") || url.contains(".mkv") ||
                url.contains(".avi") || url.contains(".mov") || url.contains(".webm") -> {
                val mimeType = when {
                    url.contains(".m3u8") -> "application/x-mpegURL"
                    url.contains(".mkv") -> "video/x-matroska"
                    url.contains(".webm") -> "video/webm"
                    else -> "video/mp4"
                }
                val html = "<!DOCTYPE html><html><head>" +
                    "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                    "<style>*{margin:0;padding:0}" +
                    "body{background:#000;display:flex;justify-content:center;align-items:center;height:100vh}" +
                    "video{width:100%;height:100%;object-fit:contain}</style></head>" +
                    "<body><video controls autoplay playsinline>" +
                    "<source src=\"$url\" type=\"$mimeType\"></video></body></html>"
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }

            else -> webView.loadUrl(url)
        }
    }

    private fun extractGoogleDriveId(url: String): String? {
        val patterns = listOf(
            Regex("file/d/([a-zA-Z0-9_-]+)"),
            Regex("id=([a-zA-Z0-9_-]+)")
        )
        for (p in patterns) {
            p.find(url)?.let { return it.groupValues[1].substringBefore("&").substringBefore("?") }
        }
        return null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        webView.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.removeAllViews()
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "video_url"
        const val EXTRA_TITLE = "video_title"
    }
}
