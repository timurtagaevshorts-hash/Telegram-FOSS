package uz.kodlikino.top4.utils

import com.google.firebase.firestore.AggregateField
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.telegram.messenger.FileLog
import java.util.Calendar

/**
 * Admin dashboard statistikasi — HAQIQIY, Firestore'dagi mavjud yozuvlardan
 * sanaladi (sun'iy/lokal hisob yo'q):
 *
 *  - Foydalanuvchilar va faollik: `comment_profiles/{uid}` hujjatlaridagi
 *    `lastActiveAt`/`createdAt`/`isPremium` ([YuzPresence] ilova ochilganda
 *    `lastActiveAt`-ni yangilaydi — shu iz bugungi/haftalik/oylik faollarni
 *    haqiqiy qiladi);
 *  - Reels kontenti: `reels` kolleksiyasi (views/likeCount/commentCount
 *    yig'indilari va `createdAt` bo'yicha bugungi/haftalik yuklashlar);
 *  - Kinolar: `movies` soni.
 *
 * Bepul tarif uchun muhim: raqamlar aggregation query'lardan olinadi —
 * Firestore bitta agregat uchun ~1000 indeks yozuvi evaziga 1 o'qish yozadi,
 * ya'ni butun kolleksiyani hujjatma-hujjat o'qish (~12k o'qish) o'rniga
 * ~10-150 o'qish yetadi. Agregat ishlamasa (oflayn / qoida rad etsa) —
 * cheklangan lokal kesh skaneriga tushamiz va natija [Dashboard.approximate]
 * bilan belgilanadi: noto'g'ri raqam "aniq" bo'lib ko'rinmasligi shart.
 */
object YuzAdminStats {

    /** "Bugungi faol" ro'yxatining bir sahifasi. */
    const val ACTIVE_PAGE = 10

    private const val COLLECTION_REELS = "reels"
    private const val COLLECTION_MOVIES = "movies"
    private const val COLLECTION_PROFILES = "comment_profiles"

    private const val PAGE = 200
    private const val FALLBACK_MAX_DOCS = 600

    /** Bitta faol foydalanuvchi qatori. `uid` = `tg_<telegram id>`. */
    data class ActiveUser(
        val uid: String,
        val name: String,
        val lastActiveAt: Long
    )

    /**
     * Sahifalangan ro'yxat. [cursor] — keyingi sahifa uchun Firestore hujjat
     * ko'rsatkichi, uni [loadActiveUsers] ga qaytarib berish kerak.
     */
    data class ActiveUsersPage(
        val users: List<ActiveUser>,
        val hasMore: Boolean,
        val cursor: DocumentSnapshot? = null
    )

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
        /** Bugun faol bo'lganlar — birinchi [ACTIVE_PAGE] tasi. */
        val activeUsers: ActiveUsersPage = ActiveUsersPage(emptyList(), false),
        /** Kun boshlanishi (epoch ms) — "yana yuklash" shu chegaradan davom etadi. */
        val dayStart: Long = 0L,
        /** Server agregatlari ishlamadi — raqamlar lokal keshdan, taxminiy. */
        val approximate: Boolean = false,
        /** Na server, na kesh javob berdi — raqamlarni ko'rsatish mumkin emas. */
        val failed: Boolean = false
    )

    suspend fun load(): Dashboard = withContext(Dispatchers.IO) {
        val startOfToday = startOfDay(0)
        val startOfWeek = startOfDay(6)
        val startOfMonth = startOfDay(29)
        try {
            loadAggregated(startOfToday, startOfWeek, startOfMonth)
        } catch (e: Exception) {
            FileLog.e(e)
            try {
                val cached = loadFromCache(startOfToday, startOfWeek, startOfMonth)
                if (cached.totalUsers == 0 && cached.reelsCount == 0) {
                    // Bo'sh kesh — nollarni "haqiqiy statistika" sifatida ko'rsatmaymiz.
                    cached.copy(failed = true, approximate = false)
                } else {
                    cached
                }
            } catch (e2: Exception) {
                FileLog.e(e2)
                Dashboard(dayStart = startOfToday, failed = true)
            }
        }
    }

    /** Server-side agregatlar: ~12k o'qish o'rniga ~o'nlab. */
    private suspend fun loadAggregated(
        startOfToday: Long,
        startOfWeek: Long,
        startOfMonth: Long
    ): Dashboard {
        val profiles = db.collection(COLLECTION_PROFILES)
        val reels = db.collection(COLLECTION_REELS)
        val sumViews = AggregateField.sum("views")
        val sumLikes = AggregateField.sum("likeCount")
        val sumComments = AggregateField.sum("commentCount")

        return coroutineScope {
            val totalUsers = async { count(profiles) }
            val todayActive = async { count(profiles.whereGreaterThanOrEqualTo("lastActiveAt", startOfToday)) }
            val weekActive = async { count(profiles.whereGreaterThanOrEqualTo("lastActiveAt", startOfWeek)) }
            val monthActive = async { count(profiles.whereGreaterThanOrEqualTo("lastActiveAt", startOfMonth)) }
            val newUsersMonth = async { count(profiles.whereGreaterThanOrEqualTo("createdAt", startOfMonth)) }
            val premiumUsers = async { count(profiles.whereEqualTo("isPremium", true)) }
            val reelsCount = async { count(reels) }
            val uploadsToday = async { count(reels.whereGreaterThanOrEqualTo("createdAt", startOfToday)) }
            val uploadsWeek = async { count(reels.whereGreaterThanOrEqualTo("createdAt", startOfWeek)) }
            val moviesCount = async { count(db.collection(COLLECTION_MOVIES)) }
            val sums = async {
                reels.aggregate(sumViews, sumLikes, sumComments)
                    .get(AggregateSource.SERVER).await()
            }
            val active = async { loadActiveUsers(startOfToday) }

            val agg = sums.await()
            Dashboard(
                totalUsers = totalUsers.await(),
                todayActive = todayActive.await(),
                weekActive = weekActive.await(),
                monthActive = monthActive.await(),
                newUsersMonth = newUsersMonth.await(),
                premiumUsers = premiumUsers.await(),
                reelsCount = reelsCount.await(),
                moviesCount = moviesCount.await(),
                totalViews = num(agg.get(sumViews)),
                totalLikes = num(agg.get(sumLikes)),
                totalComments = num(agg.get(sumComments)),
                uploadsToday = uploadsToday.await(),
                uploadsWeek = uploadsWeek.await(),
                activeUsers = active.await(),
                dayStart = startOfToday
            )
        }
    }

    /**
     * "Bugungi faol foydalanuvchilar" sahifasi — `lastActiveAt DESC`, faqat
     * [since] (kun boshlanishi) dan keyin faol bo'lganlar. [cursor] berilsa
     * keyingi sahifa qaytadi.
     */
    suspend fun loadActiveUsers(
        since: Long,
        cursor: DocumentSnapshot? = null,
        pageSize: Int = ACTIVE_PAGE
    ): ActiveUsersPage = withContext(Dispatchers.IO) {
        var q: Query = db.collection(COLLECTION_PROFILES)
            .whereGreaterThanOrEqualTo("lastActiveAt", since)
            .orderBy("lastActiveAt", Query.Direction.DESCENDING)
            .limit((pageSize + 1).toLong())
        if (cursor != null) q = q.startAfter(cursor)
        val docs = q.get(Source.SERVER).await().documents
        val hasMore = docs.size > pageSize
        val page = if (hasMore) docs.subList(0, pageSize) else docs
        ActiveUsersPage(
            users = page.map { doc ->
                ActiveUser(
                    uid = doc.id,
                    name = displayName(doc),
                    lastActiveAt = getLong(doc, "lastActiveAt")
                )
            },
            hasMore = hasMore,
            cursor = page.lastOrNull()
        )
    }

    private suspend fun count(query: Query): Int =
        query.count().get(AggregateSource.SERVER).await().count.toInt()

    /**
     * Zaxira yo'l: agregat mavjud bo'lmaganda (oflayn, qoida o'zgargan) —
     * cheklangan kesh skani. Kesh o'qishlari hisoblanmaydi, tarmoq ishlatilmaydi;
     * natija har doim taxminiy ([Dashboard.approximate]).
     */
    private suspend fun loadFromCache(
        startOfToday: Long,
        startOfWeek: Long,
        startOfMonth: Long
    ): Dashboard {
        var users = 0
        var today = 0
        var week = 0
        var month = 0
        var newMonth = 0
        var premium = 0
        val activeToday = ArrayList<ActiveUser>()
        var last: DocumentSnapshot? = null
        while (users < FALLBACK_MAX_DOCS) {
            var q = db.collection(COLLECTION_PROFILES).limit(PAGE.toLong())
            if (last != null) q = q.startAfter(last)
            val docs = q.get(Source.CACHE).await().documents
            for (doc in docs) {
                users++
                val active = getLong(doc, "lastActiveAt")
                val created = getLong(doc, "createdAt")
                if (active >= startOfToday) today++
                if (active >= startOfWeek) week++
                if (active >= startOfMonth) month++
                if (created >= startOfMonth) newMonth++
                if (doc.getBoolean("isPremium") == true) premium++
                if (active >= startOfToday) {
                    activeToday.add(ActiveUser(doc.id, displayName(doc), active))
                }
            }
            last = docs.lastOrNull()
            if (docs.size < PAGE) break
        }
        activeToday.sortByDescending { it.lastActiveAt }

        var reels = 0
        var views = 0L
        var likes = 0L
        var comments = 0L
        var upToday = 0
        var upWeek = 0
        last = null
        while (reels < FALLBACK_MAX_DOCS) {
            var q = db.collection(COLLECTION_REELS).limit(PAGE.toLong())
            if (last != null) q = q.startAfter(last)
            val docs = q.get(Source.CACHE).await().documents
            for (doc in docs) {
                reels++
                views += getLong(doc, "views")
                likes += getLong(doc, "likeCount")
                comments += getLong(doc, "commentCount")
                val created = getLong(doc, "createdAt")
                if (created >= startOfToday) upToday++
                if (created >= startOfWeek) upWeek++
            }
            last = docs.lastOrNull()
            if (docs.size < PAGE) break
        }

        val movies = try {
            db.collection(COLLECTION_MOVIES).limit(PAGE.toLong())
                .get(Source.CACHE).await().documents.size
        } catch (e: Exception) {
            0
        }

        return Dashboard(
            totalUsers = users,
            todayActive = today,
            weekActive = week,
            monthActive = month,
            newUsersMonth = newMonth,
            premiumUsers = premium,
            reelsCount = reels,
            moviesCount = movies,
            totalViews = views,
            totalLikes = likes,
            totalComments = comments,
            uploadsToday = upToday,
            uploadsWeek = upWeek,
            activeUsers = ActiveUsersPage(activeToday.take(ACTIVE_PAGE), false),
            dayStart = startOfToday,
            approximate = true
        )
    }

    private val db = FirebaseFirestore.getInstance()

    private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: 0L

    private fun getLong(doc: DocumentSnapshot, key: String): Long =
        when (val v = doc.get(key)) {
            is Number -> v.toLong()
            else -> 0L
        }

    private fun displayName(doc: DocumentSnapshot): String {
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
