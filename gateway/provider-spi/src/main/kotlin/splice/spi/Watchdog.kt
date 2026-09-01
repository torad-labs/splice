// PORT-OF: server/src/codex/stream.mjs idle-watchdog block @ pre-public-port-baseline — invariants (the v35
// headline fix IS the spec): BEFORE the client has been handed any output the idle limit is
// firstByteTimeout — a big-context prefill (compaction re-reading ~160k tokens) is legitimately
// silent for minutes; reaping prefill on streamIdle caused the abort->retry->cold-re-read loop
// ("compaction ate my quota"). AFTER the first client content frame the limit is streamIdle.
// totalCap bounds the whole turn (an overloaded backend can trickle keepalives forever and leak
// the slot — the "55 inflight, 2 agents" class). Poll interval = min(15s, max(250ms, tier/3)) for
// the tighter of the two idle tiers.
// The fired reason is a TYPED SENTINEL set BEFORE cancelling, so catch sites can tell
// watchdog-fired from client-gone from shutdown.
//
// 2026-09-01 — THE TIER IS THE CLIENT FRAME, NOT THE BYTE. The port flipped tiers on the first
// upstream BYTE (markByte on the raw SSE read / the first WS event). That is the wrong signal for a
// handshake-first protocol: the Responses API answers within 1-5s with response.created /
// response.in_progress — bytes, but no output — and the model then reasons silently over the whole
// prefill. So the v35 spec was defeated by the very stream it protected: the handshake pinned every
// compaction to the short streamIdle tier before a single token existed. Live: 109 codex
// compactions in one day (gpt-5.6-sol, 1.0-1.2MB upstream bodies) died at "no completion within
// the 180s idle cap" with first_byte=1-5s and NO first delta, each failure re-sent cold by the
// client, three sessions looping in parallel. The successful ones that day had first deltas at
// 17s..111s — the same silence, one tier apart. The tier now reads the round's own
// [ClientFrameEmitted] probe (CONTENT_FRAMES_OUT above the round's baseline — the same fact G5
// keys reissue on): until the client has seen content, the silence is prefill/reasoning and the
// first-output cap applies; after it, the stream is mid-output and streamIdle applies. Bytes still
// TOUCH the slot (liveness: a keepalive resets idleness); they no longer choose the limit. A COMPACT
// turn's first-output cap is totalCap itself (WatchdogBudget.forCompact, wired at TurnDriveFactory):
// the first compaction on the corrected tier still died silent at 300s.
package splice.spi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

public sealed class WatchdogFired {
    /** [sawClientFrame] names the tier that judged the silence — false: the first-output tier
     *  ([WatchdogBudget.firstByteTimeout]), true: the mid-output tier ([WatchdogBudget.streamIdle]) —
     *  and [limitMs] is that tier's cap, so a terminal message can name the number that fired. */
    public data class Idle(val idleMs: Long, val sawClientFrame: Boolean, val limitMs: Long) : WatchdogFired()

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
    private val firedRef = AtomicReference<WatchdogFired?>(null)
    private val startedAt = clock()

    public val fired: WatchdogFired? get() = firedRef.get()

    /** Round boundary. The idle TIER needs no reset any more — [launchIn] reads the round's own
     *  client-frame probe on every poll, and a fresh round starts from a fresh baseline — but the
     *  DR-7 half stays: a stale IDLE sentinel is cleared. The sentinel is sticky by design so the
     *  terminal decision can name why a round died, but a salvaged round is followed by another
     *  round — and a sentinel left set makes every later round terminate as "stalled" no matter how
     *  healthy it is, and re-vetoes the continuation gates. Only Idle is cleared, and only by CAS:
     *  TotalCap is a WHOLE-TURN verdict that no new round may erase, and a compareAndSet against
     *  the exact observed value cannot race away a fire landing at this instant. */
    public fun resetRound() {
        firedRef.get()?.let { if (it is WatchdogFired.Idle) firedRef.compareAndSet(it, null) }
    }

    /** Paced to the TIGHTER idle tier. Both caps are sampled by the same poller, and a first-output
     *  cap shorter than streamIdle/3 (a test rig, or an operator wanting a fast pre-output verdict)
     *  would otherwise be sampled too late to matter — the rule [launchTotalCap] already applies
     *  against totalCap. Production (180s/300s) still lands on the 15s ceiling. */
    public fun pollInterval(): Duration {
        val tighter = minOf(budget.streamIdle, budget.firstByteTimeout).inWholeMilliseconds / IDLE_DIVISOR
        return tighter.coerceIn(MIN_POLL_MS, MAX_POLL_MS).milliseconds
    }

    /**
     * Launch the sibling poller: watches [slot] IDLENESS against the tier [clientFrame] selects, and
     * on breach sets the typed sentinel FIRST, then cancels [target]. Cancel the returned job on
     * clean exit.
     *
     * [clientFrame] is the ROUND's probe (SseRoundDriver baselines CONTENT_FRAMES_OUT per round):
     * false = the client has seen no content this round, so the silence is prefill/reasoning and
     * the first-output cap applies; true = mid-output, streamIdle applies. See the file header for
     * why this is a frame and not a byte. A fold round's BUFFERED final output (held back until the
     * terminal proves the round) and a non-stream turn (no client frames are ever written) read as
     * "no client frame" and so sit on the first-output cap — the lenient side, and literally true:
     * nothing has reached the client yet.
     *
     * DR-7: [target] is a ROUND, not the turn — the SSE path parents a job to the turn job and
     * aborts that round's body channel, so the translator survives to report the stall WITH its
     * salvage. Total elapsed is NOT sampled here any more; see [launchTotalCap].
     */
    public fun launchIn(
        scope: CoroutineScope,
        slot: InflightGate.Slot,
        target: Job,
        clientFrame: ClientFrameEmitted,
    ): Job =
        scope.launch {
            while (isActive) {
                // pollInterval() is coerced to 250ms..15s, so inWholeMilliseconds is the exact value
                // delay(Duration) would have used (its <1ms coerceAtLeast(1) rounding is unreachable here).
                if (!ticker.awaitTick(pollInterval().inWholeMilliseconds)) return@launch
                val idle = slot.idleForMs()
                val seen = clientFrame()
                val idleLimit = if (seen) {
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
                    firedRef.compareAndSet(null, WatchdogFired.Idle(idle, seen, idleLimit))
                    target.cancel()
                    return@launch
                }
            }
        }

    /** NF-03: the whole-turn wall clock, armed from admission to terminal, and since DR-7 the ONLY
     *  place a totalCap breach is raised. It was once a second sampler beside [launchIn]'s, which
     *  ran only while an upstream stream was open — so connect, headers-wait, retry backoff,
     *  refresh, and between-round gaps went uncounted and an N-round fold/re-anchor turn got N x
     *  the per-round budget against one totalCap while pinning its gate slot. Idle tiers stay with
     *  [launchIn] (they need the slot, and they reap a round rather than the turn); breach
     *  semantics are identical: the typed sentinel is set FIRST, then [target] is cancelled. */
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
