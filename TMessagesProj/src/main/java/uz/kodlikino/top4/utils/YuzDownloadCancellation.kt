package uz.kodlikino.top4.utils

import okhttp3.Call
import java.io.IOException

class YuzDownloadCancellation {
    companion object {
        private val current = ThreadLocal<YuzDownloadCancellation>()

        fun current(): YuzDownloadCancellation? = current.get()
    }

    @Volatile
    var canceled = false
        private set
    private var committed = false
    private val calls = HashSet<Call>()

    fun cancel() {
        val pending = synchronized(this) {
            if (committed) return
            canceled = true
            calls.toList()
        }
        pending.forEach { it.cancel() }
    }

    fun check() {
        if (canceled) throw IOException("download canceled")
    }

    fun attach(call: Call) = synchronized(this) {
        check()
        calls.add(call)
        Unit
    }

    fun detach(call: Call) = synchronized(this) {
        calls.remove(call)
        Unit
    }

    // Cancellation and file publication must have a single winner.
    fun commit(block: () -> Boolean): Boolean = synchronized(this) {
        check()
        val ok = block()
        if (ok) committed = true
        ok
    }

    fun <T> scoped(block: () -> T): T {
        val previous = current.get()
        current.set(this)
        return try {
            check()
            block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
