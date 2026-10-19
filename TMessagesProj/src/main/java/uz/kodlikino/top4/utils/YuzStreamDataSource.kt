package uz.kodlikino.top4.utils

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
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
 *     Sabab: sukut gzip so'rovini googlevideo 403 bilan rad etadi.
 *
 * YuzGram #84: endi o'qish YuzRangeReader orqali — KICHIK, CHEKLANGAN Range
 * oynalari ketma-ketligi. Oldin initial open Range'SIZ (butun fayl) so'rovi
 * yuborardi, Google esa uzun qo'shiqni ~1 MB'da (128kbit/s ≈ 65s) kesib
 * 403 qaytarardi: shu tufayli faqat qisqa treklar eshitilar edi. Reader
 * oynalarni ulaydi, rad etilsa bayt joyidan yangi URL so'raydi va erta EOF
 * qaytarmaydi.
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
    private var reader: YuzRangeReader? = null
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
        val position = dataSpec.position.coerceAtLeast(0L)
        val specLength = if (dataSpec.length == C.LENGTH_UNSET.toLong()) -1L else dataSpec.length
        val stream = if (raw.startsWith(URI_PREFIX)) {
            val vid = raw.substring(URI_PREFIX.length)
            markerVid = vid
            YuzYouTubeApi.resolveStream(vid)
                ?: run {
                    Log.i(TAG, "resolve EMPTY vid=$vid")
                    throw FileNotFoundException("no audio stream for $vid")
                }
        } else {
            YuzAudioStream(raw, "m4a", 0L, YuzYouTubeApi.UA)
        }

        val r = YuzRangeReader(
            http = http,
            url = stream.url,
            ua = stream.ua,
            start = position,
            limit = specLength,
            expectedTotal = stream.approxBytes,
            refresh = markerVid?.let { vid ->
                { at: Long, cur: String -> YuzYouTubeApi.refreshStream(vid, at, cur) }
            },
            // Range/oyna faqat googlevideo uchun: Deezer CDN'iga eski
            // yaxshi ishlagan bitta GET yuboriladi.
            windows = stream.url.contains("googlevideo")
        )
        r.open()
        reader = r
        uri = Uri.parse(stream.url)

        val known = r.length
        openedLength = when {
            specLength >= 0L -> specLength
            known >= 0L -> known
            else -> C.LENGTH_UNSET.toLong()
        }
        bytesRemaining = if (openedLength == C.LENGTH_UNSET.toLong())
            Long.MAX_VALUE else openedLength
        Log.i(TAG, "open http pos=$position len=$openedLength total=$known")
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
        val r = reader ?: throw IOException("closed")
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        val cap = readLength.toLong().coerceAtMost(bytesRemaining).toInt()
        val n = try {
            r.read(buffer, offset, cap)
        } catch (e: IOException) {
            markerVid()?.let { YuzYouTubeApi.noteBadStream(it) }
            throw e
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= n
        // #86: o'qilgan baytlar taroq o'lchagichiga tushadi — shu songa
        // qarab oyna hajmi, deadline va format tanlanadi.
        YuzNetPulse.add(n)
        notifyBytes(n)
        return n
    }

    private fun markerVid(): String? {
        val raw = currentSpec?.uri?.toString() ?: return null
        return if (raw.startsWith(URI_PREFIX)) raw.substring(URI_PREFIX.length) else null
    }

    private fun notifyBytes(n: Int) {
        val l = listener ?: return
        val spec = currentSpec ?: return
        runCatching { l.onBytesTransferred(this, spec, false, n) }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { reader?.close() }
        runCatching { raf?.close() }
        reader = null
        raf = null
    }

    class Factory(private val http: OkHttpClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = YuzStreamDataSource(http)
    }
}
