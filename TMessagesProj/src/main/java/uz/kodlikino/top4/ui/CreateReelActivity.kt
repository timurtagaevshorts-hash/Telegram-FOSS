package uz.kodlikino.top4.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.utils.ReelStorageHelper
import uz.kodlikino.top4.utils.ReelsRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzPremiumManager
import uz.kodlikino.top4.utils.YuzReelQuota
import uz.kodlikino.top4.utils.YuzUploadUi

/**
 * "Reel joylash" — video tanlash, Firebase Storage'ga yuklash va Firestore `reels`
 * kolleksiyasiga yozish ekrani.
 *
 * [CreateClassifiedAdActivity] bilan bir xil Telegram uslubida yozildi ([BaseFragment]
 * + [ContextThemeWrapper] Material temasi ostida), chunki Elonlar/Reels bir butun.
 *
 * Muhim chegaralar (deployment qoidalari bilan bir xil):
 *  - Storage yo'li `user_reels/{uid}/{file}` va `contentType` video turdan bo'lishi shart,
 *    hajm 200 MB dan kichik ([MAX_VIDEO_BYTES]) — aks holda rules yozuvni rad etadi;
 *  - `uploadedByUid` `tg_<clientUserId>` bo'ladi ([YuzGramAdsAuth]) — Firestore
 *    create qoidasi aynan shu indifikatorni talab qiladi.
 *
 * Shu sababli rasm poster (thumbnail) YUKLANMAYDI — `user_reels/` yo'li faqat
 * video contentType'ga ruxsat beradi. Upload ekranida tanlangan videoning
 * BIRINCHI KADRI LOCAL'ga ko'rsatiladi (MediaMetadataRetriever), feed'da esa
 * poster o'rniga video kadr kutiladi.
 *
 * Haftalik kvot (Faza C, donor `UserReelUploadManager` moslamasi): oddiy
 * foydalanuvchi 3 ta/hafta · 50 MB, To'liq Premium 30 ta/hafta · 100 MB —
 * [YuzReelQuota] orqali (Firestore `user_reel_quota/{uid}`).
 */
class CreateReelActivity : BaseFragment() {

    companion object {
        private const val REQ_PICK_VIDEO = 4481
        private const val MAX_VIDEO_BYTES = 200L * 1024L * 1024L

        /** Preview kadri ekranda chiqishi yetarli — memory uchun kichraytiriladi. */
        private const val MAX_PREVIEW_PX = 540
    }

    private var themedContext: Context? = null
    private var toolbar: Toolbar? = null
    private var btnPickVideo: View? = null
    private var llPickPrompt: View? = null
    private var ivVideoPreview: ImageView? = null
    private var btnPickOverlay: TextView? = null
    private var tvVideoDuration: TextView? = null
    private var tvVideoMeta: TextView? = null
    private var switchMonetization: SwitchCompat? = null
    private var tvPickVideoLabel: TextView? = null
    private var tvVideoName: TextView? = null
    private var tvQuota: TextView? = null
    private var etCaption: TextInputEditText? = null
    private var etHashtags: TextInputEditText? = null
    private var uploadProgress: ProgressBar? = null
    private var tvUploadStatus: TextView? = null
    private var btnPublish: MaterialButton? = null

    private var videoUri: Uri? = null
    private var videoDisplayName: String = ""
    private var videoSizeBytes: Long = -1L

    private var scope: CoroutineScope? = null
    private var publishing = false

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.activity_create_reel, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE
        YuzTopInset.collapseActionBar(actionBar)

        toolbar = root.findViewById(R.id.toolbar)
        btnPickVideo = root.findViewById(R.id.btnPickVideo)
        llPickPrompt = root.findViewById(R.id.llPickPrompt)
        ivVideoPreview = root.findViewById(R.id.ivVideoPreview)
        btnPickOverlay = root.findViewById(R.id.btnPickOverlay)
        tvVideoDuration = root.findViewById(R.id.tvVideoDuration)
        tvVideoMeta = root.findViewById(R.id.tvVideoMeta)
        switchMonetization = root.findViewById(R.id.switchMonetization)
        tvPickVideoLabel = root.findViewById(R.id.tvPickVideoLabel)
        tvVideoName = root.findViewById(R.id.tvVideoName)
        tvQuota = root.findViewById(R.id.tvQuotaInfo)
        etCaption = root.findViewById(R.id.etCaption)
        etHashtags = root.findViewById(R.id.etHashtags)
        uploadProgress = root.findViewById(R.id.uploadProgress)
        tvUploadStatus = root.findViewById(R.id.tvUploadStatus)
        btnPublish = root.findViewById(R.id.btnPublish)

        toolbar?.let { bar ->
            YuzTopInset.applyPadding(bar)
            bar.setNavigationOnClickListener { removeSelfFromStack() }
        }

        btnPickVideo?.setOnClickListener { pickVideo() }
        btnPublish?.setOnClickListener { publishReel() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        refreshQuotaLabel()
        return root
    }

    /** To'liq Premium — reels uchun haftalik boost (30 ta + 100 MB). */
    private fun premiumBoost(): Boolean {
        val ctx = themedContext ?: context ?: return false
        return YuzPremiumManager.from(ctx).hasReelsBoost(currentAccount)
    }

    /**
     * "Haftalik limit: 2/3 ta · 50 MB gacha" — Firestore kvot hujjatini o'qib
     * ko'rsatadi. Hujjat yo'q/xato bo'lsa 0 ishlatilgan hisoblanadi (limitni
     * sun'iy qisqartirmaydi).
     */
    private fun refreshQuotaLabel() {
        val s = scope ?: return
        val uid = YuzGramAdsAuth.expectedUid(currentAccount)
        if (uid.isBlank()) {
            tvQuota?.visibility = View.GONE
            return
        }
        val boosted = premiumBoost()
        s.launch {
            val remaining = YuzReelQuota.getRemainingUploads(uid, boosted)
            tvQuota?.text = YuzReelQuota.quotaSummaryLabel(boosted, remaining)
            tvQuota?.visibility = View.VISIBLE
        }
    }

    private fun pickVideo() {
        val activity = parentActivity ?: return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivityForResult(
                Intent.createChooser(intent, getString(R.string.reels_pick_video)),
                REQ_PICK_VIDEO
            )
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.reels_video_pick_failed))
        }
    }

    override fun onActivityResultFragment(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_VIDEO || resultCode != Activity.RESULT_OK || data == null) return
        val uri = data.data ?: return
        videoUri = uri
        videoDisplayName = queryDisplayName(uri)
        tvVideoName?.text = videoDisplayName
        tvVideoName?.visibility = View.VISIBLE
        analyzeVideo(uri)
    }

    /**
     * Bir IO o'tida: hajm (Storage 200 MB + tier limiti), davomiylik va LOCAL
     * preview kadri (MediaMetadataRetriever). Rasm poster yuklanmaydi — rules
     * `user_reels/` ostida faqat video contentType'ni yozishga qo'yadi.
     */
    private fun analyzeVideo(uri: Uri) {
        val ctx = context ?: return
        val s = scope ?: return
        s.launch {
            val info = withContext(Dispatchers.IO) {
                val size = try {
                    ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (e: Exception) {
                    -1L
                }
                var durationMs = 0L
                var frame: Bitmap? = null
                try {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(ctx, uri)
                        durationMs = mmr.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull() ?: 0L
                        // Qisqa videoda 1/3, uzun videoda ~1s belgidagi kadr
                        val usec = minOf(durationMs / 3, 1000L) * 1000L
                        frame = mmr.getFrameAtTime(usec)?.let { bmp ->
                            val maxSide = maxOf(bmp.width, bmp.height)
                            if (maxSide <= MAX_PREVIEW_PX) bmp else {
                                val ratio = MAX_PREVIEW_PX.toFloat() / maxSide
                                Bitmap.createScaledBitmap(
                                    bmp,
                                    (bmp.width * ratio).toInt().coerceAtLeast(1),
                                    (bmp.height * ratio).toInt().coerceAtLeast(1),
                                    true
                                )
                            }
                        }
                    } finally {
                        try {
                            mmr.release()
                        } catch (_: Exception) {
                        }
                    }
                } catch (e: Exception) {
                    // Preview topolmasa ham davom etamiz — yuklash islayveradi.
                }
                Triple(size, durationMs, frame)
            }
            val size = info.first
            // Storage qoidasi 200 MB, tier limiti 50/100 MB — tanlashda tekshiriladi.
            if (size > MAX_VIDEO_BYTES) {
                rejectVideo(getString(R.string.reels_video_too_large))
                return@launch
            }
            // Haftalik kvot (donor UserReelUploadManager): bepul 50 MB, premium 100 MB.
            val maxBytes = YuzReelQuota.maxFileBytes(premiumBoost())
            if (size > maxBytes) {
                rejectVideo(YuzReelQuota.sizeLimitMessage(premiumBoost(), size))
                return@launch
            }
            videoSizeBytes = size
            val frame = info.third
            if (frame != null) {
                ivVideoPreview?.setImageBitmap(frame)
                ivVideoPreview?.visibility = View.VISIBLE
                btnPickOverlay?.visibility = View.VISIBLE
                llPickPrompt?.visibility = View.GONE
            }
            val durMs = info.second
            if (durMs > 0L) {
                tvVideoDuration?.text = humanDuration(durMs)
                tvVideoDuration?.visibility = View.VISIBLE
            }
            val ext = videoDisplayName.substringAfterLast('.', "").uppercase()
            tvVideoMeta?.text = listOfNotNull(
                if (durMs > 0L) humanDuration(durMs) else null,
                if (size > 0L) YuzUploadUi.humanBytes(size) else null,
                ext.takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } }
            ).joinToString(" · ")
            tvVideoMeta?.visibility = View.VISIBLE
        }
    }

    /** Video rad etilganda maydonni bo'sh holatga qaytaradi. */
    private fun rejectVideo(message: String) {
        videoUri = null
        videoSizeBytes = -1L
        ivVideoPreview?.setImageDrawable(null)
        ivVideoPreview?.visibility = View.GONE
        btnPickOverlay?.visibility = View.GONE
        tvVideoDuration?.visibility = View.GONE
        tvVideoMeta?.visibility = View.GONE
        llPickPrompt?.visibility = View.VISIBLE
        tvPickVideoLabel?.text = getString(R.string.reels_pick_video)
        tvVideoName?.visibility = View.GONE
        toast(message)
    }

    private fun humanDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "kino, #top" → "#kino #top" — tartib saqlanadi, duplikatlar o'chadi. */
    private fun normalizeHashtags(raw: String): String {
        return raw.split(Regex("[,;\\s]+"))
            .map { it.trim().removePrefix("#") }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString(" ") { "#$it" }
    }

    private fun queryDisplayName(uri: Uri): String {
        val ctx = context ?: return uri.lastPathSegment.orEmpty()
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) cursor.getString(index) else null
                    } else null
                } ?: uri.lastPathSegment.orEmpty()
        } catch (e: Exception) {
            uri.lastPathSegment.orEmpty()
        }
    }

    private fun publishReel() {
        if (publishing) return
        val uri = videoUri
        if (uri == null) {
            toast(getString(R.string.reels_video_required))
            return
        }
        val caption = etCaption?.text?.toString()?.trim().orEmpty()
        if (caption.isBlank()) {
            etCaption?.error = getString(R.string.reels_caption_required)
            return
        }
        val hashtags = normalizeHashtags(etHashtags?.text?.toString().orEmpty())
        val account = currentAccount
        if (YuzGramAdsAuth.expectedUid(account).isBlank()) {
            toast(R.string.reels_sign_in_required)
            return
        }

        val user = UserConfig.getInstance(account).currentUser
        val uploaderName = listOfNotNull(
            user?.first_name?.takeIf { it.isNotBlank() },
            user?.last_name?.takeIf { it.isNotBlank() }
        ).joinToString(" ").orEmpty()

        publishing = true
        btnPublish?.isEnabled = false
        uploadProgress?.visibility = View.VISIBLE
        uploadProgress?.progress = 0
        tvUploadStatus?.visibility = View.VISIBLE
        tvUploadStatus?.text = getString(R.string.reels_uploading_video)
        YuzUploadUi.keepScreenOn(parentActivity, true)

        val s = scope ?: return
        s.launch {
            val id = System.currentTimeMillis().toString()
            // Fayl cho'zzisi tanlangan videodan olinadi — Storage qoidasi
            // `contentType` video/* bo'lishini talab qiladi, cho'zi esa uni belgilaydi.
            val rawExt = videoDisplayName.substringAfterLast('.', "mp4")
                .lowercase()
                .takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } }
            val ext = when (rawExt) {
                "mov", "mkv", "webm", "3gp", "mp4" -> rawExt!!
                else -> "mp4"
            }
            // Slot transaction bilan OLINDI — upload/save xatosida rollback
            // qilinadi (donor UserReelUploadManager xatti-harakati).
            var reservedUid: String? = null
            try {
                val uid = YuzGramAdsAuth.ensureSignedIn(account)
                val boosted = premiumBoost()
                val reservation = YuzReelQuota.reserveUploadSlot(uid, boosted)
                if (reservation.isFailure) {
                    unlockingAfterFailure()
                    val msg = reservation.exceptionOrNull()?.message
                        ?: YuzReelQuota.limitExceededMessage(boosted)
                    // Limit tugasa QAYTA URINISH foydasiz — dialog shunchaki xabar.
                    YuzUploadUi.showError(themedContext ?: context, msg, onRetry = null)
                    if (!boosted) {
                        // Donor'ning upsell yo'li: premium ekraniga o'tkazamiz.
                        presentFragment(YuzPremiumActivity(), false)
                    }
                    return@launch
                }
                reservedUid = uid
                val url = ReelStorageHelper.uploadVideo(
                    uid, "$id.$ext", uri
                ) { pct, transferred, total ->
                    uploadProgress?.progress = pct
                    tvUploadStatus?.text = getString(
                        R.string.reels_upload_progress,
                        pct,
                        YuzUploadUi.humanBytes(transferred),
                        YuzUploadUi.humanBytes(total)
                    )
                }
                tvUploadStatus?.text = getString(R.string.reels_saving)
                val reel = Reel(
                    id = id,
                    videoUrl = url,
                    thumbnailUrl = "",
                    views = 0,
                    title = caption,
                    hashtags = hashtags,
                    monetizationEnabled = switchMonetization?.isChecked == true,
                    commentCount = 0,
                    likeCount = 0,
                    createdAt = System.currentTimeMillis(),
                    uploadedByUid = uid,
                    uploadedByName = uploaderName,
                    uploadedByPhotoUrl = "",
                    isUserUpload = true
                )
                ReelsRepository.saveReel(reel)
                YuzUploadUi.keepScreenOn(parentActivity, false)
                toast(getString(R.string.reels_published))
                removeSelfFromStack()
            } catch (e: Exception) {
                reservedUid?.let { YuzReelQuota.releaseUploadSlot(it) }
                unlockingAfterFailure()
                val reason = e.message ?: getString(R.string.reels_publish_failed)
                // Uzun sabab 2 soniyalik toast'da o'qilmaydi — dialog + qayta urinish.
                YuzUploadUi.showError(themedContext ?: context, reason) { publishReel() }
            }
        }
    }

    /** UI qulflarini ochish (limit rad etilishi va yuklash xatoda bir xil). */
    private fun unlockingAfterFailure() {
        publishing = false
        btnPublish?.isEnabled = true
        uploadProgress?.visibility = View.GONE
        tvUploadStatus?.visibility = View.GONE
        YuzUploadUi.keepScreenOn(parentActivity, false)
    }

    /**
     * Yuklash ketayotganda orqaga qaytish scope'ni buzadi — yuklash abadiy
     * "yuqolgan" bo'ladi. BaseFragment: true = fragment yopiladi, false = bizni.
     */
    override fun onBackPressed(): Boolean {
        if (publishing) {
            toast(getString(R.string.reels_uploading_video))
            return false
        }
        return super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        actionBar?.visibility = View.GONE
        // Premium ekranidan qaytilgan bo'lishi mumkin — limit label'ini yangilaymiz.
        refreshQuotaLabel()
    }

    override fun onFragmentDestroy() {
        scope?.cancel()
        scope = null
        YuzUploadUi.keepScreenOn(parentActivity, false)
        super.onFragmentDestroy()
    }

    private fun toast(text: String) {
        val ctx = parentActivity ?: context ?: return
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    private fun toast(resId: Int) {
        val ctx = themedContext ?: context ?: return
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }

    // View'da getString() yo'q (faqat Context'da bor) — shuning uchun o'z helperimiz.
    private fun getString(resId: Int): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId)
    }

    /** Formatlangan matnlar uchun (`%1$d%% · 12.4 MB / 36.0 MB`). */
    private fun getString(resId: Int, vararg args: Any): String {
        val ctx = themedContext ?: context ?: return ""
        return ctx.getString(resId, *args)
    }
}
