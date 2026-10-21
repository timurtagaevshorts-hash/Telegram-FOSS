package uz.kodlikino.top4.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uz.kodlikino.top4.models.Reel

/**
 * Reels uchun "oldingi kishi" yuklash (preload) — eski loyihadagi
 * `ReelsPreloadManager` mexanizmining qisqartirilgan nusxasi.
 *
 * G'oya: foydalanuvchi N-reel'ni ko'rayotganda N+1, N+2 (va ortda N-1)
 * videolarining bosh qismini [YuzVideoCache] disk keshiga oldindan yozib
 * qo'yamiz. Snaps'langach video keshdan o'qiladi va deyarli darhol boshlanadi.
 *
 * Nima uchun `CacheWriter` EMAS: media3 1.0.2'de CacheWriter quruvchisining
 * imzosi versiyalar bo'ylab o'zgargan; bu yerda ancha sodda va versiyaga
 * bog'liq bo'lmagan yo'l tanlandi — [CacheDataSource] orqali cheklangan
 * (birinchi ~1.5 MB) o'qish. O'qish avtomatik ravishda keshga yozadi.
 *
 * Xavfsizlik chegaralari:
 *  - har bir video faqat bir marta "illyutatsiya" qilinadi ([inflight]);
 *  - faqat bitta coroutine navbatda ishlaydi — telefon tarmog'ini to'ymaslik
 *    uchun preload'lar ketma-ket (launch ichida for emas, alohida launchlar
 *    FIFO Dispatchers.IO disk ichida tartibli boshlanadi).
 */
object YuzReelPreloader {

    private const val TAG = "YuzReelPreloader"

    // #99: tarmoq turiga qarab oldindan yuklash hajmi.
    private const val WARM_BYTES_WIFI = 2_500_000L
    private const val WARM_BYTES_CELLULAR = 700_000L
    private const val NONE = 0L

    /** oldinga nechta reel illyutatsiya qilinadi (qorin bu tomonda siljitiladi). */
    private const val AHEAD = 2

    /** ortga nechta reel (teskari snapda ham kesh ishlasin). */
    private const val BEHIND = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** URL'ning kesh-kaliti (query'siz) — takroriy ishlarni oldini oladi. */
    private val inflight = HashSet<String>()

    /** #99: yangi snap avvalgi navbatni bekor qiladi (generatsiya hisobagichi). */
    @Volatile
    private var generation = 0L

    /**
     * [center] atrofidagi reel'larni oldindan yuklashga buyruq. Faol sahifa
     * almashtganda [uz.kodlikino.top4.ui.ReelsActivity]ning snap-listener'idan
     * chaqiriladi. Ro'yxat bo'lsa ham bo'sh bo'lmasligi kerak.
     */
    fun preloadAround(context: Context, reels: List<Reel>, center: Int) {
        if (reels.isEmpty() || center < 0) return
        // Butun ish IO oqimida: [YuzVideoCache.cache] birinchi chaqiruvda
        // SimpleCache'ni disk + SQLite bilan ochadi — main thread'da bu
        // birinchi kadrni sekinlashtirishi mumkin.
        // #120: NetworkReachability.kind() tizim IPC'si (ConnectivityManager) —
        // uni ham main IP'da chaqirish snap paytida kadrni tortib
        // qo'ymasligi uchun butun tekshiruv IO ichiga ko'chirildi.
        // Uzilgan tarmoqda keraksiz urinish va trafik sarfini oldini olamiz.
        val gen = ++generation
        scope.launch {
            val app = context.applicationContext
            if (NetworkReachability.kind(app) == NetworkReachability.NetKind.NONE) return@launch
            val limit = when (NetworkReachability.kind(app)) {
                NetworkReachability.NetKind.WIFI -> WARM_BYTES_WIFI
                NetworkReachability.NetKind.CELLULAR -> WARM_BYTES_CELLULAR
                else -> WARM_BYTES_CELLULAR
            }
            // oldingilar ustuvor: foydalanuvchi ko'pincha pastga (keyingi) ketadi.
            for (off in 1..AHEAD) {
                if (generation != gen) return@launch
                reels.getOrNull(center + off)?.let { warm(app, it.videoUrl, limit, gen) }
            }
            for (off in 1..BEHIND) {
                if (generation != gen) return@launch
                reels.getOrNull(center - off)?.let { warm(app, it.videoUrl, limit, gen) }
            }
        }
    }

    /**
     * Bitta videoning bosh qismini keshga tortadi. [inflight] tufayli bir URL
     * asossida faqat borta boradi; tugagach yoki xatoda set'dan olib tashlanadi.
     */
    @JvmOverloads
    fun warm(context: Context, url: String, bytes: Long = WARM_BYTES_WIFI, gen: Long = -1L) {
        if (url.isBlank() || bytes <= 0L) return
        val app = context.applicationContext
        val key = cacheKey(url)
        val start = synchronized(this) { inflight.add(key) }
        if (!start) return
        scope.launch {
            try {
                val source: DataSource = YuzVideoCache.dataSourceFactory(app).createDataSource()
                val spec = DataSpec.Builder()
                    .setUri(Uri.parse(url))
                    .setPosition(0L)
                    .setLength(bytes)
                    .build()
                try {
                    // media3: open() uzunlik(qaytaradi, o'qish manbai SHU source.
                    source.open(spec)
                    val buffer = ByteArray(64 * 1024)
                    var transferred = 0L
                    while (transferred < bytes) {
                        // #99: foydalanuvchi ketgani — yarim o'qishni kesamiz;
                        // CacheDataSource yozgan qismini to'lamiz, keyingi
                        // urinish qolgandan davom etadi.
                        if (gen >= 0 && generation != gen) break
                        // media3 1.0.2'da NetworkConfigurable yo'q (1.1+) —
                        // o'z ConnectivityMonitor'imiz bilan tekshiramiz.
                        if (!NetworkReachability.isOnline(app)) break
                        val chunk = minOf(buffer.size.toLong(), bytes - transferred).toInt()
                        // read() versiyaniga qarab Int yoki Long qaytadi — ikkalasi ham
                        // shu kod bilan ishlaydi (EOF/0 == to'xtash).
                        val n = source.read(buffer, 0, chunk)
                        if (n <= 0) break
                        transferred += n
                    }
                } finally {
                    try {
                        source.close()
                    } catch (_: Exception) {
                        // close'dagi xato preload'ning muvaffaqiyatiga ta'sir qilmaydi.
                    }
                }
            } catch (e: Exception) {
                // Tarmoq xatosi odatiy hol — feed'ni buzmaydi, keyingi snap'da qayta uriladi.
                Log.d(TAG, "warm $key: ${e.message}")
                synchronized(this@YuzReelPreloader) { inflight.remove(key) }
                return@launch
            }
            // Muvaffaqiyatda ham kalitni ochamiz — yarim qolgan fayl qayta urilishi mumkin.
            synchronized(this@YuzReelPreloader) { inflight.remove(key) }
        }
    }

    /** [YuzVideoCache] kaliti bilan bir xil: `scheme://host/path` (query/fragment siz). */
    private fun cacheKey(url: String): String {
        return try {
            Uri.parse(url).buildUpon().clearQuery().fragment(null).build().toString()
        } catch (_: Exception) {
            url
        }
    }
}
