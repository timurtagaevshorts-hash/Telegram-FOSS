package uz.kodlikino.top4.utils

import android.util.Log
import com.google.android.gms.tasks.OnProgressListener
import com.google.firebase.storage.UploadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Firebase Storage yuklashini "mangu kutish"dan saqlaydigan kuzatuvchi.
 *
 * Muammo (2026-10-16 foydalanuvchi shikoyati: "reel/elon yuklab bo'lmayapti"):
 * `task.await()` faqat yuklash tugagach qaytadi. Internet sekinlashsa yoki
 * upload tutaqib qolsa task tugamaydi — ekran abadiy "yuklanmoqda" holatida
 * qoladi va foydalanuvchi ilova ishlamayapti deb o'ylaydi.
 *
 * Yechim: har [WATCHDOG_TICK_MS] ms da jo'natilgan baytlar solishtiriladi.
 * [STALL_MS] davomida 1 bayt ham ko'chmasa `UploadTask.cancel()` chaqiriladi va
 * [IllegalStateException] ko'tariladi — chaqiruvchi tomon buni urinishlar
 * soni doirasida qaytadan boshlaydi.
 */
object YuzUploadWatchdog {

    private const val TAG = "YuzUploadWatchdog"
    const val STALL_MS = 45_000L
    private const val WATCHDOG_TICK_MS = 5_000L

    /**
     * @param onSnapshot har bir progress event'da chaqiriladigan UI yangilash.
     * @throws IllegalStateException yuklash [STALL_MS] davomida to'xtagan bo'lsa.
     */
    suspend fun awaitWithStall(
        task: UploadTask,
        label: String,
        onSnapshot: (UploadTask.TaskSnapshot) -> Unit = {}
    ) = coroutineScope {
        val lastByteAt = AtomicLong(System.currentTimeMillis())
        val stalled = AtomicBoolean(false)
        val listener = OnProgressListener<UploadTask.TaskSnapshot> { snap ->
            lastByteAt.set(System.currentTimeMillis())
            onSnapshot(snap)
        }
        task.addOnProgressListener(listener)
        val watchdog = launch(Dispatchers.Default) {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                if (System.currentTimeMillis() - lastByteAt.get() > STALL_MS) {
                    Log.w(TAG, "$label: no bytes for $STALL_MS ms -> cancelling")
                    stalled.set(true)
                    try {
                        task.cancel()
                    } catch (e: Exception) {
                        Log.w(TAG, "cancel: ${e.message}")
                    }
                    break
                }
            }
        }
        try {
            task.await()
            if (stalled.get()) throw stallError(label)
        } catch (e: Exception) {
            if (stalled.get()) throw stallError(label)
            throw e
        } finally {
            watchdog.cancel()
            // Progress listener'ni alohida o'chirmaymiz: task allaqachon tugagan
            // yoki bekor qilingan — listener bir marta ishlatilgan holda qoladi.
            // (removeOnProgressListener API'siga tayanmaslik = CI'da compile xatosi riski yo'q.)
        }
    }

    fun stallError(label: String) =
        IllegalStateException("$label yuklashi to'xtab qoldi — internet sekin yoki uzildi")
}
