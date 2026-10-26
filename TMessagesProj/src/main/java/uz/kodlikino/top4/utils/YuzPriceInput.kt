package uz.kodlikino.top4.utils

import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.EditText

/**
 * Narx maydonlari uchun yagona formatlash: yozayotganda har uchta raqamdan keyin
 * bo'sh joy qo'yiladi (100 → 1 000 → 10 000). Faqat raqamlar saqlanadi, shuning
 * uchun filtr/qidiruvdagi qiymatlar [parse] orqali o'qiladi.
 */
object YuzPriceInput {

    private const val MAX_DIGITS = 15

    fun digits(text: CharSequence?): String =
        text?.toString().orEmpty().filter { it.isDigit() }.take(MAX_DIGITS)

    fun parse(text: CharSequence?): Long? = digits(text).toLongOrNull()

    fun format(rawDigits: String): String {
        if (rawDigits.isEmpty()) return ""
        val sb = StringBuilder(rawDigits.length + rawDigits.length / 3)
        var group = 0
        for (i in rawDigits.indices.reversed()) {
            sb.append(rawDigits[i])
            group++
            if (group == 3 && i > 0) {
                sb.append(' ')
                group = 0
            }
        }
        return sb.reverse().toString()
    }

    /** Maydonni formatlovchi kuzatuvchi bilan ta'minlaydi (ikki marta chaqirilsa takror qo'shilmaydi). */
    fun attach(editText: EditText) {
        if (editText.tag == WATCHER_TAG) return
        editText.tag = WATCHER_TAG
        editText.filters = arrayOf(InputFilter.LengthFilter(MAX_DIGITS + MAX_DIGITS / 3))
        editText.addTextChangedListener(object : TextWatcher {
            private var updating = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            // Formatlash `afterTextChanged`da: `onTextChanged` ichida matnni
            // o'zgartirish ba'zi IME'larda rekursiya/kursor sakrashiga olib keladi.
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (updating || s == null) return
                val raw = s.toString()
                val digitsBeforeCaret = raw
                    .substring(0, editText.selectionStart.coerceIn(0, raw.length))
                    .count { it.isDigit() }
                val formatted = format(digits(raw))
                if (formatted == raw) return

                updating = true
                editText.setText(formatted)
                var pos = 0
                var seen = 0
                while (pos < formatted.length && seen < digitsBeforeCaret) {
                    if (formatted[pos].isDigit()) seen++
                    pos++
                }
                editText.setSelection(pos)
                updating = false
            }
        })
    }

    private val WATCHER_TAG = "yuz_price_input_attached"
}
