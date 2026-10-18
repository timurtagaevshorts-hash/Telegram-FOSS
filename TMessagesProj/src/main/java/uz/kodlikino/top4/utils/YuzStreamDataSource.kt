package uz.kodlikino.top4.utils

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * YuzGram #67: media3 uchun yagona manba — barcha HTTP oqimlar shu
 * DataSource orqali o'tadi (media3-datasource-okhttp 1.0.2'da
 * RequestFactory yo'q, shuning uchun qo'lda yozildi).
 *
 * Ikkita vazifa:
 *  1) "https://yuz.stream/v/<id>" marker URI'larni real googlevideo URL'iga
 *     aylantirish — open() media3'ning LOADER IPIDA chaqiriladi, shuning
 *     uchun UI qotmaydi va keyingi qo'shiq oldindan tayyorlanadi.
 *  2) SOZLANGAN SO'ROVLAR: "Accept-Encoding: identity" + formatga mos UA.
 *     Sabab: sukut gzip so'rovini googlevideo 403 bilan rad etadi
 *     (telefon logcat'da tasdiqlangan).
 *
 * Uch xil manba: marker (YouTube oqimi), oddiy https (Deezer preview),
 * file:// (keshdagi local fayl).
 */
class YuzStreamDataSource(private val http: OkHttpClient) : DataSource {

    companion object {
        const val URI_PREFIX = "https://yuz.stream/v/"
        private const val TAG = "YuzMusic"
    }

    private var listener: TransferListener? = null
    private var currentSpec: DataSpec? = null
    private var response: Response? = null
    private var body: InputStream? = null
    private var raf: RandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining = Long.MAX_VALUE
    private var openedLength = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        listener = transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        currentSpec = dataSpec
        val raw = dataSpec.uri.toString()
        if (raw.startsWith("file://")) return openFile(dataSpec)
        return openHttp(dataSpec, raw)
    }

    private fun openFile(dataSpec: DataSpec): Long {
        val path = dataSpec.uri.path
            ?: throw FileNotFoundException("no path in ${dataSpec.uri}")
        val f = File(path)
        if (!f.exists()) throw FileNotFoundException(path)
        val raf0 = RandomAccessFile(f, "r")
        raf = raf0
        uri = dataSpec.uri
        raf0.seek(dataSpec.position.coerceAtLeast(0L))
        val total = raf0.length() - dataSpec.position
        val len = if (dataSpec.length != C.LENGTH_UNSET.toLong())
            dataSpec.length.coerceAtMost(total) else total
        bytesRemaining = len
        openedLength = len
        Log.i(TAG, "open local ${f.name} pos=${dataSpec.position} len=$len")
        return len
    }

    private fun openHttp(dataSpec: DataSpec, raw: String): Long {
        var markerVid: String? = null
        val (url, ua) = if (raw.startsWith(URI_PREFIX)) {
            val vid = raw.substring(URI_PREFIX.length)
            markerVid = vid
            val stream = YuzYouTubeApi.resolveStream(vid)
                ?: run {
                    Log.i(TAG, "resolve EMPTY vid=$vid")
                    throw FileNotFoundException("no audio stream for $vid")
                }
            Log.i(TAG, "resolved vid=$vid ext=${stream.ext} bits=${stream.approxBytes}")
            stream.url to stream.ua
        } else {
            raw to YuzYouTubeApi.UA
        }

        val builder = Request.Builder().url(url)
            // #67 403 tuzatishi: googlevideo gzip so'rovini yoqtirmaydi.
            .header("User-Agent", ua)
            .header("Accept-Encoding", "identity")
        val position = dataSpec.position
        val length = dataSpec.length
        when {
            length != C.LENGTH_UNSET.toLong() ->
                builder.header("Range", "bytes=$position-${position + length - 1}")
            position != 0L ->
                builder.header("Range", "bytes=$position-")
        }

        val resp = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            // #69: tarmoq xatosida ham nomzod aylanadi — keyingi open()
            // boshqa URL'ni oladi (prefetch cache yangilanadi).
            markerVid?.let { YuzYouTubeApi.noteBadStream(it) }
            throw e
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            Log.i(TAG, "stream HTTP $code url=${url.take(70)}")
            markerVid?.let { YuzYouTubeApi.noteBadStream(it) }
            throw IOException("HTTP $code")
        }
        response = resp
        body = resp.body?.byteStream()
            ?: run { resp.close(); throw IOException("empty body") }
        uri = Uri.parse(url)

        val contentLen = resp.body?.contentLength() ?: -1L
        openedLength = when {
            length != C.LENGTH_UNSET.toLong() -> length
            contentLen >= 0L -> contentLen
            else -> C.LENGTH_UNSET.toLong()
        }
        bytesRemaining = if (openedLength == C.LENGTH_UNSET.toLong())
            Long.MAX_VALUE else openedLength
        Log.i(TAG, "open http code=${resp.code} len=$openedLength")
        return openedLength
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        val rf = raf
        if (rf != null) {
            if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
            val cap = readLength.toLong().coerceAtMost(bytesRemaining).toInt()
            val n = runCatching { rf.read(buffer, offset, cap) }.getOrDefault(-1)
            if (n <= 0) return C.RESULT_END_OF_INPUT
            bytesRemaining -= n
            notifyBytes(n)
            return n
        }
        val stream = body ?: throw IOException("closed")
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        val cap = readLength.toLong().coerceAtMost(bytesRemaining).toInt()
        val n = stream.read(buffer, offset, cap)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= n
        notifyBytes(n)
        return n
    }

    private fun notifyBytes(n: Int) {
        val l = listener ?: return
        val spec = currentSpec ?: return
        runCatching { l.onBytesTransferred(this, spec, false, n) }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { body?.close() }
        runCatching { response?.close() }
        runCatching { raf?.close() }
        body = null
        response = null
        raf = null
    }

    class Factory(private val http: OkHttpClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = YuzStreamDataSource(http)
    }
}
