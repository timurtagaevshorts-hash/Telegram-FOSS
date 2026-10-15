package uz.kodlikino.top4.utils

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import uz.kodlikino.top4.models.ClassifiedAd
import uz.kodlikino.top4.models.ClassifiedAdCardThemes
import uz.kodlikino.top4.models.ClassifiedAdCategories
import uz.kodlikino.top4.models.JobCardCategories
import kotlin.math.exp
import kotlin.random.Random

object ClassifiedAdRepository {

    private const val TAG = "ClassifiedAdRepo"
    const val COLLECTION_NAME = "classified_ads"
    private const val COLLECTION = COLLECTION_NAME
    /** Yangi e'lonlar random tartibda yuqoriroq chiqishi uchun yarim hayot (7 kun). */
    private const val FEED_RECENCY_HALF_LIFE_MS = 7L * 24L * 60L * 60L * 1000L
    private val db = FirebaseFirestore.getInstance()

    fun parseDocument(doc: DocumentSnapshot): ClassifiedAd? = parseAd(doc)

    private fun parseAd(doc: DocumentSnapshot): ClassifiedAd? {
        return try {
            val data = doc.data ?: return null
            val imageUrls = when (val raw = data["imageUrls"]) {
                is List<*> -> raw.mapNotNull { it as? String }.filter { it.isNotBlank() }
                else -> emptyList()
            }
            ClassifiedAd(
                id = doc.id,
                title = data["title"] as? String ?: "",
                model = data["model"] as? String ?: "",
                description = data["description"] as? String ?: "",
                price = readLong(data["price"]),
                category = data["category"] as? String ?: ClassifiedAdCategories.OTHER,
                imageUrls = imageUrls.take(5),
                address = data["address"] as? String ?: "",
                phone = data["phone"] as? String ?: "",
                ownerUid = data["ownerUid"] as? String ?: "",
                ownerName = data["ownerName"] as? String ?: "",
                ownerPhotoUrl = data["ownerPhotoUrl"] as? String ?: "",
                createdAt = readTimestamp(data["createdAt"]),
                isActive = data["isActive"] as? Boolean ?: true,
                isBlocked = data["isBlocked"] as? Boolean ?: false,
                cardTheme = data["cardTheme"] as? String ?: ClassifiedAdCardThemes.BLACK,
                region = data["region"] as? String ?: "",
                listingType = data["listingType"] as? String ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseAd ${doc.id}: ${e.message}", e)
            null
        }
    }

    private fun readLong(value: Any?): Long {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun readTimestamp(value: Any?): Long {
        return when (value) {
            is Number -> value.toLong()
            is Timestamp -> value.toDate().time
            else -> System.currentTimeMillis()
        }
    }

    /** Kunlik barqaror seed — listener har safar ro'yxatni silkitmasligi uchun. */
    fun feedOrderSeed(ads: List<ClassifiedAd>): Long {
        val dayBucket = System.currentTimeMillis() / (24L * 60L * 60L * 1000L)
        val idsHash = ads.map { it.id }.sorted().joinToString(",").hashCode().toLong()
        return dayBucket xor idsHash
    }

    /**
     * E'lonlar doskasi: kategoriyalar aralash random, lekin yangi e'lonlar ustunlik oladi.
     */
    fun orderForPublicFeed(ads: List<ClassifiedAd>, seed: Long = feedOrderSeed(ads)): List<ClassifiedAd> {
        if (ads.size <= 1) return ads
        val now = System.currentTimeMillis()
        val rng = Random(seed)
        return ads
            .map { ad ->
                val ageMs = (now - ad.createdAt).coerceAtLeast(0L)
                val recencyBoost = exp(-ageMs.toDouble() / FEED_RECENCY_HALF_LIFE_MS.toDouble())
                val score = rng.nextDouble() * (0.3 + 0.7 * recencyBoost)
                ad to score
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** O'z e'lonlari tepada, qolganlari doska tartibida (random + yangilik). */
    fun orderForFeed(
        ads: List<ClassifiedAd>,
        myUid: String,
        seed: Long = feedOrderSeed(ads)
    ): List<ClassifiedAd> {
        val uid = myUid.trim()
        if (uid.isBlank()) return orderForPublicFeed(ads, seed)
        val mine = ads.filter { it.ownerUid == uid }.sortedByDescending { it.createdAt }
        val others = ads.filter { it.ownerUid != uid }
        val orderedOthers = orderForPublicFeed(others, seed xor uid.hashCode().toLong())
        return mine + orderedOthers
    }

    private fun isMarketplaceAd(ad: ClassifiedAd): Boolean =
        !JobCardCategories.isJobBoardCategory(ad.category)

    suspend fun getActiveAds(limit: Long = 100): List<ClassifiedAd> {
        return try {
            val ads = db.collection(COLLECTION)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(limit)
                .get().await()
                .documents
                .mapNotNull { doc -> parseAd(doc) }
                .filter { it.isPubliclyVisible() && isMarketplaceAd(it) }
            orderForPublicFeed(ads)
        } catch (e: Exception) {
            Log.e(TAG, "getActiveAds: ${e.message}", e)
            emptyList()
        }
    }

    fun listenActiveAds(
        limit: Long = 100,
        shuffleForFeed: Boolean = true,
        onUpdate: (List<ClassifiedAd>) -> Unit
    ): ListenerRegistration {
        return db.collection(COLLECTION)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "listenActiveAds: ${error.message}", error)
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { doc -> parseAd(doc) }
                    ?.filter { it.isPubliclyVisible() && isMarketplaceAd(it) } ?: emptyList()
                onUpdate(if (shuffleForFeed) orderForPublicFeed(list) else list)
            }
    }

    suspend fun getAdById(id: String): ClassifiedAd? {
        if (id.isBlank()) return null
        return try {
            val doc = db.collection(COLLECTION).document(id).get().await()
            if (!doc.exists()) return null
            parseAd(doc)
        } catch (e: Exception) {
            Log.e(TAG, "getAdById: ${e.message}", e)
            null
        }
    }

    suspend fun getUserAds(uid: String): List<ClassifiedAd> {
        if (uid.isBlank()) return emptyList()
        return try {
            db.collection(COLLECTION)
                .whereEqualTo("ownerUid", uid)
                .get().await()
                .documents
                .mapNotNull { doc -> parseAd(doc) }
                .filter { isMarketplaceAd(it) }
                .sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            Log.e(TAG, "getUserAds: ${e.message}", e)
            emptyList()
        }
    }

    fun listenUserAds(
        uid: String,
        onUpdate: (List<ClassifiedAd>) -> Unit
    ): ListenerRegistration? {
        if (uid.isBlank()) return null
        return db.collection(COLLECTION)
            .whereEqualTo("ownerUid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "listenUserAds: ${error.message}", error)
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { doc -> parseAd(doc) }
                    ?.filter { isMarketplaceAd(it) }
                    ?.sortedByDescending { it.createdAt } ?: emptyList()
                onUpdate(list)
            }
    }

    suspend fun saveAd(ad: ClassifiedAd) {
        val id = ad.id.ifBlank { System.currentTimeMillis().toString() }
        val existing = if (id.isNotBlank()) getAdById(id) else null
        val preservedBlocked = existing?.isBlocked ?: ad.isBlocked
        val payload = ad.copy(
            id = id,
            imageUrls = ad.imageUrls.filter { it.isNotBlank() }.take(5),
            isActive = true,
            isBlocked = preservedBlocked,
            cardTheme = ad.cardTheme.ifBlank { existing?.cardTheme ?: ClassifiedAdCardThemes.BLACK }
        )
        val data = hashMapOf(
            "id" to id,
            "title" to payload.title,
            "model" to payload.model,
            "description" to payload.description,
            "price" to payload.price,
            "category" to payload.category,
            "imageUrls" to payload.imageUrls,
            "address" to payload.address,
            "phone" to payload.phone,
            "ownerUid" to payload.ownerUid,
            "ownerName" to payload.ownerName,
            "ownerPhotoUrl" to payload.ownerPhotoUrl,
            "createdAt" to payload.createdAt,
            "isActive" to payload.isActive,
            "isBlocked" to payload.isBlocked,
            "cardTheme" to payload.cardTheme,
            "region" to payload.region,
            "listingType" to payload.listingType
        )
        try {
            db.collection(COLLECTION).document(id).set(data).await()
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (message.contains("PERMISSION_DENIED", ignoreCase = true) ||
                message.contains("permission", ignoreCase = true)
            ) {
                throw IllegalStateException(
                    "E'lon saqlash ruxsati yo'q. Telegram orqali qayta kiring yoki biroz kutib qayta urinib ko'ring."
                )
            }
            throw e
        }
    }

    suspend fun deleteAd(id: String) {
        if (id.isBlank()) return
        db.collection(COLLECTION).document(id).delete().await()
    }

    suspend fun setActive(id: String, active: Boolean) {
        if (id.isBlank()) return
        db.collection(COLLECTION).document(id).update("isActive", active).await()
    }
}
