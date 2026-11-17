package uz.kodlikino.top4.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.R
import uz.kodlikino.top4.models.AdBanner

/**
 * `ad_banners` kolleksiyasi bilan ishlash: admin CRUD + ommaviy o'qish.
 *
 * Firestore qoidalari: read — hamma uchun ochiq, write — `admins/{uid}` uchun
 * (claimAdminSession yozadi). Rasm [AdBannerStorageHelper] orqali `ad_banners/`
 * ga yuklanadi va `imageUrl`da download URL saqlanadi; tashqi URL ham qabul
 * qilinadi (eski yozuvlar bilan moslik).
 *
 * Faol bannerlar SharedPreferences'da keshlanadi: internet uzilganda chat
 * ekrani bo'sh joy bilan qolmaydi.
 */
object YuzAdStore {

    private const val COLLECTION = "ad_banners"
    private const val PREFS = "yuz_ads_cache"
    private const val KEY_ACTIVE = "active"
    private const val KEY_SYNCED_AT = "syncedAt"
    private const val ACTIVE_LIMIT = 20L

    /** Kesh faqat oflayn sovuq start uchun; eski banner abadiy qolmasin. */
    private const val CACHE_TTL_MS = 72L * 60L * 60L * 1000L

    data class AdTheme(
        val key: String,
        val labelRes: Int,
        val start: Int,
        val end: Int,
        val accent: Int
    )

    val THEMES: List<AdTheme> = listOf(
        AdTheme("brand", R.string.yuz_ad_theme_brand, 0xFFC2185B.toInt(), 0xFF6E0E33.toInt(), 0xFFC2185B.toInt()),
        AdTheme("violet", R.string.yuz_ad_theme_violet, 0xFFEB5CF6.toInt(), 0xFF4C1D95.toInt(), 0xFFEB5CF6.toInt()),
        AdTheme("emerald", R.string.yuz_ad_theme_emerald, 0xFF10B981.toInt(), 0xFF065F46.toInt(), 0xFF10B981.toInt()),
        AdTheme("sunset", R.string.yuz_ad_theme_sunset, 0xFFFF8A3D.toInt(), 0xFFD9366B.toInt(), 0xFFFF8A3D.toInt()),
        AdTheme("crimson", R.string.yuz_ad_theme_crimson, 0xFFFF4D6D.toInt(), 0xFF7F1D3A.toInt(), 0xFFFF4D6D.toInt()),
        AdTheme("graphite", R.string.yuz_ad_theme_graphite, 0xFF475569.toInt(), 0xFF200F2A.toInt(), 0xFF94A3B8.toInt())
    )

    fun theme(key: String?): AdTheme =
        THEMES.firstOrNull { it.key == key } ?: THEMES[0]

    private val db = FirebaseFirestore.getInstance()

    // ── Admin ────────────────────────────────────────────────────────────

    suspend fun loadAll(): Result<List<AdBanner>> = try {
        val snapshot = db.collection(COLLECTION).get().await()
        val list = snapshot.documents
            .mapNotNull { doc -> doc.data?.let { toBanner(doc.id, it) } }
            .sortedWith(compareByDescending<AdBanner> { it.createdAt })
        Result.success(list)
    } catch (e: Exception) {
        FileLog.e(e)
        Result.failure(e)
    }

    suspend fun save(banner: AdBanner): Result<Unit> = try {
        val collection = db.collection(COLLECTION)
        val ref = if (banner.id.isBlank()) collection.document()
        else collection.document(banner.id)
        val now = System.currentTimeMillis()
        val payload = hashMapOf<String, Any>(
            "title" to banner.title.trim(),
            "subtitle" to banner.subtitle.trim(),
            "imageUrl" to banner.imageUrl.trim(),
            "linkUrl" to banner.linkUrl.trim(),
            "theme" to theme(banner.theme).key,
            "showInChats" to banner.showInChats,
            "showInFunctions" to banner.showInFunctions,
            "isActive" to banner.isActive,
            "createdAt" to (if (banner.createdAt > 0L) banner.createdAt else now),
            "updatedAt" to now
        )
        ref.set(payload, SetOptions.merge()).await()
        Result.success(Unit)
    } catch (e: Exception) {
        FileLog.e(e)
        Result.failure(e)
    }

    suspend fun delete(id: String): Result<Unit> = try {
        if (id.isBlank()) Result.failure(IllegalArgumentException("empty id"))
        else {
            db.collection(COLLECTION).document(id).delete().await()
            Result.success(Unit)
        }
    } catch (e: Exception) {
        FileLog.e(e)
        Result.failure(e)
    }

    // ── Ommaviy o'qish ───────────────────────────────────────────────────

    /**
     * Faol bannerlar uchun real-time query. [YuzAdFeed] shu listener orqali
     * admin qo'shgan yoki o'chirgan reklamani barcha ochiq ekranlarga darhol
     * yetkazadi — 10 daqiqalik kesh bilan qolib ketmaydi.
     */
    internal fun activeQuery(): Query = db.collection(COLLECTION)
        .whereEqualTo("isActive", true)
        .limit(ACTIVE_LIMIT)

    internal fun bannersFrom(snapshot: QuerySnapshot): List<AdBanner> =
        snapshot.documents
            .mapNotNull { doc -> doc.data?.let { toBanner(doc.id, it) } }
            .filter { it.isActive && (it.title.isNotBlank() || it.imageUrl.isNotBlank()) }
            .sortedWith(compareByDescending<AdBanner> { it.createdAt })

    fun pick(list: List<AdBanner>, forChats: Boolean): AdBanner? =
        list.firstOrNull { if (forChats) it.showInChats else it.showInFunctions }

    private fun toBanner(id: String, data: Map<String, Any?>): AdBanner {
        fun text(key: String): String = (data[key] as? String).orEmpty().trim()
        fun flag(key: String, def: Boolean): Boolean = data[key] as? Boolean ?: def
        return AdBanner(
            id = id,
            title = text("title"),
            subtitle = text("subtitle"),
            imageUrl = text("imageUrl"),
            linkUrl = text("linkUrl"),
            theme = text("theme"),
            showInChats = flag("showInChats", false),
            showInFunctions = flag("showInFunctions", false),
            isActive = flag("isActive", true),
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
            updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L
        )
    }

    // ── Offline kesh ─────────────────────────────────────────────────────

    private fun prefs(): SharedPreferences? = try {
        ApplicationLoader.applicationContext
            ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    } catch (_: Exception) {
        null
    }

    internal fun cacheActive(list: List<AdBanner>) {
        try {
            val array = JSONArray()
            for (b in list.take(8)) {
                array.put(
                    JSONObject()
                        .put("id", b.id)
                        .put("title", b.title)
                        .put("subtitle", b.subtitle)
                        .put("imageUrl", b.imageUrl)
                        .put("linkUrl", b.linkUrl)
                        .put("theme", b.theme)
                        .put("showInChats", b.showInChats)
                        .put("showInFunctions", b.showInFunctions)
                        .put("isActive", b.isActive)
                        .put("createdAt", b.createdAt)
                )
            }
            prefs()?.edit()
                ?.putString(KEY_ACTIVE, array.toString())
                ?.putLong(KEY_SYNCED_AT, System.currentTimeMillis())
                ?.apply()
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    fun loadCachedActive(): List<AdBanner> {
        val st = prefs() ?: return emptyList()
        val syncedAt = st.getLong(KEY_SYNCED_AT, 0L)
        if (System.currentTimeMillis() - syncedAt > CACHE_TTL_MS) return emptyList()
        val raw = st.getString(KEY_ACTIVE, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<AdBanner>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                list.add(
                    AdBanner(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        subtitle = o.optString("subtitle"),
                        imageUrl = o.optString("imageUrl"),
                        linkUrl = o.optString("linkUrl"),
                        theme = o.optString("theme"),
                        showInChats = o.optBoolean("showInChats", false),
                        showInFunctions = o.optBoolean("showInFunctions", false),
                        isActive = o.optBoolean("isActive", true),
                        createdAt = o.optLong("createdAt", 0L)
                    )
                )
            }
            list
        } catch (e: Exception) {
            FileLog.e(e)
            emptyList()
        }
    }
}

/**
 * Bannerlarni UI'ga yetkazuvchi qatlam: Firestore'ga real-time listener
 * o'rnatiladi, shuning uchun admin qo'shgan, tahrirlagan yoki o'chirgan
 * reklama barcha qurilmalarda va ochiq ekranlarda darhol yangilanadi.
 * Sovuq startda oxirgi kesh (72 soatlik TTL) darhol ko'rsatiladi, keyin
 * server holati ustidan yoziladi. Callback har doim UI thread'da.
 * Java tomonidan (`DialogsActivity`) SAM listener bilan chaqiriladi.
 */
object YuzAdFeed {

    fun interface Listener {
        fun onBanner(banner: AdBanner?)
    }

    private val lock = Any()

    @Volatile
    private var active: List<AdBanner>? = null

    @Volatile
    private var chatListener: Listener? = null

    @Volatile
    private var functionsListener: Listener? = null

    @Volatile
    private var registration: ListenerRegistration? = null

    /** Listener bir marta server javobini olgach, SDK'ning eski kesh nusxalari e'tiborsiz qoladi. */
    @Volatile
    private var serverSeen = false

    @JvmStatic
    fun loadChatBanner(listener: Listener) = register(true, listener)

    @JvmStatic
    fun loadFunctionsBanner(listener: Listener) = register(false, listener)

    private fun register(forChats: Boolean, listener: Listener) {
        synchronized(lock) {
            if (forChats) chatListener = listener else functionsListener = listener
            if (registration == null) {
                registration = YuzAdStore.activeQuery().addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        FileLog.e(error)
                        return@addSnapshotListener
                    }
                    if (snapshot == null) return@addSnapshotListener
                    val fromCache = snapshot.metadata.isFromCache
                    if (fromCache && serverSeen) return@addSnapshotListener
                    if (!fromCache) serverSeen = true
                    val list = YuzAdStore.bannersFrom(snapshot)
                    active = list
                    YuzAdStore.cacheActive(list)
                    dispatch()
                }
            }
        }
        deliver(active ?: YuzAdStore.loadCachedActive(), forChats, listener)
    }

    private fun dispatch() {
        val list = active ?: return
        synchronized(lock) {
            chatListener?.let { deliver(list, true, it) }
            functionsListener?.let { deliver(list, false, it) }
        }
    }

    private fun deliver(list: List<AdBanner>, forChats: Boolean, listener: Listener) {
        val banner = YuzAdStore.pick(list, forChats)
        AndroidUtilities.runOnUIThread {
            try {
                listener.onBanner(banner)
            } catch (t: Throwable) {
                FileLog.e(t)
            }
        }
    }
}
