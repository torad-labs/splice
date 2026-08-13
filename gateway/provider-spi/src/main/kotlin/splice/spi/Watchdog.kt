// PORT-OF: server/src/codex/stream.mjs idle-watchdog block @ pre-public-port-baseline — invariants (the v35
// headline fix IS the spec): BEFORE the first byte the idle limit is firstByteTimeout — a
// big-context prefill (compaction re-reading ~160k tokens) is legitimately silent for
// minutes; reaping prefill on streamIdle caused the abort->retry->cold-re-read loop
// ("compaction ate my quota"). AFTER first byte the limit is streamIdle. totalCap bounds the
// whole turn (an overloaded backend can trickle keepalives forever and leak the slot — the
// "55 inflight, 2 agents" class). Poll interval = min(15s, max(250ms, streamIdle/3)).
// The fired reason is a TYPED SENTINEL set BEFORE cancelling, so catch sites can tell
// watchdog-fired from client-gone from shutdown.
package splice.spi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splice.core.turn.WatchdogBudget
import splice.core.util.MonoClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

public sealed class WatchdogFired {
    public data class Idle(val idleMs: Long, val sawFirstByte: Boolean) : WatchdogFired()

    public data class TotalCap(val elapsedMs: Long) : WatchdogFired()
}

public class TurnWatchdog(
    private val budget: WatchdogBudget,
    // Default is monotonic — sleep/wake/NTP must not invent stalls or freeze totalCap.
    private val clock: () -> Long = MonoClock::nowMs,
) {
    private val sawFirstByte = AtomicBoolean(false)
    private val firedRef = AtomicReference<WatchdogFired?>(null)
    private val startedAt = clock()

    public val fired: WatchdogFired? get() = firedRef.get()

    /** Reader-side touch: the first byte flips the idle tier from firstByteTimeout to streamIdle. */
    public fun markByte() {
        sawFirstByte.set(true)
    }

    /** Reasoning-continuation fold: each round is a fresh upstream POST whose prefill can be silent
     *  for minutes. The watchdog is shared across rounds (totalCap must span the whole turn), but the
     *  idle TIER must reset per round — otherwise round 1's first byte pins every later round to the
     *  short streamIdle cap instead of firstByteTimeout, wrongly aborting a slow continuation prefill.
     *  Only the first-byte tier resets; startedAt/totalCap are untouched. */
    public fun resetFirstByte() {
        sawFirstByte.set(false)
    }

    public fun pollInterval(): Duration {
        val third = budget.streamIdle.inWholeMilliseconds / IDLE_DIVISOR
        return third.coerceIn(MIN_POLL_MS, MAX_POLL_MS).milliseconds
    }

    /**
     * Launch the sibling poller: watches [slot] idleness + total elapsed, and on breach sets
     * the typed sentinel FIRST, then cancels [target]. Cancel the returned job on clean exit.
     */
    public fun launchIn(scope: CoroutineScope, slot: InflightGate.Slot, target: Job): Job =
        scope.launch {
            while (isActive) {
                delay(pollInterval())
                val idle = slot.idleForMs()
                val first = sawFirstByte.get()
                val idleLimit = if (first) {
                    budget.streamIdle.inWholeMilliseconds
                } else {
                    budget.firstByteTimeout.inWholeMilliseconds
                }
                val elapsed = clock() - startedAt
                val breach = when {
                    elapsed >= budget.totalCap.inWholeMilliseconds -> WatchdogFired.TotalCap(elapsed)
                    idle >= idleLimit -> WatchdogFired.Idle(idle, first)
                    else -> null
                }
                if (breach != null) {
                    firedRef.compareAndSet(null, breach)
                    target.cancel()
                    return@launch
                }
            }
        }

    /** NF-03: the whole-turn wall clock, armed from admission to terminal. [launchIn]'s totalCap
     *  check only runs while an upstream stream is open, so connect, headers-wait, retry backoff,
     *  refresh, and between-round gaps were previously uncounted — an N-round fold/re-anchor turn
     *  got N x the per-round budget against one totalCap while pinning its gate slot. Idle tiers
     *  stay with [launchIn] (they need the slot); breach semantics are identical: the typed
     *  sentinel is set FIRST, then [target] is cancelled. */
    public fun launchTotalCap(scope: CoroutineScope, target: Job): Job =
        scope.launch {
            // Paced against totalCap as well as streamIdle: pollInterval() alone is streamIdle/3,
            // so a cap tighter than the idle budget would be sampled too late to matter.
            val capThird = budget.totalCap.inWholeMilliseconds / IDLE_DIVISOR
            val interval = minOf(
                pollInterval().inWholeMilliseconds,
                capThird.coerceIn(MIN_POLL_MS, MAX_POLL_MS),
            ).milliseconds
            while (isActive) {
                delay(interval)
                val elapsed = clock() - startedAt
                if (elapsed >= budget.totalCap.inWholeMilliseconds) {
                    firedRef.compareAndSet(null, WatchdogFired.TotalCap(elapsed))
                    target.cancel()
                    return@launch
                }
            }
        }

    private companion object {
        const val IDLE_DIVISOR = 3
        const val MIN_POLL_MS = 250L
        const val MAX_POLL_MS = 15_000L
    }
}
