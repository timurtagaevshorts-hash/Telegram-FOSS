package uz.kodlikino.top4.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * YuzGram #87: bildirishnoma va qulf ekranidagi pleyerning muqovalari.
 *
 * Nima uchun kerak: media3 1.0.2'ning `MediaMetadata`'da bitmap maydoni
 * YO'Q — faqat `artworkUri`, sukut bitmap loader'i esa `http(s)://`
 * URL'larni umuman tortmaydi. Natijada muqova faqat bizning loader orqali
 * chiqadi. Yuklash async: media3 bildirishnomani bitmap tayyor bo'lgach
 * o'zi qayta quradi (shuning uchun ijro boshlanishi bilan muqova
 * "chiqib qolmaydi", bir necha yuz ms'dan keyin paydo bo'ladi).
 *
 * Kesh: xotira (LruCache, hajm bo'yicha) + disk (`filesDir/yuzart`), shunda
 * bir marta ko'rilgan muqova qayta tarmoqqa chiqmaydi — past internetda ham
 * qulf ekrani darhol to'la ko'rinadi.
 */
object YuzArtwork {

    private const val TAG = "YuzMusic"
    private const val SIDE = 512
    private const val DIR = "yuzart"
    private const val MEM_BYTES = 3 * 1024 * 1024
    private const val KEEP_FILES = 80

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val io = Executors.newFixedThreadPool(2)

    private val mem = object : LruCache<String, Bitmap>(MEM_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Ijro oldidan muqovani isitib qo'yish (natija cache'ga tushadi). */
    fun prefetch(context: Context, url: String?) {
        if (!url.isNullOrBlank() && mem.get(url) == null) load(context, url)
    }

    fun load(context: Context, url: String?): ListenableFuture<Bitmap> {
        val f = SettableFuture.create<Bitmap>()
        if (url.isNullOrBlank()) {
            f.setException(IllegalArgumentException("muqova URL'i bo'sh"))
            return f
        }
        val hit = mem.get(url)
        if (hit != null) {
            f.set(hit)
            return f
        }
        io.execute {
            val bmp = runCatching { fetch(context, url) }.getOrNull()
            if (bmp == null) f.setException(IllegalStateException("muqova yuklanmadi: $url"))
            else f.set(bmp)
        }
        return f
    }

    /** Baytlardan bitmap (media3'ning artworkData yo'li uchun). */
    fun decode(data: ByteArray): ListenableFuture<Bitmap> {
        val f = SettableFuture.create<Bitmap>()
        io.execute {
            val bmp = downsample(data)
            if (bmp == null) f.setException(IllegalStateException("muqova o'qilmadi"))
            else f.set(bmp)
        }
        return f
    }

    // ---------------------------------------------------------------- fetch

    private fun fetch(context: Context, url: String): Bitmap? {
        mem.get(url)?.let { return it }
        fromDisk(context, url)?.let {
            mem.put(url, it)
            return it
        }
        val bytes = (if (url.startsWith("http")) httpBytes(url) else localBytes(context, url))
            ?: return null
        val bmp = downsample(bytes) ?: return null
        mem.put(url, bmp)
        toDisk(context, url, bytes)
        Log.i(TAG, "artwork ok ${url.take(48)} ${bmp.width}x${bmp.height}")
        return bmp
    }

    private fun httpBytes(url: String): ByteArray? {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        return runCatching {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.bytes()
            }
        }.getOrNull()
    }

    private fun localBytes(context: Context, url: String): ByteArray? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
    }.getOrNull()

    // ---------------------------------------------------------------- kesh

    private fun file(context: Context, url: String): File {
        val d = File(context.filesDir, DIR)
        if (!d.exists()) d.mkdirs()
        return File(d, sha1(url))
    }

    private fun fromDisk(context: Context, url: String): Bitmap? {
        val f = file(context, url)
        if (!f.isFile) return null
        return runCatching {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.absolutePath, o)
            o.inJustDecodeBounds = false
            o.inSampleSize = sample(o.outWidth, o.outHeight)
            BitmapFactory.decodeFile(f.absolutePath, o)
        }.getOrNull()
    }

    /** Asl baytlar saqlanadi — keyingi ochishda sifat yo'qolmaydi. */
    private fun toDisk(context: Context, url: String, bytes: ByteArray) {
        runCatching {
            val f = file(context, url)
            if (!f.isFile) f.outputStream().use { it.write(bytes) }
            trim(context)
        }
    }

    private fun trim(context: Context) {
        val d = File(context.filesDir, DIR)
        val fs = d.listFiles() ?: return
        if (fs.size <= KEEP_FILES) return
        fs.sortBy { it.lastModified() }
        for (i in 0 until fs.size - KEEP_FILES) runCatching { fs[i].delete() }
    }

    // ---------------------------------------------------------------- decode

    private fun downsample(bytes: ByteArray): Bitmap? = runCatching {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        o.inJustDecodeBounds = false
        o.inSampleSize = sample(o.outWidth, o.outHeight)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }.getOrNull()

    /** Tomonlarni [SIDE] atrofiga tushiruvchi daraja (2^n, tezkor decode). */
    private fun sample(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        while (maxOf(w, h) / (s * 2L) >= SIDE) s *= 2
        return s
    }

    private fun sha1(url: String): String = runCatching {
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }.getOrDefault(url.hashCode().toString(16))
}
