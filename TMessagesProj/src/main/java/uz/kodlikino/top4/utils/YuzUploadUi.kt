package uz.kodlikino.top4.utils

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import org.telegram.messenger.R

/**
 * Yuklash (reel/elon) UI yordamchilari — CreateReelActivity va
 * CreateClassifiedAdActivity ikkalasida bir xil xatti-harakat bo'lishi uchun.
 *
 * Nima uchun kerak bo'ldi (2026-10-16): "yuklab bo'lmayapti" shikoyati
 * qisman ko'rinmaslikdan ham kelib chiqqan — foiz/MB hech qayerda ko'rsatilmas,
 * xato esa 2 soniyalik toast'da (uzun matn o'qilmaydi) yashiringan edi.
 */
object YuzUploadUi {

    /**
     * Yuklash davomida ekran o'chib qolsa ba'zi qurilmalarda tarmoq vaqtincha
     * to'xtaydi — shuning uchun publish tugaguncha ekranni yoniq saqlaymiz.
     */
    fun keepScreenOn(activity: Activity?, on: Boolean) {
        val window = activity?.window ?: return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Xatoni dialog'da ko'rsatadi (toast'dan farqli ravishda uzun sabab o'qiladi)
     * va imkon bo'lsa "Qayta urinish" tugmasini beradi.
     */
    fun showError(context: Context?, message: String, onRetry: (() -> Unit)?) {
        val ctx = context ?: return
        try {
            val builder = AlertDialog.Builder(ctx)
                .setTitle(R.string.yuz_upload_failed_title)
                .setMessage(message)
                .setCancelable(true)
            if (onRetry != null) {
                builder.setPositiveButton(R.string.yuz_upload_retry) { d, _ ->
                    d.dismiss()
                    onRetry()
                }
            }
            builder.setNegativeButton(R.string.yuz_upload_close, null)
            builder.show()
        } catch (_: Exception) {
            // Context (ContextThemeWrapper) dialog uchun yaroqsiz bo'lsa — jim o'tamiz,
            // chaqiruvchi tomon toast bilan ham qoplaydi.
        }
    }

    /** Baytlarni "12.4 MB" ko'rinishiga keltirish (foiz bilan birga ko'rsatiladi). */
    fun humanBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.1f MB", bytes / mb)
            else -> String.format("%.0f KB", bytes / kb)
        }
    }
}
