package uz.kodlikino.top4.utils

import android.view.View
import com.google.firebase.firestore.ListenerRegistration

/**
 * Profil qo'ng'irog'idagi o'qilmagan nuqtasi — [org.telegram.ui.ProfileActivity]
 * (Java) dan chaqiriladi: nuqtaning ko'rinishi unread soniga qarab yangilanadi.
 */
object YuzReelNotifBadge {

    @JvmStatic
    fun attach(dot: View, uid: String): ListenerRegistration? =
        ReelNotificationManager.listenUnreadCount(uid) { count ->
            dot.visibility = if (count > 0) View.VISIBLE else View.GONE
        }

    @JvmStatic
    fun detach(reg: ListenerRegistration?) {
        try {
            reg?.remove()
        } catch (_: Exception) {
        }
    }
}
