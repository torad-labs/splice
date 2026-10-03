// NEW: queued coroutine callers retain a conversation lock until its last user releases it.
package splice.provider.codex.state

import kotlinx.coroutines.sync.Mutex
import splice.provider.codex.CodexCodeModeBridge
import java.security.MessageDigest

/** Full upstream drives share a mutex only when their conversation keys are equal. */
internal class CodeModeTurnLocks {
    class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val monitor = Any()
    private val keys = mutableMapOf<String, Entry>()

    suspend fun acquire(key: String): Entry {
        val entry = synchronized(monitor) {
            keys.getOrPut(key) { Entry() }.also { it.users++ }
        }
        var acquired = false
        return try {
            entry.mutex.lock()
            acquired = true
            entry
        } finally {
            if (!acquired) unreference(key, entry)
        }
    }

    fun release(key: String, entry: Entry) {
        entry.mutex.unlock()
        unreference(key, entry)
    }

    private fun unreference(key: String, entry: Entry) = synchronized(monitor) {
        entry.users--
        if (entry.users == 0) keys.remove(key)
    }
}

/** The exact conversation key and request digest that acquire the coroutine turn lock. */
internal class CodeModeTurnIdentity {
    fun turnKey(turn: CodexCodeModeBridge.Turn): String = digest(
        "${turn.sessionId}${0.toChar()}${turn.conversationKey}${0.toChar()}${turn.model}",
    )

    /** Model changes can supersede parked programs, but never sibling conversations or a busy engine. */
    fun conversationId(turn: CodexCodeModeBridge.Turn): String = digest(
        "${turn.sessionId}${0.toChar()}${turn.conversationKey}",
    )

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
