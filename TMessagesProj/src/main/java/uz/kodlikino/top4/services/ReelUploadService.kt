package uz.kodlikino.top4.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.firebase.storage.UploadTask
import kotlinx.coroutines.*
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.utils.ReelStorageHelper
import uz.kodlikino.top4.utils.ReelsRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzPremiumManager
import uz.kodlikino.top4.utils.YuzReelCompressor
import uz.kodlikino.top4.utils.YuzReelQuota
import uz.kodlikino.top4.utils.YuzUploadWatchdog
import java.io.File

/**
 * Reel yuklashni fon davom ettiradigan service.
 *
 * Muammo: CreateReelActivity yopilganda coroutine scope bekor qilinadi va yuklash
 * uziladi. Foydalanuvchi "yuklanmoqda" ekranida qotib qoladi — boshqa ish qila olmaydi.
 *
 * Yechim: Foreground Service — yuklash activity yopilganda ham davom etadi.
 * Notification'da progress ko'rsatiladi. Tugagach notification "Reel joylandi" ga
 * o'zgaradi va bosilganda ReelsActivity ochiladi.
 *
 * Instagram uslubida: foydalanuvchi video tanlab, ma'lumotlarni kiritib "Joylash"ni
 * bossin — ekran yopiladi, yuklash fonda ketadi. Notification'da progress ko'radi.
 */
class ReelUploadService : android.app.Service() {

    companion object {
        const val ACTION_UPLOAD = "uz.kodlikino.top4.action.UPLOAD_REEL"
        const val ACTION_CANCEL = "uz.kodlikino.top4.action.CANCEL_UPLOAD"
        const val EXTRA_VIDEO_URI = "video_uri"
        const val EXTRA_VIDEO_NAME = "video_name"
        const val EXTRA_VIDEO_SIZE = "video_size"
        const val EXTRA_CAPTION = "caption"
        const val EXTRA_HASHTAGS = "hashtags"
        const val EXTRA_MONETIZATION = "monetization"
        const val EXTRA_ACCOUNT = "account"

        private const val NOTIFICATION_ID = 20261022
        private const val CHANNEL_ID = "reel_upload"
        private const val CHANNEL_NAME = "Reel yuklash"

        @Volatile
        private var activeUpload: UploadTask? = null

        @Volatile
        private var isUploading = false

        fun isUploading(): Boolean = isUploading

        fun startUpload(
            context: Context,
            videoUri: Uri,
            videoName: String,
            videoSize: Long,
            caption: String,
            hashtags: String,
            monetization: Boolean,
            account: Int
        ) {
            val intent = Intent(context, ReelUploadService::class.java).apply {
                action = ACTION_UPLOAD
                putExtra(EXTRA_VIDEO_URI, videoUri.toString())
                putExtra(EXTRA_VIDEO_NAME, videoName)
                putExtra(EXTRA_VIDEO_SIZE, videoSize)
                putExtra(EXTRA_CAPTION, caption)
                putExtra(EXTRA_HASHTAGS, hashtags)
                putExtra(EXTRA_MONETIZATION, monetization)
                putExtra(EXTRA_ACCOUNT, account)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelUpload(context: Context) {
            val intent = Intent(context, ReelUploadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "YuzGram:ReelUpload"
        ).apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10 daqiqa maksimal
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPLOAD -> {
                if (isUploading) {
                    // Allaqachon yuklayapti — qayta boshlamaymiz
                    return START_NOT_STICKY
                }
                val videoUriStr = intent.getStringExtra(EXTRA_VIDEO_URI) ?: return START_NOT_STICKY
                val videoUri = Uri.parse(videoUriStr)
                val videoName = intent.getStringExtra(EXTRA_VIDEO_NAME) ?: "video.mp4"
                val videoSize = intent.getLongExtra(EXTRA_VIDEO_SIZE, 0L)
                val caption = intent.getStringExtra(EXTRA_CAPTION) ?: ""
                val hashtags = intent.getStringExtra(EXTRA_HASHTAGS) ?: ""
                val monetization = intent.getBooleanExtra(EXTRA_MONETIZATION, true)
                val account = intent.getIntExtra(EXTRA_ACCOUNT, UserConfig.selectedAccount)

                startForeground(NOTIFICATION_ID, createProgressNotification(0, "Tayyorlanmoqda..."))
                performUpload(
                    videoUri, videoName, videoSize, caption, hashtags, monetization, account
                )
            }
            ACTION_CANCEL -> {
                activeUpload?.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun performUpload(
        videoUri: Uri,
        videoName: String,
        videoSize: Long,
        caption: String,
        hashtags: String,
        monetization: Boolean,
        account: Int
    ) {
        isUploading = true
        scope.launch {
            val id = System.currentTimeMillis().toString()
            val rawExt = videoName.substringAfterLast('.', "mp4")
                .lowercase()
                .takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } }
            val ext = when (rawExt) {
                "mov", "mkv", "webm", "3gp", "mp4" -> rawExt!!
                else -> "mp4"
            }

            var reservedUid: String? = null
            var compressedFile: File? = null
            val boosted = YuzPremiumManager.from(this@ReelUploadService).hasReelsBoost(account)

            try {
                // Firebase auth
                updateNotification(0, "Kirish tekshirilmoqda...")
                val uid = YuzGramAdsAuth.ensureSignedIn(account)

                // Kvota tekshirish
                val reservation = YuzReelQuota.reserveUploadSlot(uid, boosted)
                if (reservation.isFailure) {
                    val msg = reservation.exceptionOrNull()?.message
                        ?: YuzReelQuota.limitExceededMessage(boosted)
                    showErrorNotification(msg)
                    stopSelf()
                    return@launch
                }
                reservedUid = uid

                // Siqish
                updateNotification(0, "Video siqilmoqda...")
                compressedFile = try {
                    YuzReelCompressor.compress(
                        context = applicationContext,
                        uri = videoUri,
                        sizeBytes = videoSize,
                        targetBytes = if (boosted) 24L * 1024L * 1024L else 12L * 1024L * 1024L,
                        onProgress = { pct ->
                            updateNotification(pct / 2, "Siqish: $pct%") // 0-50%
                        },
                        isCanceled = { !isUploading }
                    )
                } catch (e: Exception) {
                    FileLog.e(e)
                    null
                }

                // Yuklash
                updateNotification(50, "Yuklanmoqda...")
                val uploadProgressListener: (Int, Long, Long) -> Unit = { pct, transferred, total ->
                    val progress = 50 + (pct / 2) // 50-100%
                    val transferredMB = transferred / (1024 * 1024)
                    val totalMB = total / (1024 * 1024)
                    updateNotification(progress, "Yuklanmoqda: $transferredMB/$totalMB MB")
                }

                val url = if (compressedFile != null) {
                    ReelStorageHelper.uploadVideo(
                        uid, "$id.mp4", compressedFile!!, uploadProgressListener
                    )
                } else {
                    ReelStorageHelper.uploadVideo(uid, "$id.$ext", videoUri, uploadProgressListener)
                }

                // Firestore'ga saqlash
                updateNotification(100, "Saqlanmoqda...")
                val user = UserConfig.getInstance(account).currentUser
                val uploaderName = listOfNotNull(
                    user?.first_name?.takeIf { it.isNotBlank() },
                    user?.last_name?.takeIf { it.isNotBlank() }
                ).joinToString(" ").orEmpty()

                val reel = Reel(
                    id = id,
                    videoUrl = url,
                    thumbnailUrl = "",
                    views = 0,
                    title = caption,
                    hashtags = hashtags,
                    monetizationEnabled = monetization,
                    commentCount = 0,
                    likeCount = 0,
                    createdAt = System.currentTimeMillis(),
                    uploadedByUid = uid,
                    uploadedByName = uploaderName,
                    uploadedByPhotoUrl = "",
                    isUserUpload = true
                )
                ReelsRepository.saveReel(reel)

                // Muvaffaqiyat
                showSuccessNotification()
                NotificationCenter.getInstance().postNotificationName(
                    NotificationCenter.didReceiveNewMessage, 0, 0
                ) // Reels feed'ni yangilash
            } catch (e: Exception) {
                FileLog.e(e)
                reservedUid?.let {
                    try {
                        YuzReelQuota.releaseUploadSlot(it, boosted)
                    } catch (_: Exception) {
                    }
                }
                val reason = e.message ?: "Reel joylanmadi"
                showErrorNotification(reason)
            } finally {
                try {
                    compressedFile?.delete()
                } catch (_: Exception) {
                }
                isUploading = false
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Reel yuklash holati"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun createProgressNotification(progress: Int, text: String): Notification {
        val intent = Intent(this, org.telegram.ui.LaunchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, ReelUploadService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reel yuklanmoqda")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .addAction(R.drawable.ic_close, "Bekor qilish", cancelPendingIntent)
            .build()
    }

    private fun updateNotification(progress: Int, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createProgressNotification(progress, text))
    }

    private fun showSuccessNotification() {
        val intent = Intent(this, org.telegram.ui.LaunchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reel joylandi")
            .setContentText("Reel profilingizga qo'shildi")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(message: String) {
        val intent = Intent(this, org.telegram.ui.LaunchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reel joylanmadi")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        scope.cancel()
    }
}
