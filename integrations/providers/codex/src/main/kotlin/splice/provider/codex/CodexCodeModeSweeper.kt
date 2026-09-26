// NEW: closes parked cells — aged out, or evicted at capacity — so a client that never returned
// cannot hold a worker slot for the rest of the day.
//
// V4-287: records expire 24 hours (the config's ttl) after their last use, on a timer. The sweep ran
// only when the registry was built or a code-mode turn touched it, so a head that ran one script and
// went idle kept the record, with the model's reasoning summaries in plaintext, for as long as the
// daemon stayed up. Parking a cell is splice's own act, not a use, so it keeps the record's time.
package splice.provider.codex

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.upstream.codemode.CodeModeCell
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** V4-287: the timer every code-mode registry sweeps on, one named daemon thread for all heads. */
internal object CodeModeSweeps {
    // The platform factory, as AsyncFileIo's lane: this timer owns only its name and daemon-ness.
    private val threads = Executors.defaultThreadFactory()
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        threads.newThread(task).apply {
            name = "splice-code-mode-sweep"
            isDaemon = true
        }
    }.apply { removeOnCancelPolicy = true }

    /** Runs [sweep] every [interval] until the returned future is cancelled. [sweep] must not throw:
     *  an exception ends a periodic task in silence. */
    fun every(interval: Duration, sweep: Runnable): ScheduledFuture<*> {
        val ms = interval.inWholeMilliseconds
        return timer.scheduleWithFixedDelay(sweep, ms, ms, TimeUnit.MILLISECONDS)
    }
}

/** V4-287: one registry's sweep on [CodeModeSweeps]' timer, running while the registry keeps any
 *  record. It holds the registry's [monitor] for each sweep; [save] writes the registry's state. */
internal class CodeModeTimedSweep(
    private val monitor: Any,
    private val sweeper: CodexCodeModeSweeper,
    private val records: List<CodeModeRecord>,
    private val save: Runnable,
    private val config: CodeModeBridgeConfig,
    private val interval: Duration,
) {
    private var running: ScheduledFuture<*>? = null

    /** A sweep changed the records and could not save them yet; the next sweep saves again. */
    private var unsaved = false

    /** Under [monitor]: starts the sweeps when a record is kept and none run. */
    fun arm() {
        if (running == null && records.isNotEmpty()) running = CodeModeSweeps.every(interval) { sweep() }
    }

    /** One sweep: what the sweeper changed is saved, a save that fails is logged and made again at the
     *  next sweep, and the sweeps stop once no record is kept. Never throws: a throw would end the
     *  periodic task in silence. */
    private fun sweep() = synchronized(monitor) {
        Cancellables.runCatchingBestEffort {
            if (sweeper.sweep() or unsaved) {
                unsaved = true
                save.run()
                unsaved = false
            }
        }.exceptionOrNull()?.let { failure ->
            config.log(
                "[code-mode] the timed sweep could not save ${config.stateFile} " +
                    "(${SafeFailureText.render(failure)}); it saves again at the next sweep",
            )
        }
        if (records.isEmpty() && !unsaved) {
            running?.cancel(false)
            running = null
        }
    }
}

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
        park(victim, "code-mode cell evicted after $idle min without client results to admit a newer script")
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
            park(record, "code-mode cell closed after $minutes min without client results")
        }
        return idle.isNotEmpty()
    }

    /** Closes [record]'s cell and marks it lost. Its updatedAt stays the time of its last use, so its
     *  24 hours are not restarted by the park (V4-287: they were, keeping it past the promise). */
    private fun park(record: CodeModeRecord, message: String) {
        admissions.remove(record.id)
        cells.remove(record.id)?.close()
        record.phase = CodeModePhase.LOST
        record.error = "$message; source was not rerun"
        config.log("[code-mode] ${record.id.take(RECORD_ID_LOG_CHARS)} (outer ${record.outerCallId}): $message")
    }
}

private const val MILLIS_PER_MINUTE: Long = 60_000L
private const val RECORD_ID_LOG_CHARS: Int = 8
