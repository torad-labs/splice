package splice.provider.codex.availability

import splice.core.util.LruSizing
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.state.CodeModeTurnIdentity

// Live services per head are dozens. Retire idle owners rather than keeping an unbounded warning-only registry.
private const val LIVE_NOTICE_OWNERS: Int = 1024

/** Source warning flags belong to live services, never a journal or a restarted provider. */
internal class CodeModeAvailabilityOwners(private val config: CodeModeBridgeConfig) {
    private val identity = CodeModeTurnIdentity()
    private val owners = LinkedHashMap<String, Long>(LruSizing.INITIAL_CAPACITY, LruSizing.LOAD_FACTOR, true)

    @Synchronized
    fun claim(turn: CodexCodeModeBridge.Turn): Boolean {
        val now = config.clock.millis()
        owners.entries.removeIf { now - it.value >= config.retention.ttl.inWholeMilliseconds }
        val key = identity.conversationId(turn)
        val previous = owners.put(key, now)
        while (owners.size > LIVE_NOTICE_OWNERS) owners.remove(owners.keys.first())
        return previous == null
    }

    @Synchronized
    fun clear() = owners.clear()
}
