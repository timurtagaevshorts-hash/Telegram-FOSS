package uz.kodlikino.top4.utils

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Firebase chaqiruvlarini muddat bilan bajarish.
 *
 * Muammo (2026-10-27 "e'lon/ish kartasi/reel yuklash yuklanmoqda deb qolib
 * ketyapti"): Firestore `set()/update()`, Storage `downloadUrl` va callable
 * funksiyalar `await()` bilan server javobini CHEKSIZ kutadi. Tarmoq sekinlashsa
 * yoki javob umuman kelmasa, yuklash bosqichi abadiy osilib qoladi — foydalanuvchi
 * esa faqat "yuklanmoqda" ko'radi va hech qanday xato chiqmaydi.
 *
 * Muddat tugaganda [TimeoutCancellationException] `catch (CancellationException)`
 * bloklari tomonidan "foydalanuvchi bekor qildi" deb yutilib ketmasligi uchun u
 * [IllegalStateException]ga aylantiriladi: servis buni xato deb ko'rsatadi va
 * "Qayta urinish" tugmasini beradi.
 */
object YuzTaskTimeouts {

    /** `mintTelegramCustomToken` — sovuq start 20–30 s olishi mumkin. */
    const val AUTH_MINT_MS = 60_000L

    /** `signInWithCustomToken` — token allaqachon olingan, faqat sessiya almashadi. */
    const val AUTH_SIGN_IN_MS = 45_000L

    /** Storage `downloadUrl` — bitta kichik token so'rovi. */
    const val DOWNLOAD_URL_MS = 25_000L

    /** Firestore hujjat yozish/o'qish. */
    const val FIRESTORE_MS = 45_000L

    /** Kichik sozlama hujjati — kesh fallback bor, uzoq kutish shart emas. */
    const val SETTINGS_MS = 15_000L

    suspend fun <T> awaitBound(
        operation: String,
        timeoutMs: Long,
        block: suspend () -> T
    ): T = try {
        withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        throw IllegalStateException(
            "$operation: server javob bermadi — internetni tekshirib, qayta urinib ko'ring"
        )
    }
}
