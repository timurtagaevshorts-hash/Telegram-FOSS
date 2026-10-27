package uz.kodlikino.top4.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fonda ketayotgan e'lon yuklashlarining lokal trekeri.
 *
 * Nega kerak: yuklash [uz.kodlikino.top4.services.ClassifiedAdUploadService]
 * ichida davom etadi va foydalanuvchi "Joylash"dan keyin ekranni yopadi, ilovadan
 * foydalanishda davom etadi. Holat ko'rinmasa "yukladim, lekin e'lonim yo'q"
 * taassuroti qoladi — shuning uchun har bir yuklash id bo'yicha yoziladi:
 *  - Elonlar feed'i "yuklanmoqda … %" bannerini shu yerdan oladi;
 *  - xatodan keyin "Qayta urinish" aynan shu yozuvdan qayta boshlanadi;
 *  - ilova qayta ochilsa yozuvlar `SharedPreferences`'dan tiklanadi.
 *
 * Yozuvda e'lonning TO'LIQ matni va rasm URL'lari saqlanadi — shu sababli
 * servisga faqat id beriladi, qayta urinish uchun ekran ma'lumotlari kerak emas.
 */
object YuzAdUploads {

    enum class Phase { PREPARE, UPLOAD, SAVE, DONE, FAILED }

    /** Bir xil treker/servis ikki turdagi yozuvni tashiydi: e'lon va ish kartasi. */
    enum class Kind { AD, JOB }

    data class Upload(
        val id: String,
        val adId: String,
        val kind: Kind = Kind.AD,
        val account: Int,
        val title: String,
        val model: String,
        val description: String,
        val price: Long,
        val category: String,
        val phone: String,
        val region: String,
        val address: String,
        val createdAt: Long,
        val localUris: List<String>,
        val existingUrls: List<String>,
        val removedUrls: List<String>,
        /** Ish kartasi maydonlari ([Kind.JOB]); e'lonlarda bo'sh qoladi. */
        val listingType: String = "",
        val salaryType: String = "",
        val salaryAmount: Long = 0L,
        val startedAt: Long,
        val updatedAt: Long = startedAt,
        val progress: Int = 0,
        val phase: Phase = Phase.PREPARE,
        val error: String = ""
    ) {
        val failed: Boolean get() = phase == Phase.FAILED
        /** Yuklash tugagan (muvaffaqiyatli yoki xato) — banner faqat xato holatida qoladi. */
        val finished: Boolean get() = phase == Phase.DONE || phase == Phase.FAILED
        val imageCount: Int
            get() = (existingUrls.size + localUris.size)
                .coerceAtMost(if (kind == Kind.JOB) 1 else 5)
        val displayTitle: String get() = title.ifBlank { model }
    }

    private const val PREFS = "yuz_ad_uploads"
    private const val KEY_LIST = "pending"
    private const val STALL_MS = 10 * 60 * 1000L
    private const val FAILED_KEEP_MS = 60 * 60 * 1000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Runnable>()

    @Volatile
    private var cache: MutableList<Upload>? = null

    fun addListener(listener: Runnable) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    /** Joriy yozuvlar (eskirganlari tozalangan), eng yangi birinchi. */
    fun list(): List<Upload> {
        val items = ensureLoaded()
        val changed = pruneLocked(items)
        if (changed) persistLocked(items)
        return synchronized(items) { items.toList() }
    }

    fun find(id: String): Upload? = list().firstOrNull { it.id == id }

    /** Feed banneri uchun: shu akkauntdagi eng dolzarb yozuv (faol ustun). */
    fun bannerFor(account: Int, kind: Kind = Kind.AD): Upload? {
        val mine = list().filter { it.account == account && it.kind == kind }
        return mine.firstOrNull { !it.finished } ?: mine.firstOrNull { it.failed }
    }

    fun begin(upload: Upload) {
        mutate { items ->
            items.removeAll { it.id == upload.id }
            items.add(0, upload)
        }
    }

    fun phase(id: String, progress: Int, phase: Phase) {
        mutate { items ->
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val old = items[idx]
                items[idx] = old.copy(
                    progress = progress.coerceIn(0, 100),
                    phase = phase,
                    error = if (phase == Phase.FAILED) old.error else "",
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    fun fail(id: String, message: String) {
        mutate { items ->
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) {
                val old = items[idx]
                items[idx] = old.copy(
                    phase = Phase.FAILED,
                    error = message,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
    }

    fun complete(id: String) {
        val items = ensureLoaded()
        synchronized(items) { items.removeAll { it.id == id } }
        persist()
        notifyChanged()
    }

    /** "Qayta urinish" — xato yozuvini noldan boshlab servisga qaytaradi. */
    fun retry(context: Context?, id: String) {
        val item = find(id) ?: return
        if (!item.failed) return
        phase(id, 0, Phase.PREPARE)
        val ctx = context ?: ApplicationLoader.applicationContext ?: return
        try {
            uz.kodlikino.top4.services.ClassifiedAdUploadService.startUpload(ctx, id)
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    /** Foydalanuvchi bekor qildi — servis to'xtatiladi va yozuv o'chadi. */
    fun dismiss(context: Context?, id: String) {
        try {
            uz.kodlikino.top4.services.ClassifiedAdUploadService.cancelUpload(context, id)
        } catch (e: Exception) {
            FileLog.e(e)
        }
        complete(id)
    }

    private fun ensureLoaded(): MutableList<Upload> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val loaded = ArrayList<Upload>()
            try {
                val raw = prefs()?.getString(KEY_LIST, null)
                if (!raw.isNullOrBlank()) {
                    val array = JSONArray(raw)
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        parse(obj)?.let { loaded.add(it) }
                    }
                }
            } catch (e: Exception) {
                FileLog.e(e)
            }
            loaded.sortByDescending { it.startedAt }
            cache = loaded
            return loaded
        }
    }

    private fun parse(obj: JSONObject): Upload? {
        return try {
            val id = obj.optString("id")
            if (id.isBlank()) return null
            Upload(
                id = id,
                adId = obj.optString("adId"),
                kind = runCatching { Kind.valueOf(obj.optString("kind")) }
                    .getOrDefault(Kind.AD),
                account = obj.optInt("account", 0),
                title = obj.optString("title"),
                model = obj.optString("model"),
                description = obj.optString("description"),
                price = obj.optLong("price", 0L),
                category = obj.optString("category"),
                phone = obj.optString("phone"),
                region = obj.optString("region"),
                address = obj.optString("address"),
                createdAt = obj.optLong("createdAt", 0L),
                localUris = readStringList(obj, "localUris"),
                existingUrls = readStringList(obj, "existingUrls"),
                removedUrls = readStringList(obj, "removedUrls"),
                listingType = obj.optString("listingType"),
                salaryType = obj.optString("salaryType"),
                salaryAmount = obj.optLong("salaryAmount", 0L),
                startedAt = obj.optLong("startedAt", 0L),
                updatedAt = obj.optLong("updatedAt", 0L),
                progress = obj.optInt("progress", 0),
                phase = runCatching { Phase.valueOf(obj.optString("phase")) }
                    .getOrDefault(Phase.PREPARE),
                error = obj.optString("error")
            )
        } catch (e: Exception) {
            FileLog.e(e)
            null
        }
    }

    private fun readStringList(obj: JSONObject, key: String): List<String> {
        val array = obj.optJSONArray(key) ?: return emptyList()
        val result = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotBlank()) result.add(value)
        }
        return result
    }

    private fun serialize(item: Upload): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("adId", item.adId)
        put("kind", item.kind.name)
        put("account", item.account)
        put("title", item.title)
        put("model", item.model)
        put("description", item.description)
        put("price", item.price)
        put("category", item.category)
        put("phone", item.phone)
        put("region", item.region)
        put("address", item.address)
        put("createdAt", item.createdAt)
        put("localUris", JSONArray(item.localUris))
        put("existingUrls", JSONArray(item.existingUrls))
        put("removedUrls", JSONArray(item.removedUrls))
        put("listingType", item.listingType)
        put("salaryType", item.salaryType)
        put("salaryAmount", item.salaryAmount)
        put("startedAt", item.startedAt)
        put("updatedAt", item.updatedAt)
        put("progress", item.progress)
        put("phase", item.phase.name)
        put("error", item.error)
    }

    /**
     * Jarayon o'ldirilgan bo'lsa faol yozuv 10 daqiqa yangilanmasdan qoladi —
     * uni xato holatiga o'tkazamiz, aks holda "abadiy yuklanmoqda" ko'rinadi.
     * Xato yozuv 1 soatdan keyin o'chadi.
     */
    private fun pruneLocked(items: MutableList<Upload>): Boolean {
        val now = System.currentTimeMillis()
        var changed = false
        val running = try {
            uz.kodlikino.top4.services.ClassifiedAdUploadService.isUploading()
        } catch (_: Throwable) {
            false
        }
        synchronized(items) {
            val iterator = items.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                when {
                    item.phase == Phase.FAILED && now - item.updatedAt > FAILED_KEEP_MS -> {
                        iterator.remove()
                        changed = true
                    }
                    !item.finished && !running && now - item.updatedAt > STALL_MS -> {
                        val idx = items.indexOf(item)
                        items[idx] = item.copy(
                            phase = Phase.FAILED,
                            error = "Yuklash to'xtab qoldi",
                            updatedAt = now
                        )
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    private fun mutate(block: (MutableList<Upload>) -> Unit) {
        val items = ensureLoaded()
        synchronized(items) { block(items) }
        persist()
        notifyChanged()
    }

    private fun persist() {
        val items = cache ?: return
        persistLocked(items)
    }

    private fun persistLocked(items: MutableList<Upload>) {
        try {
            val array = JSONArray()
            synchronized(items) { items.forEach { array.put(serialize(it)) } }
            prefs()?.edit()?.putString(KEY_LIST, array.toString())?.apply()
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun notifyChanged() {
        if (listeners.isEmpty()) return
        val dispatch = Runnable {
            for (listener in listeners) {
                try {
                    listener.run()
                } catch (e: Exception) {
                    FileLog.e(e)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) dispatch.run() else mainHandler.post(dispatch)
    }

    private fun prefs() = ApplicationLoader.applicationContext
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
