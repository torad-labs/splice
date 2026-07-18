// PORT-OF: server/src/codex/stream.mjs idle-watchdog block @ 4ca99f7 — invariants (the v35
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sawFirstByte = AtomicBoolean(false)
    private val firedRef = AtomicReference<WatchdogFired?>(null)
    private val startedAt = clock()

    public val fired: WatchdogFired? get() = firedRef.get()

    /** Reader-side touch: the first byte flips the idle tier from firstByteTimeout to streamIdle. */
    public fun markByte() {
        sawFirstByte.set(true)
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

    private companion object {
        const val IDLE_DIVISOR = 3
        const val MIN_POLL_MS = 250L
        const val MAX_POLL_MS = 15_000L
    }
}
