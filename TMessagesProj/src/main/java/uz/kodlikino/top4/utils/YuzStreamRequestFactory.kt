package uz.kodlikino.top4.utils

import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Request
import java.io.FileNotFoundException
import java.io.IOException

/**
 * YuzGram #67: media3 uchun barcha HTTP so'rovlar shu fabrikadan o'tadi.
 *
 * Ikkita vazifa:
 *  1) "yuz://" marker URI'larni real googlevideo URL'iga aylantirish —
 *     resolve media3'ning LOADER IPIDA bo'ladi (aniq shu torda), shuning
 *     uchun UI qotmaydi va keyingi qo'shiq oldindan tayyorlanadi.
 *  2) SOZLANGAN SO'ROVLAR: "Accept-Encoding: identity" + formatga mos UA.
 *     Sabab: media3 DefaultHttpDataSource sukut "gzip" yuboradi va
 *     googlevideo buni 403 bilan rad etadi (telefon logcat'da tasdiqlangan).
 */
class YuzStreamRequestFactory : OkHttpDataSource.RequestFactory {

    companion object {
        const val URI_PREFIX = "https://yuz.stream/v/"
        private const val TAG = "YuzMusic"
    }

    override fun createRequest(dataSpec: DataSpec): Request {
        val uri = dataSpec.uri.toString()
        val fullUrl = if (uri.startsWith(URI_PREFIX)) {
            val vid = uri.substring(URI_PREFIX.length)
            val stream = YuzYouTubeApi.resolveStream(vid)
            if (stream == null) {
                Log.i(TAG, "resolve EMPTY vid=$vid")
                throw FileNotFoundException("no audio stream for $vid")
            }
            Log.i(TAG, "resolved vid=$vid ext=${stream.ext} bits=${stream.approxBytes}")
            stream
        } else {
            null
        }

        val builder = Request.Builder().url(fullUrl?.url ?: uri)
        builder.header("User-Agent", fullUrl?.ua ?: YuzYouTubeApi.UA)
        // #67 403 tuzatishi: googlevideo gzip so'rovini yoqtirmaydi.
        builder.header("Accept-Encoding", "identity")

        val position = dataSpec.position
        val length = dataSpec.length
        when {
            length != C.LENGTH_UNSET.toLong() ->
                builder.header("Range", "bytes=$position-${position + length - 1}")
            position != 0L ->
                builder.header("Range", "bytes=$position-")
        }
        return builder.build()
    }

    override fun getDataScheme(): Set<String> = setOf("http", "https")
}
