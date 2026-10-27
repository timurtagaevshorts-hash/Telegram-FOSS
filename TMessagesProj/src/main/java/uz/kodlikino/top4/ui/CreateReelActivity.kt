package uz.kodlikino.top4.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.services.ReelUploadService
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzPremiumManager
import uz.kodlikino.top4.utils.YuzReelQuota
import uz.kodlikino.top4.utils.YuzReelUploads
import uz.kodlikino.top4.utils.YuzUploadUi
import java.io.File
import java.io.FileOutputStream

/**
 * "Reel joylash" — video tanlash, nom/tavsif yozish va yuklashni fonda
 * boshlash ekrani.
 *
 * Yuklashning o'zi [ReelUploadService] ichida ketadi: ekran darhol yopiladi,
 * progress bildirishnomada va profil > Reels grid'idagi "yuklanmoqda" katagida
 * ko'rinadi ([YuzReelUploads] trekeri).
 *
 * Muhim chegaralar (Firebase qoidalari bilan bir xil):
 *  - Storage yo'li `user_reels/{uid}/{file}`, contentType video turidan, hajm
 *    200 MB dan kichik ([MAX_VIDEO_BYTES]) — aks holda qoidalar yozuvni rad etadi;
 *  - `uploadedByUid` = `tg_<clientUserId>` ([YuzGramAdsAuth]) — Firestore create
 *    qoidasi aynan shu identifikatorni talab qiladi.
 *
 * Poster rasm yuklanmaydi (rules faqat video contentType'ga ruxsat beradi):
 * tanlangan videoning birinchi kadri lokal saqlanadi va faqat yuklash jarayonida
 * profildagi katakda ko'rsatiladi.
 *
 * Kunlik kvota va hajm limiti admin panelda (Limitlar tabi) oddiy va premium
 * foydalanuvchilar uchun alohida — [YuzReelQuota].
 */
class CreateReelActivity : BaseFragment() {

    companion object {
        private const val REQ_PICK_VIDEO = 4481
        private const val MAX_VIDEO_BYTES = 200L * 1024L * 1024L

        /** Preview kadri ekranda va grid katagida yetarli — memory uchun kichraytiriladi. */
        private const val MAX_PREVIEW_PX = 540
    }

    private var themedContext: Context? = null
    private var toolbar: Toolbar? = null
    private var btnPickVideo: View? = null
    private var llPickPrompt: View? = null
    private var ivVideoPreview: ImageView? = null
    private var previewScrim: View? = null
    private var btnPickOverlay: View? = null
    private var tvVideoDuration: TextView? = null
    private var tvVideoMeta: TextView? = null
    private var progressAnalyze: ProgressBar? = null
    private var tvPickVideoLabel: TextView? = null
    private var tvMediaPlaceholder: TextView? = null
    private var tvVideoName: TextView? = null
    private var quotaCard: View? = null
    private var tvQuota: TextView? = null
    private var etReelName: TextInputEditText? = null
    private var etCaption: TextInputEditText? = null
    private var btnPublish: MaterialButton? = null

    private var videoUri: Uri? = null
    private var videoDisplayName: String = ""
    private var videoSizeBytes: Long = -1L
    private var previewFrame: Bitmap? = null

    private var scope: CoroutineScope? = null
    private var publishing = false
    /** Video tekshiruvi (hajm/davomiylik/kadr) tugamaguncha joylash bloklanadi. */
    private var analyzing = false
    /** Har tanlovda oshadi — eski tekshiruv natijasi yangisini bosib ketmaydi. */
    private var analyzeToken = 0

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
        previewScrim = root.findViewById(R.id.previewScrim)
        btnPickOverlay = root.findViewById(R.id.btnPickOverlay)
        tvVideoDuration = root.findViewById(R.id.tvVideoDuration)
        tvVideoMeta = root.findViewById(R.id.tvVideoMeta)
        progressAnalyze = root.findViewById(R.id.progressAnalyze)
        tvPickVideoLabel = root.findViewById(R.id.tvPickVideoLabel)
        tvMediaPlaceholder = root.findViewById(R.id.tvMediaPlaceholder)
        tvVideoName = root.findViewById(R.id.tvVideoName)
        quotaCard = root.findViewById(R.id.quotaCard)
        tvQuota = root.findViewById(R.id.tvQuotaInfo)
        etReelName = root.findViewById(R.id.etReelName)
        etCaption = root.findViewById(R.id.etCaption)
        btnPublish = root.findViewById(R.id.btnPublish)

        toolbar?.let { bar ->
            YuzTopInset.applyPadding(bar)
            bar.setNavigationOnClickListener { removeSelfFromStack() }
        }

        btnPickVideo?.setOnClickListener { pickVideo() }
        btnPublish?.setOnClickListener { publishReel() }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                refreshPublishState()
            }
        }
        etReelName?.addTextChangedListener(watcher)
        etCaption?.addTextChangedListener(watcher)

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        refreshPublishState()
        refreshQuotaLabel()
        return root
    }

    /** To'liq Premium — admin panelda belgilangan premium limitlar. */
    private fun premiumBoost(): Boolean {
        val ctx = themedContext ?: context ?: return false
        return YuzPremiumManager.from(ctx).hasReelsBoost(currentAccount)
    }

    /**
     * "Bugungi limit: 4/5 ta · 50 MB gacha" — admin panel sozlagan limitlar
     * (`settings/app`) va Firestore kvota hujjati asosida. Hujjat yo'q/xato
     * bo'lsa 0 ishlatilgan hisoblanadi (limitni sun'iy qisqartirmaydi).
     */
    private fun refreshQuotaLabel() {
        val s = scope ?: return
        val uid = YuzGramAdsAuth.expectedUid(currentAccount)
        if (uid.isBlank()) {
            quotaCard?.visibility = View.GONE
            return
        }
        val boosted = premiumBoost()
        s.launch {
            val remaining = YuzReelQuota.getRemainingUploads(uid, boosted, force = true)
            tvQuota?.text = YuzReelQuota.quotaSummaryLabel(boosted, remaining)
            quotaCard?.visibility = View.VISIBLE
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
        tvMediaPlaceholder?.visibility = View.GONE
        tvVideoName?.text = videoDisplayName
        tvVideoName?.visibility = View.VISIBLE
        tvVideoMeta?.visibility = View.GONE
        analyzeVideo(uri)
    }

    /**
     * Bir IO o'tida: hajm (Storage 200 MB + tier limiti), davomiylik va lokal
     * preview kadri (MediaMetadataRetriever). Kadr keyinchalik profildagi
     * "yuklanmoqda" katagiga ham yoziladi.
     */
    private fun analyzeVideo(uri: Uri) {
        val ctx = context ?: return
        val s = scope ?: return
        val token = ++analyzeToken
        analyzing = true
        videoSizeBytes = -1L
        previewFrame = null
        progressAnalyze?.visibility = View.VISIBLE
        refreshPublishState()
        s.launch {
            val info = withContext(Dispatchers.IO) {
                // ContentResolver'dan aniq hajm: OpenableColumns.SIZE + fd.statSize.
                // Eski openAssetFileDescriptor.length ba'zi provayderlarda -1
                // qaytarardi — hajm limiti va siqish qarori buzilardi.
                val size = YuzReelQuota.resolveUriSizeBytes(ctx, uri)
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
            // Foydalanuvchi shu orada boshqa video tanlagan bo'lsa — eski natija tashlanadi.
            if (token != analyzeToken) return@launch
            progressAnalyze?.visibility = View.GONE
            val size = info.first
            // Storage qoidasi 200 MB, tier limiti 50/100 MB — tanlashda tekshiriladi.
            if (size > MAX_VIDEO_BYTES) {
                rejectVideo(getString(R.string.reels_video_too_large))
                return@launch
            }
            // Kadr va meta avval ko'rsatiladi: limitlar Firestore'dan kelishini
            // kutish sekin tarmoqda ekranni bo'sh qoldirardi.
            videoSizeBytes = size
            val frame = info.third
            previewFrame = frame
            if (frame != null) {
                ivVideoPreview?.apply {
                    setImageBitmap(frame)
                    alpha = 0f
                    visibility = View.VISIBLE
                    animate().alpha(1f).setDuration(180L).start()
                }
                previewScrim?.visibility = View.VISIBLE
                btnPickOverlay?.visibility = View.VISIBLE
                llPickPrompt?.visibility = View.GONE
            }
            val durMs = info.second
            if (durMs > 0L) {
                tvVideoDuration?.text = humanDuration(durMs)
                tvVideoDuration?.visibility = View.VISIBLE
            }
            val ext = videoDisplayName.substringAfterLast('.', "").uppercase()
            val metaText = listOfNotNull(
                if (durMs > 0L) humanDuration(durMs) else null,
                if (size > 0L) YuzUploadUi.humanBytes(size) else null,
                ext.takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } }
            ).joinToString(" · ")
            tvVideoMeta?.text = metaText.ifBlank { getString(R.string.reels_media_empty_hint) }
            tvVideoMeta?.visibility = View.VISIBLE

            // Kunlik/hajm limitlari — admin panel sozlamasidan (settings/app).
            // Kesh iliq bo'lsa darhol; sovuq bo'lsa tarmoq javobi kutiladi.
            YuzReelQuota.loadLimits()
            if (token != analyzeToken) return@launch
            val maxBytes = YuzReelQuota.maxFileBytes(premiumBoost())
            if (size > 0L && size > maxBytes) {
                rejectVideo(YuzReelQuota.sizeLimitMessage(premiumBoost(), size))
                return@launch
            }
            analyzing = false
            refreshPublishState()
        }
    }

    /** Video rad etilganda maydonni bo'sh holatga qaytaradi. */
    private fun rejectVideo(message: String) {
        analyzing = false
        videoUri = null
        videoSizeBytes = -1L
        previewFrame = null
        ivVideoPreview?.animate()?.cancel()
        ivVideoPreview?.alpha = 1f
        ivVideoPreview?.setImageDrawable(null)
        ivVideoPreview?.visibility = View.GONE
        previewScrim?.visibility = View.GONE
        btnPickOverlay?.visibility = View.GONE
        tvVideoDuration?.visibility = View.GONE
        tvMediaPlaceholder?.visibility = View.VISIBLE
        tvVideoName?.visibility = View.GONE
        tvVideoMeta?.text = getString(R.string.reels_media_empty_hint)
        tvVideoMeta?.visibility = View.VISIBLE
        llPickPrompt?.visibility = View.VISIBLE
        tvPickVideoLabel?.text = getString(R.string.reels_pick_video)
        refreshPublishState()
        toast(message)
    }

    private fun humanDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
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

    private fun refreshPublishState() {
        val ready = videoUri != null && !analyzing && !etReelName?.text.isNullOrBlank()
        btnPublish?.isEnabled = ready && !publishing
        btnPublish?.alpha = if (ready && !publishing) 1f else 0.45f
    }

    private fun publishReel() {
        if (publishing || analyzing) return
        val uri = videoUri
        if (uri == null) {
            toast(getString(R.string.reels_video_required))
            return
        }
        val name = etReelName?.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            etReelName?.error = getString(R.string.reels_name_required)
            return
        }
        val description = etCaption?.text?.toString()?.trim().orEmpty()
        // Firestore'da alohida `description` maydoni yo'q — nom va tavsif bitta
        // `title` sifatida saqlanadi (feed'da caption bo'lib ko'rinadi).
        // Hashtag maydoni yo'q: kerak bo'lsa tavsif ichida yoziladi.
        val title = if (description.isBlank()) name else "$name\n\n$description"
        val account = currentAccount
        if (YuzGramAdsAuth.expectedUid(account).isBlank()) {
            toast(R.string.reels_sign_in_required)
            return
        }
        val ctx = context ?: return
        val s = scope ?: return

        publishing = true
        refreshPublishState()
        btnPublish?.text = getString(R.string.reels_publishing)
        val id = System.currentTimeMillis().toString()
        val frame = previewFrame

        s.launch {
            val uid = YuzGramAdsAuth.expectedUid(account)
            val boosted = premiumBoost()
            val remaining = YuzReelQuota.getRemainingUploads(uid, boosted, force = true)
            if (remaining <= 0) {
                publishing = false
                btnPublish?.text = getString(R.string.reels_publish)
                refreshPublishState()
                toast(YuzReelQuota.limitExceededMessage(boosted))
                refreshQuotaLabel()
                return@launch
            }
            val thumbPath = withContext(Dispatchers.IO) {
                saveThumbnail(frame, YuzReelUploads.thumbPathFor(id))
            }
            val now = System.currentTimeMillis()
            YuzReelUploads.begin(
                YuzReelUploads.Upload(
                    id = id,
                    uri = uri.toString(),
                    fileName = videoDisplayName,
                    title = title,
                    hashtags = "",
                    sizeBytes = videoSizeBytes,
                    thumbPath = thumbPath,
                    account = account,
                    startedAt = now,
                    updatedAt = now
                )
            )
            try {
                ReelUploadService.startUpload(
                    context = ctx,
                    uploadId = id,
                    videoUri = uri,
                    videoName = videoDisplayName,
                    videoSize = videoSizeBytes,
                    caption = title,
                    hashtags = "",
                    thumbPath = thumbPath,
                    account = account
                )
            } catch (e: Exception) {
                // Servisni ishga tushirish taqiqlangan bo'lsa (fon cheklovi) —
                // trekerda "yuklanmoqda" osilib qolmasin.
                YuzReelUploads.fail(id, getString(R.string.reels_publish_failed))
                publishing = false
                btnPublish?.text = getString(R.string.reels_publish)
                refreshPublishState()
                toast(getString(R.string.reels_publish_failed))
                return@launch
            }
            toast(getString(R.string.reels_uploading_in_background))
            removeSelfFromStack()
        }
    }

    /** Preview kadrini profildagi "yuklanmoqda" katagi uchun JPEG qilib saqlaydi. */
    private fun saveThumbnail(bitmap: Bitmap?, path: String): String {
        if (bitmap == null || path.isBlank()) return ""
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Yuklash fonda ketadi — ekran yopilishi mumkin.
     * BaseFragment: true = fragment yopiladi, false = bizni.
     */
    override fun onBackPressed(): Boolean {
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
