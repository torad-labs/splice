package splice.provider.codex.state.retention

import splice.core.memory.HeapReservations
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.state.CodeModeHeap
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeRegistryAccess
import splice.provider.codex.state.collection.CodeModeRecordKeys
import kotlin.concurrent.withLock

/** The byte limit bounds hot records, not replay lifetime. Only a committed, unused conversation can
 *  leave memory. Turns hold references across suspensions; process registration is irrelevant here. */
internal class CodeModeRecordCache(
    private val config: CodeModeBridgeConfig,
    private val access: CodeModeRegistryAccess,
    private val state: CodeModeSweepRecords,
    private val store: CodexCodeModeStore,
    private val heap: HeapReservations,
) {
    private val users = mutableMapOf<String, Int>()

    fun acquire(key: String) = access.withKey(key) {
        restore(key)
        users[key] = users.getOrDefault(key, 0) + 1
    }

    fun release(key: String) {
        access.withKey(key) {
            val remaining = checkNotNull(users[key]) - 1
            if (remaining == 0) users.remove(key) else users[key] = remaining
        }
        trim()
    }

    /** Caller owns this conversation's lock and the registry monitor. */
    fun restore(key: String) {
        val loaded = store.cold.restore(key) ?: return
        val records = loaded.records.map { it.restore().also { record -> CodeModeHeap.own(record, heap) } }
        CodeModeNativeChain.link(records)
        store.cold.restored(key, loaded)
        state.records.addAll(records)
    }

    /** A model switch can refer to a completed native cell recorded under an earlier key. */
    fun restoreConversation(conversationId: String?) {
        if (conversationId == null) return
        store.cold.forConversation(conversationId).forEach { key -> access.withKey(key) { restore(key) } }
    }

    /** Never wait for another conversation's lock or fail a completed turn for cache housekeeping. */
    fun trim() {
        Cancellables.runCatchingBestEffort { trimIdle() }.exceptionOrNull()?.let { failure ->
            config.log("[code-mode] record cache remains resident (${SafeFailureText.render(failure)}); retrying later")
        }
    }

    private fun trimIdle() {
        val candidates = access.monitor.withLock {
            if (bytes() <= config.retention.cacheBytes) return
            state.records.groupBy(CodeModeRecord::key).filter { (key, records) -> idle(key, records) }
                .entries.sortedBy { (_, records) -> records.maxOf { it.progress.updatedAt } }.map { it.key }
        }
        candidates.forEach { key ->
            access.tryKey(key) {
                val own = CodeModeRecordKeys.of(state.records, key)
                if (bytes() <= config.retention.cacheBytes || !idle(key, own)) return@tryKey
                if (store.cool(key, state.records, state.history.entries)) {
                    state.records.removeAll(own.toSet())
                    config.log("[code-mode] conversation ${key.take(CACHE_KEY_CHARS)}: records moved to durable cache")
                }
            }
        }
    }

    private fun idle(key: String, records: List<CodeModeRecord>): Boolean =
        key !in users && records.isNotEmpty() && records.all { record ->
            record.phase == CodeModePhase.COMPLETED && record.sourceEnd?.reading != true &&
                record.sourceState?.let { it.usage != null && !it.consumed } != true
        }

    private fun bytes(): Long = state.records.sumOf { it.retainedBytes ?: 0L }
}

private const val CACHE_KEY_CHARS = 8
