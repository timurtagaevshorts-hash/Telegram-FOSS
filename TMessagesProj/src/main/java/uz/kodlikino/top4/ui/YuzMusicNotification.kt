package uz.kodlikino.top4.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import org.telegram.messenger.R

/**
 * YuzGram #87: bildirishnoma + qulf ekranidagi pleyer — media3'ning sukut
 * ko'rinishi professional darajaga ko'tarildi.
 *
 * Nima o'zgardi (sukutga qarab):
 *  1) ALOHIDA kanal "YuzGram musiqa" (IMPORTANCE_LOW — jiringlatmaydi, lekin
 *     doim ko'rinadi). Telegram'ning umumiy kanali bilan aralashmaydi:
 *     foydalanuvchi shu kanalni alohida boshqara oladi.
 *  2) Kanal qulf-ekranida VISIBILITY_PUBLIC — Android 13+ pleyer ma'lumotni
 *     MediaSession'dan oladi (shu playerning o'zi), eski versiyalarda va
 *     OEM kabuqlarida esa kanal sozlamasi qulf ekranidagi kontentni ochadi.
 *  3) Kichik ikonka — musiqi belgisi (status-bar uchun mo'ljallangan),
 *     aksent rangi brend ko'kigi.
 *  4) Matn: qator "Ijrochi" yoki "Ijrochi • Albom"; bo'sh metadata bo'lsa
 *     ham bo'sh qator qolmaydi.
 *  5) Qisqa (qulf ekrani / Android Auto) ko'rinishda oldingi-ijro-keyingi
 *     uchta tuganing o'ziga sig'adi.
 *
 * Kanalnici o'zimiz yaratamiz: provider ham kanal ochishga urinadi, lekin
 * mavjud kanal bo'lsa Android birinchi yozuvni saqlaydi — shuning uchun
 * qulf-ekrani sozlamalari biznikida qoladi.
 */
class YuzMusicNotification(context: Context) : DefaultMediaNotificationProvider(
    context,
    { _: MediaSession -> ID },
    CHANNEL_ID,
    R.string.musiqa_notif_channel
) {

    private val appContext: Context = context

    init {
        setSmallIcon(R.drawable.player)
        ensureChannel(context)
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.musiqa_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.musiqa_notif_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        runCatching { nm.createNotificationChannel(ch) }
    }

    override fun getNotificationContentTitle(metadata: MediaMetadata): CharSequence {
        val t = metadata.title
        return if (t.isNullOrEmpty()) "YuzGram" else t
    }

    override fun getNotificationContentText(metadata: MediaMetadata): CharSequence {
        val artist = metadata.artist
        val album = metadata.albumTitle
        return when {
            !artist.isNullOrEmpty() && !album.isNullOrEmpty() -> "$artist • $album"
            !artist.isNullOrEmpty() -> artist
            else -> "YuzGram musiqa"
        }
    }

    override fun addNotificationActions(
        session: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        builder: NotificationCompat.Builder,
        actionFactory: MediaNotification.ActionFactory
    ): IntArray {
        val compact = super.addNotificationActions(session, mediaButtons, builder, actionFactory)
        builder.setColor(ContextCompat.getColor(appContext, R.color.yuz_action))
        // media3 tugmalari tartibi: [oldingi, ijro, keyingi, (to'xtatish)].
        // Qulf ekrani/yon o'rnaki qisqa ko'rinishda aylanish tugasi o'rtada
        // qolishi uchun birinchi uchtasini ko'rsatamiz.
        return if (mediaButtons.size >= 3) intArrayOf(0, 1, 2) else compact
    }

    companion object {
        /** Alohida id — boshqa bildirishnomalar bilan urishmaydi. */
        const val ID = 870042
        const val CHANNEL_ID = "yuzgram_music"
    }
}
