package uz.kodlikino.top4.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import uz.kodlikino.top4.models.ReelNotification

/**
 * Reels bildirishnomalari — ovoz yoqish/o'chirish flag'i, tizim kanallari va
 * heads-up bildirishnomasi. Donor `ReelNotificationSoundHelper` +
 * `AppUserSettings` (KEY_REEL_NOTIF_SOUND) g'oyasining fork varianti.
 *
 * FCM yo'q: bildirishnomalar Firestore listener orqali keladi, shuning uchun
 * heads-up faqat ilova ishlayotganda ko'rsatiladi (v1 skopi).
 */
object YuzReelNotify {

    private const val PREFS = "yuz_reel_notifications"
    private const val KEY_SOUND = "reel_notif_sound"

    const val CHANNEL_LOUD = "yuz_reel_activity"
    const val CHANNEL_SILENT = "yuz_reel_activity_silent"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var seq = 1000

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSoundEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOUND, true)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUND, enabled).apply()
        // Kanal ovozi yaratilganda qotib qoladi — toggle'langanda qayta yaratamiz.
        ensureChannels(context)
    }

    private fun defaultSound(context: Context) =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    private fun audioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val silent = manager.getNotificationChannel(CHANNEL_SILENT)
        val loud = manager.getNotificationChannel(CHANNEL_LOUD)
        // eski/notog'ri holatdagi kanallarni tozalab qayta yaratamiz
        if ((silent != null && (silent.shouldVibrate() || silent.sound != null)) ||
            (loud != null && loud.sound == null)
        ) {
            manager.deleteNotificationChannel(CHANNEL_LOUD)
            manager.deleteNotificationChannel(CHANNEL_SILENT)
        }
        if (manager.getNotificationChannel(CHANNEL_LOUD) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_LOUD, "Reels faolligi", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reels izohlari, javoblari va yoqtirishlari"
                    setSound(defaultSound(context), audioAttributes())
                    enableVibration(true)
                }
            )
        }
        if (manager.getNotificationChannel(CHANNEL_SILENT) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SILENT, "Reels faolligi (ovozsiz)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Reels izohlari va yoqtirishlari — ovoz va tebranishsiz"
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    fun channelId(context: Context): String =
        if (isSoundEnabled(context)) CHANNEL_LOUD else CHANNEL_SILENT

    /** Ilova ichida qo'ng'iroq ovozi (listener orqali yangi item kelganda). */
    fun playInAppAlert(context: Context) {
        if (!isSoundEnabled(context)) return
        val appContext = context.applicationContext
        val uri = defaultSound(appContext) ?: return
        mainHandler.post {
            try {
                val ringtone = RingtoneManager.getRingtone(appContext, uri) ?: return@post
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.isLooping = false
                ringtone.audioAttributes = audioAttributes()
                ringtone.play()
                mainHandler.postDelayed({
                    try {
                        if (ringtone.isPlaying) ringtone.stop()
                        // #126: release() chaqirilmasa har signalda native
                        // AudioTrack tutib qolardi. Fork'ning patchlangan
                        // android.jar'ida release() compile-time yo'q —
                        // refleksiya bilan chaqiramiz (API 28+).
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            try {
                                ringtone.javaClass.getMethod("release").invoke(ringtone)
                            } catch (_: Exception) {
                            }
                        }
                    } catch (_: Exception) {
                    }
                }, 900L)
            } catch (_: Exception) {
            }
        }
    }

    /** Tizim notifications paneliga heads-up chiqaradi; bosilganda ilova ochiladi. */
    fun show(context: Context, item: ReelNotification) {
        try {
            ensureChannels(context)
            val appContext = context.applicationContext
            val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            val pi = if (launch != null) {
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
                PendingIntent.getActivity(appContext, 0, launch, flags)
            } else null

            val title = when (item.type) {
                ReelNotification.TYPE_REPLY -> "${item.actorName} izohingizga javob berdi"
                ReelNotification.TYPE_COMMENT -> "${item.actorName} izoh qoldirdi"
                else -> "${item.actorName} reelsingizni yoqtirdi"
            }
            val text = when {
                item.commentText.isNotBlank() -> item.commentText
                item.reelTitle.isNotBlank() -> item.reelTitle
                else -> "Reels"
            }

            val channel = channelId(appContext)
            val notification = NotificationCompat.Builder(appContext, channel)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(appContext.applicationInfo.icon)
                .setAutoCancel(true)
                .setPriority(if (isSoundEnabled(appContext)) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .build()
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(seq++, notification)
        } catch (_: Exception) {
        }
    }
}
