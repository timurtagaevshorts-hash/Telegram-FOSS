package uz.kodlikino.top4.utils

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Reels video'lari uchun umumiy DISK keshi (oldingi loyihadagi
 * `VideoCacheManager` mexanizmining qisqartirilgan nusxasi).
 *
 * Muammo: fork'dagi [uz.kodlikino.top4.ui.ReelsAdapter] yagona ExoPlayer islatadi,
 * lekin hech qanday kesh yo'q edi — har bir video har safar to'liq network'dan
 * tortilardi, orqaga qaytishda ham qaytadan. Bu esa "juda sekin yuklanadi"
 * shikoyatining asosiy sababi.
 *
 * Yechim: [SimpleCache] ustida qurilgan [CacheDataSource.Factory]'ni ExoPlayer'ga
 * beramiz — video bir marta yuklansa diskda qoladi, keyingi ko'rishlar deyarli
 * birinchi kadrda boshlanadi. Shu kesh [YuzReelPreloader] orqali keyingi reel'larni
 * oldindan illyutatsiya qilish uchun ham ishlatiladi.
 *
 * Cache kaliti URL'ning `?token=...` qismini tashlab qoyadi — shunda Firebase
 * Storage bir xil fayliga kelgan turli imzolangan URL'lar ham bitta kesh yozuviga
 * tushadi (oldingi loyihadagi `getCacheKey` g'oyasi).
 */
object YuzVideoCache {

    /** Disk keshi hajmi — 350 MB (arzon telefon uchun chegarada). */
    private const val MAX_BYTES = 350L * 1024L * 1024L

    @Volatile
    private var simpleCache: SimpleCache? = null

    // `?token=` va boshqa query-larni tashlab, faqat scheme://host/path qoldiramiz.
    private val cacheKeyFactory = object : CacheKeyFactory {
        override fun generateCacheKeyForUri(uri: Uri): String =
            uri.buildUpon().clearQuery().fragment(null).build().toString()
    }

    /** Jarayon davomida bir marta ochiladigan [SimpleCache]. */
    @Synchronized
    fun cache(context: Context): SimpleCache {
        simpleCache?.let { return it }
        val dir = File(context.applicationContext.cacheDir, "yuz_reels")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val created = SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext)
        )
        simpleCache = created
        return created
    }

    /**
     * O'qish/yozish uchun [CacheDataSource.Factory]. ExoPlayer buni
     * [androidx.media3.exoplayer.source.ProgressiveMediaSource] ichiga qo'yadi.
     */
    fun dataSourceFactory(context: Context): CacheDataSource.Factory {
        val app = context.applicationContext
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
            .setUserAgent("YuzGram/1.0 (Reels)")
        return CacheDataSource.Factory()
            .setCache(cache(app))
            .setUpstreamDataSourceFactory(http)
            .setCacheKeyFactory(cacheKeyFactory)
            // Kesh xatosi playback'ni buzmasin — tarmoqqa fallback.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
