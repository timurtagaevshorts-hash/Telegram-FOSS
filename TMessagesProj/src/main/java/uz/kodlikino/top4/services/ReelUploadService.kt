package uz.kodlikino.top4.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.utils.ReelStorageHelper
import uz.kodlikino.top4.utils.ReelsRepository
import uz.kodlikino.top4.utils.YuzGramAdsAuth
import uz.kodlikino.top4.utils.YuzPremiumManager
import uz.kodlikino.top4.utils.YuzReelCompressor
import uz.kodlikino.top4.utils.YuzReelQuota
import uz.kodlikino.top4.utils.YuzReelUploads
import java.io.File

/**
 * Reel yuklashni fon davom ettiradigan service.
 *
 * Yuklash ekrani (CreateReelActivity) "Joylash" bosilishi bilan yopiladi va
 * ish shu servisga o'tadi: siqish → Storage'ga yuklash → Firestore'ga yozish.
 * Har bir qadam [YuzReelUploads] trekeriga yoziladi, shuning uchun profil >
 * Reels grid'ida "yuklanmoqda" katagi progress bilan turadi.
 *
 * Navbat: avvalgi versiyada bitta global `isUploading` bayrog'i bor edi va
 * ikkinchi reel jimgina yo'qolardi. Endi so'rovlar navbatga qo'yiladi va
 * ketma-ket bajariladi — foydalanuvchi ketma-ket bir nechta reel joylashi
 * mumkin, bekor qilish esa aynan bitta yuklashni (id bo'yicha) to'xtatadi.
 *
 * Tezlik: siqish nishoni kichraytirildi (9 MB oddiy / 16 MB premium) — mobil
 * tarmoqda yuklash vaqti qisqaradi, sifat esa 720p uchun yetarli.
 */
class ReelUploadService : Service() {

    companion object {
        const val ACTION_UPLOAD = "uz.kodlikino.top4.action.UPLOAD_REEL"
        const val ACTION_CANCEL = "uz.kodlikino.top4.action.CANCEL_UPLOAD"
        const val EXTRA_UPLOAD_ID = "upload_id"
        const val EXTRA_VIDEO_URI = "video_uri"
        const val EXTRA_VIDEO_NAME = "video_name"
        const val EXTRA_VIDEO_SIZE = "video_size"
        const val EXTRA_CAPTION = "caption"
        const val EXTRA_HASHTAGS = "hashtags"
        const val EXTRA_THUMB_PATH = "thumb_path"
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_SOURCE_PATH = "source_path"

        private const val NOTIFICATION_ID = 20261022
        private const val CHANNEL_ID = "reel_upload"
        private const val CHANNEL_NAME = "Reel yuklash"

        /**
         * Tanlangan videoning lokal nusxasi shu papkada turadi. Nusxa birinchi
         * qadamda olinadi: content-URI ruxsati jarayon o'lsa yo'qoladi, kesh
         * fayli esa qoladi — yuklash xato bilan tugasa [YuzReelUploads.retry]
         * videoni qaytadan tanlashsiz davom ettiradi.
         */
        private const val QUEUE_DIR = "reel_queue"

        /** Siqish nishonlari — kichikroq fayl, tezroq yuklash. */
        private const val TARGET_BYTES_REGULAR = 9L * 1024L * 1024L
        private const val TARGET_BYTES_PREMIUM = 16L * 1024L * 1024L
        private const val SAVE_ATTEMPTS = 2

        /** Treker va bildirishnomani har tikda yangilamaslik chegaralari. */
        private const val TRACKER_TICK_MS = 700L
        private const val NOTIFICATION_TICK_MS = 500L

        @Volatile
        private var runningCount = 0

        /** Servisda navbatda yoki bajarilayotgan yuklash bormi. */
        fun isUploading(): Boolean = runningCount > 0

        fun startUpload(
            context: Context,
            uploadId: String,
            videoUri: Uri,
            videoName: String,
            videoSize: Long,
            caption: String,
            hashtags: String,
            thumbPath: String,
            account: Int,
            sourcePath: String = ""
        ) {
            val intent = Intent(context, ReelUploadService::class.java).apply {
                action = ACTION_UPLOAD
                putExtra(EXTRA_UPLOAD_ID, uploadId)
                putExtra(EXTRA_VIDEO_URI, videoUri.toString())
                putExtra(EXTRA_VIDEO_NAME, videoName)
                putExtra(EXTRA_VIDEO_SIZE, videoSize)
                putExtra(EXTRA_CAPTION, caption)
                putExtra(EXTRA_HASHTAGS, hashtags)
                putExtra(EXTRA_THUMB_PATH, thumbPath)
                putExtra(EXTRA_ACCOUNT, account)
                putExtra(EXTRA_SOURCE_PATH, sourcePath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Aynan [uploadId] yuklashini bekor qiladi. Servis ishlamayotgan bo'lsa
         * chaqirilmaydi: `startService` fon holatida istisno beradi, trekerdagi
         * yozuvni esa [YuzReelUploads.dismiss] o'zi olib tashlaydi.
         */
        fun cancelUpload(context: Context?, uploadId: String) {
            if (uploadId.isBlank() || !isUploading()) return
            val ctx = context ?: ApplicationLoader.applicationContext ?: return
            val intent = Intent(ctx, ReelUploadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_UPLOAD_ID, uploadId)
            }
            try {
                ctx.startService(intent)
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }
    }

    private data class Request(
        val id: String,
        val uri: Uri,
        val name: String,
        val size: Long,
        val caption: String,
        val hashtags: String,
        val thumbPath: String,
        val account: Int,
        /** Oldindan tayyorlangan lokal nusxa (retry yo'li) — bo'sh bo'lsa servis o'zi yaratadi. */
        val sourcePath: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = ArrayDeque<Request>()
    private var current: Request? = null
    private var currentJob: Job? = null

    @Volatile
    private var currentCancelled = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastTrackerAt = 0L
    private var lastNotificationAt = 0L
    private var lastPhase: YuzReelUploads.Phase? = null
    /** Oxirgi start so'rovi — navbat bo'shaganda faqat shu id bilan to'xtaymiz. */
    private var lastStartId = 0

    private var doneCount = 0
    private var lastDoneName = ""
    private var lastError = ""

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
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_UPLOAD -> {
                // startForegroundService → 5 soniya ichida startForeground shart.
                startForegroundSafe(getString(R.string.reels_upload_phase_prepare))
                val request = parseRequest(intent)
                if (request == null) {
                    if (currentJob == null && queue.isEmpty()) finishService()
                    return START_NOT_STICKY
                }
                if (current?.id == request.id || queue.any { it.id == request.id }) {
                    return START_NOT_STICKY
                }
                queue.addLast(request)
                pump()
            }
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_UPLOAD_ID).orEmpty()
                if (id.isNotBlank()) cancel(id)
            }
        }
        return START_NOT_STICKY
    }

    private fun parseRequest(intent: Intent): Request? {
        val uriStr = intent.getStringExtra(EXTRA_VIDEO_URI) ?: return null
        val id = intent.getStringExtra(EXTRA_UPLOAD_ID)?.takeIf { it.isNotBlank() }
            ?: System.currentTimeMillis().toString()
        return Request(
            id = id,
            uri = Uri.parse(uriStr),
            name = intent.getStringExtra(EXTRA_VIDEO_NAME) ?: "video.mp4",
            size = intent.getLongExtra(EXTRA_VIDEO_SIZE, 0L),
            caption = intent.getStringExtra(EXTRA_CAPTION) ?: "",
            hashtags = intent.getStringExtra(EXTRA_HASHTAGS) ?: "",
            thumbPath = intent.getStringExtra(EXTRA_THUMB_PATH) ?: "",
            account = intent.getIntExtra(EXTRA_ACCOUNT, UserConfig.selectedAccount),
            sourcePath = intent.getStringExtra(EXTRA_SOURCE_PATH) ?: ""
        )
    }

    /** Navbatdan keyingi so'rovni olib, yuklashni boshlaydi. */
    private fun pump() {
        if (currentJob != null) return
        val next = queue.removeFirstOrNull()
        if (next == null) {
            finishService()
            return
        }
        current = next
        currentCancelled = false
        lastPhase = null
        lastTrackerAt = 0L
        lastNotificationAt = 0L
        runningCount = 1
        acquireWakeLock()
        currentJob = scope.launch {
            try {
                performUpload(next)
            } finally {
                currentJob = null
                current = null
                currentCancelled = false
                queue.removeAll { it.id == next.id }
                pump()
            }
        }
    }

    private suspend fun performUpload(req: Request) {
        var reservedUid: String? = null
        var slotReserved = false
        var published = false
        var compressedFile: File? = null
        val boosted = YuzPremiumManager.from(this).hasReelsBoost(req.account)

        try {
            report(req, 2, YuzReelUploads.Phase.PREPARE,
                getString(R.string.reels_upload_phase_prepare), force = true)

            if (!ApplicationLoader.isNetworkOnline()) {
                throw UploadFailure(getString(R.string.reels_upload_offline))
            }

            val uid = YuzGramAdsAuth.ensureSignedIn(req.account)

            // Kvota — admin panel limitlari majburiy yangilanadi.
            val reservation = YuzReelQuota.reserveUploadSlot(uid, boosted)
            if (reservation.isFailure) {
                throw UploadFailure(
                    reservation.exceptionOrNull()?.message
                        ?: YuzReelQuota.limitExceededMessage(boosted)
                )
            }
            reservedUid = uid
            slotReserved = reservation.getOrDefault(false)
            ensureNotCancelled()

            // Manba nusxa: content-URI ruxsati jarayon o'lsa yo'qoladi, kesh
            // fayli esa qoladi — xato bo'lsa retry videoni qaytadan tanlashsiz
            // davom etadi.
            report(req, 3, YuzReelUploads.Phase.PREPARE,
                getString(R.string.reels_preparing_video), force = true)
            val sourceFile = withContext(Dispatchers.IO) { ensureLocalSource(req) }
            ensureNotCancelled()

            // Siqish: umumiy progress 5–45%.
            report(req, 5, YuzReelUploads.Phase.COMPRESS,
                getString(R.string.reels_upload_phase_compress, 0), force = true)
            compressedFile = try {
                YuzReelCompressor.compress(
                    context = applicationContext,
                    source = sourceFile,
                    targetBytes = if (boosted) TARGET_BYTES_PREMIUM else TARGET_BYTES_REGULAR,
                    onProgress = { pct ->
                        report(
                            req,
                            5 + pct * 40 / 100,
                            YuzReelUploads.Phase.COMPRESS,
                            getString(R.string.reels_upload_phase_compress, pct)
                        )
                    },
                    isCanceled = { currentCancelled }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Siqish imkonsiz bo'lsa xom fayl yuboriladi.
                FileLog.e(e)
                null
            }
            ensureNotCancelled()

            // Yuklash: 45–95%.
            val fileName = compressedFile?.let { "${req.id}.mp4" }
                ?: "${req.id}.${extensionOf(sourceFile.name)}"
            report(req, 45, YuzReelUploads.Phase.UPLOAD,
                getString(R.string.reels_upload_phase_upload, 0), force = true)
            val uploadProgress: (Int, Long, Long) -> Unit = { pct, transferred, total ->
                report(
                    req,
                    45 + pct * 50 / 100,
                    YuzReelUploads.Phase.UPLOAD,
                    getString(
                        R.string.reels_upload_progress,
                        pct,
                        humanBytes(transferred),
                        humanBytes(total)
                    )
                )
            }
            val url = if (compressedFile != null) {
                ReelStorageHelper.uploadVideo(uid, fileName, compressedFile, uploadProgress)
            } else {
                ReelStorageHelper.uploadVideo(uid, fileName, sourceFile, uploadProgress)
            }
            ensureNotCancelled()

            // Poster — feed va profil grid'ida video boshlangunicha ko'rinadigan
            // kadr. Ixtiyoriy: ruxsat/tarmoq bo'lmasa `""` qoladi va reel
            // avvalgidek (postersiz) joylanadi.
            val thumbUrl = if (req.thumbPath.isNotBlank()) {
                ReelStorageHelper.uploadThumbnail(uid, "${req.id}.jpg", File(req.thumbPath))
            } else {
                ""
            }

            // Firestore: 95–100%.
            report(req, 95, YuzReelUploads.Phase.SAVE,
                getString(R.string.reels_upload_phase_save), force = true)
            val user = UserConfig.getInstance(req.account).currentUser
            val uploaderName = listOfNotNull(
                user?.first_name?.takeIf { it.isNotBlank() },
                user?.last_name?.takeIf { it.isNotBlank() }
            ).joinToString(" ").orEmpty()

            val reel = Reel(
                id = req.id,
                videoUrl = url,
                thumbnailUrl = thumbUrl,
                views = 0,
                title = req.caption,
                hashtags = req.hashtags,
                monetizationEnabled = true,
                commentCount = 0,
                likeCount = 0,
                createdAt = System.currentTimeMillis(),
                uploadedByUid = uid,
                uploadedByName = uploaderName,
                uploadedByPhotoUrl = "",
                isUserUpload = true
            )
            saveToFirestore(reel)

            published = true
            doneCount++
            lastDoneName = req.name
            YuzReelUploads.complete(req.id)
        } catch (e: CancellationException) {
            // Bekor qilish — xato ko'rsatilmaydi, kvota finally'da qaytadi.
            try {
                YuzReelUploads.complete(req.id)
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            FileLog.e(e)
            if (!currentCancelled) {
                val message = e.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.reels_publish_failed)
                lastError = message
                YuzReelUploads.fail(req.id, message)
            }
        } finally {
            if (!published) {
                val uid = reservedUid
                if (uid != null && slotReserved) {
                    withContext(NonCancellable) {
                        try {
                            YuzReelQuota.releaseUploadSlot(uid, true)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            try {
                compressedFile?.delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Yuklanadigan videoning lokal nusxasini qaytaradi (kesh papkasida).
     * Retry'da tayyor nusxa ishlatiladi; aks holda content-URI keshga
     * ko'chiriladi — keyingi urinishlar tarmoq/ruxsat uzilishidan qat'i nazar
     * aynan shu fayldan ketadi. Nusxa [YuzReelUploads] tugagach o'chiriladi.
     */
    private fun ensureLocalSource(req: Request): File {
        val prepared = req.sourcePath.takeIf { it.isNotBlank() }?.let { File(it) }
        if (prepared != null && prepared.isFile && prepared.length() > 0L) return prepared
        val dir = File(cacheDir, QUEUE_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            throw UploadFailure(getString(R.string.reels_publish_failed))
        }
        val dest = File(dir, "${req.id}.${extensionOf(req.name)}")
        try {
            val input = contentResolver.openInputStream(req.uri)
                ?: throw IllegalStateException("Video oqimi ochilmadi: ${req.uri}")
            input.use { ins ->
                dest.outputStream().use { out -> ins.copyTo(out, 512 * 1024) }
            }
        } catch (e: Exception) {
            FileLog.e(e)
            try {
                dest.delete()
            } catch (_: Exception) {
            }
            throw UploadFailure(getString(R.string.reels_video_not_readable))
        }
        if (!dest.isFile || dest.length() <= 0L) {
            try {
                dest.delete()
            } catch (_: Exception) {
            }
            throw UploadFailure(getString(R.string.reels_video_not_readable))
        }
        YuzReelUploads.setSourcePath(req.id, dest.absolutePath)
        return dest
    }

    /**
     * Firestore yozuvi qisqa tarmoq uzilishida yo'qolmasin: 2 urinish.
     * Ruxsat xatosi (qoidalar/auth) takrorlashdan foyda yo'q — darhol chiqadi.
     */
    private suspend fun saveToFirestore(reel: Reel) {
        var last: Exception? = null
        for (attempt in 1..SAVE_ATTEMPTS) {
            try {
                ReelsRepository.saveReel(reel)
                return
            } catch (e: Exception) {
                last = e
                val message = e.message.orEmpty()
                if (message.contains("ruxsat", true) || message.contains("permission", true)) {
                    throw e
                }
                FileLog.w("ReelUpload save attempt $attempt/$SAVE_ATTEMPTS: $message")
                if (attempt < SAVE_ATTEMPTS) delay(1500L * attempt)
            }
        }
        throw last ?: UploadFailure(getString(R.string.reels_publish_failed))
    }

    private fun extensionOf(name: String): String {
        val raw = name.substringAfterLast('.', "").lowercase()
        return if (raw.length in 2..4 && raw.all { it.isLetterOrDigit() }) raw else "mp4"
    }

    /** Treker + bildirishnomani chegaralangan tezlikda yangilaydi. */
    private fun report(
        req: Request,
        overall: Int,
        phase: YuzReelUploads.Phase,
        text: String,
        force: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        if (force || phase != lastPhase || now - lastTrackerAt >= TRACKER_TICK_MS) {
            lastPhase = phase
            lastTrackerAt = now
            YuzReelUploads.phase(req.id, overall, phase)
        }
        if (force || now - lastNotificationAt >= NOTIFICATION_TICK_MS) {
            lastNotificationAt = now
            updateNotification(req, overall, text)
        }
    }

    private fun ensureNotCancelled() {
        if (currentCancelled) throw CancellationException("Yuklash bekor qilindi")
    }

    private fun cancel(id: String) {
        if (current?.id == id) {
            currentCancelled = true
            currentJob?.cancel()
            return
        }
        if (queue.removeAll { it.id == id }) {
            if (currentJob == null && queue.isEmpty()) finishService()
        }
    }

    private fun acquireWakeLock() {
        try {
            val lock = wakeLock ?: return
            if (!lock.isHeld) lock.acquire(60 * 60 * 1000L)
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
    }

    /**
     * Navbat bo'shadi: foreground'ni yechib, natija bildirishnomasini qo'yadi.
     * Avval `stopForeground(REMOVE)` — aks holda natija bildirishnomasi
     * aynan shu id bilan darhol o'chirilib ketardi.
     */
    private fun finishService() {
        runningCount = 0
        releaseWakeLock()
        val doneName = lastDoneName
        val error = lastError
        val done = doneCount
        doneCount = 0
        lastDoneName = ""
        lastError = ""
        stopForegroundCompat()
        when {
            error.isNotBlank() -> showResultNotification(
                getString(R.string.reels_publish_failed),
                error
            )
            done > 0 -> showResultNotification(
                getString(R.string.reels_published),
                doneName
            )
        }
        // stopSelf(startId): shu lahzada yangi yuklash so'rovi kelib qolsa servis
        // o'chmaydi. `stopSelf()` yangi startId'ni e'tiborsiz qoldirib, navbatga
        // qo'yilgan so'rovni jarayon bilan birga o'ldirardi.
        stopSelf(lastStartId)
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

    private fun startForegroundSafe(text: String) {
        try {
            val title = current?.name?.takeIf { it.isNotBlank() }
                ?: getString(R.string.reels_pending_title)
            startForeground(NOTIFICATION_ID, buildProgressNotification(title, text, 0))
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun buildProgressNotification(
        title: String,
        text: String,
        progress: Int
    ): Notification {
        val id = current?.id.orEmpty()
        val cancelIntent = Intent(this, ReelUploadService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_UPLOAD_ID, id)
        }
        val cancelPending = PendingIntent.getService(
            this,
            if (id.isBlank()) 1 else id.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val queueSuffix = if (queue.isEmpty()) "" else " · +${queue.size}"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_yuz_gallery)
            .setColor(ContextCompat.getColor(this, R.color.yuz_action))
            .setContentTitle(title)
            .setContentText(text + queueSuffix)
            .setContentIntent(launchPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .addAction(R.drawable.ic_yuz_close, getString(R.string.reels_pending_cancel), cancelPending)
            .build()
    }

    private fun updateNotification(req: Request, progress: Int, text: String) {
        acquireWakeLock()
        try {
            val title = req.name.takeIf { it.isNotBlank() }
                ?: getString(R.string.reels_pending_title)
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildProgressNotification(title, text, progress))
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun showResultNotification(title: String, text: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_yuz_gallery)
                .setColor(ContextCompat.getColor(this, R.color.yuz_action))
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(launchPendingIntent())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun launchPendingIntent(): PendingIntent {
        val intent = Intent(this, org.telegram.ui.LaunchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun humanBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
    }

    override fun onDestroy() {
        super.onDestroy()
        runningCount = 0
        releaseWakeLock()
        scope.cancel()
    }

    /** Xato matni foydalanuvchiga tayyor — qayta o'rashsiz ko'rsatiladi. */
    private class UploadFailure(message: String) : Exception(message)
}
