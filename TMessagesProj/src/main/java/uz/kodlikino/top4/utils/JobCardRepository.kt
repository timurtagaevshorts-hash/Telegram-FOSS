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
import uz.kodlikino.top4.models.JobCard
import uz.kodlikino.top4.models.JobCardCategories
import uz.kodlikino.top4.models.JobCardListingTypes
import uz.kodlikino.top4.models.JobCardSalaryTypes

object JobCardRepository {

    private const val TAG = "JobCardRepo"
    /** Mavjud Firebase qoidalari bilan ishlashi uchun classified_ads kolleksiyasi. */
    const val COLLECTION_NAME = ClassifiedAdRepository.COLLECTION_NAME
    private const val LEGACY_COLLECTION = "job_cards"
    private val db = FirebaseFirestore.getInstance()

    fun parseDocument(doc: DocumentSnapshot): JobCard? = parseCard(doc)

    private fun parseCard(doc: DocumentSnapshot): JobCard? {
        return try {
            val data = doc.data ?: return null
            val category = data["category"] as? String ?: ""
            val listingType = when {
                JobCardCategories.isJobBoardCategory(category) ->
                    JobCardCategories.toListingType(category)
                data.containsKey("listingType") ->
                    data["listingType"] as? String ?: JobCardListingTypes.HIRING
                else -> return null
            }
            val imageUrls = when (val raw = data["imageUrls"]) {
                is List<*> -> raw.mapNotNull { it as? String }.filter { it.isNotBlank() }
                else -> emptyList()
            }
            val legacyImage = data["imageUrl"] as? String ?: ""
            val imageUrl = imageUrls.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: legacyImage.takeIf { it.isNotBlank() }
                ?: ""
            JobCard(
                id = doc.id,
                listingType = listingType,
                title = data["title"] as? String ?: "",
                description = data["description"] as? String ?: "",
                location = (data["location"] as? String)
                    ?: (data["address"] as? String)
                    ?: "",
                phone = data["phone"] as? String ?: "",
                salaryAmount = readLong(data["salaryAmount"]).takeIf { it > 0L }
                    ?: readLong(data["price"]),
                salaryType = (data["salaryType"] as? String)
                    ?: (data["model"] as? String)
                    ?: JobCardSalaryTypes.NEGOTIABLE,
                imageUrl = imageUrl,
                cardTheme = data["cardTheme"] as? String ?: ClassifiedAdCardThemes.BLACK,
                ownerUid = data["ownerUid"] as? String ?: "",
                ownerName = data["ownerName"] as? String ?: "",
                ownerPhotoUrl = data["ownerPhotoUrl"] as? String ?: "",
                createdAt = readTimestamp(data["createdAt"]),
                isActive = data["isActive"] as? Boolean ?: true,
                isBlocked = data["isBlocked"] as? Boolean ?: false,
                region = data["region"] as? String ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseCard ${doc.id}: ${e.message}", e)
            null
        }
    }

    private fun readLong(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun readTimestamp(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is Timestamp -> value.toDate().time
        else -> System.currentTimeMillis()
    }

    private fun toClassifiedAd(card: JobCard): ClassifiedAd {
        val images = card.imageUrl.trim().takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
        return ClassifiedAd(
            id = card.id,
            title = card.title,
            model = card.salaryType,
            description = card.description,
            price = if (card.listingType == JobCardListingTypes.HIRING) card.salaryAmount else 0L,
            category = JobCardCategories.fromListingType(card.listingType),
            imageUrls = images,
            address = card.location,
            phone = card.phone,
            ownerUid = card.ownerUid,
            ownerName = card.ownerName,
            ownerPhotoUrl = card.ownerPhotoUrl,
            createdAt = card.createdAt,
            isActive = card.isActive,
            isBlocked = card.isBlocked,
            cardTheme = card.cardTheme,
            region = card.region,
            listingType = card.listingType
        )
    }

    /** O'z e'loni tepada, qolganlari yangilik bo'yicha. */
    fun orderForFeed(cards: List<JobCard>, myUid: String): List<JobCard> {
        val uid = myUid.trim()
        val mine = cards.filter { it.ownerUid == uid }
            .sortedByDescending { it.createdAt }
        val others = cards.filter { it.ownerUid != uid }
            .sortedByDescending { it.createdAt }
        return mine + others
    }

    fun listenActiveCards(
        listingType: String,
        myUid: String,
        limit: Long = 200,
        onUpdate: (List<JobCard>) -> Unit
    ): ListenerRegistration {
        val category = JobCardCategories.fromListingType(listingType)
        // #120: fallback registration avval chiqindiga tushib qolardi —
        // endi u ham qaytariladigan o'ram orqali remove() qilinadi.
        val fallbackRef = java.util.concurrent.atomic.AtomicReference<ListenerRegistration?>(null)
        val primary = db.collection(COLLECTION_NAME)
            .whereEqualTo("category", category)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "listenActiveCards: ${error.message}", error)
                    if (error.message?.contains("index", ignoreCase = true) == true &&
                        fallbackRef.get() == null
                    ) {
                        fallbackRef.set(listenActiveCardsFallback(listingType, myUid, limit, onUpdate))
                    }
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { parseCard(it) }
                    ?.filter { it.isPubliclyVisible() }
                    ?: emptyList()
                onUpdate(orderForFeed(list, myUid))
            }
        return object : ListenerRegistration {
            override fun remove() {
                primary.remove()
                fallbackRef.get()?.remove()
            }
            // #120: bu Firestore versiyasida ListenerRegistration'da
            // areAllListenersComplete() yo'q — faqat remove() abstrakt.
        }
    }

    private fun listenActiveCardsFallback(
        listingType: String,
        myUid: String,
        limit: Long,
        onUpdate: (List<JobCard>) -> Unit
    ): ListenerRegistration {
        return db.collection(COLLECTION_NAME)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit * 2)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "listenActiveCardsFallback: ${error.message}", error)
                    return@addSnapshotListener
                }
                val list = snapshot?.documents?.mapNotNull { parseCard(it) }
                    ?.filter { it.isPubliclyVisible() && it.listingType == listingType }
                    ?: emptyList()
                onUpdate(orderForFeed(list, myUid))
            }
    }

    suspend fun getCardById(id: String): JobCard? {
        if (id.isBlank()) return null
        return try {
            val primary = db.collection(COLLECTION_NAME).document(id).get().await()
            if (primary.exists()) {
                parseCard(primary)?.let { return it }
            }
            val legacy = db.collection(LEGACY_COLLECTION).document(id).get().await()
            if (!legacy.exists()) return null
            parseCard(legacy)
        } catch (e: Exception) {
            Log.e(TAG, "getCardById: ${e.message}", e)
            null
        }
    }

    suspend fun findLatestImageUrlForOwner(ownerUid: String, excludeId: String = ""): String {
        val uid = ownerUid.trim()
        if (uid.isBlank()) return ""
        return try {
            db.collection(COLLECTION_NAME)
                .whereEqualTo("ownerUid", uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(20)
                .get()
                .await()
                .documents
                .mapNotNull { parseCard(it) }
                .asSequence()
                .filter { it.id != excludeId }
                .map { it.imageUrl.trim() }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "findLatestImageUrlForOwner: ${e.message}")
            ""
        }
    }

    suspend fun saveCard(card: JobCard) {
        val id = card.id.ifBlank { System.currentTimeMillis().toString() }
        val existing = getCardById(id)
        val payload = card.copy(
            id = id,
            isActive = true,
            isBlocked = existing?.isBlocked ?: card.isBlocked,
            cardTheme = card.cardTheme.ifBlank { existing?.cardTheme ?: ClassifiedAdCardThemes.BLACK },
            createdAt = if (card.createdAt > 0L) card.createdAt else existing?.createdAt
                ?: System.currentTimeMillis()
        )
        ClassifiedAdRepository.saveAd(toClassifiedAd(payload))
    }

    suspend fun deleteCard(id: String) {
        if (id.isBlank()) return
        ClassifiedAdRepository.deleteAd(id)
    }
}
