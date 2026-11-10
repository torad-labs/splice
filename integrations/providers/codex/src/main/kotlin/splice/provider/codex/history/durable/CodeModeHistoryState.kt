// Moved out of CodeModeHistoryStorage.kt on Nov 10, 2026 (concentration): the state a conversation's history holds,
// and what its journal held when the storage last read or wrote it, which the storage and its migration both build.
package splice.provider.codex.history.durable

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import splice.provider.codex.history.CodeModeLedgerEntry

@Serializable
internal data class CodeModeHistoryState(
    val key: String,
    val posts: List<CodeModeLedgerEntry>,
    val nextId: Long,
    val version: Int = 1,
    val revision: Long = 0,
    @Transient val memoryBytes: Long = 0,
)

/** What one conversation's journal held when [CodeModeHistoryStorage] last read or wrote it. Replaced whole, never
 *  edited, so a writer that took it works from one consistent view even when [CodeModeHistoryStorage.forget] drops it
 *  meanwhile. */
internal data class CodeModeJournaled(val revision: Long, val bytes: Long, val digest: String?)
