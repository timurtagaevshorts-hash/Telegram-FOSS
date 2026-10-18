package uz.kodlikino.top4.utils

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Admin dashboard statistikasi — HAQIQIY, Firestore'dagi mavjud yozuvlardan
 * sanaladi (sun'iy/lokal hisob yo'q):
 *
 *  - Foydalanuvchilar va faollik: `comment_profiles/{uid}` hujjatlaridagi
 *    `lastActiveAt`/`createdAt`/`isPremium` ([YuzPresence] har ~20 daqiqada
 *    `lastActiveAt`-ni yangilaydi — shu iz bugungi/haftalik/oylik faollarni
 *    haqiqiy qiladi);
 *  - Reels kontenti: `reels` kolleksiyasi (views/likeCount/commentCount
 *    yig'indilari va `createdAt` bo'yicha bugungi/haftalik yuklashlar);
 *  - Kinolar: `movies` soni.
 *
 * Sahifalash: har kolleksiyadan 200'lik bloklarda o'qiladi (limit [MAX_DOCS] —
 * o'n minglab hujjatda ham telefoni qotirib qo'ymaydi; natijalar "≈" emas,
 * sanalgan qism aniq).
 */
object YuzAdminStats {

    private const val PAGE = 200
    private const val MAX_DOCS = 6000

    data class Dashboard(
        val totalUsers: Int = 0,
        val todayActive: Int = 0,
        val weekActive: Int = 0,
        val monthActive: Int = 0,
        val newUsersMonth: Int = 0,
        val premiumUsers: Int = 0,
        val reelsCount: Int = 0,
        val moviesCount: Int = 0,
        val totalViews: Long = 0,
        val totalLikes: Long = 0,
        val totalComments: Long = 0,
        val uploadsToday: Int = 0,
        val uploadsWeek: Int = 0,
        /** lastActiveAt bo'yicha eng yaqin 8 kishi: (ism, epoch ms). */
        val recentActive: List<Pair<String, Long>> = emptyList()
    )

    suspend fun load(): Dashboard = withContext(Dispatchers.IO) {
        val startOfToday = startOfDay(0)
        val startOfWeek = startOfDay(6)
        val startOfMonth = startOfDay(29)

        // ── comment_profiles ──
        var users = 0
        var today = 0
        var week = 0
        var month = 0
        var newMonth = 0
        var premium = 0
        val recent = ArrayList<Pair<String, Long>>()
        var last: com.google.firebase.firestore.DocumentSnapshot? = null
        while (users < MAX_DOCS) {
            // DIQQAT: bu SDK yig'indisida Query.select() yo'q — hujjatlar to'liq o'qiladi.
            var q = db.collection("comment_profiles")
                .limit(PAGE.toLong())
            if (last != null) q = q.startAfter(last)
            val snap: QuerySnapshot = q.get().await()
            for (doc in snap.documents) {
                users++
                val active = getLong(doc, "lastActiveAt")
                val created = getLong(doc, "createdAt")
                if (active >= startOfToday) today++
                if (active >= startOfWeek) week++
                if (active >= startOfMonth) month++
                if (created >= startOfMonth) newMonth++
                if (doc.getBoolean("isPremium") == true) premium++
                if (active > 0) {
                    recent.add(
                        displayName(doc) to active
                    )
                }
            }
            last = snap.documents.lastOrNull()
            if (snap.documents.size < PAGE) break
        }
        recent.sortByDescending { it.second }

        // ── reels ──
        var reels = 0
        var views = 0L
        var likes = 0L
        var comments = 0L
        var upToday = 0
        var upWeek = 0
        last = null
        while (reels < MAX_DOCS) {
            var q = db.collection("reels")
                .limit(PAGE.toLong())
            if (last != null) q = q.startAfter(last)
            val snap: QuerySnapshot = q.get().await()
            for (doc in snap.documents) {
                reels++
                views += getLong(doc, "views")
                likes += getLong(doc, "likeCount")
                comments += getLong(doc, "commentCount")
                val created = getLong(doc, "createdAt")
                if (created >= startOfToday) upToday++
                if (created >= startOfWeek) upWeek++
            }
            last = snap.documents.lastOrNull()
            if (snap.documents.size < PAGE) break
        }

        // ── movies ──
        val movies = db.collection("movies").get().await().documents.size

        Dashboard(
            totalUsers = users,
            todayActive = today,
            weekActive = week,
            monthActive = month,
            newUsersMonth = newMonth,
            premiumUsers = premium,
            reelsCount = reels,
            moviesCount = movies.coerceAtMost(MAX_DOCS),
            totalViews = views,
            totalLikes = likes,
            totalComments = comments,
            uploadsToday = upToday,
            uploadsWeek = upWeek,
            recentActive = recent.take(8)
        )
    }

    private val db = FirebaseFirestore.getInstance()

    private fun getLong(doc: com.google.firebase.firestore.DocumentSnapshot, key: String): Long =
        when (val v = doc.get(key)) {
            is Number -> v.toLong()
            else -> 0L
        }

    private fun displayName(doc: com.google.firebase.firestore.DocumentSnapshot): String {
        val first = doc.getString("firstName").orEmpty().trim()
        val last = doc.getString("lastName").orEmpty().trim()
        return listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "YuzGram" }
    }

    private fun startOfDay(daysAgo: Int): Long {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -daysAgo)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
