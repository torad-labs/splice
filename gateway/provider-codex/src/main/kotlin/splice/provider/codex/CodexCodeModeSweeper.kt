// NEW: closes parked cells — aged out, or evicted at capacity — so a client that never returned
// cannot hold a worker slot for the rest of the day.
package splice.provider.codex

import splice.spi.CodeModeCell

/** Runs under the registry's monitor; it mutates the registry's own collections in place. */
internal class CodexCodeModeSweeper(
    private val config: CodeModeBridgeConfig,
    private val records: MutableList<CodeModeRecord>,
    private val cells: MutableMap<String, CodeModeCell>,
    private val admissions: MutableMap<String, Long>,
    private val history: CodeModeExpiredHistory,
) {
    /** Expires records past their retention and parks cells past the idle timeout; true when anything changed. */
    fun sweep(): Boolean = expireRecords() or reapIdleCells()

    /** At capacity: park the oldest cell idle at least [CodeModeBridgeConfig.cellEvictionFloor] so the
     *  newer script can take its slot. Null when every cell is presumed busy (mid-call, or a prompt). */
    fun evictIdleCell(): CodeModeRecord? {
        val now = config.clock.millis()
        val floor = config.cellEvictionFloor.inWholeMilliseconds
        val victim = records
            .filter { it.phase == CodeModePhase.ACTIVE && now - it.updatedAt >= floor }
            .minByOrNull(CodeModeRecord::updatedAt)
            ?: return null
        val idle = (now - victim.updatedAt) / MILLIS_PER_MINUTE
        park(victim, now, "code-mode cell evicted after $idle min without client results to admit a newer script")
        return victim
    }

    private fun expireRecords(): Boolean {
        val cutoff = config.clock.millis() - config.ttl.inWholeMilliseconds
        val stale = records.filter { it.updatedAt < cutoff }
        if (stale.isEmpty()) return false
        stale.forEach { record ->
            admissions.remove(record.id)
            cells.remove(record.id)?.close()
            history.remember(record, config.clock.millis())
        }
        records.removeAll(stale.toSet())
        return true
    }

    /** A cell parked past [CodeModeBridgeConfig.cellIdleTimeout] is closed: its slot is the scarce resource. */
    private fun reapIdleCells(): Boolean {
        val now = config.clock.millis()
        val limit = config.cellIdleTimeout.inWholeMilliseconds
        val idle = records.filter { it.phase == CodeModePhase.ACTIVE && now - it.updatedAt >= limit }
        idle.forEach { record ->
            val minutes = (now - record.updatedAt) / MILLIS_PER_MINUTE
            park(record, now, "code-mode cell closed after $minutes min without client results")
        }
        return idle.isNotEmpty()
    }

    private fun park(record: CodeModeRecord, now: Long, message: String) {
        admissions.remove(record.id)
        cells.remove(record.id)?.close()
        record.phase = CodeModePhase.LOST
        record.error = "$message; source was not rerun"
        record.updatedAt = now
        config.log("[code-mode] ${record.id.take(RECORD_ID_LOG_CHARS)} (outer ${record.outerCallId}): $message")
    }
}

private const val MILLIS_PER_MINUTE: Long = 60_000L
private const val RECORD_ID_LOG_CHARS: Int = 8
