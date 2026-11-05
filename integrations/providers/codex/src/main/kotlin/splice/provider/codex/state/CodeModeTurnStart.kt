// NEW: retention at a conversation's turn boundary releases its session engine when all records expire.
package splice.provider.codex.state

import splice.provider.codex.CodexCodeModeStore
import splice.provider.codex.state.retention.CodeModeRecordCache
import splice.provider.codex.state.retention.CodeModeSweepRecords
import java.time.Clock
internal fun interface CodeModeSessionEnd {
    operator fun invoke(key: String)
}

/** Only turn start trims canonical history; mid-turn replay cannot invalidate a running source. */
internal class CodeModeTurnStart(
    private val access: CodeModeRegistryAccess,
    private val state: CodeModeSweepRecords,
    private val store: CodexCodeModeStore,
    private val clock: Clock,
    private val cells: CodeModeCellRetention,
    private val cache: CodeModeRecordCache,
) {
    fun begin(key: String, digest: String? = null, continued: Set<String> = emptySet()) = access.withKey(key) {
        cache.restore(key)
        val reaped = digest?.let { cells.supersede(key, it, continued) } == true
        val changed = state.retention.beginTurn(state.records, state.history, key, clock.millis()) or reaped
        if (changed) cells.closeEmpty(key)
        store.save(state.records, state.history.entries, retryOnly = !changed, dirtyKeys = setOf(key))
    }
}
