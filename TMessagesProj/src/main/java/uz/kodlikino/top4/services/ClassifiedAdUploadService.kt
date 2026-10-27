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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import uz.kodlikino.top4.models.ClassifiedAd
import uz.kodlikino.top4.models.ClassifiedAdCardThemes
import uz.kodlikino.top4.models.JobCard
import uz.kodlikino.top4.models.JobCardListingTypes
import uz.kodlikino.top4.models.JobCardSalaryTypes
import uz.kodlikino.top4.utils.ClassifiedAdRepository
import uz.kodlikino.top4.utils.ClassifiedAdStorageHelper
import uz.kodlikino.top4.utils.JobCardRepository
import uz.kodlikino.top4.utils.YuzAdUploads
import uz.kodlikino.top4.utils.YuzGramAdsAuth

/**
 * E'lon yuklashni fonda davom ettiradigan service.
 *
 * "Joylash" bosilishi bilan yuklash ekrani yopiladi va ish shu servisga o'tadi:
 * rasmlarni Storage'ga yuklash → Firestore'ga e'lonni yozish. Foydalanuvchi
 * ilovadan bemalol foydalanadi; holat [YuzAdUploads] trekeri (feed banneri) va
 * tizim bildirishnomasida ko'rinadi.
 *
 * Navbat: yuklashlar ketma-ket bajariladi (Firebase Auth sessiyasi va Storage
 * uchun bitta faol oqim yetarli), bekor qilish esa aynan bitta yozuvni to'xtatadi.
 *
 * Rasmlar [ClassifiedAdStorageHelper] ichida parallel (3 tagacha) yuklanadi —
 * avval 5 rasm ketma-ket ketardi va sekin tarmoqda daqiqalab cho'zilardi.
 */
class ClassifiedAdUploadService : Service() {

    companion object {
        const val ACTION_UPLOAD = "uz.kodlikino.top4.action.UPLOAD_AD"
        const val ACTION_CANCEL = "uz.kodlikino.top4.action.CANCEL_AD_UPLOAD"
        const val EXTRA_UPLOAD_ID = "ad_upload_id"

        private const val NOTIFICATION_ID = 20261026
        private const val CHANNEL_ID = "ad_upload"

        private const val TRACKER_TICK_MS = 700L
        private const val NOTIFICATION_TICK_MS = 500L

        @Volatile
        private var runningCount = 0

        fun isUploading(): Boolean = runningCount > 0

        fun startUpload(context: Context, uploadId: String) {
            if (uploadId.isBlank()) return
            val intent = Intent(context, ClassifiedAdUploadService::class.java).apply {
                action = ACTION_UPLOAD
                putExtra(EXTRA_UPLOAD_ID, uploadId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelUpload(context: Context?, uploadId: String) {
            if (uploadId.isBlank()) return
            val ctx = context ?: ApplicationLoader.applicationContext ?: return
            val intent = Intent(ctx, ClassifiedAdUploadService::class.java).apply {
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = ArrayDeque<String>()
    private var current: YuzAdUploads.Upload? = null
    private var currentJob: Job? = null

    @Volatile
    private var currentCancelled = false
    private var wakeLock: PowerManager.WakeLock? = null

    private var lastTrackerAt = 0L
    private var lastNotificationAt = 0L
    private var lastPhase: YuzAdUploads.Phase? = null

    private var doneCount = 0
    private var lastDoneTitle = ""
    private var lastDoneKind = YuzAdUploads.Kind.AD
    private var lastError = ""
    private var lastFailedId = ""
    private var lastFailedKind = YuzAdUploads.Kind.AD

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YuzGram:AdUpload").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPLOAD -> {
                // startForegroundService → 5 soniya ichida startForeground shart.
                startForegroundSafe(getString(R.string.classified_upload_phase_prepare))
                val id = intent.getStringExtra(EXTRA_UPLOAD_ID).orEmpty()
                val upload = if (id.isBlank()) null else YuzAdUploads.find(id)
                if (upload == null) {
                    if (currentJob == null && queue.isEmpty()) finishService()
                    return START_NOT_STICKY
                }
                // "Qayta urinish" ham shu yo'l orqali keladi — xato holatini tozalaymiz.
                if (upload.failed) YuzAdUploads.phase(upload.id, 0, YuzAdUploads.Phase.PREPARE)
                if (current?.id == upload.id || queue.any { it == upload.id }) {
                    return START_NOT_STICKY
                }
                queue.addLast(upload.id)
                pump()
            }
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_UPLOAD_ID).orEmpty()
                if (id.isNotBlank()) cancel(id)
            }
        }
        return START_NOT_STICKY
    }

    /** Navbatdan keyingi e'lonni olib, yuklashni boshlaydi. */
    private fun pump() {
        if (currentJob != null) return
        val nextId = queue.removeFirstOrNull()
        if (nextId == null) {
            finishService()
            return
        }
        val upload = YuzAdUploads.find(nextId)
        if (upload == null) {
            pump()
            return
        }
        current = upload
        currentCancelled = false
        lastPhase = null
        lastTrackerAt = 0L
        lastNotificationAt = 0L
        runningCount = 1
        acquireWakeLock()
        currentJob = scope.launch {
            try {
                performUpload(upload)
            } finally {
                currentJob = null
                current = null
                currentCancelled = false
                queue.removeAll { it == nextId }
                pump()
            }
        }
    }

    private suspend fun performUpload(req: YuzAdUploads.Upload) {
        try {
            report(
                req, 2, YuzAdUploads.Phase.PREPARE,
                getString(R.string.classified_upload_phase_prepare), force = true
            )

            if (!ApplicationLoader.isNetworkOnline()) {
                throw UploadFailure(getString(R.string.classified_upload_offline))
            }

            val uid = YuzGramAdsAuth.ensureSignedIn(req.account)

            val localUris = req.localUris.mapNotNull { raw ->
                try {
                    Uri.parse(raw)
                } catch (_: Exception) {
                    null
                }
            }

            // Yuklash: progress 5–90%. Rasm soni va siqish ichkarida boshqariladi.
            val uploaded = if (localUris.isEmpty()) {
                emptyList()
            } else {
                ClassifiedAdStorageHelper.uploadImages(
                    applicationContext, uid, req.adId, localUris
                ) { pct, done, total ->
                    report(
                        req,
                        5 + pct * 85 / 100,
                        YuzAdUploads.Phase.UPLOAD,
                        getString(R.string.classified_upload_progress, pct, done, total)
                    )
                }
            }
            ensureNotCancelled()

            val allUrls = (req.existingUrls + uploaded)
                .filter { it.isNotBlank() }
                .distinct()

            val user = UserConfig.getInstance(req.account).currentUser
            val ownerName = listOfNotNull(
                user?.first_name?.takeIf { it.isNotBlank() },
                user?.last_name?.takeIf { it.isNotBlank() }
            ).joinToString(" ").orEmpty()

            report(
                req, 96, YuzAdUploads.Phase.SAVE,
                getString(
                    if (req.kind == YuzAdUploads.Kind.JOB)
                        R.string.job_board_saving else R.string.classified_saving
                ),
                force = true
            )
            when (req.kind) {
                YuzAdUploads.Kind.JOB -> saveJobCard(req, uid, ownerName, uploaded)
                YuzAdUploads.Kind.AD -> saveAd(req, uid, ownerName, allUrls)
            }

            doneCount++
            lastDoneTitle = req.displayTitle
            lastDoneKind = req.kind
            YuzAdUploads.complete(req.id)
        } catch (e: CancellationException) {
            // Bekor qilish — xato ko'rsatilmaydi, yozuv dismiss orqali o'chirilgan.
            try {
                YuzAdUploads.complete(req.id)
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            FileLog.e(e)
            if (!currentCancelled) {
                val message = e.message?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.classified_upload_failed)
                lastError = message
                lastFailedId = req.id
                lastFailedKind = req.kind
                YuzAdUploads.fail(req.id, message)
            }
        }
    }

    /** E'lon yozuvi — rasmlar (ko'pi bilan 5) + Firestore hujjati. */
    private suspend fun saveAd(
        req: YuzAdUploads.Upload,
        uid: String,
        ownerName: String,
        urls: List<String>
    ) {
        val ad = ClassifiedAd(
            id = req.adId,
            title = req.title,
            model = req.model,
            description = req.description,
            price = req.price,
            category = req.category,
            imageUrls = urls.take(5),
            address = req.address,
            phone = req.phone,
            ownerUid = uid,
            ownerName = ownerName,
            ownerPhotoUrl = "",
            createdAt = req.createdAt,
            isActive = true,
            cardTheme = ClassifiedAdCardThemes.BLACK,
            region = req.region
        )
        ClassifiedAdRepository.saveAd(ad)

        if (req.removedUrls.isNotEmpty()) {
            ClassifiedAdStorageHelper.deleteImages(req.removedUrls)
        }
    }

    /** Ish kartasi — bitta rasm (yangi yuklangan yoki eskisi) + Firestore hujjati. */
    private suspend fun saveJobCard(
        req: YuzAdUploads.Upload,
        uid: String,
        ownerName: String,
        uploaded: List<String>
    ) {
        // Yangi rasm eskisini almashtiradi; yangi tanlanmasa — eskisi qoladi.
        val imageUrl = uploaded.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: req.existingUrls.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: JobCardRepository.findLatestImageUrlForOwner(uid, excludeId = req.adId)
        JobCardRepository.saveCard(
            JobCard(
                id = req.adId,
                listingType = req.listingType.ifBlank { JobCardListingTypes.HIRING },
                title = req.title,
                description = req.description,
                location = req.region,
                phone = req.phone,
                salaryAmount = req.salaryAmount,
                salaryType = req.salaryType.ifBlank { JobCardSalaryTypes.NEGOTIABLE },
                imageUrl = imageUrl,
                cardTheme = ClassifiedAdCardThemes.BLACK,
                ownerUid = uid,
                ownerName = ownerName,
                ownerPhotoUrl = "",
                createdAt = req.createdAt,
                region = req.region
            )
        )
    }

    /** Treker + bildirishnomani chegaralangan tezlikda yangilaydi. */
    @Synchronized
    private fun report(
        req: YuzAdUploads.Upload,
        overall: Int,
        phase: YuzAdUploads.Phase,
        text: String,
        force: Boolean = false
    ) {
        val now = SystemClock.elapsedRealtime()
        if (force || phase != lastPhase || now - lastTrackerAt >= TRACKER_TICK_MS) {
            lastPhase = phase
            lastTrackerAt = now
            YuzAdUploads.phase(req.id, overall, phase)
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
        if (queue.removeAll { it == id }) {
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

    private fun finishService() {
        runningCount = 0
        releaseWakeLock()
        val doneTitle = lastDoneTitle
        val doneKind = lastDoneKind
        val error = lastError
        val failedId = lastFailedId
        val failedKind = lastFailedKind
        val done = doneCount
        doneCount = 0
        lastDoneTitle = ""
        lastError = ""
        lastFailedId = ""
        stopForegroundCompat()
        when {
            error.isNotBlank() -> showResultNotification(
                getString(
                    if (failedKind == YuzAdUploads.Kind.JOB)
                        R.string.job_board_upload_failed_title
                    else R.string.classified_upload_failed_title
                ),
                error,
                retryUploadId = failedId
            )
            done > 0 -> showResultNotification(
                getString(
                    if (doneKind == YuzAdUploads.Kind.JOB)
                        R.string.job_board_upload_done
                    else R.string.classified_upload_done
                ),
                doneTitle,
                retryUploadId = null
            )
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.classified_upload_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "E'lon yuklash holati"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundSafe(text: String) {
        try {
            startForeground(NOTIFICATION_ID, buildProgressNotification(text, 0))
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun buildProgressNotification(text: String, progress: Int): Notification {
        val id = current?.id.orEmpty()
        val cancelIntent = Intent(this, ClassifiedAdUploadService::class.java).apply {
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
        val title = current?.displayTitle?.takeIf { it.isNotBlank() }
            ?: getString(R.string.classified_pending_title)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_yuz_tag)
            .setColor(ContextCompat.getColor(this, R.color.yuz_action))
            .setContentTitle(title)
            .setContentText(text + queueSuffix)
            .setContentIntent(launchPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .addAction(R.drawable.ic_yuz_close, getString(R.string.classified_pending_cancel), cancelPending)
            .build()
    }

    private fun updateNotification(req: YuzAdUploads.Upload, progress: Int, text: String) {
        acquireWakeLock()
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildProgressNotification(text, progress))
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun showResultNotification(title: String, text: String, retryUploadId: String?) {
        try {
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_yuz_tag)
                .setColor(ContextCompat.getColor(this, R.color.yuz_action))
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(launchPendingIntent())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
            if (!retryUploadId.isNullOrBlank()) {
                builder.addAction(
                    R.drawable.ic_yuz_loop,
                    getString(R.string.classified_pending_retry),
                    retryPendingIntent(retryUploadId)
                )
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    /**
     * Xato bildirishnomasidagi "Qayta urinish" — servisni foreground rejimda
     * qayta boshlaydi (fon cheklovlari oddiy `startService`ni bloklashi mumkin).
     */
    private fun retryPendingIntent(uploadId: String): PendingIntent {
        val intent = Intent(this, ClassifiedAdUploadService::class.java).apply {
            action = ACTION_UPLOAD
            putExtra(EXTRA_UPLOAD_ID, uploadId)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
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

    override fun onDestroy() {
        super.onDestroy()
        runningCount = 0
        releaseWakeLock()
        scope.cancel()
    }

    /** Xato matni foydalanuvchiga tayyor — qayta o'rashsiz ko'rsatiladi. */
    private class UploadFailure(message: String) : Exception(message)
}
