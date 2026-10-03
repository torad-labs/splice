// NEW: parked workers have monotonic leases; nested callback and runtime borrowers prevent reclamation.
package splice.provider.codex.state

import splice.provider.codex.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.stream.CodeModeSourceEnds
import splice.upstream.codemode.CodeModeCell
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.minutes

private val CELL_IDLE_MS = 30.minutes.inWholeMilliseconds

/** Runs under the registry monitor. Persistence remains the registry's conversation-key transaction. */
internal class CodeModeCellRetention(
    private val config: CodeModeBridgeConfig,
    private val access: CodeModeRegistryAccess,
    private val records: List<CodeModeRecord>,
    private val cells: MutableMap<String, CodeModeCell>,
    private val admissions: MutableMap<String, Long>,
    private val closeSession: CodeModeSessionEnd,
) {
    /** Resume holds this lease through validation and result application; the runtime may borrow again. */
    fun acquire(record: CodeModeRecord): CodeModeCell? = access.withKey(record.key) {
        cells[record.id]?.takeIf { record.phase == CodeModePhase.ACTIVE }?.also {
            record.cellBorrowers++
            record.cellIdleSince = null
            alive(record)
        }
    }

    fun release(record: CodeModeRecord) = access.withKey(record.key) {
        if (record.cellBorrowers > 0) record.cellBorrowers--
        if (record.cellBorrowers != 0) return@withKey
        if (record.phase != CodeModePhase.ACTIVE) return@withKey
        cells[record.id]?.let {
            record.cellIdleSince = config.cellClock()
            alive(record)
        }
    }

    /** Source-only death cleanup remains valid. A request never ages out its own retained worker. */
    fun sweep(key: String?, protectedKey: String? = null): Boolean {
        val idle = ranked(key).filter { (priority, record) ->
            priority < 2 && (record.id !in cells || record.key != protectedKey)
        }
        idle.forEach { (priority, record) -> park(record, reasons[priority]) }
        return idle.isNotEmpty()
    }

    /** Dead first, then grace-expired unknown, then sufficiently idle alive workers. */
    fun candidateKeys(): List<String> = ranked(null).map { it.second.key }.distinct()

    fun evict(key: String): CodeModeRecord? {
        val (priority, victim) = ranked(key).firstOrNull() ?: return null
        park(victim, "${reasons[priority]} to admit a newer script")
        return victim
    }

    fun running(record: CodeModeRecord): Boolean =
        record.cellBorrowers > 0 || (record.id in cells && record.cellIdleSince == null)

    fun supersede(key: String, digest: String, continued: Set<String>): Boolean {
        val stale = records.filter {
            it.key == key && it.phase == CodeModePhase.ACTIVE && it.id in cells && !running(it) &&
                it.lastDigest != digest && it.id !in continued
        }
        stale.forEach { park(it, "code-mode parked program was superseded by the client's later history") }
        return stale.isNotEmpty()
    }

    fun logRefusal() = access.monitor.withLock {
        val retained = records.filter { it.phase == CodeModePhase.ACTIVE && it.id in cells }
        val liveness = retained.map(::alive)
        val oldest = retained.mapNotNull { it.cellIdleSince }.minOrNull()
        val age = oldest?.let { (config.cellClock() - it).coerceAtLeast(0) } ?: 0
        config.log(
            "[code-mode] pool refusal: retained=${retained.size} " +
                "dead=${liveness.count { it == false }} unknown=${liveness.count { it == null }} " +
                "alive=${liveness.count { it == true }} executing=${retained.count(::running)} " +
                "oldestIdleMs=$age idleBoundMs=$CELL_IDLE_MS",
        )
    }

    private fun ranked(key: String?): List<Pair<Int, CodeModeRecord>> = records.mapNotNull { record ->
        if (key == null || record.key == key) rank(record)?.let { it to record } else null
    }.sortedWith(compareBy({ it.first }, { it.second.cellIdleSince ?: Long.MIN_VALUE }))

    private fun rank(record: CodeModeRecord): Int? {
        if (record.phase != CodeModePhase.ACTIVE || running(record)) return null
        val liveness = alive(record)
        return when {
            liveness == false -> if (record.id in cells || record.sourceEnd != null) 0 else null
            record.id !in cells -> null
            else -> idleRank(record, liveness)
        }
    }

    private fun idleRank(record: CodeModeRecord, liveness: Boolean?): Int? {
        val idle = record.cellIdleSince ?: return null
        val since = if (liveness == null) maxOf(idle, record.cellLastAliveAt ?: idle) else idle
        return when {
            config.cellClock() - since < CELL_IDLE_MS -> null
            liveness == null -> 1
            else -> 2
        }
    }

    private fun alive(record: CodeModeRecord): Boolean? = record.sessionId?.let(config.sessionAlive::invoke).also {
        if (it == true) record.cellLastAliveAt = config.cellClock()
    }

    private val reasons = listOf(
        "code-mode cell closed after its session ended",
        "code-mode cell closed after unknown session exceeded its idle lifetime",
        "code-mode alive cell closed at capacity after its idle lifetime",
    )

    fun closeEmpty(key: String) {
        if (records.none { it.key == key }) closeSession(key)
    }

    /** A park is not a use: keep the record's last-use timestamp and all no-rerun callback evidence. */
    fun park(record: CodeModeRecord, message: String) {
        check(!running(record)) { "an executing code-mode cell cannot be reclaimed" }
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
