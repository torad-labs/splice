package splice.provider.codex.state.retention

import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.CodeModeCellProtection
import splice.provider.codex.state.CodeModeCellRetention
import splice.provider.codex.state.CodeModeExpiredHistory
import splice.provider.codex.state.CodeModeSessionEnd
import splice.provider.codex.state.collection.CodeModeRecordKeys
import splice.upstream.codemode.CodeModeCell

/** Execution snapshots may expire; session-owned native cells remain protected by explicit client liveness. */
internal class CodeModeRecordExpiry(
    private val config: CodeModeBridgeConfig,
    private val records: MutableList<CodeModeRecord>,
    private val cells: MutableMap<String, CodeModeCell>,
    private val admissions: MutableMap<String, Long>,
    private val history: CodeModeExpiredHistory,
    private val closeSession: CodeModeSessionEnd,
    private val retainedCells: CodeModeCellRetention,
) {
    fun expire(key: String?): Boolean {
        val cutoff = config.clock.millis() - config.retention.ttl.inWholeMilliseconds
        // A query prepares its own conversation; do not group and inspect every other session
        // while holding the shared registry lock. The timer supplies each other key separately.
        val candidates = if (key == null) records else CodeModeRecordKeys.of(records, key)
        val lastUse = candidates.groupBy(CodeModeRecord::key)
            .mapValues { (_, kept) -> kept.maxOf { it.progress.updatedAt } }
        val protected = candidates.filter {
            retainedCells.running(it, splice.provider.codex.state.CodeModeCellProtection.SESSION_OWNED)
        }.map(CodeModeRecord::key).toSet()
        val stale = candidates.filter {
            it.key !in protected && lastUse.getValue(it.key) < cutoff
        }
        if (stale.isEmpty()) return false
        stale.forEach { record ->
            admissions.remove(record.id)
            record.endSource()
            cells.remove(record.id)?.close()
            history.remember(record, config.clock.millis())
        }
        records.removeAll(stale.toSet())
        stale.map(CodeModeRecord::key).distinct().forEach(closeSession::invoke)
        return true
    }
}
