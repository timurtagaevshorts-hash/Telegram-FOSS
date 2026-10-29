package uz.kodlikino.top4.games

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Taklif siyosati: raqib YuzGram ilovasidan foydalanadimi?
 *
 * YuzGram foydalanuvchisining belgisi — Firestore `comment_profiles/{tg_id}`
 * hujjati (uni ilovaning o‘zi [uz.kodlikino.top4.utils.YuzGramProfileStore]
 * yoki presence orqali yaratadi). Hujjat bor bo‘lsa — taklif interaktiv
 * oynasi bilan boradi; yo‘q bo‘lsa — Telegram’da yangilash haqidagi xabar.
 */
object GameInvitePolicy {

    private const val TAG = "GameInvitePolicy"

    /**
     * `true` — YuzGram foydalanuvchisi, `false` — yo‘q, `null` — aniqlab
     * bo‘lmadi (tarmoq). Noma’lum holatda taklifni YUZGRAM foydalanuvchisi
     * deb qabul qilamiz: noto‘g‘ri "yangilang" xabari yuborilmaydi.
     */
    suspend fun peerUsesYuzGram(uid: String): Boolean? = withContext(Dispatchers.IO) {
        val clean = uid.trim()
        if (clean.isEmpty() || !clean.startsWith("tg_")) return@withContext null
        try {
            if (!GamesCore.hasSession()) return@withContext null
            val snap = GamesCore.db.collection("comment_profiles").document(clean).get().await()
            snap.exists()
        } catch (e: Exception) {
            Log.w(TAG, "peerUsesYuzGram($clean): ${e.message}")
            null
        }
    }
}
