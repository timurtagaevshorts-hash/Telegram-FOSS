package uz.kodlikino.top4.utils

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import uz.kodlikino.top4.models.ReferralStats
import uz.kodlikino.top4.models.WithdrawalRequest

/**
 * Monetizatsiya / hisobdan pul yechish ma'lumot qatlami.
 * KinoTOP4`dagi FirebaseManager'ning `withdrawal_requests` va `referral_stats`
 * bilan ishlovchi funksiyalari — tuzilma o'zgarmagan.
 */
object MonetizationRepository {

    private const val TAG = "MonetizationRepo"
    const val WITHDRAWALS = "withdrawal_requests"
    const val REFERRAL_STATS = "referral_stats"

    private val db = FirebaseFirestore.getInstance()

    suspend fun createWithdrawalRequest(request: WithdrawalRequest) = withContext(Dispatchers.IO) {
        db.collection(WITHDRAWALS).document(request.id).set(request).await()
    }

    suspend fun getWithdrawalRequest(id: String): WithdrawalRequest? = withContext(Dispatchers.IO) {
        try {
            db.collection(WITHDRAWALS).document(id).get().await()
                .toObject(WithdrawalRequest::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getUserWithdrawalRequests(userId: String): List<WithdrawalRequest> =
        withContext(Dispatchers.IO) {
            if (userId.isBlank()) return@withContext emptyList()
            try {
                db.collection(WITHDRAWALS)
                    .whereEqualTo("userId", userId)
                    .get().await()
                    .toObjects(WithdrawalRequest::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "getUserWithdrawalRequests: ${e.message}", e)
                emptyList()
            }
        }

    fun listenUserWithdrawalRequests(
        userId: String,
        onUpdate: (List<WithdrawalRequest>) -> Unit
    ): ListenerRegistration? {
        if (userId.isBlank()) return null
        return db.collection(WITHDRAWALS)
            .whereEqualTo("userId", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "listenUserWithdrawalRequests: ${error.message}", error)
                    return@addSnapshotListener
                }
                onUpdate(snapshot?.toObjects(WithdrawalRequest::class.java) ?: emptyList())
            }
    }

    fun listenSourceWithdrawals(
        userId: String,
        source: String,
        onUpdate: (List<WithdrawalRequest>) -> Unit
    ): ListenerRegistration? {
        if (userId.isBlank()) return null
        return db.collection(WITHDRAWALS)
            .whereEqualTo("userId", userId)
            .whereEqualTo("source", source)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "listenSourceWithdrawals: ${error.message}", error)
                    return@addSnapshotListener
                }
                onUpdate(snapshot?.toObjects(WithdrawalRequest::class.java) ?: emptyList())
            }
    }

    suspend fun getPendingWithdrawalRequest(
        userId: String,
        source: String = WithdrawalRequest.SOURCE_REEL
    ): WithdrawalRequest? = withContext(Dispatchers.IO) {
        try {
            db.collection(WITHDRAWALS)
                .whereEqualTo("userId", userId)
                .whereEqualTo("status", WithdrawalRequest.STATUS_PENDING)
                .get().await()
                .toObjects(WithdrawalRequest::class.java)
                .filter {
                    MonetizationCalculator.isMonetizationCurrency(it.currency) &&
                        it.source == source
                }
                .maxByOrNull { it.createdAt }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun approveWithdrawalRequest(requestId: String) = withContext(Dispatchers.IO) {
        val request = getWithdrawalRequest(requestId)
            ?: throw IllegalStateException("So'rov topilmadi")
        if (request.status != WithdrawalRequest.STATUS_PENDING) return@withContext
        db.collection(WITHDRAWALS).document(requestId)
            .update("status", WithdrawalRequest.STATUS_APPROVED).await()
    }

    suspend fun rejectWithdrawalRequest(requestId: String) = withContext(Dispatchers.IO) {
        val request = getWithdrawalRequest(requestId)
            ?: throw IllegalStateException("So'rov topilmadi")
        if (request.status != WithdrawalRequest.STATUS_PENDING) return@withContext
        db.collection(WITHDRAWALS).document(requestId)
            .update("status", WithdrawalRequest.STATUS_REJECTED).await()
    }

    suspend fun updateWithdrawalTelegramId(requestId: String, messageId: String) =
        withContext(Dispatchers.IO) {
            db.collection(WITHDRAWALS).document(requestId)
                .update("telegramMessageId", messageId).await()
        }

    // ============ REFERRAL ============
    suspend fun getReferralStats(uid: String): ReferralStats = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext ReferralStats()
        try {
            val doc = db.collection(REFERRAL_STATS).document(uid).get().await()
            if (!doc.exists()) {
                ReferralStats()
            } else {
                ReferralStats(
                    count = (doc.getLong("count") ?: 0L).toInt(),
                    updatedAt = doc.getLong("updatedAt") ?: 0L
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getReferralStats: ${e.message}", e)
            ReferralStats()
        }
    }

    fun listenReferralStats(
        uid: String,
        onUpdate: (ReferralStats) -> Unit
    ): ListenerRegistration? {
        if (uid.isBlank()) return null
        return db.collection(REFERRAL_STATS).document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "listenReferralStats: ${error.message}", error)
                    return@addSnapshotListener
                }
                val stats = if (snapshot == null || !snapshot.exists()) {
                    ReferralStats()
                } else {
                    ReferralStats(
                        count = (snapshot.getLong("count") ?: 0L).toInt(),
                        updatedAt = snapshot.getLong("updatedAt") ?: 0L
                    )
                }
                onUpdate(stats)
            }
    }
}
