package uz.kodlikino.top4.utils

import android.app.Activity
import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * YuzGram #113 crash-yig'uvchi.
 *
 * Telefon kompyuterga ulanmagan holatda ham crash SABABINI topish uchun:
 * istalgan nobilan crash stack-trace'i faylga yoziladi, keyingi ochilishda
 * LaunchActivity uni tanlanadigan dialog'da ko'rsatadi — foydalanuvchi
 * matnni nusxalab pult'ga (Telegram) yuboradi.
 *
 * Handler zanjiri buzilmaydi: biz fayl yozib, oldingi (Telegram'ning)
 * handler'ni chaqiramiz — unix harakati o'zgarishsiz davom etadi.
 */
object YuzCrashJar {

    private const val FILE = "yuz_last_crash.txt"
    private const val MAX_CHARS = 20_000

    @JvmStatic
    fun install(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val app = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                pw.println("thread=" + t.name)
                e.printStackTrace(pw)
                pw.flush()
                File(app.filesDir, FILE).writeText(sw.toString())
            } catch (_: Throwable) {
                // Fayl yozolmasa ham crash oqimiga xalaqit bermaymiz.
            }
            try {
                prev?.uncaughtException(t, e)
            } catch (_: Throwable) {
            }
        }
    }

    /** Fayl bo'lsa — o'qib o'chiradi va dialog'da ko'rsatadi (bir kora). */
    @JvmStatic
    fun showLastCrashIfAny(activity: Activity) {
        try {
            if (activity.isFinishing) return
            val f = File(activity.filesDir, FILE)
            if (!f.exists()) return
            val text = f.readText()
            f.delete()
            val tv = TextView(activity).apply {
                setText(text.take(MAX_CHARS))
                setTextIsSelectable(true)
                movementMethod = ScrollingMovementMethod()
                textSize = 11f
                val pad = (10 * activity.resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val scroll = ScrollView(activity)
            scroll.addView(tv)
            // #156: release build'da ham crash matni ko'rinadi (bizning yagona
            // adb'siz diagnostika kanalimiz), lekin ichki "pult" tili o'rniga
            // foydalanuvchiga tushunarli til + bir bosishli nusxa olish.
            android.app.AlertDialog.Builder(activity)
                .setTitle("Ilova nosozligi qayd etildi")
                .setView(scroll)
                .setNeutralButton("Nusxa olish") { _, _ -> copyToClipboard(activity, text) }
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (_: Throwable) {
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("yuzgram-crash", text))
            android.widget.Toast.makeText(context, "Nusxa olindi", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
