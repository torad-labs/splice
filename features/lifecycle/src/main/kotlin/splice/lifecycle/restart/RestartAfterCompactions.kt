// NEW: V4-220 item 6 (2026-09-25) — a restart the daemon takes on itself waits for a compaction in
// flight, as `splice restart` does from the CLI (V4-216, CompactionWait).
//
// POST /api/daemon/restart went straight into the 45 s drain, so the console's restart button cut the
// one turn a restart must not cut: a compaction runs for minutes, Claude Code gives it 600 s and then
// retries the same bytes, and the daemon keeps the answer for that retry only once it has finished.
// This is the SAME wait the CLI runs — CompactionWait, over the daemon's own gates instead of
// /api/heads — held in ONE place so every restart the daemon itself takes on (the console's button,
// an add's save) goes through it and none goes around it. The daemon keeps serving while it waits, no
// head closes admission, ordinary turns never hold it, and `now` skips it.
package splice.lifecycle.restart

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import splice.core.terminal.TerminalOutput
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.lifecycle.upgrade.CompactionSlot
import splice.lifecycle.upgrade.CompactionWait
import splice.lifecycle.upgrade.INFLIGHT_POLL_MS
import splice.lifecycle.upgrade.InflightRead
import splice.lifecycle.upgrade.UpgradeInflight

/** Every compaction the daemon's heads hold a gate slot for right now (V4-213's live rows). */
public fun interface CompactionsInFlight {
    public operator fun invoke(): List<CompactionSlot>
}

/** Where a restart the daemon took on stands. */
public sealed class RestartPhase {
    /** No restart was asked for. */
    public data object Idle : RestartPhase()

    /** Taken on, and waiting for [compactions] to finish before the drain. */
    public data class Waiting(val compactions: List<CompactionSlot>) : RestartPhase()

    /** The drain was requested: the daemon is stopping, and its supervisor brings it back. */
    public data object Draining : RestartPhase()
}

public class RestartAfterCompactions(
    compactions: CompactionsInFlight,
    private val scope: CoroutineScope,
    log: LogSink,
    pollMs: Long = INFLIGHT_POLL_MS,
) {
    private val wait = CompactionWait(
        output = TerminalOutput { line -> log("[control] restart: ${LogSafe.str(line.removePrefix(CLI_PREFIX))}\n") },
        inflight = UpgradeInflight { InflightRead.Count(0, compactions()) },
        pollMs = pollMs,
    )
    private val lock = Any()
    private var pending: Job? = null
    private var drained = false

    /** Takes a restart on. [RestartPhase.Draining] means the CALLER requests the drain, once its own
     *  answer is written (DaemonRoutes' order: a daemon tearing itself down must not race its reply
     *  out of the socket it closes). [RestartPhase.Waiting] means [shutdown] runs here, after the wait.
     *  A second request while one waits changes nothing, unless it is [now]: that ends the wait. */
    public fun request(now: Boolean, shutdown: ShutdownDaemon): RestartPhase = synchronized(lock) {
        val waiting = pending
        if (waiting != null && !now) return RestartPhase.Waiting(wait.waiting())
        waiting?.cancel()
        pending = null
        val compactions = if (now) emptyList() else wait.waiting()
        if (compactions.isEmpty()) {
            drained = true
            return RestartPhase.Draining
        }
        pending = scope.launch { drainAfterWait(shutdown) }
        RestartPhase.Waiting(compactions)
    }

    public fun phase(): RestartPhase = synchronized(lock) {
        when {
            pending != null -> RestartPhase.Waiting(wait.waiting())
            drained -> RestartPhase.Draining
            else -> RestartPhase.Idle
        }
    }

    // A cancelled wait (a `now` that drained already) ends in the interrupt and never drains again;
    // the check under the lock covers a `now` that lands between the wait's end and this drain.
    private suspend fun drainAfterWait(shutdown: ShutdownDaemon) {
        runInterruptible { wait.await() }
        val mine = currentCoroutineContext().job
        val still = synchronized(lock) {
            (pending === mine).also { current ->
                if (current) {
                    pending = null
                    drained = true
                }
            }
        }
        if (still) shutdown()
    }
}

private const val CLI_PREFIX = "splice: "
