package uz.kodlikino.top4.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.ActionBar.BaseFragment
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.utils.ReelStorageHelper
import uz.kodlikino.top4.utils.ReelsRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth
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
 * video contentType'ga ruxsat beradi. Feed'da poster o'rniga birinchi kadr kutiladi.
 */
class CreateReelActivity : BaseFragment() {

    companion object {
        private const val REQ_PICK_VIDEO = 4481
        private const val MAX_VIDEO_BYTES = 200L * 1024L * 1024L
    }

    private var themedContext: Context? = null
    private var toolbar: Toolbar? = null
    private var btnPickVideo: View? = null
    private var tvPickVideoLabel: TextView? = null
    private var tvVideoName: TextView? = null
    private var etCaption: TextInputEditText? = null
    private var etHashtags: TextInputEditText? = null
    private var uploadProgress: ProgressBar? = null
    private var tvUploadStatus: TextView? = null
    private var btnPublish: MaterialButton? = null

    private var videoUri: Uri? = null
    private var videoDisplayName: String = ""

    private var scope: CoroutineScope? = null
    private var publishing = false

    override fun createView(context: Context): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_YuzGramAds)
        themedContext = themed
        val root = LayoutInflater.from(themed)
            .inflate(R.layout.activity_create_reel, null) as ViewGroup
        fragmentView = root

        actionBar?.visibility = View.GONE

        toolbar = root.findViewById(R.id.toolbar)
        btnPickVideo = root.findViewById(R.id.btnPickVideo)
        tvPickVideoLabel = root.findViewById(R.id.tvPickVideoLabel)
        tvVideoName = root.findViewById(R.id.tvVideoName)
        etCaption = root.findViewById(R.id.etCaption)
        etHashtags = root.findViewById(R.id.etHashtags)
        uploadProgress = root.findViewById(R.id.uploadProgress)
        tvUploadStatus = root.findViewById(R.id.tvUploadStatus)
        btnPublish = root.findViewById(R.id.btnPublish)

        toolbar?.let { bar ->
            bar.setPadding(
                bar.paddingLeft,
                AndroidUtilities.statusBarHeight,
                bar.paddingRight,
                bar.paddingBottom
            )
            bar.setNavigationOnClickListener { removeSelfFromStack() }
        }

        btnPickVideo?.setOnClickListener { pickVideo() }
        btnPublish?.setOnClickListener { publishReel() }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        return root
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
        tvPickVideoLabel?.text = getString(R.string.reels_change_video)
        tvVideoName?.text = videoDisplayName
        tvVideoName?.visibility = View.VISIBLE
        checkVideoSize(uri)
    }

    /** Firestore/Storage qoidalari 200 MB dan kichik videoni talab qiladi. */
    private fun checkVideoSize(uri: Uri) {
        val ctx = context ?: return
        val s = scope ?: return
        s.launch {
            val size = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (e: Exception) {
                    -1L
                }
            }
            if (size > MAX_VIDEO_BYTES) {
                videoUri = null
                tvPickVideoLabel?.text = getString(R.string.reels_pick_video)
                tvVideoName?.visibility = View.GONE
                toast(getString(R.string.reels_video_too_large))
            }
        }
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
        val hashtags = etHashtags?.text?.toString()?.trim().orEmpty()
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
            try {
                val uid = YuzGramAdsAuth.ensureSignedIn(account)
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
                    monetizationEnabled = true,
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
                publishing = false
                btnPublish?.isEnabled = true
                uploadProgress?.visibility = View.GONE
                tvUploadStatus?.visibility = View.GONE
                YuzUploadUi.keepScreenOn(parentActivity, false)
                val reason = e.message ?: getString(R.string.reels_publish_failed)
                // Uzun sabab 2 soniyalik toast'da o'qilmaydi — dialog + qayta urinish.
                YuzUploadUi.showError(themedContext ?: context, reason) { publishReel() }
            }
        }
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
