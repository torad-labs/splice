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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
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
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    // HD-19: the poll cadence of both loops below. ProcessTicker is `delay(intervalMs); true`, so
    // production paces exactly as it did; a test wires a ticker that returns instantly and can stop
    // the loop after N samples instead of racing a cancellation against a real 250ms..15s sleep.
    private val ticker: Ticker = ProcessTicker(),
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
        // DR-7: also clear a STALE IDLE fire. The sentinel is sticky by design so the terminal
        // decision can name why a round died, but a salvaged round is followed by another round —
        // and a sentinel left set makes every later round terminate as "stalled" no matter how
        // healthy it is, and re-vetoes the continuation gates. Only Idle is cleared, and only by
        // CAS: TotalCap is a WHOLE-TURN verdict that no new round may erase, and a compareAndSet
        // against the exact observed value cannot race away a fire landing at this instant.
        firedRef.get()?.let { if (it is WatchdogFired.Idle) firedRef.compareAndSet(it, null) }
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
                // pollInterval() is coerced to 250ms..15s, so inWholeMilliseconds is the exact value
                // delay(Duration) would have used (its <1ms coerceAtLeast(1) rounding is unreachable here).
                if (!ticker.awaitTick(pollInterval().inWholeMilliseconds)) return@launch
                val idle = slot.idleForMs()
                val first = sawFirstByte.get()
                val idleLimit = if (first) {
                    budget.streamIdle.inWholeMilliseconds
                } else {
                    budget.firstByteTimeout.inWholeMilliseconds
                }
                // DR-7: totalCap is NOT sampled here any more. [launchTotalCap] owns the only
                // whole-turn cancel, and it targets the turn job; this poller now targets a single
                // ROUND, so raising a TotalCap verdict from here would reap one round and let the
                // fold loop open the next — spending past the cap under a name that means "stop".
                // One breach kind per poller, each cancelling the scope it actually owns.
                if (idle >= idleLimit) {
                    firedRef.compareAndSet(null, WatchdogFired.Idle(idle, first))
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
                // Same coercion floor (250ms) as launchIn, so this is delay(interval)'s exact value.
                if (!ticker.awaitTick(interval.inWholeMilliseconds)) return@launch
                val elapsed = clock() - startedAt
                if (elapsed >= budget.totalCap.inWholeMilliseconds) {
                    firedRef.compareAndSet(null, WatchdogFired.TotalCap(elapsed))
                    target.cancel()
                    return@launch
                }
            }
        }
}

private const val IDLE_DIVISOR = 3
private const val MIN_POLL_MS = 250L
private const val MAX_POLL_MS = 15_000L
