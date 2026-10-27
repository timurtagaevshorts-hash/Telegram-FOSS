package uz.kodlikino.top4.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bajarilayotgan (va yaqinda xato bilan tugagan) reel yuklashlarining lokal
 * trekeri.
 *
 * Nega kerak: yuklash [uz.kodlikino.top4.services.ReelUploadService] ichida
 * fonda ketadi — foydalanuvchi upload ekranini yopadi. Reel Firestore'ga
 * yozilmaguncha u hech qayerda ko'rinmasdi, ya'ni "yukladim, lekin profilimda
 * yo'q" degan taassurot qolardi. Endi har bir yuklash id bo'yicha kuzatiladi:
 * profil > Reels grid'i trekerdan "yuklanmoqda" kataklarini oladi, progress
 * shu yerdan yangilanadi, tugagach keshga yozilgan reel grid'da paydo bo'ladi.
 *
 * Saqlash: `SharedPreferences` + jarayon ichidagi nusxa. Ilov qayta ochilsa
 * (yoki yuklash jarayoni o'ldirilgan bo'lsa) yozuvlar yo'qolmaydi — [list]
 * chaqirilganda eskirgan faol yozuv "to'xtab qoldi" deb belgilanadi.
 */
object YuzReelUploads {

    enum class Phase { PREPARE, COMPRESS, UPLOAD, SAVE, DONE, FAILED }

    data class Upload(
        val id: String,
        val uri: String,
        val fileName: String,
        val title: String,
        val hashtags: String,
        val sizeBytes: Long,
        val thumbPath: String,
        val account: Int,
        val startedAt: Long,
        /**
         * Yuklanadigan videoning lokal nusxasi (ilova keshida). Servis uni
         * birinchi qadamda tayyorlaydi va muvaffaqiyatgacha saqlaydi: xato
         * bo'lsa foydalanuvchi videoni qaytadan tanlamasdan [retry] qila oladi.
         */
        val sourcePath: String = "",
        val updatedAt: Long = startedAt,
        val progress: Int = 0,
        val phase: Phase = Phase.PREPARE,
        val error: String = ""
    ) {
        val failed: Boolean get() = phase == Phase.FAILED
        /** Trekerdagi yozuv tugagan (muvaffaqiyatli yoki xato) — grid'da qolmaydi. */
        val finished: Boolean get() = phase == Phase.DONE || phase == Phase.FAILED
    }

    private const val PREFS = "yuz_reel_uploads"
    private const val KEY_LIST = "pending"
    /** Faol yozuv shu vaqt davomida yangilanmasa — jarayon o'lgan deb hisoblanadi. */
    private const val STALL_MS = 10 * 60 * 1000L
    /** Xato yozuvlar grid'da shuncha vaqt turadi (foydalanuvchi ko'rib ulgursin). */
    private const val FAILED_KEEP_MS = 60 * 60 * 1000L
    private const val THUMB_DIR = "reel_pending_thumbs"

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

    fun listForAccount(account: Int): List<Upload> =
        list().filter { it.account == account }

    fun find(id: String): Upload? = list().firstOrNull { it.id == id }

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

    /** Yuklash muvaffaqiyatli — yozuv, lokal kadr va manba nusxa o'chadi. */
    fun complete(id: String) {
        val items = ensureLoaded()
        val removed = synchronized(items) {
            val found = items.firstOrNull { it.id == id }
            items.removeAll { it.id == id }
            found
        }
        deleteQuietly(removed?.thumbPath)
        deleteQuietly(removed?.sourcePath)
        persist()
        notifyChanged()
    }

    /**
     * Servis manba nusxani tayyorlagach yozuvga yozadi. Nusxa trekerdagi
     * `Complete`gacha turadi — xato bo'lsa [retry] shundan foydalanadi.
     */
    fun setSourcePath(id: String, path: String) {
        if (id.isBlank() || path.isBlank()) return
        mutate { items ->
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) items[idx] = items[idx].copy(sourcePath = path)
        }
    }

    /** Xato yozuvni lokal nusxadan qayta boshlash mumkinmi. */
    fun canRetry(upload: Upload): Boolean =
        upload.sourcePath.isNotBlank() && File(upload.sourcePath).let { it.isFile && it.length() > 0L }

    /**
     * Xato bilan tugagan yuklashni qayta boshlaydi — video qaytadan tanlanmaydi.
     * @return `true` bo'lsa servisga topshirildi va yozuv PREPARE holatiga qaytdi.
     */
    fun retry(context: Context?, upload: Upload): Boolean {
        if (!canRetry(upload)) return false
        val ctx = context ?: ApplicationLoader.applicationContext ?: return false
        val source = File(upload.sourcePath)
        try {
            uz.kodlikino.top4.services.ReelUploadService.startUpload(
                context = ctx,
                uploadId = upload.id,
                videoUri = android.net.Uri.fromFile(source),
                videoName = upload.fileName,
                videoSize = source.length(),
                caption = upload.title,
                hashtags = upload.hashtags,
                thumbPath = upload.thumbPath,
                account = upload.account,
                sourcePath = source.absolutePath
            )
        } catch (e: Exception) {
            FileLog.e(e)
            return false
        }
        mutate { items ->
            val idx = items.indexOfFirst { it.id == upload.id }
            if (idx >= 0) {
                items[idx] = items[idx].copy(
                    progress = 0,
                    phase = Phase.PREPARE,
                    error = "",
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        return true
    }

    /** Foydalanuvchi ✕ bosdi — yozuv olib tashlanadi (servis ham to'xtatiladi). */
    fun dismiss(context: Context?, id: String, cancelRunning: Boolean) {
        if (cancelRunning) {
            try {
                uz.kodlikino.top4.services.ReelUploadService.cancelUpload(context, id)
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }
        complete(id)
    }

    /** Yuklash uchun lokal kadr fayli yo'li (kadr bo'lmasa bo'sh satr). */
    fun thumbPathFor(id: String): String {
        val ctx = ApplicationLoader.applicationContext ?: return ""
        val dir = File(ctx.cacheDir, THUMB_DIR)
        if (!dir.exists() && !dir.mkdirs()) return ""
        return File(dir, "$id.jpg").absolutePath
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
                uri = obj.optString("uri"),
                fileName = obj.optString("fileName"),
                title = obj.optString("title"),
                hashtags = obj.optString("hashtags"),
                sizeBytes = obj.optLong("sizeBytes", -1L),
                thumbPath = obj.optString("thumbPath"),
                account = obj.optInt("account", 0),
                startedAt = obj.optLong("startedAt", 0L),
                sourcePath = obj.optString("sourcePath"),
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

    private fun serialize(item: Upload): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("uri", item.uri)
        put("fileName", item.fileName)
        put("title", item.title)
        put("hashtags", item.hashtags)
        put("sizeBytes", item.sizeBytes)
        put("thumbPath", item.thumbPath)
        put("account", item.account)
        put("startedAt", item.startedAt)
        put("sourcePath", item.sourcePath)
        put("updatedAt", item.updatedAt)
        put("progress", item.progress)
        put("phase", item.phase.name)
        put("error", item.error)
    }

    /**
     * Eskirganlarni tozalash: faol yozuv 10 daqiqa yangilanmasa (jarayon
     * o'ldirilgan) — xato holatiga o'tadi; xato yozuv 1 soatdan keyin o'chadi.
     * @return ro'yxat o'zgargan bo'lsa true.
     */
    private fun pruneLocked(items: MutableList<Upload>): Boolean {
        val now = System.currentTimeMillis()
        var changed = false
        val running = try {
            uz.kodlikino.top4.services.ReelUploadService.isUploading()
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
                        deleteQuietly(item.thumbPath)
                        deleteQuietly(item.sourcePath)
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
        try {
            val array = JSONArray()
            synchronized(items) { items.forEach { array.put(serialize(it)) } }
            prefs()?.edit()?.putString(KEY_LIST, array.toString())?.apply()
        } catch (e: Exception) {
            FileLog.e(e)
        }
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

    private fun deleteQuietly(path: String?) {
        if (path.isNullOrBlank()) return
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
    }

    private fun prefs() = ApplicationLoader.applicationContext
        ?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
