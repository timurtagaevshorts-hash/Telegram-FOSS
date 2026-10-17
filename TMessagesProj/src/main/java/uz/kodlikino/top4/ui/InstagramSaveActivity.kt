package uz.kodlikino.top4.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.utils.InstagramMediaSaver
import uz.kodlikino.top4.utils.NetworkReachability
import uz.kodlikino.top4.utils.instagram.InstagramMediaResolver
import java.io.File

/**
 * Instagram Reels havolasidan videoni yuklab olish va galereyaga saqlash.
 * KinoTOP4`dagi [androidx.appcompat.app.AppCompatActivity] versiyasi [BaseFragment]
 * ko'rinishiga moslashtirildi; saqlash [InstagramMediaSaver] orqali (Movies/YuzGram).
 */
class InstagramSaveActivity : BaseFragment() {

    private var themedContext: Context? = null
    private var etLink: TextInputEditText? = null
    private var btnPaste: MaterialButton? = null
    private var btnDownload: MaterialButton? = null
    private var btnSaveGallery: MaterialButton? = null
    private var progressPanel: LinearLayout? = null
    private var tvProgress: TextView? = null
    private var resultCard: MaterialCardView? = null
    private var previewPlayer: PlayerView? = null
    private var tvAuthor: TextView? = null

    private var exoPlayer: ExoPlayer? = null
    private var resolved: InstagramMediaResolver.Media? = null
    private var cachedVideoFile: File? = null
    private var busy = false
    private var scope: CoroutineScope? = null

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.activity_instagram_save, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE

        val toolbar = root.findViewById<Toolbar>(R.id.instagramSaveToolbar)
        YuzTopInset.applyPadding(toolbar)
        toolbar.setNavigationOnClickListener { removeSelfFromStack() }

        etLink = root.findViewById(R.id.etInstagramLink)
        btnPaste = root.findViewById(R.id.btnInstagramPaste)
        btnDownload = root.findViewById(R.id.btnInstagramDownload)
        btnSaveGallery = root.findViewById(R.id.btnInstagramSaveGallery)
        progressPanel = root.findViewById(R.id.instagramProgressPanel)
        tvProgress = root.findViewById(R.id.tvInstagramProgress)
        resultCard = root.findViewById(R.id.instagramResultCard)
        previewPlayer = root.findViewById(R.id.instagramPreviewPlayer)
        tvAuthor = root.findViewById(R.id.tvInstagramAuthor)

        btnPaste?.setOnClickListener { pasteFromClipboard(showEmptyWarning = true) }
        btnDownload?.setOnClickListener { startDownload() }
        btnSaveGallery?.setOnClickListener { saveToGallery() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        return root
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        if (etLink?.text.isNullOrBlank()) {
            pasteFromClipboard(showEmptyWarning = false)
        }
    }

    override fun onPause() {
        exoPlayer?.pause()
        super.onPause()
    }

    override fun onFragmentDestroy() {
        releasePlayer()
        scope?.cancel()
        scope = null
        super.onFragmentDestroy()
    }

    private fun pasteFromClipboard(showEmptyWarning: Boolean) {
        val ctx = themedContext ?: context ?: return
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(ctx)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isBlank() || !InstagramMediaResolver.looksLikeInstagramLink(text)) {
            if (showEmptyWarning) toast(R.string.instagram_save_clipboard_empty)
            return
        }
        val best = InstagramMediaResolver.extractBestUrl(text)
        etLink?.setText(best)
        etLink?.setSelection(best.length)
    }

    private fun startDownload() {
        if (busy) return
        val link = InstagramMediaResolver.extractBestUrl(etLink?.text?.toString().orEmpty())
        if (link.isBlank() || !InstagramMediaResolver.looksLikeInstagramLink(link)) {
            toast(R.string.instagram_save_error_link)
            return
        }
        val ctx = themedContext ?: context ?: return
        if (!NetworkReachability.isOnline(ctx)) {
            toast(R.string.instagram_save_error_offline)
            return
        }
        resolved = null
        cachedVideoFile = null
        releasePlayer()
        resultCard?.visibility = View.GONE
        setBusy(true, getString(R.string.instagram_save_resolving))

        val s = scope ?: return
        s.launch {
            val result = InstagramMediaResolver.resolve(ctx, link)
            val media = result.getOrNull()
            if (media == null) {
                setBusy(false, "")
                toast(errorTextFor(result.exceptionOrNull()?.message))
                return@launch
            }
            setBusy(true, getString(R.string.instagram_save_downloading, 0))
            val downloaded = runCatching {
                InstagramMediaResolver.downloadToCache(ctx, media) { percent ->
                    tvProgress?.text = getString(R.string.instagram_save_downloading, percent)
                }
            }
            setBusy(false, "")
            val file = downloaded.getOrNull()
            if (file == null) {
                toast(getString(R.string.instagram_save_error_failed))
                return@launch
            }
            resolved = media
            cachedVideoFile = file
            showResult(media, file)
        }
    }

    private fun showResult(media: InstagramMediaResolver.Media, file: File) {
        resultCard?.visibility = View.VISIBLE
        if (media.authorName.isBlank()) {
            tvAuthor?.visibility = View.GONE
        } else {
            tvAuthor?.visibility = View.VISIBLE
            tvAuthor?.text = getString(R.string.instagram_save_author, media.authorName)
        }
        playPreview(file)
    }

    private fun playPreview(file: File) {
        releasePlayer()
        val ctx = themedContext ?: return
        exoPlayer = ExoPlayer.Builder(ctx).build().also { player ->
            previewPlayer?.player = player
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.playWhenReady = true
        }
    }

    private fun releasePlayer() {
        previewPlayer?.player = null
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun saveToGallery() {
        val media = resolved ?: return
        val file = cachedVideoFile
        if (file == null || !file.exists()) {
            toast(getString(R.string.instagram_save_error_failed))
            return
        }
        if (busy) return
        val ctx = themedContext ?: context ?: return
        setBusy(true, getString(R.string.instagram_save_saving))
        val s = scope ?: return
        s.launch {
            val saved = InstagramMediaSaver.saveLocalVideoFile(ctx, file, media.fileName())
            setBusy(false, "")
            if (saved.isSuccess) {
                toast(R.string.instagram_save_saved)
            } else {
                toast(getString(R.string.instagram_save_error_failed))
            }
        }
    }

    private fun setBusy(value: Boolean, status: String) {
        busy = value
        progressPanel?.visibility = if (value) View.VISIBLE else View.GONE
        tvProgress?.text = status
        btnDownload?.isEnabled = !value
        btnPaste?.isEnabled = !value
        btnSaveGallery?.isEnabled = !value
    }

    private fun errorTextFor(code: String?): String = when (code) {
        InstagramMediaResolver.ERROR_INVALID_LINK -> getString(R.string.instagram_save_error_link)
        InstagramMediaResolver.ERROR_NOT_FOUND -> getString(R.string.instagram_save_error_not_found)
        InstagramMediaResolver.ERROR_LOGIN_REQUIRED -> getString(R.string.instagram_save_error_not_found)
        else -> getString(R.string.instagram_save_error_failed)
    }

    private fun toast(resId: Int) {
        val ctx = themedContext ?: context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }

    private fun getString(resId: Int, vararg args: Any): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId, *args)
    }
}
