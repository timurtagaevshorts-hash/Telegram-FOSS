package uz.kodlikino.top4.utils

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import uz.kodlikino.top4.models.CommentUserProfile
import uz.kodlikino.top4.models.FollowUserItem
import java.util.concurrent.ConcurrentHashMap

/**
 * YuzGram foydalanuvchilari orasidagi ikki tomonlama obuna (subscribe) tizimi.
 * KinoTOP4`dagi [uz.kodlikino.top4.utils.FollowManager] tuzilmasi bilan bir xil
 * (`user_follows/{uid}/followers|following` + `comment_profiles` hisoblagichlari).
 * Faqat Firebase uid YuzGram custom-token (`tg_<telegramId>`) bilan keladi.
 */
object FollowManager {

    data class FollowCounts(
        val followers: Int = 0,
        val following: Int = 0
    )

    private class CompositeListenerRegistration(
        private val registrations: List<ListenerRegistration>
    ) : ListenerRegistration {
        override fun remove() {
            registrations.forEach { it.remove() }
        }
    }

    private const val TAG = "FollowManager"
    private const val ROOT = "user_follows"
    private const val PROFILES = "comment_profiles"

    private val db = FirebaseFirestore.getInstance()
    private val followingCache = ConcurrentHashMap<String, MutableSet<String>>()
    private val countsCache = ConcurrentHashMap<String, FollowCounts>()

    fun getCachedFollowing(myUid: String): Set<String> =
        followingCache[myUid.trim()]?.toSet() ?: emptySet()

    fun clearCache(uid: String? = null) {
        if (uid.isNullOrBlank()) {
            followingCache.clear()
        } else {
            followingCache.remove(uid.trim())
        }
    }

    fun getCachedFollowCounts(uid: String): FollowCounts? {
        val trimmed = uid.trim()
        if (trimmed.isBlank()) return null
        return countsCache[trimmed]
    }

    fun listenFollowStats(
        uid: String,
        onUpdate: (FollowCounts) -> Unit
    ): ListenerRegistration? {
        val trimmed = uid.trim()
        if (trimmed.isBlank()) return null

        countsCache[trimmed]?.let(onUpdate)

        var followers = countsCache[trimmed]?.followers ?: 0
        var following = countsCache[trimmed]?.following ?: 0
        var followersFromServer = false
        var followingFromServer = false

        fun emit() {
            val counts = FollowCounts(
                followers = followers.coerceAtLeast(0),
                following = following.coerceAtLeast(0)
            )
            countsCache[trimmed] = counts
            onUpdate(counts)
        }

        val followersReg = db.collection(ROOT).document(trimmed).collection("followers")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "listenFollowStats followers: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                if (snapshot.metadata.isFromCache && followersFromServer) return@addSnapshotListener
                if (!snapshot.metadata.isFromCache) followersFromServer = true
                followers = snapshot.size()
                emit()
            }

        val followingReg = db.collection(ROOT).document(trimmed).collection("following")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "listenFollowStats following: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                if (snapshot.metadata.isFromCache && followingFromServer) return@addSnapshotListener
                if (!snapshot.metadata.isFromCache) followingFromServer = true
                following = snapshot.size()
                emit()
            }

        return CompositeListenerRegistration(listOf(followersReg, followingReg))
    }

    suspend fun fetchFollowCounts(uid: String): FollowCounts = withContext(Dispatchers.IO) {
        val trimmed = uid.trim()
        if (trimmed.isBlank()) return@withContext FollowCounts()
        try {
            coroutineScope {
                val followersDeferred = async {
                    db.collection(ROOT).document(trimmed).collection("followers").get().await().size()
                }
                val followingDeferred = async {
                    db.collection(ROOT).document(trimmed).collection("following").get().await().size()
                }
                val counts = FollowCounts(
                    followers = followersDeferred.await().coerceAtLeast(0),
                    following = followingDeferred.await().coerceAtLeast(0)
                )
                countsCache[trimmed] = counts
                syncProfileFollowCounts(trimmed, counts)
                counts
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchFollowCounts: ${e.message}", e)
            countsCache[trimmed] ?: FollowCounts()
        }
    }

    private suspend fun syncProfileFollowCounts(uid: String, counts: FollowCounts) {
        try {
            db.collection(PROFILES).document(uid).set(
                mapOf(
                    "followerCount" to counts.followers,
                    "followingCount" to counts.following
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "syncProfileFollowCounts: ${e.message}")
        }
    }

    fun listenFollowingIds(
        myUid: String,
        onUpdate: (Set<String>) -> Unit
    ): ListenerRegistration? {
        val uid = myUid.trim()
        if (uid.isBlank()) return null
        return db.collection(ROOT).document(uid).collection("following")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "listenFollowingIds: ${error.message}")
                    return@addSnapshotListener
                }
                val ids = snapshot?.documents
                    ?.mapNotNull { it.id.trim().takeIf { id -> id.isNotBlank() } }
                    ?.toSet()
                    ?: emptySet()
                followingCache[uid] = ids.toMutableSet()
                onUpdate(ids)
            }
    }

    suspend fun isFollowing(myUid: String, targetUid: String): Boolean {
        val me = myUid.trim()
        val target = targetUid.trim()
        if (me.isBlank() || target.isBlank() || me == target) return false
        return withContext(Dispatchers.IO) {
            try {
                db.collection(ROOT).document(me).collection("following").document(target)
                    .get().await().exists()
            } catch (e: Exception) {
                Log.e(TAG, "isFollowing: ${e.message}")
                false
            }
        }
    }

    suspend fun follow(
        myUid: String,
        targetUid: String,
        myProfile: CommentUserProfile?,
        targetProfile: CommentUserProfile?
    ): Result<Unit> {
        val me = myUid.trim()
        val target = targetUid.trim()
        if (me.isBlank() || target.isBlank()) {
            return Result.failure(IllegalArgumentException("Noto'g'ri foydalanuvchi"))
        }
        if (me == target) {
            return Result.failure(IllegalArgumentException("O'zingizga obuna bo'la olmaysiz"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val followingRef = db.collection(ROOT).document(me).collection("following").document(target)
                if (followingRef.get().await().exists()) {
                    return@withContext Result.success(Unit)
                }
                val now = System.currentTimeMillis()
                val myName = myProfile?.displayName.orEmpty().ifBlank { "Foydalanuvchi" }
                val targetName = targetProfile?.displayName.orEmpty().ifBlank { "Foydalanuvchi" }
                val followerRef =
                    db.collection(ROOT).document(target).collection("followers").document(me)
                val myProfileRef = db.collection(PROFILES).document(me)
                val targetProfileRef = db.collection(PROFILES).document(target)
                db.runTransaction { tx ->
                    // BARCHA O'QISHLAR yozishdan oldin bo'lishi shart (Firestore talabi).
                    val followingSnap = tx.get(followingRef)
                    if (followingSnap.exists()) return@runTransaction
                    val myProfileSnap = tx.get(myProfileRef)
                    val targetProfileSnap = tx.get(targetProfileRef)

                    val followingEdge = mapOf(
                        "targetUid" to target,
                        "createdAt" to now,
                        "targetName" to targetName,
                        "targetPhotoUrl" to targetProfile?.photoUrl.orEmpty()
                    )
                    val followerEdge = mapOf(
                        "followerUid" to me,
                        "createdAt" to now,
                        "followerName" to myName,
                        "followerPhotoUrl" to myProfile?.photoUrl.orEmpty()
                    )
                    tx.set(followingRef, followingEdge)
                    tx.set(followerRef, followerEdge)
                    // comment_profiles hisoblagichlarini faqat hujjat MAVBOOT bo'lsa
                    // yangilaymiz — aks holda nodiron egasi CREATE huquqiga ega emas va
                    // butun tranzaksiya PERMISSION_DENIED bilan qulaydi. Proldagi sonlar
                    // user_follows listener'laridan keladi, shuning uchun bu tashlash
                    // hisobga ta'sir qilmaydi.
                    if (myProfileSnap.exists()) {
                        tx.set(
                            myProfileRef,
                            mapOf("followingCount" to FieldValue.increment(1)),
                            SetOptions.merge()
                        )
                    }
                    if (targetProfileSnap.exists()) {
                        tx.set(
                            targetProfileRef,
                            mapOf("followerCount" to FieldValue.increment(1)),
                            SetOptions.merge()
                        )
                    }
                }.await()
                followingCache.getOrPut(me) { mutableSetOf() }.add(target)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "follow: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    suspend fun unfollow(myUid: String, targetUid: String): Result<Unit> {
        val me = myUid.trim()
        val target = targetUid.trim()
        if (me.isBlank() || target.isBlank() || me == target) {
            return Result.failure(IllegalArgumentException("Noto'g'ri foydalanuvchi"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val followingRef = db.collection(ROOT).document(me).collection("following").document(target)
                if (!followingRef.get().await().exists()) {
                    return@withContext Result.success(Unit)
                }
                val followerRef =
                    db.collection(ROOT).document(target).collection("followers").document(me)
                val myProfileRef = db.collection(PROFILES).document(me)
                val targetProfileRef = db.collection(PROFILES).document(target)
                db.runTransaction { tx ->
                    // Barcha o'qishlar yozishdan oldin.
                    val followingSnap = tx.get(followingRef)
                    if (!followingSnap.exists()) return@runTransaction
                    val myProfileSnap = tx.get(myProfileRef)
                    val targetProfileSnap = tx.get(targetProfileRef)
                    tx.delete(followingRef)
                    tx.delete(followerRef)
                    if (myProfileSnap.exists()) {
                        tx.set(
                            myProfileRef,
                            mapOf("followingCount" to FieldValue.increment(-1)),
                            SetOptions.merge()
                        )
                    }
                    if (targetProfileSnap.exists()) {
                        tx.set(
                            targetProfileRef,
                            mapOf("followerCount" to FieldValue.increment(-1)),
                            SetOptions.merge()
                        )
                    }
                }.await()
                followingCache[me]?.remove(target)
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "unfollow: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    suspend fun fetchFollowers(uid: String, limit: Long = 100): List<FollowUserItem> =
        fetchEdgeList(uid, followers = true, limit = limit)

    suspend fun fetchFollowing(uid: String, limit: Long = 100): List<FollowUserItem> =
        fetchEdgeList(uid, followers = false, limit = limit)

    private suspend fun fetchEdgeList(
        uid: String,
        followers: Boolean,
        limit: Long
    ): List<FollowUserItem> = withContext(Dispatchers.IO) {
        val trimmed = uid.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        try {
            val collection = if (followers) "followers" else "following"
            val snap = db.collection(ROOT).document(trimmed).collection(collection)
                .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(limit)
                .get().await()
            snap.documents.mapNotNull { doc ->
                if (followers) {
                    FollowUserItem(
                        uid = doc.getString("followerUid")?.trim().orEmpty().ifBlank { doc.id },
                        name = doc.getString("followerName").orEmpty(),
                        photoUrl = doc.getString("followerPhotoUrl").orEmpty()
                    )
                } else {
                    FollowUserItem(
                        uid = doc.getString("targetUid")?.trim().orEmpty().ifBlank { doc.id },
                        name = doc.getString("targetName").orEmpty(),
                        photoUrl = doc.getString("targetPhotoUrl").orEmpty()
                    )
                }
            }.filter { it.uid.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "fetchEdgeList: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun warmFollowingCache(myUid: String) {
        val uid = myUid.trim()
        if (uid.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val snap = db.collection(ROOT).document(uid).collection("following").get().await()
                val ids = snap.documents.map { it.id.trim() }.filter { it.isNotBlank() }.toMutableSet()
                followingCache[uid] = ids
            } catch (e: Exception) {
                Log.e(TAG, "warmFollowingCache: ${e.message}")
            }
        }
    }
}
