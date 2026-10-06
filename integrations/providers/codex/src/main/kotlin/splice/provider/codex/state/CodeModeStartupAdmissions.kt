// NEW: only proven unexecuted source is retryable; every dispatch is durably no-rerun first.
package splice.provider.codex.state

import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeStore
import splice.upstream.codemode.CodeModeCell
import java.time.Clock

/** Owns generation tickets and retry-proof transitions through the existing exact-key changed-cell journal. */
internal class CodeModeStartupAdmissions(
    private val access: CodeModeRegistryAccess,
    private val records: List<CodeModeRecord>,
    private val history: CodeModeExpiredHistory,
    private val store: CodexCodeModeStore,
    private val clock: Clock,
    private val cells: MutableMap<String, CodeModeCell>,
) {
    val entries = mutableMapOf<String, Long>()
    var generation = 0L
        private set

    fun stop() {
        generation++
    }

    /** A first admission is already LOST. Only a proven failed boot can be admitted again. */
    fun resume(record: CodeModeRecord): Boolean? = access.withKey(record.key) {
        when {
            record !in records -> null
            record.phase != CodeModePhase.STARTING -> false
            record.error != null -> false
            else -> dispatch(record)
        }
    }

    private fun dispatch(record: CodeModeRecord): Boolean {
        val admittedGeneration = generation
        record.phase = CodeModePhase.LOST
        val snapshotGeneration = record.saveGeneration + 1
        try {
            store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
        } catch (error: CodeModePersistenceException) {
            // No dispatch occurred. Preserve the earlier proof unless a newer transition owns it.
            if (record.saveGeneration <= snapshotGeneration) record.phase = CodeModePhase.STARTING
            throw error
        }
        entries[record.id] = admittedGeneration
        return true
    }

    /** A worker attaches only to the generation that admitted its source. */
    fun attach(record: CodeModeRecord, cell: CodeModeCell): Boolean = access.withKey(record.key) {
        val admittedGeneration = entries.remove(record.id)
        val rejected = admittedGeneration != generation || record !in records || record.error != null
        if (rejected) {
            cell.close()
            if (record in records) {
                record.phase = CodeModePhase.LOST
                CodeModeRecordErrors.replace(
                    record,
                    record.error ?: "code-mode runtime stopped during startup; source was not rerun",
                )
                record.updatedAt = clock.millis()
                store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
            }
            false
        } else {
            cells[record.id] = cell
            record.phase = CodeModePhase.ACTIVE
            true
        }
    }

    /** The typed runtime failure proved source was never sent, including across a head stop. */
    fun failed(record: CodeModeRecord) = access.withKey(record.key) {
        entries.remove(record.id)
        record.phase = CodeModePhase.STARTING
        record.error = null
        record.updatedAt = clock.millis()
        store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
    }
}
