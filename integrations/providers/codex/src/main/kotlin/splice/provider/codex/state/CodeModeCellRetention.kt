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

// why: a full pool is the one moment an idle parked cell costs somebody a script. A cell of a session the registry
// does not vouch for (a subagent or a headless client never registers, and an ended session's file is gone, so both
// read as unknown) has not been resumed for this long when its client is almost surely gone; waiting the whole
// lifetime above left 50 of 53 engines parked and idle on Oct 15 while every new exec was refused (executing=1 of
// retained=53). Only pressure uses this bound; the timed sweep keeps the lifetime, and a session the registry
// vouches for is never reclaimed.
private val PRESSURE_IDLE_MS = 5.minutes.inWholeMilliseconds

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
            record.cellIdleSince = config.cellLease.clock()
            alive(record)
        }
    }

    /** Source-only death cleanup remains valid. A request never ages out its own retained worker. */
    fun sweep(key: String?, protectedKey: String? = null): Boolean {
        val idle = ranked(key, CELL_IDLE_MS).filter { (priority, record) ->
            priority < 2 && (record.id !in cells || record.key != protectedKey)
        }
        idle.forEach { (priority, record) -> park(record, reasons[priority]) }
        return idle.isNotEmpty()
    }

    /** Pressure cannot revoke a live continuation, including one waiting for client tool results. It reclaims a cell
     *  its session no longer vouches for once it has sat idle for [PRESSURE_IDLE_MS], oldest first. */
    fun capacityCandidates(): List<CodeModeRecord> = ranked(null, PRESSURE_IDLE_MS).filter { (priority, record) ->
        priority < 2 && record.id in cells
    }.map { it.second }

    fun evict(key: String): CodeModeRecord? {
        val victim = capacityCandidates().firstOrNull { it.key == key } ?: return null
        val priority = rank(victim, PRESSURE_IDLE_MS)?.takeIf { it < 2 } ?: return null
        val reason = pressureReasons[priority]
        park(victim, "$reason to admit a newer script")
        return victim
    }

    fun running(record: CodeModeRecord): Boolean =
        record.cellBorrowers > 0 || (record.id in cells && record.cellIdleSince == null)

    fun supersede(key: String, digest: String, continued: Set<String>): Boolean {
        val stale = records.filter {
            it.key == key && it.phase == CodeModePhase.ACTIVE && it.id in cells && !running(it) &&
                it.progress.lastDigest != digest && it.id !in continued
        }
        stale.forEach {
            it.sourceEnd?.claimClient()
            park(it, "code-mode parked program was superseded by the client's later history")
        }
        return stale.isNotEmpty()
    }

    fun logRefusal() = access.monitor.withLock {
        val retained = records.filter { it.phase == CodeModePhase.ACTIVE && it.id in cells }
        val liveness = retained.map(::alive)
        val oldest = retained.mapNotNull { it.cellIdleSince }.minOrNull()
        val age = oldest?.let { (config.cellLease.clock() - it).coerceAtLeast(0) } ?: 0
        config.log(
            "[code-mode] pool refusal: retained=${retained.size} " +
                "dead=${liveness.count { it == false }} unknown=${liveness.count { it == null }} " +
                "alive=${liveness.count { it == true }} executing=${retained.count(::running)} " +
                "oldestIdleMs=$age idleBoundMs=$CELL_IDLE_MS pressureBoundMs=$PRESSURE_IDLE_MS",
        )
    }

    private fun ranked(key: String?, bound: Long): List<Pair<Int, CodeModeRecord>> = records.mapNotNull { record ->
        if (key == null || record.key == key) rank(record, bound)?.let { it to record } else null
    }.sortedWith(compareBy({ it.first }, { it.second.cellIdleSince ?: Long.MIN_VALUE }))

    private fun rank(record: CodeModeRecord, bound: Long): Int? {
        if (record.phase != CodeModePhase.ACTIVE || running(record)) return null
        val liveness = alive(record)
        return when {
            liveness == false -> if (record.id in cells || record.sourceEnd != null) 0 else null
            record.id !in cells -> null
            else -> idleRank(record, liveness, bound)
        }
    }

    private fun idleRank(record: CodeModeRecord, liveness: Boolean?, bound: Long): Int? {
        val idle = record.cellIdleSince ?: return null
        val since = if (liveness == null) maxOf(idle, record.cellLastAliveAt ?: idle) else idle
        return when {
            config.cellLease.clock() - since < bound -> null
            liveness == null -> 1
            else -> 2
        }
    }

    private fun alive(record: CodeModeRecord): Boolean? = record.sessionId
        ?.let(config.cellLease.sessionAlive::invoke)
        .also { if (it == true) record.cellLastAliveAt = config.cellLease.clock() }

    private val reasons = listOf(
        "code-mode cell closed after its session ended",
        "code-mode cell closed after unknown session exceeded its idle lifetime",
    )

    private val pressureReasons = listOf(
        reasons[0],
        "code-mode cell closed after unknown session sat idle past the pool-pressure bound",
    )

    fun closeEmpty(key: String) {
        if (records.none { it.key == key }) closeSession(key)
    }

    /** A park is not a use: keep the record's last-use timestamp and all no-rerun callback evidence. */
    fun park(record: CodeModeRecord, message: String) {
        check(!running(record)) { "an executing code-mode cell cannot be reclaimed" }
        admissions.remove(record.id)
        record.phase = CodeModePhase.LOST
        CodeModeRecordErrors.replace(record, "$message; source was not rerun")
        CodeModeSourceEnds.defer(record.sourceEnd)
        record.sourceEnd = null
        cells.remove(record.id)?.close()
        record.cellIdleSince = null
        if (records.none { it.key == record.key && it.phase == CodeModePhase.ACTIVE }) closeSession(record.key)
        config.log(
            "[code-mode] ${record.id.take(CODE_MODE_RECORD_LOG_CHARS)} (outer ${record.origin.outerCallId}): $message",
        )
    }
}
