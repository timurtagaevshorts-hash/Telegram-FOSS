package uz.kodlikino.top4.utils

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * YuzGram #68: NewPipeExtractor (TeamNewPipe) — YouTube oqimlarini ochishning
 * eng ishonchli ochiq manba kutubxonasi (NewPipe/LibreTube har oy yangilaydi,
 * bizning qo'lda yozilgan InnerTube zanjiri esa Google o'zgartirishlaridan
 * darhol sinadi). Marker URI ochilganda avval shu sinov qilinadi, so'ng
 * YuzYouTubeApi'ning o'z zanjiri zaxira bo'ladi.
 *
 * FAQAT audio URL olish ishlatiladi — qolgan arxitektura (navbat, radio,
 * fon ijrosi, YuzStreamDataSource) o'zgarmaydi.
 */
object YuzNpeExtractor {

    private const val TAG = "YuzMusic"

    // googlevideo oqimlari uchun oddiy brauzer UA (m4a URL'lari imzolangan).
    const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/140.0.0.0 Safari/537.36"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var ready = false

    private class OkDownloader(val http: OkHttpClient) : Downloader() {
        @Throws(IOException::class)
        override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
            val builder = Request.Builder().url(request.url())
            for ((key, values) in request.headers()) {
                for (value in values) builder.addHeader(key, value)
            }
            val data = request.dataToSend()
            val method = request.httpMethod()
            if (method == "GET" || method == "HEAD") {
                builder.method(method, null)
            } else {
                val bytes = data ?: ByteArray(0)
                val ct = builder.build().header("Content-Type")
                    ?: "application/octet-stream"
                builder.method(method, bytes.toRequestBody(ct.toMediaType()))
            }
            val response = http.newCall(builder.build()).execute()
            try {
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful && (response.code == 403 || response.code == 429)) {
                    throw IOException("HTTP ${response.code} (blocked) ${request.url().take(60)}")
                }
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    body,
                    response.request.url.toString()
                )
            } finally {
                response.close()
            }
        }
    }

    /** Bir marta init — keyin xotira sarfi yo'q. */
    private fun ensureInit() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            try {
                NewPipe.init(OkDownloader(http))
                ready = true
                Log.i(TAG, "npe init OK")
            } catch (e: Throwable) {
                Log.i(TAG, "npe init FAIL ${e.message}")
            }
        }
    }

    /**
     * videoId uchun sof audio oqimlar (bitrate bo'yicha eng yuqori avval).
     * Xato bo'lsa bo'sh ro'yxat — chaqiruvchi o'z zanjiriga o'tadi.
     */
    fun audioStreams(videoId: String, max: Int = 4): List<YuzAudioStream> {
        if (!ready) ensureInit()
        if (!ready) return emptyList()
        return try {
            val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
            val out = ArrayList<YuzAudioStream>()
            for (s in info.audioStreams.sortedByDescending { it.averageBitrate }) {
                if (out.size >= max) break
                val url = if (s.isUrl) s.content else null
                if (url.isNullOrBlank()) continue
                out.add(
                    YuzAudioStream(
                        url = url,
                        ext = s.format?.name?.lowercase() ?: "m4a",
                        approxBytes = clenOf(url),
                        ua = WEB_UA,
                        bitrate = s.averageBitrate
                    )
                )
            }
            Log.i(TAG, "npe $videoId -> ${out.size} audio (${out.firstOrNull()?.ext})")
            out
        } catch (e: Throwable) {
            Log.i(TAG, "npe fail $videoId ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            emptyList()
        }
    }

    /** googlevideo URL'laridagi clen= paramtri — to'liq fayl hajmi (#84 reader). */
    private fun clenOf(url: String): Long {
        val i = url.indexOf("clen=")
        if (i < 0) return 0L
        var j = i + 5
        while (j < url.length && url[j] in '0'..'9') j++
        return url.substring(i + 5, j).toLongOrNull() ?: 0L
    }
}
