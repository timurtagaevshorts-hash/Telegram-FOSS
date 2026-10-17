package uz.kodlikino.top4.utils

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import uz.kodlikino.top4.models.TaxiOrder

/** Taksi buyurtmalari — `taxi_orders` kolleksiyasi. KinoTOP4 tuzilmasi bilan bir xil. */
class TaxiOrderRepository {

    private val db = FirebaseFirestore.getInstance()

    suspend fun getActiveCooldownEnd(userId: String): Long {
        if (userId.isBlank()) return 0L
        return try {
            val snap = db.collection(COLLECTION)
                .whereEqualTo("userId", userId)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .await()
            val order = snap.documents.firstOrNull()?.toObject(TaxiOrder::class.java) ?: return 0L
            val blocked = order.callBlockedUntil
            if (blocked > System.currentTimeMillis()) blocked else 0L
        } catch (_: Exception) {
            0L
        }
    }

    suspend fun createOrder(order: TaxiOrder): Result<TaxiOrder> {
        return try {
            val payload = order.copy(
                callBlockedUntil = 0L,
                status = TaxiOrder.STATUS_PENDING
            )
            db.collection(COLLECTION).document(payload.id).set(payload).await()
            Result.success(payload)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun listenUserOrders(
        userId: String,
        onChange: (List<TaxiOrder>) -> Unit,
        onError: ((Exception) -> Unit)? = null
    ): ListenerRegistration? {
        if (userId.isBlank()) {
            onChange(emptyList())
            return null
        }
        return db.collection(COLLECTION)
            .whereEqualTo("userId", userId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(30)
            .addSnapshotListener { snap, error ->
                // Eski/donor yozuvlarida maydon tiplari boshqacha bo'lsa
                // (masalan createdAt = Timestamp) toObject() xato beradi —
                // bu crash'ni ekranga yetkazmaymiz: bitta hujjatni o'tkazib
                // yuboramiz, umumiy xatoni esa [onError]ga beramiz.
                try {
                    if (error != null) {
                        onError?.invoke(error)
                        return@addSnapshotListener
                    }
                    val list = snap?.documents
                        ?.mapNotNull { doc ->
                            try {
                                doc.toObject(TaxiOrder::class.java)
                            } catch (_: Exception) {
                                null
                            }
                        }
                        .orEmpty()
                    onChange(list)
                } catch (e: Exception) {
                    onError?.invoke(e)
                }
            }
    }

    fun isPermissionDenied(error: Throwable?): Boolean {
        return error is FirebaseFirestoreException &&
            error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
    }

    suspend fun cancelOrder(orderId: String): Result<Unit> {
        return updateUserOrderStatus(
            orderId,
            TaxiOrder.STATUS_CANCELLED,
            "Foydalanuvchi bekor qildi."
        )
    }

    private suspend fun updateUserOrderStatus(
        orderId: String,
        status: String,
        statusMessage: String
    ): Result<Unit> {
        if (orderId.isBlank()) return Result.failure(IllegalArgumentException("orderId"))
        return try {
            db.collection(COLLECTION).document(orderId).update(
                mapOf(
                    "status" to status,
                    "statusMessage" to statusMessage
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isActiveTrackingStatus(status: String): Boolean {
        return status == TaxiOrder.STATUS_PENDING || status == TaxiOrder.STATUS_APPROVED
    }

    companion object {
        const val COLLECTION = "taxi_orders"
    }
}
