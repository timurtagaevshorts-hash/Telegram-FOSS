package uz.kodlikino.top4.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import org.telegram.messenger.ApplicationLoader
import java.util.Locale
import java.util.TimeZone

/**
 * Reels yuklash kvotasi.
 *
 * Limitlar admin panelda `settings/app` hujjatida belgilanadi:
 * `reelDailyLimitRegular`, `reelDailyLimitPremium`, `reelMaxMbRegular`,
 * `reelMaxMbPremium`. Kunlik limit `0` bo'lsa — cheksiz. Standart qiymatlar:
 * oddiy 5 ta/kun & 50 MB, premium cheksiz & 100 MB.
 *
 * Saqlash: Firestore `user_reel_quota/{uid}` (uid = `tg_<id>`). Kun bucket'i
 * Toshkent vaqti bo'yicha yarim tunda almashadi. Cheksiz tarif uchun hujjat
 * umuman yozilmaydi.
 *
 * [reserveUploadSlot] transaction orqali ishlaydi (ikki qurilma bir vaqtda
 * urinsa ham limit ochilmaydi) va slot yozilgan-yozilmaganini qaytaradi;
 * muvaffaqiyatsiz publish'da faqat yozilgan slot [releaseUploadSlot] bilan
 * rollback qilinadi.
 */
object YuzReelQuota {

    private const val COLLECTION = "user_reel_quota"
    private const val SETTINGS_COLLECTION = "settings"
    private const val SETTINGS_DOC = "app"
    private const val PREFS = "yuz_reel_limits"
    private const val KEY_REGULAR = "regular_daily"
    private const val KEY_PREMIUM = "premium_daily"
    private const val KEY_REGULAR_MB = "regular_mb"
    private const val KEY_PREMIUM_MB = "premium_mb"
    private const val KEY_LOADED_AT = "loaded_at"
    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    private const val DAY_MS = 86_400_000L

    const val DEFAULT_REGULAR_DAILY_LIMIT = 5
    const val DEFAULT_PREMIUM_DAILY_LIMIT = 0
    const val DEFAULT_REGULAR_MAX_FILE_MB = 50
    const val DEFAULT_PREMIUM_MAX_FILE_MB = 100

    data class ReelLimits(
        val regularDaily: Int = DEFAULT_REGULAR_DAILY_LIMIT,
        val premiumDaily: Int = DEFAULT_PREMIUM_DAILY_LIMIT,
        val regularMaxMb: Int = DEFAULT_REGULAR_MAX_FILE_MB,
        val premiumMaxMb: Int = DEFAULT_PREMIUM_MAX_FILE_MB
    ) {
        fun dailyLimit(isPremium: Boolean): Int =
            (if (isPremium) premiumDaily else regularDaily).coerceAtLeast(0)

        fun maxFileMb(isPremium: Boolean): Int =
            (if (isPremium) premiumMaxMb else regularMaxMb).coerceAtLeast(1)

        fun isUnlimited(isPremium: Boolean): Boolean = dailyLimit(isPremium) == 0
    }

    private val db = FirebaseFirestore.getInstance()
    private val tashkentZone: TimeZone = TimeZone.getTimeZone("Asia/Tashkent")

    @Volatile
    private var memoryLimits: ReelLimits? = null

    @Volatile
    private var lastFetchAt: Long = 0L

    private fun prefs(): SharedPreferences? =
        ApplicationLoader.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sanitize(
        regularDaily: Int,
        premiumDaily: Int,
        regularMb: Int,
        premiumMb: Int
    ): ReelLimits = ReelLimits(
        regularDaily = regularDaily.coerceAtLeast(0),
        premiumDaily = premiumDaily.coerceAtLeast(0),
        regularMaxMb = if (regularMb > 0) regularMb else DEFAULT_REGULAR_MAX_FILE_MB,
        premiumMaxMb = if (premiumMb > 0) premiumMb else DEFAULT_PREMIUM_MAX_FILE_MB
    )

    private fun parse(data: Map<String, Any>?): ReelLimits = sanitize(
        regularDaily = (data?.get("reelDailyLimitRegular") as? Number)?.toInt()
            ?: DEFAULT_REGULAR_DAILY_LIMIT,
        premiumDaily = (data?.get("reelDailyLimitPremium") as? Number)?.toInt()
            ?: DEFAULT_PREMIUM_DAILY_LIMIT,
        regularMb = (data?.get("reelMaxMbRegular") as? Number)?.toInt()
            ?: DEFAULT_REGULAR_MAX_FILE_MB,
        premiumMb = (data?.get("reelMaxMbPremium") as? Number)?.toInt()
            ?: DEFAULT_PREMIUM_MAX_FILE_MB
    )

    private fun persist(limits: ReelLimits) {
        try {
            val editor = prefs()?.edit() ?: return
            editor
                .putInt(KEY_REGULAR, limits.regularDaily)
                .putInt(KEY_PREMIUM, limits.premiumDaily)
                .putInt(KEY_REGULAR_MB, limits.regularMaxMb)
                .putInt(KEY_PREMIUM_MB, limits.premiumMaxMb)
                .putLong(KEY_LOADED_AT, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
        }
    }

    /** Oxirgi ma'lum limitlar — tarmoqqa chiqmaydi (UI yorliqlari uchun). */
    fun cachedLimits(): ReelLimits {
        memoryLimits?.let { return it }
        return try {
            val sp = prefs() ?: return ReelLimits()
            sanitize(
                regularDaily = sp.getInt(KEY_REGULAR, DEFAULT_REGULAR_DAILY_LIMIT),
                premiumDaily = sp.getInt(KEY_PREMIUM, DEFAULT_PREMIUM_DAILY_LIMIT),
                regularMb = sp.getInt(KEY_REGULAR_MB, DEFAULT_REGULAR_MAX_FILE_MB),
                premiumMb = sp.getInt(KEY_PREMIUM_MB, DEFAULT_PREMIUM_MAX_FILE_MB)
            )
        } catch (_: Exception) {
            ReelLimits()
        }
    }

    /**
     * Limitlarni `settings/app`dan o'qiydi (10 daqiqalik kesh). Xatoda oxirgi
     * ma'lum qiymatlar qaytadi — yuklashni to'sib qo'ymaydi.
     */
    suspend fun loadLimits(force: Boolean = false): ReelLimits {
        val now = System.currentTimeMillis()
        val cached = memoryLimits ?: cachedLimits()
        if (!force && now - lastFetchAt < CACHE_TTL_MS) return cached
        return try {
            val snapshot = db.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC).get().await()
            val limits = parse(snapshot.data)
            memoryLimits = limits
            lastFetchAt = now
            persist(limits)
            limits
        } catch (_: Exception) {
            cached
        }
    }

    /** Admin panel uchun — majburiy o'qish, xatoni yashirmaydi. */
    suspend fun adminLoadLimits(): Result<ReelLimits> = try {
        val snapshot = db.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC).get().await()
        val limits = parse(snapshot.data)
        memoryLimits = limits
        lastFetchAt = System.currentTimeMillis()
        persist(limits)
        Result.success(limits)
    } catch (e: Exception) {
        Result.failure(IllegalStateException("Limitlar yuklanmadi. Internetni tekshiring."))
    }

    /** Admin panel uchun — `settings/app`ga yozish (faqat admin huquqi bilan). */
    suspend fun saveLimits(limits: ReelLimits): Result<Unit> {
        val clean = sanitize(
            limits.regularDaily, limits.premiumDaily,
            limits.regularMaxMb, limits.premiumMaxMb
        )
        return try {
            db.collection(SETTINGS_COLLECTION).document(SETTINGS_DOC).set(
                mapOf(
                    "reelDailyLimitRegular" to clean.regularDaily,
                    "reelDailyLimitPremium" to clean.premiumDaily,
                    "reelMaxMbRegular" to clean.regularMaxMb,
                    "reelMaxMbPremium" to clean.premiumMaxMb,
                    "reelLimitsUpdatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            memoryLimits = clean
            lastFetchAt = System.currentTimeMillis()
            persist(clean)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("Limitlar saqlanmadi. Admin huquqini tekshiring.")
            )
        }
    }

    fun isUnlimited(isPremium: Boolean): Boolean = cachedLimits().isUnlimited(isPremium)

    fun dailyLimit(isPremium: Boolean): Int = cachedLimits().dailyLimit(isPremium)

    fun maxFileBytes(isPremium: Boolean): Long =
        cachedLimits().maxFileMb(isPremium) * 1024L * 1024L

    fun maxFileSizeLabel(isPremium: Boolean): String =
        "${cachedLimits().maxFileMb(isPremium)} MB"

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
        val limit = loadLimits().dailyLimit(isPremium)
        if (limit == 0) return Int.MAX_VALUE
        return (limit - getUploadCount(uid)).coerceAtLeast(0)
    }

    fun limitExceededMessage(isPremium: Boolean): String {
        val limit = cachedLimits().dailyLimit(isPremium)
        if (isPremium) {
            return "Premium kunlik limit tugadi ($limit ta/kun). " +
                "Ertaga yana joylashingiz mumkin."
        }
        return "Kunlik limit tugadi ($limit ta/kun). " +
            "Ertaga yana joylashingiz mumkin yoki To'liq Premium bilan ko'proq joylang."
    }

    fun quotaSummaryLabel(isPremium: Boolean, remaining: Int): String {
        val limits = cachedLimits()
        val limit = limits.dailyLimit(isPremium)
        val sizeLabel = "${limits.maxFileMb(isPremium)} MB"
        return if (limit == 0) {
            "Cheksiz · $sizeLabel gacha"
        } else {
            "Bugungi limit: $remaining/$limit ta · $sizeLabel gacha"
        }
    }

    /** Videoni tanlaganda hajm tekshiruvi ([size] <= 0 bo'lsa o'tkazib yuboradi). */
    fun sizeLimitMessage(isPremium: Boolean, actualBytes: Long): String {
        val actualMb = String.format(Locale.US, "%.1f", actualBytes / (1024.0 * 1024.0))
        val limits = cachedLimits()
        val limitMb = limits.maxFileMb(isPremium)
        return if (isPremium) {
            "Video hajmi $actualMb MB. Premium limiti: $limitMb MB gacha"
        } else {
            "Video hajmi $actualMb MB. Oddiy limit: $limitMb MB gacha. " +
                "To'liq Premium bilan ${limits.premiumMaxMb} MB gacha yuklash mumkin"
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
     * [IllegalStateException] bilan [Result.failure]. Muvaffaqiyatli natija
     * `true` — slot YOZILDI va xatoda [releaseUploadSlot] kerak; `false` —
     * tarif cheksiz, rollback shart emas.
     */
    suspend fun reserveUploadSlot(uid: String, isPremium: Boolean): Result<Boolean> {
        if (uid.isBlank()) {
            return Result.failure(IllegalStateException("Telegram orqali kiring"))
        }
        val limit = loadLimits().dailyLimit(isPremium)
        if (limit == 0) return Result.success(false)
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
                if (current >= limit) {
                    throw IllegalStateException(limitExceededMessage(isPremium))
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
            Result.success(true)
        } catch (e: IllegalStateException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("Yuklash limitini tekshirib bo'lmadi. Internetni sinab qayta urinib ko'ring.")
            )
        }
    }

    /** Muvaffaqiyatsiz publish'da faqat yozilgan slotni qaytaradi (jimgina). */
    suspend fun releaseUploadSlot(uid: String, reserved: Boolean) {
        if (uid.isBlank() || !reserved) return
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
