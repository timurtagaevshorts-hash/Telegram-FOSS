package uz.kodlikino.top4.utils

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.UserConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * YuzGram #161: musiqa uzilishlari haqidagi asosiy dalil — telefon
 * logcat'i emas, balki haqiqiy xato bo'lganda yuborilgan qisqa hisobot.
 *
 * Nima uchun: PO-token bayt chegarasi (PC'da 384KB, telefonda ~1MB)
 * faqat haqiqiy IP'da ko'rinadi — JVM probe'da tasdiqlab bo'lmaydi.
 * Telefonda ijro uzilsa, bu hisobot pult bot chat'iga tushadi, pult
 * daemon'i uni requests.txt'ga yozadi va PC'dan o'qiyman. Shu bilan
 * qaysi mijoz (ANDROID_VR / TVHTML5 / ...) ishlab, qaysi xatoda
 * to'xtaganini aniq bilamiz — taxmin emas, fakt.
 *
 * Yuborish: SendMessagesHelper (TDLib oqimi) — ilova allaqachon
 * Telegram'ga kirgan bo'lsa hech qanday qo'shimcha so'rov kerak emas.
 * Qabul qiluvchi: pult bot'ning o'zi (getUpdates faqat botga kelgan
 * xabarlarni ko'radi); bot daemon'i ularni requests.txt'ga yozadi.
 *
 * Cheklov: sessiyada ko'pi bilan 4 ta hisobot — musiqa sekansiyasi
 * buzilganda ham bot chatni to'ldirib yubormaslik uchun.
 */
object YuzMusicDiagnostics {

    // Pult bot'ning o'zi (xabar SHU botga yuboriladi — getUpdates faqat
    // user->bot xabarlarini ko'radi; user'ning o'z chatiga yozsa bot
    // uni umuman ko'rmaydi).
    private const val PULT_CHAT = 8276337527L
    private const val MAX_REPORTS = 4

    private val sent = AtomicInteger(0)

    fun reportGiveUp(
        title: String,
        videoId: String,
        detail: String
    ) {
        if (sent.incrementAndGet() > MAX_REPORTS) return
        val text = buildString {
            append("YUZ-MUSIC #161 uzildi\n")
            append(title.take(60)).append('\n')
            append("id=").append(videoId).append('\n')
            append(detail.take(400))
        }
        post(text)
    }

    /** Bitta muhim voqea (masalan yangi mijoz ishga tushdi) — juda kam. */
    fun reportEvent(detail: String) {
        if (sent.get() >= MAX_REPORTS) return
        sent.incrementAndGet()
        post("YUZ-MUSIC #161 voqea\n${detail.take(400)}")
    }

    private fun post(text: String) {
        AndroidUtilities.runOnUIThread {
            runCatching {
                val account = UserConfig.selectedAccount
                SendMessagesHelper.getInstance(account)
                    .sendMessage(SendMessagesHelper.SendMessageParams.of(text, PULT_CHAT))
            }
        }
    }
}
