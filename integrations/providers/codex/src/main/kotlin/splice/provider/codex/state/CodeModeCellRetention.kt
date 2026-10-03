// NEW: unknown-session parked cells have a bounded idle lease; executing and positively alive cells do not.
package splice.provider.codex.state

import splice.provider.codex.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.stream.CodeModeSourceEnds
import splice.upstream.codemode.CodeModeCell
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.minutes

// An absent session registration is not death, but cannot reserve a parked worker forever.
private val UNKNOWN_CELL_IDLE_MS = 30.minutes.inWholeMilliseconds

/** Runs under the registry monitor. Persistence remains the registry's conversation-key transaction. */
internal class CodeModeCellRetention(
    private val config: CodeModeBridgeConfig,
    private val access: CodeModeRegistryAccess,
    private val records: List<CodeModeRecord>,
    private val cells: MutableMap<String, CodeModeCell>,
    private val admissions: MutableMap<String, Long>,
    private val closeSession: CodeModeSessionEnd,
) {
    /** Borrowing and idle eligibility change atomically before the driver advances. */
    fun acquire(record: CodeModeRecord): CodeModeCell? = access.withKey(record.key) {
        cells[record.id]?.also { record.cellIdleSince = null }
    }

    fun release(record: CodeModeRecord) = access.withKey(record.key) {
        if (record.phase == CodeModePhase.ACTIVE && record.id in cells) record.cellIdleSince = config.clock.millis()
    }

    fun sweep(key: String?): Boolean {
        val idle = records.filter { (key == null || it.key == key) && eligible(it) }
        idle.forEach { park(it, reason(it)) }
        return idle.isNotEmpty()
    }

    /** Globally oldest eligible cell first, even when its conversation was inserted later. */
    fun candidateKeys(): List<String> = records.filter(::eligible)
        .sortedBy { it.cellIdleSince }.map(CodeModeRecord::key).distinct()

    /** Rechecks eligibility under the key: a resumed cell or new positive evidence cancels eviction. */
    fun evict(key: String): CodeModeRecord? {
        val victim = records.filter { it.key == key && eligible(it) }
            .minByOrNull { checkNotNull(it.cellIdleSince) } ?: return null
        park(victim, "${reason(victim)} to admit a newer script")
        return victim
    }

    fun logRefusal() = access.monitor.withLock {
        val retained = records.filter { it.phase == CodeModePhase.ACTIVE && it.id in cells }
        val liveness = retained.map(::alive)
        val oldest = retained.mapNotNull { it.cellIdleSince }.minOrNull()
        val age = oldest?.let { (config.clock.millis() - it).coerceAtLeast(0) } ?: 0
        config.log(
            "[code-mode] pool refusal: retained=${retained.size} " +
                "dead=${liveness.count { it == false }} unknown=${liveness.count { it == null }} " +
                "alive=${liveness.count { it == true }} executing=${retained.count { it.cellIdleSince == null }} " +
                "oldestIdleMs=$age idleBoundMs=$UNKNOWN_CELL_IDLE_MS",
        )
    }

    private fun eligible(record: CodeModeRecord): Boolean {
        if (record.phase != CodeModePhase.ACTIVE || record.id !in cells) return false
        val since = record.cellIdleSince ?: return false
        return when (alive(record)) {
            true -> false
            false -> true
            null -> config.clock.millis() - since >= UNKNOWN_CELL_IDLE_MS
        }
    }

    private fun alive(record: CodeModeRecord): Boolean? = record.sessionId?.let(config.sessionAlive::invoke)

    private fun reason(record: CodeModeRecord): String = if (alive(record) == false) {
        "code-mode cell closed after its session ended"
    } else {
        "code-mode cell closed after unknown session exceeded its idle lifetime"
    }

    /** A park is not a use: keep the record's last-use timestamp and all no-rerun callback evidence. */
    private fun park(record: CodeModeRecord, message: String) {
        admissions.remove(record.id)
        CodeModeSourceEnds.defer(record.sourceEnd)
        record.sourceEnd = null
        cells.remove(record.id)?.close()
        record.cellIdleSince = null
        record.phase = CodeModePhase.LOST
        record.error = "$message; source was not rerun"
        if (records.none { it.key == record.key && it.phase == CodeModePhase.ACTIVE }) closeSession(record.key)
        config.log("[code-mode] ${record.id.take(CODE_MODE_RECORD_LOG_CHARS)} (outer ${record.outerCallId}): $message")
    }
}
