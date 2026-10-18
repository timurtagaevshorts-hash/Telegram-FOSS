package uz.kodlikino.top4.utils

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * O'zbekiston viloyatlari va tumanlari ro'yxati + ikki bosqichli tanlovchi.
 * Donor KinoTOP4'dagi `uz.kodlikino.top4.utils.ClassifiedLocationHelper` porti;
 * filtr qidiruvi uchun `matches()` qo'shimchasi bilan (erkin matn va
 * "Viloyat, Tuman" satrlarini e'lon maydonlari bilan moslashtiradi).
 */
object ClassifiedLocationHelper {

    data class Region(val name: String, val districts: List<String>)

    val regions = listOf(
        Region("Toshkent shahri", listOf("Bektemir", "Chilonzor", "Hamza", "Miraobod", "Mirzo Ulug'bek", "Sergeli", "Shayxontohur", "Olmazor", "Uchtepa", "Yakkasaroy", "Yunusobod", "Yangihayot")),
        Region("Toshkent viloyati", listOf("Angren", "Bekobod", "Olmaliq", "Ohangaron", "Chirchiq", "Yangiyo'l", "Bo'stonliq", "Bo'ka", "Chinoz", "Qibray", "Parkent", "Piskent", "Quyi Chirchiq", "O'rta Chirchiq", "Yuqori Chirchiq", "Zangiota")),
        Region("Andijon viloyati", listOf("Andijon shahri", "Xonobod", "Andijon tumani", "Asaka", "Baliqchi", "Buloqboshi", "Bo'ston", "Jalaquduq", "Izboskan", "Qo'rg'ontepa", "Marhamat", "Oltinko'l", "Paxtaobod", "Ulug'nor", "Shahrixon", "Xo'jaobod")),
        Region("Farg'ona viloyati", listOf("Farg'ona shahri", "Qo'qon", "Marg'ilon", "Quvasoy", "Oltiariq", "Bag'dod", "Beshariq", "Buvayda", "Dang'ara", "Yozyovon", "Quva", "Rishton", "So'x", "Toshloq", "O'zbekiston", "Uchko'prik", "Farg'ona tumani", "Fursat")),
        Region("Namangan viloyati", listOf("Namangan shahri", "Chortoq", "Chust", "Kosonsoy", "Mingbuloq", "Namangan tumani", "Norin", "Pop", "To'raqo'rg'on", "Uychi", "Uchqo'rg'on", "Yangiqo'rg'on")),
        Region("Sirdaryo viloyati", listOf("Guliston shahri", "Shirin", "Yangiyer", "Boyovut", "Guliston tumani", "Mirzaobod", "Oqoltin", "Sayhunobod", "Sardoba", "Sirdaryo tumani", "Xovos")),
        Region("Jizzax viloyati", listOf("Jizzax shahri", "Arnasoy", "Baxmal", "Do'stlik", "Forish", "G'allaorol", "Sharof Rashidov", "Mirzachol", "Paxtakor", "Yangiobod", "Zafarobod", "Zarbdor", "Zomin")),
        Region("Samarqand viloyati", listOf("Samarqand shahri", "Kattaqo'rg'on", "Bulung'ur", "Ishtixon", "Jomboy", "Kattaqo'rg'on tumani", "Narpay", "Nurobod", "Oqdaryo", "Payariq", "Pastdarg'om", "Paxtachi", "Samarqand tumani", "Toyloq", "Urgut", "Qo'shrabot")),
        Region("Buxoro viloyati", listOf("Buxoro shahri", "Kogon shahri", "Buxoro tumani", "G'ijduvon", "Jondor", "Kogon tumani", "Qorako'l", "Qorovulbozor", "Olot", "Peshku", "Romitan", "Shofirkon", "Vobkent")),
        Region("Navoiy viloyati", listOf("Navoiy shahri", "Zarafshon", "Karmana", "Konimex", "Navbahor", "Nurota", "Tomdi", "Uchkuduk", "Xatirchi", "Qiziltepa")),
        Region("Qashqadaryo viloyati", listOf("Qarshi shahri", "Shahrisabz shahri", "Dehqonobod", "Kasbi", "Kitob", "Koson", "Mirishkor", "Muborak", "Nishon", "Qamashi", "Qarshi tumani", "Yakkabog'", "G'uzor", "Chiroqchi", "Shahrisabz tumani")),
        Region("Surxondaryo viloyati", listOf("Termiz shahri", "Angor", "Boysun", "Denov", "Jarqo'rg'on", "Qiziriq", "Qumqo'rg'on", "Muzrabot", "Oltinsoy", "Sariosiyo", "Sherobod", "Sho'rchi", "Termiz tumani", "Uzun")),
        Region("Xorazm viloyati", listOf("Urganch shahri", "Xiva shahri", "Bog'ot", "Gurlan", "Urganch tumani", "Xiva tumani", "Xonqa", "Hazorasp", "Shovot", "Yangiariq", "Yangibozor", "Qo'shko'pir")),
        Region("Qoraqalpog'iston Respublikasi", listOf("Nukus shahri", "Amudaryo", "Beruniy", "Kegeyli", "Qanliko'l", "Qorao'zak", "Qo'ng'irot", "Mo'ynoq", "Nukus tumani", "Taxtako'pir", "To'rtko'l", "Xo'jayli", "Chimboy", "Shumanay", "Ellikqala"))
    )

    private val STOPWORDS = setOf(
        "shahri", "shahar", "viloyati", "viloyat", "tumani", "tuman", "respublikasi"
    )

    private const val DEFAULT_PICKER_LABEL = "Viloyat / tuman tanlash"

    /** Umumiylashtirish: kichitish + barcha apostrof variantlarini olib tashlash. */
    private fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            when (c) {
                '\'', '\u02BB', '\u02BC', '\u0060', '\u2018', '\u2019', '\u2032' -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * So'rov ("Toshkent shahri, Chilonzor" yoki erkin "Samarqand") e'lonning
     * region/address/location maydonlariga mos keladimi. Bo'sh so'rov = hammasi mos.
     * Barcha qismlar topilsa mos; aks holda eng aniq (oxirgi) qism — tumansiz
     * viloyat nomlari ham tushsin.
     */
    fun matches(query: String, vararg fields: String?): Boolean {
        val q = norm(query.trim())
        if (q.isEmpty()) return true
        val parts = q.split(',', ' ', '-').map { it.trim() }
            .filter { it.isNotEmpty() && it !in STOPWORDS }
        if (parts.isEmpty()) return true
        val hay = fields.filter { !it.isNullOrBlank() }
            .joinToString(" ") { norm(it) }
        if (hay.isEmpty()) return false
        if (parts.all { hay.contains(it) }) return true
        return hay.contains(parts.last())
    }

    fun pickerLabel(loc: String): String =
        if (loc.isBlank()) DEFAULT_PICKER_LABEL else loc

    fun showLocationPicker(context: Context, onLocationSelected: (String) -> Unit) {
        val regionNames = regions.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle("Viloyatni tanlang")
            .setItems(regionNames) { _, regionIndex ->
                val selectedRegion = regions[regionIndex]
                val districtNames =
                    (listOf("Butun viloyat") + selectedRegion.districts).toTypedArray()
                MaterialAlertDialogBuilder(context)
                    .setTitle("${selectedRegion.name}: Tumanni tanlang")
                    .setItems(districtNames) { _, districtIndex ->
                        if (districtIndex == 0) {
                            onLocationSelected(selectedRegion.name)
                        } else {
                            onLocationSelected("${selectedRegion.name}, ${districtNames[districtIndex]}")
                        }
                    }
                    .setNegativeButton("Orqaga") { _, _ ->
                        showLocationPicker(context, onLocationSelected)
                    }
                    .show()
            }
            .setNegativeButton("Bekor qilish", null)
            .show()
    }
}
