// NEW: parked workers have monotonic leases; nested callback and runtime borrowers prevent reclamation.
package splice.provider.codex.state

import splice.provider.codex.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.state.collection.CodeModeRecordKeys
import splice.provider.codex.stream.CodeModeSourceEnds
import splice.upstream.codemode.CodeModeCell
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.minutes

// why: a parked cell holds its engine, and one held engine keeps its whole host JVM (400-600 MB resident). A cell of a
// session the registry does not vouch for (a subagent or a headless client never registers, and an ended session's
// file is gone, so both read as unknown) has not been resumed for this long when its client is almost surely gone.
// Codex frees a cell when its owner ends; splice cannot see these owners end, so this bound stands in for it. At 30
// minutes, 14 hosts held 5.5 GB on Oct 15 with almost nothing executing, and the pool's own idle exit never ran
// because no host ever emptied. Pressure and the timed sweep share the bound; a session the registry vouches for is
// never reclaimed, and an executing or borrowed cell never is.
private val CELL_IDLE_MS = 5.minutes.inWholeMilliseconds

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
        idle.forEach { (priority, record) -> park(record, reasons[priority], reclaim = true) }
        return idle.isNotEmpty()
    }

    /** Pressure cannot revoke a live continuation, including one waiting for client tool results. It reclaims a cell
     *  its session no longer vouches for once it has sat idle for [CELL_IDLE_MS], oldest first. */
    fun capacityCandidates(): List<CodeModeRecord> = ranked(null, CELL_IDLE_MS).filter { (priority, record) ->
        priority < 2 && record.id in cells
    }.map { it.second }

    fun evict(key: String): CodeModeRecord? {
        val victim = ranked(key, CELL_IDLE_MS).firstOrNull { (priority, record) ->
            priority < 2 && record.id in cells
        }?.second ?: return null
        val priority = rank(victim, CELL_IDLE_MS)?.takeIf { it < 2 } ?: return null
        val reason = pressureReasons[priority]
        park(victim, "$reason to admit a newer script", reclaim = true)
        return victim
    }

    fun running(
        record: CodeModeRecord,
        protection: CodeModeCellProtection = CodeModeCellProtection.EXECUTION,
    ): Boolean {
        val foreground = record.cellBorrowers > 0 || (record.id in cells && record.cellIdleSince == null)
        return when (protection) {
            CodeModeCellProtection.EXECUTION -> foreground
            CodeModeCellProtection.SESSION_OWNED ->
                foreground ||
                    CodeModeNativeCellLifetime.retained(record, cells[record.id], config.cellLease.sessionAlive)
        }
    }

    fun supersede(key: String, digest: String, continued: Set<String>): Boolean {
        val stale = CodeModeRecordKeys.of(records, key).filter {
            it.phase == CodeModePhase.ACTIVE && it.id in cells && !running(it) &&
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
                "alive=${liveness.count { it == true }} executing=${retained.count { running(it) }} " +
                "oldestIdleMs=$age idleBoundMs=$CELL_IDLE_MS",
        )
    }

    private fun ranked(key: String?, bound: Long): List<Pair<Int, CodeModeRecord>> =
        (if (key == null) records else CodeModeRecordKeys.of(records, key)).mapNotNull { record ->
            rank(record, bound)?.let { it to record }
        }.sortedWith(compareBy({ it.first }, { it.second.cellIdleSince ?: Long.MIN_VALUE }))

    private fun rank(record: CodeModeRecord, bound: Long): Int? {
        if (record.phase != CodeModePhase.ACTIVE || running(record)) return null
        val liveness = alive(record)
        if (CodeModeNativeCellLifetime.retained(record, cells[record.id], config.cellLease.sessionAlive)) return null
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

    // why: a live seat idle at its prompt is not running the calls its parked cell waits on (an interrupted turn
    // leaves the cell parked), so its cell takes the unknown lease from the last moment the seat was seen at work;
    // only cells read it, and a busy or waiting seat keeps its cell however long it is quiet.
    private fun alive(record: CodeModeRecord): Boolean? = record.sessionId
        ?.let { id ->
            config.cellLease.sessionAlive(id)?.takeUnless { live -> live && config.cellLease.sessionIdle(id) }
        }
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
        if (CodeModeRecordKeys.of(records, key).isEmpty()) closeSession(key)
    }

    /** A park is not a use: keep the record's last-use timestamp and all no-rerun callback evidence. A [reclaim] takes
     *  back a cell its session no longer vouches for. It is no client's cut, so a round still streaming toward its
     *  posting step's row is let go to its terminal, which bills that row the round's usage, and closing its cell then
     *  frees only the engine. Ending its lease, or closing its cell, cancelled the round, and the row was released with
     *  a cut and none of it (rounds 14 and 15 of #247). A newer program or a
     *  superseding history is the client's own cut, which the lease's end gives to the step that made it. */
    fun park(record: CodeModeRecord, message: String, reclaim: Boolean = false) {
        check(!running(record)) { "an executing code-mode cell cannot be reclaimed" }
        admissions.remove(record.id)
        record.phase = CodeModePhase.LOST
        record.cellStartedAt = null
        CodeModeRecordErrors.replace(record, "$message; source was not rerun")
        record.sourceEnd?.let { lease ->
            if (reclaim && lease.owed) lease.release() else CodeModeSourceEnds.defer(lease)
        }
        record.sourceEnd = null
        cells.remove(record.id)?.close()
        record.cellIdleSince = null
        val live = CodeModeRecordKeys.of(records, record.key).any { it.phase == CodeModePhase.ACTIVE }
        if (!live) closeSession(record.key)
        config.log(
            "[code-mode] ${record.id.take(CODE_MODE_RECORD_LOG_CHARS)} (outer ${record.origin.outerCallId}): $message",
        )
    }
}

/** Native continuation ownership is independent of execution-history age and unknown-session idle leases. */
internal object CodeModeNativeCellLifetime {
    fun retained(
        record: CodeModeRecord,
        cell: CodeModeCell?,
        alive: splice.provider.codex.state.lease.CodeModeSessionAlive,
    ): Boolean {
        val native = cell is splice.provider.codex.stream.CodeModeNativeObservation ||
            (cell as? splice.provider.codex.stream.CodeModeStreamingCell)?.nativeObservation != null
        val positiveDeath = record.sessionId?.let(alive::invoke) == false
        return native && !positiveDeath
    }
}

internal enum class CodeModeCellProtection { EXECUTION, SESSION_OWNED }
