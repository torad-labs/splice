// NEW: retention at a conversation's turn boundary releases its session engine when all records expire.
package splice.provider.codex.state

import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordRetention
import splice.provider.codex.CodexCodeModeStore
import java.time.Clock

internal fun interface CodeModeSessionEnd {
    operator fun invoke(key: String)
}

/** Only turn start trims canonical history; mid-turn replay cannot invalidate a running source. */
internal class CodeModeTurnStart(
    private val access: CodeModeRegistryAccess,
    private val retention: CodeModeRecordRetention,
    private val records: MutableList<CodeModeRecord>,
    private val history: CodeModeExpiredHistory,
    private val store: CodexCodeModeStore,
    private val clock: Clock,
    private val cells: CodeModeCellRetention,
) {
    fun begin(key: String, digest: String? = null, continued: Set<String> = emptySet()) = access.withKey(key) {
        val reaped = digest?.let { cells.supersede(key, it, continued) } == true
        val changed = retention.beginTurn(records, history, key, clock.millis()) or reaped
        if (changed) cells.closeEmpty(key)
        store.save(records, history.entries, retryOnly = !changed, dirtyKeys = setOf(key))
    }
}
