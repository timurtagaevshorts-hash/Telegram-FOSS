package uz.kodlikino.top4.utils

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import uz.kodlikino.top4.models.Reel
import uz.kodlikino.top4.models.ReelComment

/**
 * "Reels" ma'lumotlar ombori — Firestore `reels` kolleksiyasi.
 * KinoTOP4 bilan bir xil kollekciya/field nomlari, shuning uchun mavjud
 * reels ma'lumotlari ham ko'rinadi va yangi postlar umumiy tasмага qo'shiladi.
 */
object ReelsRepository {

    private const val TAG = "ReelsRepository"
    const val COLLECTION = "reels"
    private const val LIKES = "likes"
    private const val COMMENTS = "comments"
    private const val FEED_LIMIT = 100L
    private const val COMMENTS_LIMIT = 200L
    /** Firestore hujjati hajmi chegarasi (1 MiB) — matn maydonlarini shundan uzoq tutamiz. */
    private const val MAX_TITLE_CHARS = 2000
    private const val MAX_COMMENT_CHARS = 1000
    private const val MAX_NAME_CHARS = 120
    /**
     * Hikoya ko'rishlarini sanash yo'li: `stories/{storyKey}/reactions/{uid}` —
     * bitta hujjat = bitta noyob ko'ruvchi. Qoidalarda bu subkolleksiya kirgan
     * foydalanuvchiga yozishga va hammaga o'qishga ochiq; `reels` ostidagi
     * `views` subkolleksiyasi esa faqat adminga ochiq.
     */
    private const val STORIES = "stories"
    private const val STORY_VIEWS = "reactions"

    private val db = FirebaseFirestore.getInstance()

    /** Faqat http(s) manzillar: begona hujjat `file://` sxemasi bilan lokal faylni ochmasin. */
    private fun httpUrlOrNull(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        return if (url.startsWith("https://") || url.startsWith("http://")) url else null
    }

    private fun sanitizeReel(reel: Reel): Reel? {
        val video = httpUrlOrNull(reel.videoUrl) ?: return null
        return reel.copy(
            videoUrl = video,
            thumbnailUrl = httpUrlOrNull(reel.thumbnailUrl).orEmpty(),
            uploadedByPhotoUrl = httpUrlOrNull(reel.uploadedByPhotoUrl).orEmpty()
        )
    }

    /**
     * In-memory feed keshi (oldingi loyihadagi `ReelsDataCache` g'oyasi).
     * Maqsad: ekronga qaytishda (onResume/tab almashish) 100 hujjatlik
     * Firestore so'rovi tugashini kutmasdan [peekReels] orqali tasmani
     * BIRINCHI KADRDA ko'rsatish; tarmoq javobi kelgach ro'yxat yangilanadi.
     * Faqat muvaffaqiyatli o'qishlar saqlanadi (xato/null holat keshmaydi).
     */
    @Volatile
    private var cachedFeed: List<Reel>? = null
    private val cachedByUploader = HashMap<String, List<Reel>>()

    /** Oxirgi muvaffaqiyatli umumiy feed — bo'lsa, darhol render qilish uchun. */
    fun peekReels(): List<Reel>? = cachedFeed

    /** Oxirgi muvaffaqiyatli scoped feed (profil grid'i uchun). */
    fun peekReelsByUploader(uid: String): List<Reel>? {
        if (uid.isBlank()) return null
        return synchronized(cachedByUploader) { cachedByUploader[uid] }
    }

    /** Feed — oxirgi qo'shilgan reelslar (createdAt bo'yicha kamayish tartibida). */
    suspend fun getReels(): List<Reel> = getReelsOrNull() ?: emptyList()

    /**
     * [getReels]ning xato holatini ajratib qaytaradigan varianti:
     * `null` — o'qish XATO bilan tugadi (internet/qoida),
     * bo'sh ro'yxat — haqiqatan ham reels yo'q. Ekranda "bo'sh" va "yuklanmadi"
     * xabarlarini chalkashtirmaslik uchun kerak.
     */
    suspend fun getReelsOrNull(): List<Reel>? = readFeed(Source.DEFAULT)

    /**
     * Disk keşidan tezkor o'qish — tarmoq javobini kutmasdan tasmani
     * to'ldirish uchun (sovuq start, sekin internet, oflayn). Bo'sh bo'lsa
     * chaqiruvchi oddiy [getReelsOrNull] bilan davom etadi.
     */
    suspend fun getReelsFromCache(): List<Reel>? = readFeed(Source.CACHE)

    private suspend fun readFeed(source: Source): List<Reel>? {
        return try {
            val snapshot = db.collection(COLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(FEED_LIMIT)
                .get(source)
                .await()
            val result = snapshot.documents.mapNotNull { doc ->
                try {
                    val reel = doc.toObject(Reel::class.java) ?: return@mapNotNull null
                    sanitizeReel(reel.copy(id = reel.id.ifBlank { doc.id }))
                } catch (e: Exception) {
                    Log.e(TAG, "parse ${doc.id}: ${e.message}", e)
                    null
                }
            }
            // Xotira keshi faqat bo'sh bo'lmagan natija bilan yangilanadi:
            // "hali kesh yo'q" holati tasmani bo'shatib yubormasin.
            if (result.isNotEmpty()) cachedFeed = result
            result
        } catch (e: Exception) {
            // CACHE o'qishi ma'lumot yo'qligida ham xato beradi — bu odatiy hol.
            if (source == Source.CACHE) {
                Log.d(TAG, "readFeed(cache): ${e.message}")
            } else {
                Log.e(TAG, "getReels: ${e.message}", e)
            }
            null
        }
    }

    /** Muayyan uid tomonidan yuklangan reel'lar (monetizatsiya va kontent boshqaruvi uchun). */
    suspend fun getReelsByUploader(uid: String): List<Reel> =
        readByUploader(uid, Source.DEFAULT) ?: emptyList()

    /** Profil grid'i uchun disk keşidan tezkor o'qish. */
    suspend fun getReelsByUploaderFromCache(uid: String): List<Reel>? =
        readByUploader(uid, Source.CACHE)

    private suspend fun readByUploader(uid: String, source: Source): List<Reel>? {
        if (uid.isBlank()) return null
        return try {
            val snapshot = db.collection(COLLECTION)
                .whereEqualTo("uploadedByUid", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(FEED_LIMIT)
                .get(source)
                .await()
            val result = snapshot.documents.mapNotNull { doc ->
                try {
                    val reel = doc.toObject(Reel::class.java) ?: return@mapNotNull null
                    sanitizeReel(reel.copy(id = reel.id.ifBlank { doc.id }))
                        ?.takeIf { !it.isStory }
                } catch (e: Exception) {
                    Log.e(TAG, "parse ${doc.id}: ${e.message}", e)
                    null
                }
            }
            if (result.isNotEmpty()) {
                synchronized(cachedByUploader) { cachedByUploader[uid] = result }
            }
            result
        } catch (e: Exception) {
            if (source == Source.CACHE) {
                Log.d(TAG, "readByUploader(cache) $uid: ${e.message}")
            } else {
                Log.e(TAG, "getReelsByUploader: ${e.message}", e)
            }
            null
        }
    }

    /** Yangi reel hujjatini `reels/{id}` ga yozadi. */
    suspend fun saveReel(reel: Reel) {
        val id = reel.id.ifBlank { System.currentTimeMillis().toString() }
        val videoUrl = httpUrlOrNull(reel.videoUrl)
        if (videoUrl == null || reel.uploadedByUid.isBlank()) {
            throw IllegalArgumentException("Reel ma'lumotlari to'liq emas — qayta urinib ko'ring")
        }
        // Sessiya boshqa Telegram akkauntga o'tgan bo'lsa yozuv egasi
        // chalkashmasin: qoida `uploadedByUid == auth.uid` talab qiladi.
        val authUid = FirebaseAuth.getInstance().currentUser?.uid
        if (authUid != null && authUid != reel.uploadedByUid) {
            throw IllegalStateException("Sessiya boshqa akkauntga bog'langan — Telegram orqali qayta kiring.")
        }
        val data = hashMapOf<String, Any>(
            "id" to id,
            "videoUrl" to videoUrl,
            "thumbnailUrl" to httpUrlOrNull(reel.thumbnailUrl).orEmpty(),
            "views" to reel.views,
            "title" to reel.title.trim().take(MAX_TITLE_CHARS),
            "hashtags" to reel.hashtags.take(MAX_TITLE_CHARS),
            "monetizationEnabled" to reel.monetizationEnabled,
            "movieUrl" to reel.movieUrl,
            "adTitle" to reel.adTitle,
            "adUrl" to reel.adUrl,
            "commentCount" to reel.commentCount,
            "likeCount" to reel.likeCount,
            "createdAt" to reel.createdAt,
            "uploadedByUid" to reel.uploadedByUid,
            "uploadedByName" to reel.uploadedByName.take(MAX_NAME_CHARS),
            "uploadedByPhotoUrl" to httpUrlOrNull(reel.uploadedByPhotoUrl).orEmpty(),
            "isUserUpload" to reel.isUserUpload
        )
        try {
            YuzTaskTimeouts.awaitBound(
                "Reelni saqlash",
                YuzTaskTimeouts.FIRESTORE_MS
            ) { db.collection(COLLECTION).document(id).set(data).await() }
            // Kesheski holat qolmasligi uchun: yangi reel keyingi ochilishda
            // darhol ko'rinishi kerak (network fetch yangi list keshlaydi).
            cachedFeed = null
            synchronized(cachedByUploader) { cachedByUploader.remove(reel.uploadedByUid) }
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (message.contains("PERMISSION_DENIED", true) || message.contains("permission", true)) {
                throw IllegalStateException(
                    "Reel saqlash ruxsati yo'q. Telegram orqali qayta kiring yoki biroz kutib urinib ko'ring."
                )
            }
            throw e
        }
    }

    /**
     * Like holati — `reels/{id}/likes/{uid}` hujjatining mavjudligi.
     * Firestore qoidalari bu subkolleksiyani o'qishga ham, o'z hujjatini
     * yozib/o'chirishga ham ruxsat beradi (rules: signedIn() && uid == userId).
     */
    suspend fun isLiked(reelId: String, uid: String): Boolean {
        if (reelId.isBlank() || uid.isBlank()) return false
        return try {
            db.collection(COLLECTION).document(reelId)
                .collection(LIKES).document(uid).get().await().exists()
        } catch (e: Exception) {
            Log.e(TAG, "isLiked $reelId: ${e.message}", e)
            false
        }
    }

    /**
     * Like qo'yish / olish. `likeCount` maydoni faqat 3 ta ruxsat etilgan
     * (`views`, `likeCount`, `commentCount`) dan biri — shuning uchun increment
     * oddiy `update()` orqali o'tadi (hasOnly([likeCount]) qoidasiga mos).
     * Muvaffaqiyatli LIKE'dan so'ng reel egasiga bildirishnoma fan-out qilinadi.
     */
    suspend fun setLiked(reelId: String, uid: String, liked: Boolean, actorName: String = "", actorPhoto: String = "") {
        if (reelId.isBlank() || uid.isBlank()) return
        val authUid = FirebaseAuth.getInstance().currentUser?.uid
        if (authUid != null && authUid != uid) {
            throw IllegalStateException("Sessiya boshqa akkauntga bog'langan — Telegram orqali qayta kiring.")
        }
        val reelRef = db.collection(COLLECTION).document(reelId)
        val likeRef = reelRef.collection(LIKES).document(uid)
        // Tranzaksiya — sonni ikki marta oshirib/kamaytirib yubormaslik
        // kafolati (tez ikki marta bosish, qayta urinish, ikki qurilma).
        // Qoidalar `likes/{uid}` create/delete va `likeCount` increment'ni
        // ruxsat etadi; boshqa maydonlarga tegmaymiz.
        val changed = db.runTransaction { tx ->
            val alreadyLiked = tx.get(likeRef).exists()
            if (alreadyLiked == liked) return@runTransaction false
            val current = tx.get(reelRef).getLong("likeCount") ?: 0L
            if (liked) {
                tx.set(
                    likeRef,
                    hashMapOf<String, Any>("userId" to uid, "createdAt" to System.currentTimeMillis())
                )
                tx.update(reelRef, "likeCount", current + 1L)
            } else {
                tx.delete(likeRef)
                tx.update(reelRef, "likeCount", (current - 1L).coerceAtLeast(0L))
            }
            true
        }.await()
        if (changed && liked) {
            try {
                val reel = reelMeta(reelId)
                if (reel != null && reel.uploadedByUid.isNotBlank() && reel.uploadedByUid != uid) {
                    ReelNotificationManager.pushLike(
                        ownerUid = reel.uploadedByUid,
                        actorUid = uid,
                        actorName = actorName,
                        actorPhoto = actorPhoto,
                        reelId = reelId,
                        reelTitle = reel.title,
                        reelThumbUrl = reel.thumbnailUrl
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "pushLike $reelId: ${e.message}", e)
            }
        }
    }

    /** Hikoya reel'ining Firebase'dagi yangi statistikasi. */
    data class StoryStats(
        val views: Int,
        val likeCount: Int,
        val commentCount: Int,
        val likedByMe: Boolean
    )

    /**
     * Hikoya statistikasi — faqat Firebase hisoblagichlari:
     *  - ko'rishlar: `stories/{key}/reactions` hujjatlari soni (noyob ko'ruvchilar);
     *  - layklar: `reels/{key}/likes` hujjatlari soni;
     *  - izohlar: `reels/{key}/comments` hujjatlari soni.
     * Telegram'ning eski `views_count`/`reactions_count` qiymatlari ishlatilmaydi.
     */
    suspend fun getStoryStats(storyKey: String, uid: String): StoryStats? {
        if (storyKey.isBlank() || YuzStoryFeed.parseKey(storyKey) == null) return null
        val reelRef = db.collection(COLLECTION).document(storyKey)
        val likesRef = reelRef.collection(LIKES)
        return try {
            // To'rtta o'qish parallel — sahifa almashganda kechikish sezilmasin.
            coroutineScope {
                val views = async {
                    db.collection(STORIES).document(storyKey)
                        .collection(STORY_VIEWS).count()
                        .get(AggregateSource.SERVER).await().count.toInt()
                }
                val likes = async {
                    likesRef.count().get(AggregateSource.SERVER).await().count.toInt()
                }
                val comments = async {
                    reelRef.collection(COMMENTS).count()
                        .get(AggregateSource.SERVER).await().count.toInt()
                }
                val likedByMe = async {
                    uid.isNotBlank() && likesRef.document(uid).get().await().exists()
                }
                StoryStats(views.await(), likes.await(), comments.await(), likedByMe.await())
            }
        } catch (e: Exception) {
            Log.w(TAG, "getStoryStats $storyKey: ${e.message}")
            null
        }
    }

    /**
     * Hikoya ko'rildi — noyob ko'ruvchi belgisi (hujjat id = uid, takroriy
     * ko'rishlar sonni oshirmaydi). Yozuv qoidasi faqat o'z uid'iga ruxsat beradi.
     */
    suspend fun registerStoryView(storyKey: String, uid: String) {
        if (storyKey.isBlank() || uid.isBlank() || YuzStoryFeed.parseKey(storyKey) == null) return
        try {
            db.collection(STORIES).document(storyKey)
                .collection(STORY_VIEWS).document(uid)
                .set(hashMapOf("viewerUid" to uid, "viewedAt" to System.currentTimeMillis()))
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "registerStoryView $storyKey: ${e.message}")
        }
    }

    /**
     * Hikoya layki — `reels/{key}/likes/{uid}` hujjati (create/delete; update
     * qoidasi yo'q, shuning uchun avval mavjudligini tekshiramiz). Yangi laykda
     * hikoya egasiga (`tg_<dialogId>`) bildirishnoma yuboriladi.
     */
    suspend fun setStoryLiked(
        storyKey: String,
        uid: String,
        liked: Boolean,
        actorName: String = "",
        actorPhoto: String = "",
        ownerUid: String = "",
        reelTitle: String = "",
        reelThumbUrl: String = ""
    ) {
        if (storyKey.isBlank() || uid.isBlank() || YuzStoryFeed.parseKey(storyKey) == null) return
        val ref = db.collection(COLLECTION).document(storyKey).collection(LIKES).document(uid)
        val exists = ref.get().await().exists()
        if (exists == liked) return
        if (!liked) {
            ref.delete().await()
            return
        }
        ref.set(hashMapOf("userId" to uid, "createdAt" to System.currentTimeMillis())).await()
        if (ownerUid.isNotBlank() && ownerUid != uid) {
            try {
                ReelNotificationManager.pushLike(
                    ownerUid = ownerUid,
                    actorUid = uid,
                    actorName = actorName,
                    actorPhoto = actorPhoto,
                    reelId = storyKey,
                    reelTitle = reelTitle,
                    reelThumbUrl = reelThumbUrl
                )
            } catch (e: Exception) {
                Log.w(TAG, "pushStoryLike $storyKey: ${e.message}")
            }
        }
    }

    /** Ko'rishni hisobga olish (har sessiyada bir marta chaqiriladi). */
    suspend fun registerView(reelId: String) {
        if (reelId.isBlank()) return
        try {
            db.collection(COLLECTION).document(reelId)
                .update(mapOf("views" to FieldValue.increment(1L))).await()
        } catch (e: Exception) {
            Log.e(TAG, "registerView $reelId: ${e.message}", e)
        }
    }

    /** Reel'ning haqiqiy sonlari (Firestore hujjatidan). */
    data class Stats(val views: Int, val likeCount: Int, val commentCount: Int)

    /**
     * Faol reel uchun jonli statistika: boshqa qurilmada qo'yilgan layk,
     * ko'rish yoki izoh shu listener orqali darhol ko'rinadi. Reels ekrani
     * faqat bitta (ko'rinib turgan) sahifa uchun ulaydi.
     */
    fun observeStats(reelId: String, onChange: (Stats?) -> Unit): ListenerRegistration? {
        if (reelId.isBlank()) return null
        return try {
            db.collection(COLLECTION).document(reelId)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        Log.e(TAG, "observeStats $reelId: ${error.message}", error)
                        return@addSnapshotListener
                    }
                    if (snap == null || !snap.exists()) {
                        onChange(null)
                        return@addSnapshotListener
                    }
                    onChange(
                        Stats(
                            views = (snap.getLong("views") ?: 0L).toInt(),
                            likeCount = (snap.getLong("likeCount") ?: 0L).toInt(),
                            commentCount = (snap.getLong("commentCount") ?: 0L).toInt()
                        )
                    )
                }
        } catch (e: Exception) {
            Log.e(TAG, "observeStats $reelId: ${e.message}", e)
            null
        }
    }

    /**
     * Izohlar — `reels reelId / comments` subkolleksiyasi (eskidana yangi).
     * O'qish qoidasi `if true`, shuning uchun Firebase sessiyasi shart emas.
     *
     * @return `null` — o'qish xatosi (tarmoq/ruxsat), bo'sh ro'yxat — izoh yo'q.
     */
    suspend fun getComments(reelId: String): List<ReelComment>? = readComments(reelId, Source.DEFAULT)

    /** Disk keşidan izohlar — panel qayta ochilganda tarmoqni kutmaydi. */
    suspend fun getCommentsFromCache(reelId: String): List<ReelComment>? =
        readComments(reelId, Source.CACHE)

    private suspend fun readComments(reelId: String, source: Source): List<ReelComment>? {
        if (reelId.isBlank()) return emptyList()
        return try {
            val snapshot = db.collection(COLLECTION).document(reelId)
                .collection(COMMENTS)
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limit(COMMENTS_LIMIT)
                .get(source).await()
            snapshot.documents.mapNotNull { doc ->
                try {
                    val c = doc.toObject(ReelComment::class.java) ?: return@mapNotNull null
                    c.copy(
                        id = c.id.ifBlank { doc.id },
                        reelId = c.reelId.ifBlank { reelId }
                    ).takeIf { it.text.isNotBlank() }
                } catch (e: Exception) {
                    Log.e(TAG, "comment parse ${doc.id}: ${e.message}", e)
                    null
                }
            }
        } catch (e: Exception) {
            if (source == Source.CACHE) {
                Log.d(TAG, "comments(cache) $reelId: ${e.message}")
            } else {
                Log.e(TAG, "getComments $reelId: ${e.message}", e)
            }
            null
        }
    }

    /**
     * Yangi izoh yozadi va `commentCount` ni bittaga oshiradi.
     *
     * Qoida: izoh `create` faqat `signedIn() && data.userId == auth.uid` da o'tadi —
     * shuning uchun chaqiruvdan oldin Firebase sessiyasi ochilgan bo'lishi shart
     * (qarang [YuzGramAdsAuth.ensureSignedIn]). `commentCount` uchta ruxsat etilgan
     * maydondan biri (`views`, `likeCount`, `commentCount`), shuning uchun alohida
     * `update()` orqali ketadi — izoh yozuvi muvaffaqiyatli bo'lsagina oshiriladi.
     *
     * @return yozilgan izoh (id bilan)
     */
    suspend fun addComment(
        reelId: String,
        comment: ReelComment,
        reelTitle: String = "",
        reelThumbUrl: String = ""
    ): ReelComment {
        if (reelId.isBlank() || comment.text.isBlank() || comment.userId.isBlank()) {
            throw IllegalArgumentException("Izoh matni bo'sh")
        }
        val authUid = FirebaseAuth.getInstance().currentUser?.uid
        if (authUid != null && authUid != comment.userId) {
            throw IllegalStateException("Sessiya boshqa akkauntga bog'langan — Telegram orqali qayta kiring.")
        }
        val text = comment.text.trim().take(MAX_COMMENT_CHARS)
        if (text.isEmpty()) throw IllegalArgumentException("Izoh matni bo'sh")
        val docRef = db.collection(COLLECTION).document(reelId)
            .collection(COMMENTS).document()
        val saved = comment.copy(
            id = docRef.id,
            reelId = reelId,
            text = text,
            authorName = comment.authorName.trim().take(MAX_NAME_CHARS)
        )
        val data = hashMapOf<String, Any>(
            "id" to docRef.id,
            "reelId" to reelId,
            "text" to saved.text,
            "authorName" to saved.authorName,
            "authorPhotoUrl" to saved.authorPhotoUrl,
            "userId" to saved.userId,
            "deviceId" to saved.deviceId,
            "createdAt" to saved.createdAt,
            "replyToCommentId" to saved.replyToCommentId,
            "replyToUserId" to saved.replyToUserId,
            "replyToAuthorName" to saved.replyToAuthorName
        )
        try {
            docRef.set(data).await()
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (message.contains("PERMISSION_DENIED", true) || message.contains("permission", true)) {
                throw IllegalStateException(
                    "Izoh yozish ruxsati yo'q. Telegram orqali qayta kiring yoki biroz kutib urinib ko'ring."
                )
            }
            throw e
        }
        try {
            db.collection(COLLECTION).document(reelId)
                .update(mapOf("commentCount" to FieldValue.increment(1L))).await()
        } catch (e: Exception) {
            // Hisob yozuvi muvaffaqiyatli bo'lsa, izoh baribir ko'rinadi.
            Log.e(TAG, "commentCount $reelId: ${e.message}", e)
        }
        try {
            notifyCommentAdded(saved, reelTitle, reelThumbUrl)
        } catch (e: Exception) {
            Log.e(TAG, "notifyCommentAdded $reelId: ${e.message}", e)
        }
        return saved
    }

    /**
     * Izoh fan-out (donor `notifyReelCommentAdded` mantig'i):
     *  - javob (replyToCommentId bor) → asosiy izoh egasiga TYPE_REPLY,
     *    reel egasi izohchidan va javob berilgan egadan boshqa bo'lsa TYPE_COMMENT;
     *  - oddiy izoh → reel egasiga TYPE_COMMENT (o'ziga yozsa — yo'q).
     */
    private suspend fun notifyCommentAdded(
        comment: ReelComment,
        reelTitle: String,
        reelThumbUrl: String
    ) {
        // Hikoya kalitida `reels/{id}` hujjati yo'q — egasi Telegram peer'i:
        // foydalanuvchi hikoyasida `tg_<dialogId>`; kanal/guruh hikoyasida esa
        // Firebase egasi bo'lmaydi (faqat javob egasi bildirishnoma oladi).
        val storyKey = YuzStoryFeed.parseKey(comment.reelId)
        val reel = if (storyKey == null) reelMeta(comment.reelId) else null
        val ownerUid = reel?.uploadedByUid.orEmpty().ifBlank {
            val dialogId = storyKey?.first ?: 0L
            if (dialogId > 0L) "tg_$dialogId" else ""
        }
        val title = reel?.title?.takeIf { it.isNotBlank() } ?: reelTitle
        val thumb = reel?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: reelThumbUrl
        if (comment.replyToCommentId.isNotBlank()) {
            val parent = getReelComment(comment.reelId, comment.replyToCommentId)
            val parentOwner = parent?.userId?.takeIf { it.isNotBlank() } ?: comment.replyToUserId
            if (parentOwner.isNotBlank() && parentOwner != comment.userId) {
                ReelNotificationManager.pushReply(
                    commentOwnerUid = parentOwner,
                    actorUid = comment.userId,
                    actorName = comment.authorName,
                    actorPhoto = comment.authorPhotoUrl,
                    reelId = comment.reelId,
                    reelTitle = title,
                    reelThumbUrl = thumb,
                    commentId = comment.replyToCommentId,
                    commentText = comment.text
                )
            }
            if (ownerUid.isNotBlank() && ownerUid != comment.userId && ownerUid != parentOwner) {
                ReelNotificationManager.pushComment(
                    ownerUid = ownerUid,
                    actorUid = comment.userId,
                    actorName = comment.authorName,
                    actorPhoto = comment.authorPhotoUrl,
                    reelId = comment.reelId,
                    reelTitle = title,
                    reelThumbUrl = thumb,
                    commentId = comment.id,
                    commentText = comment.text
                )
            }
        } else if (ownerUid.isNotBlank() && ownerUid != comment.userId) {
            ReelNotificationManager.pushComment(
                ownerUid = ownerUid,
                actorUid = comment.userId,
                actorName = comment.authorName,
                actorPhoto = comment.authorPhotoUrl,
                reelId = comment.reelId,
                reelTitle = title,
                reelThumbUrl = thumb,
                commentId = comment.id,
                commentText = comment.text
            )
        }
    }

    /** Bitta izoh hujjatini o'qiydi (reply'langan otani topish uchun). */
    suspend fun getReelComment(reelId: String, commentId: String): ReelComment? {
        if (reelId.isBlank() || commentId.isBlank()) return null
        return try {
            db.collection(COLLECTION).document(reelId)
                .collection(COMMENTS).document(commentId).get().await()
                .let { doc ->
                    if (!doc.exists()) return null
                    val c = doc.toObject(ReelComment::class.java) ?: return null
                    c.copy(id = c.id.ifBlank { doc.id }, reelId = c.reelId.ifBlank { reelId })
                }
        } catch (e: Exception) {
            Log.e(TAG, "getReelComment $commentId: ${e.message}", e)
            null
        }
    }

    /** Reel'ning bildirishnoma uchun kerakli maydonlari (title/thumb/ega). */
    private suspend fun reelMeta(reelId: String): Reel? {
        if (reelId.isBlank()) return null
        return try {
            db.collection(COLLECTION).document(reelId).get().await()
                .let { doc -> if (doc.exists()) doc.toObject(Reel::class.java) else null }
        } catch (e: Exception) {
            Log.e(TAG, "reelMeta $reelId: ${e.message}", e)
            null
        }
    }

    /**
     * Reel'ni Firestore'dan o'chiradi. Qoida: faqat `uploadedByUid == auth.uid`
     * (ega) yoki admin — shuning uchun chaqiruvchi tomon oldin
     * [YuzGramAdsAuth.ensureSignedIn] bilan sessiya yaratilgan bo'lishi kerak.
     */
    suspend fun deleteReel(reelId: String) {
        if (reelId.isBlank()) return
        try {
            db.collection(COLLECTION).document(reelId).delete().await()
            // O'chirilgan reel keshda qolib keyingi ochilishda "tirilib" qolmasin.
            cachedFeed = null
            synchronized(cachedByUploader) { cachedByUploader.clear() }
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (message.contains("PERMISSION_DENIED", true) || message.contains("permission", true)) {
                throw IllegalStateException("Bu reel'ni o'chirish faqat egasiga ruxsat.")
            }
            throw e
        }
    }
}
