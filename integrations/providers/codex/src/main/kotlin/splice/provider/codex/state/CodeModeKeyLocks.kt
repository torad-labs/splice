// NEW: per-conversation transition locks are separate from the head-wide state lock.
package splice.provider.codex.state

import splice.provider.codex.stream.CodeModeSourceEnds
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Same-key admissions include their durable predecessor; unrelated keys never share this wait.
 *  References include queued callers, so a key's lock is removed only when nobody can still use it. */
internal class CodeModeKeyLocks {
    private val monitor = Any()
    private val keys = mutableMapOf<String, Entry>()

    class Entry {
        val lock = ReentrantLock()
        var users = 0
    }

    fun acquire(key: String): Entry {
        val entry = synchronized(monitor) {
            keys.getOrPut(key) { Entry() }.also { it.users++ }
        }
        entry.lock.lock()
        return entry
    }

    fun tryAcquire(key: String): Entry? = synchronized(monitor) {
        val entry = keys.getOrPut(key) { Entry() }
        if (!entry.lock.tryLock()) return@synchronized null
        entry.users++
        entry
    }

    fun release(key: String, entry: Entry) {
        entry.lock.unlock()
        synchronized(monitor) {
            entry.users--
            if (entry.users == 0) keys.remove(key)
        }
    }
}

/** Locks one conversation before taking the head-wide state lock, never the inverse. */
internal class CodeModeRegistryAccess(
    val monitor: ReentrantLock,
    val keys: CodeModeKeyLocks,
) {
    inline fun tryKey(key: String, block: () -> Unit) = CodeModeSourceEnds.unlocked {
        val entry = keys.tryAcquire(key) ?: return@unlocked
        try {
            monitor.withLock(block)
        } finally {
            keys.release(key, entry)
        }
    }

    inline fun <T> withKey(key: String, block: () -> T): T = CodeModeSourceEnds.unlocked {
        val entry = keys.acquire(key)
        try {
            monitor.withLock(block)
        } finally {
            keys.release(key, entry)
        }
    }
}
