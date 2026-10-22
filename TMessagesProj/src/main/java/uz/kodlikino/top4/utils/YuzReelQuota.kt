package uz.kodlikino.top4.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.TimeZone

/**
 * Reels yuklash kvotasi.
 *
 *  - oddiy foydalanuvchi: KUNIGA 5 reel, 50 MB gacha;
 *  - To'liq Premium:      CHEKSIZ (hajm limiti 100 MB).
 *
 * Saqlash: Firestore `user_reel_quota/{uid}` (uid = `tg_<id>`). Kun bucket'i
 * Toshkent vaqti bo'yicha yarim tunda almashadi. Premium foydalanuvchi uchun
 * hujjat umuman yozilmaydi — limit tekshirilmaydi.
 *
 * [reserveUploadSlot] transaction orqali ishlaydi (ikki qurilma bir vaqtda
 * urinsa ham limit ochilmaydi), muvaffaqiyatsiz publish'da [releaseUploadSlot]
 * orqali rollback qilinadi.
 */
object YuzReelQuota {

    private const val COLLECTION = "user_reel_quota"
    const val REGULAR_DAILY_LIMIT = 5
    const val REGULAR_MAX_FILE_MB = 50
    const val PREMIUM_MAX_FILE_MB = 100
    private const val DAY_MS = 86_400_000L

    private val db = FirebaseFirestore.getInstance()
    private val tashkentZone: TimeZone = TimeZone.getTimeZone("Asia/Tashkent")

    fun isUnlimited(isPremium: Boolean): Boolean = isPremium

    fun dailyLimit(isPremium: Boolean): Int =
        if (isPremium) Int.MAX_VALUE else REGULAR_DAILY_LIMIT

    fun maxFileBytes(isPremium: Boolean): Long =
        if (isPremium) PREMIUM_MAX_FILE_MB * 1024L * 1024L
        else REGULAR_MAX_FILE_MB * 1024L * 1024L

    fun maxFileSizeLabel(isPremium: Boolean): String =
        if (isPremium) "${PREMIUM_MAX_FILE_MB} MB" else "${REGULAR_MAX_FILE_MB} MB"

    /** Toshkent vaqti bo'yicha kun bucket'i — epoch kunlarida. */
    fun dayKey(): String {
        val now = System.currentTimeMillis()
        val localMs = now + tashkentZone.getOffset(now)
        return "D" + (localMs / DAY_MS)
    }

    /** Bugun ishlatilganlar soni (xatoda 0 — limitni sun'iy qisqartirmaydi). */
    suspend fun getUploadCount(uid: String): Int {
        if (uid.isBlank()) return 0
        return try {
            val snapshot = db.collection(COLLECTION).document(uid).get().await()
            val data = snapshot.data ?: return 0
            val storedDay = data["dayKey"] as? String ?: return 0
            if (storedDay != dayKey()) return 0
            (data["dayCount"] as? Number)?.toInt() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    suspend fun getRemainingUploads(uid: String, isPremium: Boolean): Int {
        if (isPremium) return Int.MAX_VALUE
        return (REGULAR_DAILY_LIMIT - getUploadCount(uid)).coerceAtLeast(0)
    }

    fun limitExceededMessage(isPremium: Boolean): String =
        if (isPremium) {
            "Premium limit tekshiruvida xato. Qayta urinib ko'ring."
        } else {
            "Kunlik limit tugadi ($REGULAR_DAILY_LIMIT ta/kun). " +
                "Ertaga yana joylashingiz mumkin yoki To'liq Premium bilan cheksiz joylang."
        }

    fun quotaSummaryLabel(isPremium: Boolean, remaining: Int): String =
        if (isPremium) {
            "Cheksiz · ${maxFileSizeLabel(true)} gacha"
        } else {
            "Bugungi limit: $remaining/$REGULAR_DAILY_LIMIT ta · " +
                maxFileSizeLabel(false) + " gacha"
        }

    /** Videoni tanlaganda hajm tekshiruvi ([size] <= 0 bo'lsa o'tkazib yuboradi). */
    fun sizeLimitMessage(isPremium: Boolean, actualBytes: Long): String {
        val actualMb = String.format(Locale.US, "%.1f", actualBytes / (1024.0 * 1024.0))
        return if (isPremium) {
            "Video hajmi $actualMb MB. Premium limiti: ${maxFileSizeLabel(true)} gacha"
        } else {
            "Video hajmi $actualMb MB. Oddiy limit: ${maxFileSizeLabel(false)} gacha. " +
                "To'liq Premium bilan ${PREMIUM_MAX_FILE_MB} MB gacha yuklash mumkin"
        }
    }

    fun resolveUriSizeBytes(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) {
                        val size = cursor.getLong(idx)
                        if (size > 0L) return size
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize
                if (size > 0L) return size
            }
        } catch (_: Exception) {
        }
        return -1L
    }

    /**
     * Jo'natishdan OLDIN slot ajratadi (transaction). Yetarli bo'lmasa
     * [IllegalStateException] bilan [Result.failure] — xabar foydalanuvchiga
     * ko'rsatiladi. Premium uchun hech narsa yozilmaydi (cheksiz).
     */
    suspend fun reserveUploadSlot(uid: String, isPremium: Boolean): Result<Unit> {
        if (uid.isBlank()) {
            return Result.failure(IllegalStateException("Telegram orqali kiring"))
        }
        if (isPremium) return Result.success(Unit)
        return try {
            val ref = db.collection(COLLECTION).document(uid)
            db.runTransaction { transaction ->
                val snapshot = transaction.get(ref)
                val data = snapshot.data
                val current = if ((data?.get("dayKey") as? String) == dayKey()) {
                    (data?.get("dayCount") as? Number)?.toInt() ?: 0
                } else {
                    0
                }
                if (current >= REGULAR_DAILY_LIMIT) {
                    throw IllegalStateException(limitExceededMessage(false))
                }
                transaction.set(
                    ref,
                    mapOf(
                        "dayKey" to dayKey(),
                        "dayCount" to current + 1,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                null
            }.await()
            Result.success(Unit)
        } catch (e: IllegalStateException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("Yuklash limitini tekshirib bo'lmadi. Internetni sinab qayta urinib ko'ring.")
            )
        }
    }

    /** Muvaffaqiyatsiz publish'da slotni orqaga qaytaradi (jimgina). */
    suspend fun releaseUploadSlot(uid: String, isPremium: Boolean = false) {
        if (uid.isBlank() || isPremium) return
        val ref = db.collection(COLLECTION).document(uid)
        try {
            db.runTransaction { transaction ->
                val snapshot = transaction.get(ref)
                val data = snapshot.data ?: return@runTransaction null
                if (data["dayKey"] != dayKey()) return@runTransaction null
                val current = (data["dayCount"] as? Number)?.toInt() ?: 0
                if (current > 0) transaction.update(ref, "dayCount", current - 1)
                null
            }.await()
        } catch (_: Exception) {
        }
    }
}
