package uz.kodlikino.top4.utils

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import uz.kodlikino.top4.models.Movie

/**
 * Kinolar — Firestore `movies` kolleksiyasi.
 * Qoidalar: READ public (feed uchun auth kerak emas); create/delete/update = admin;
 * istalgan tizimga kirgan foydalanuvchi faqat `views`/`likes`/`searchCount` ni
 * yangilay oladi (shu sababli [incrementViews] signed-in sessiya bilan ishlaydi).
 */
class MoviesRepository {

    private val db = FirebaseFirestore.getInstance()

    suspend fun listMovies(): List<Movie> {
        return try {
            val snap = db.collection(COLLECTION)
                .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(1000)
                .get()
                .await()
            snap.documents.mapNotNull { doc ->
                doc.toObject(Movie::class.java)?.copy(id = doc.id)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getMovie(id: String): Movie? {
        if (id.isBlank()) return null
        return try {
            db.collection(COLLECTION).document(id).get().await()
                .let { snap -> if (snap.exists()) snap.toObject(Movie::class.java)?.copy(id = snap.id) else null }
        } catch (_: Exception) {
            null
        }
    }

    /** Admin yozuvi — doc id == movie.id (donor sxemasi: id maydoni doc id bilan bir xil). */
    suspend fun saveMovie(movie: Movie): Result<Unit> {
        return try {
            val id = movie.id.ifBlank { System.currentTimeMillis().toString() }
            db.collection(COLLECTION).document(id).set(movie.copy(id = id)).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteMovie(id: String): Result<Unit> {
        if (id.isBlank()) return Result.failure(IllegalArgumentException("id"))
        return try {
            db.collection(COLLECTION).document(id).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Ko'rishlar sonini oshirish — best-effort (auth bo'lmasa jimgina o'tadi). */
    suspend fun incrementViews(id: String) {
        if (id.isBlank()) return
        try {
            db.collection(COLLECTION).document(id)
                .update("views", FieldValue.increment(1))
                .await()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val COLLECTION = "movies"
    }
}
