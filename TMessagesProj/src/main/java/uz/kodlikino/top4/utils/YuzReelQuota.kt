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
 * Reels yuklash kvotasi — KinoTOP4'dagi `UserReelUploadManager`ning fork
 * moslamasi. Eski loyihadagi admin-ga bog'liq `ReelsAdminSettings` o'rniga
 * foydalanuvchi topsirmig'idagi qat'iy raqamlar:
 *
 *  - oddiy foydalanuvchi: HAFTASIGA 3 reel, 50 MB gacha;
 *  - To'liq Premium:     HAFTASIGA 30 reel, 100 MB gacha.
 *
 * Saqlash: Firestore `user_reel_quota/{uid}` (uid = `tg_<id>`), donor bilan
 * bir xil hujjat shakli va hafta bucket'i (`weekKey` — dushanbadan, Toshkent
 * vaqti). Qoida: `allow read, write: if isOwner(uid)` — [YuzGramAdsAuth]
 * sessiyasi orqali o'tadi.
 *
 * Eslatma — [reserveUploadSlot] transaction orqali ishlaydi (ikki qurilma
 * bir vaqtda urinsa ham limit ochilmaydi), muvaffaqiyatsiz publish'da
 * [releaseUploadSlot] orqali rollback qilinadi.
 */
object YuzReelQuota {

    private const val COLLECTION = "user_reel_quota"
    const val REGULAR_WEEKLY_LIMIT = 3
    const val PREMIUM_WEEKLY_LIMIT = 30
    const val REGULAR_MAX_FILE_MB = 50
    const val PREMIUM_MAX_FILE_MB = 100

    private val db = FirebaseFirestore.getInstance()
    private val tashkentZone: TimeZone = TimeZone.getTimeZone("Asia/Tashkent")

    fun weeklyLimit(isPremium: Boolean): Int =
        if (isPremium) PREMIUM_WEEKLY_LIMIT else REGULAR_WEEKLY_LIMIT

    fun maxFileBytes(isPremium: Boolean): Long =
        if (isPremium) PREMIUM_MAX_FILE_MB * 1024L * 1024L
        else REGULAR_MAX_FILE_MB * 1024L * 1024L

    fun maxFileSizeLabel(isPremium: Boolean): String =
        if (isPremium) "${PREMIUM_MAX_FILE_MB} MB" else "${REGULAR_MAX_FILE_MB} MB"

    /**
     * Dushanbadan boshlanadigan hafta bucket'i (Toshkent vaqti).
     * #120: oldin Calendar.YEAR + WEEK_OF_YEAR ishlatilgani uchun yangi yil
     * atrofida (29-31 dek = ISO W01 keyingi yil) yil/hafta RAHMAGI kelib,
     * hafta o'rtasida kvot ertaga "siftarib" nolga tushardi. Endi epoch
     * dushanbalaridan sanoqchi yagona butun son — chegaralar bir xil.
     */
    fun weekKey(): String {
        val now = System.currentTimeMillis()
        val localMs = now + tashkentZone.getOffset(now)
        // 1970-01-01 — payshanba; birinchi DUSHANBA epoch'dan 4 kun keyin
        // (1970-01-05). Zamonaviy sanalarda ayirmani musbat — butun bo'lish
        // (floorDiv, API 24) o'rniga oddiy '/' xuddi shu natijani beradi.
        val weekIndex = (localMs - 4L * 86_400_000L) / (7L * 86_400_000L)
        return "W$weekIndex"
    }

    /** Joriy haftada ishlatilganlar soni (xatoda 0 — limitni sun'iy qisqartirmaydi). */
    suspend fun getUploadCount(uid: String): Int {
        if (uid.isBlank()) return 0
        return try {
            val snapshot = db.collection(COLLECTION).document(uid).get().await()
            val data = snapshot.data ?: return 0
            val storedWeek = data["weekKey"] as? String ?: return 0
            if (storedWeek != weekKey()) return 0
            (data["weekCount"] as? Number)?.toInt() ?: 0
        } catch (_: Exception) {
            0
        }
    }

    suspend fun getRemainingUploads(uid: String, isPremium: Boolean): Int {
        val limit = weeklyLimit(isPremium)
        return (limit - getUploadCount(uid)).coerceAtLeast(0)
    }

    fun limitExceededMessage(isPremium: Boolean): String =
        if (isPremium) {
            "Haftalik Premium limit tugadi ($PREMIUM_WEEKLY_LIMIT ta/hafta). " +
                "Yangi hafta kuting yoki kamaytirib joylang."
        } else {
            "Haftalik limit tugadi ($REGULAR_WEEKLY_LIMIT ta/hafta). " +
                "To'liq Premium bilan haftasiga $PREMIUM_WEEKLY_LIMIT tagacha reel joylash mumkin."
        }

    fun quotaSummaryLabel(isPremium: Boolean, remaining: Int): String =
        "Haftalik limit: $remaining/${weeklyLimit(isPremium)} ta · " +
            maxFileSizeLabel(isPremium) + " gacha"

    /** Videoni tanlaganda hajm tekshiruvi ([size] <= 0 bo'lsa o'tkazib yuboradi). */
    fun sizeLimitMessage(isPremium: Boolean, actualBytes: Long): String {
        val actualMb = String.format(Locale.US, "%.1f", actualBytes / (1024.0 * 1024.0))
        return if (isPremium) {
            "Video hajmi $actualMb MB. To'liq Premium limiti: ${maxFileSizeLabel(isPremium)} gacha"
        } else {
            "Video hajmi $actualMb MB. Oddiy limit: ${maxFileSizeLabel(isPremium)} gacha. " +
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
     * [IllegalStateException] bilan [Result.failure] — xabar foydachiga ko'rsatiladi.
     */
    suspend fun reserveUploadSlot(uid: String, isPremium: Boolean): Result<Unit> {
        if (uid.isBlank()) {
            return Result.failure(IllegalStateException("Telegram orqali kiring"))
        }
        val limit = weeklyLimit(isPremium)
        return try {
            val ref = db.collection(COLLECTION).document(uid)
            db.runTransaction { transaction ->
                val snapshot = transaction.get(ref)
                val data = snapshot.data
                val current = if ((data?.get("weekKey") as? String) == weekKey()) {
                    (data?.get("weekCount") as? Number)?.toInt() ?: 0
                } else {
                    0
                }
                if (current >= limit) {
                    throw IllegalStateException(limitExceededMessage(isPremium))
                }
                transaction.set(
                    ref,
                    mapOf(
                        "weekKey" to weekKey(),
                        "weekCount" to current + 1,
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
    suspend fun releaseUploadSlot(uid: String) {
        if (uid.isBlank()) return
        val ref = db.collection(COLLECTION).document(uid)
        try {
            db.runTransaction { transaction ->
                val snapshot = transaction.get(ref)
                val data = snapshot.data ?: return@runTransaction null
                if (data["weekKey"] != weekKey()) return@runTransaction null
                val current = (data["weekCount"] as? Number)?.toInt() ?: 0
                if (current > 0) transaction.update(ref, "weekCount", current - 1)
                null
            }.await()
        } catch (_: Exception) {
        }
    }
}
