package uz.kodlikino.top4.models

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import org.telegram.messenger.R

/**
 * E'lon va ish kartalari moderatsiyasi.
 *
 * Yangi e'lon admin panelda tasdiqlanmaguncha boshqa foydalanuvchilarga
 * ko'rinmaydi. Eski hujjatlarda `status` maydoni yo'q — ular avvaldan hammaga
 * ko'rinib turgan, shuning uchun bo'sh qiymat "tasdiqlangan" deb o'qiladi.
 */
object AdModeration {

    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"

    fun normalize(raw: String?): String = when (raw?.trim()?.lowercase()) {
        PENDING -> PENDING
        REJECTED -> REJECTED
        else -> APPROVED
    }

    fun isApproved(raw: String?): Boolean = normalize(raw) == APPROVED

    @StringRes
    fun labelRes(raw: String?): Int = when (normalize(raw)) {
        PENDING -> R.string.classified_status_pending
        REJECTED -> R.string.classified_status_rejected
        else -> R.string.classified_status_approved
    }

    @ColorRes
    fun colorRes(raw: String?): Int = when (normalize(raw)) {
        PENDING -> R.color.yuz_action
        REJECTED -> R.color.yuz_danger
        else -> R.color.yuz_gold
    }
}
