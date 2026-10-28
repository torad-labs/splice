package splice.provider.codex.stream.session

import splice.provider.codex.state.lease.CodeModeSessionAlive
import java.util.concurrent.ConcurrentHashMap

/** Logical services outlive cells and records. The same monitor coordinates native interruption and teardown. */
internal class CodeModeNativeServices {
    val monitor = Any()
    private val owners = ConcurrentHashMap<String, Owner>()
    private val aliases = ConcurrentHashMap<String, String>()

    fun trackSession(recordKey: String, key: String, clientSessionId: String): Unit = synchronized(monitor) {
        owner(recordKey, key, clientSessionId)
    }

    /** An unavailable service has no engine yet, but its once flag has this same actual logical lifetime. */
    fun claimUnavailableWarning(
        recordKey: String,
        key: String,
        clientSessionId: String,
    ): Boolean = synchronized(monitor) {
        owner(recordKey, key, clientSessionId).claimWarning()
    }

    private fun owner(recordKey: String, key: String, clientSessionId: String): Owner {
        val owner = owners[key] ?: Owner(clientSessionId).also { owners[key] = it }
        require(owner.clientSessionId == clientSessionId) { "Native session ownership changed" }
        aliases[recordKey] = key
        return owner
    }

    fun nativeSession(key: String): String = aliases[key] ?: key
    fun clientSession(key: String): String? = owners[nativeSession(key)]?.clientSessionId
    fun owns(key: String, clientSessionId: String): Boolean = owners[key]?.clientSessionId == clientSessionId
    fun hasSessions(): Boolean = owners.isNotEmpty()
    fun endedSessions(alive: CodeModeSessionAlive): List<String> =
        owners.filterValues { alive(it.clientSessionId) == false }.keys.toList()

    fun closeSession(key: String): Unit = synchronized(monitor) {
        owners.remove(key)
        aliases.entries.removeIf { it.value == key }
    }

    fun clear() = synchronized(monitor) {
        owners.clear()
        aliases.clear()
    }

    private class Owner(val clientSessionId: String) {
        private var warningEmitted = false

        fun claimWarning(): Boolean {
            if (warningEmitted) return false
            warningEmitted = true
            return true
        }
    }
}
