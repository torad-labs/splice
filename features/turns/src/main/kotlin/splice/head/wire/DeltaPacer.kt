// NEW: V4-456 — the pacing policy of the client write path every head shares.
//
// WHY. Providers send some output in batches: Anthropic streams a summarized thinking block as a whole
// summary at once (platform.claude.com thinking guide, "Streaming thinking"), and a JFR socket recording
// of the live daemon (Oct 1, 7:49 PM CT) showed 190 thinking deltas, about 31 KB, arriving in ONE read
// after a 16 s provider silence. splice wrote each of them 1 to 3 ms after reading it, so Claude Code
// drew the whole batch in one frame: nothing for a long time, then a burst. Codex's terminal paces its
// own input (codex-rs/tui/src/streaming/chunking.rs), so the same provider looks smoother there. This
// is splice's pacing, on the one path every head writes through.
//
// THE POLICY, which is pure and holds no clock, coroutine or socket (ClientChannel drives it):
//   - Only a visible model delta (text or thinking) ever starts a wait, and only when it arrives
//     within one tick of the last visible delta written. A lone delta after a quiet stretch is written
//     at once, and so is every token of a stream slower than one delta per tick.
//   - Once anything is held, EVERY frame queues behind it, so the wire order is the emitter's order:
//     a content_block_stop, a tool call or message_stop never passes a held delta.
//   - Each tick releases enough visible deltas to finish what is held within [windowMs] of the oldest
//     one's arrival, and never fewer than [floorPerTick]. A burst spreads evenly over the window;
//     a steady stream up to floorPerTick deltas a tick is never lagged by more than a tick; a held
//     delta never waits longer than the window plus one tick (the catch-up gear: once the oldest is
//     within a tick of the window, everything held goes).
//   - Frames that are not visible deltas ride free: they leave as soon as the deltas ahead of them do.
package splice.head.wire

import kotlinx.coroutines.channels.Channel
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf

/** One release step per frame tick, about sixty a second. */
internal const val PACE_TICK_MS = 16L

/** The longest a held delta waits, the tick it is released in aside. */
private const val PACE_WINDOW_MS = 1_000L

/** Deltas a tick releases whatever is held: four a tick is 250 a second, faster than any token stream
 *  measured on this proxy, so pacing only ever stretches a batch. */
private const val PACE_FLOOR_PER_TICK = 4

private const val DELTA_EVENT = "event: content_block_delta\n"
private const val TEXT_DELTA_MARK = "\"delta\":{\"type\":\"text_delta\""
private const val THINKING_DELTA_MARK = "\"delta\":{\"type\":\"thinking_delta\""

/** Holds the frames a burst queues and decides which leave each tick. Every member except
 *  [awaitSignal] and [finish] runs under the channel's writeMutex, which is what makes the queue safe. */
internal class DeltaPacer(
    private val windowMs: Long = PACE_WINDOW_MS,
    private val floorPerTick: Int = PACE_FLOOR_PER_TICK,
    private val tickMs: Long = PACE_TICK_MS,
) {
    /** A frame waiting its turn, with the perf ledger its socket write is counted in. */
    internal data class Held(val frame: String, val perf: TurnPerf, val visible: Boolean, val atMs: Long)

    private val queue = ArrayDeque<Held>()
    private var visibleHeld = 0
    private var lastVisibleMs: Long? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** True while a release loop runs for the turn: only then may a frame wait. */
    @Volatile var active: Boolean = false

    /** Set when the turn's last frame is written: the loop releases what is held, then stops. */
    @Volatile private var finishing = false

    /** Queues [frame] when it has to wait and answers true; false means write it now. Only a model
     *  frame ([modelOutput]) can be a visible delta: the pinger's status line never starts a wait. */
    fun hold(frame: String, perf: TurnPerf, modelOutput: Boolean, nowMs: Long): Boolean {
        val visible = modelOutput && isVisibleDelta(frame)
        if (!mustWait(visible, nowMs)) {
            if (visible) lastVisibleMs = nowMs
            return false
        }
        if (queue.isEmpty()) signal()
        queue.addLast(Held(frame, perf, visible, nowMs))
        if (visible) visibleHeld += 1
        return true
    }

    /** The frames that leave at [nowMs], in order: every visible delta the budget allows and every
     *  other frame up to the first delta it does not. */
    fun due(nowMs: Long): List<Held> {
        val budget = budget(nowMs)
        val out = ArrayList<Held>()
        var spent = 0
        while (queue.isNotEmpty() && leaves(queue.first(), spent, budget)) {
            val next = released(queue.removeFirst(), nowMs)
            if (next.visible) {
                spent += 1
                visibleHeld -= 1
                lastVisibleMs = nowMs
            }
            out += next
        }
        return out
    }

    /** Everything held, in order, for a write that cannot wait for the loop (it stopped, or the turn
     *  is ending without it). */
    fun takeAll(nowMs: Long): List<Held> = queue.map { released(it, nowMs) }.also {
        queue.clear()
        visibleHeld = 0
    }

    private fun released(frame: Held, nowMs: Long): Held = frame.also {
        val origin = it.perf.clockOriginMs
        it.perf.intervals.record(PerfKeys.OUT_HOLD_MAX_MS, it.atMs - origin, nowMs - origin)
    }

    /** After a release: true while frames still wait. Once the queue is empty on a finishing turn the
     *  pacer goes inactive in the same step, so no frame can queue behind a loop that has stopped. */
    fun stillHeld(): Boolean {
        if (queue.isEmpty() && finishing) active = false
        return queue.isNotEmpty()
    }

    private fun signal() {
        wake.trySend(Unit)
    }

    suspend fun awaitSignal() {
        wake.receive()
    }

    fun finish() {
        finishing = true
        signal()
    }

    /** A frame that is not a visible delta rides free; a delta needs budget left this tick. */
    private fun leaves(next: Held, spent: Int, budget: Int): Boolean = !next.visible || spent < budget

    private fun mustWait(visible: Boolean, nowMs: Long): Boolean = when {
        !active -> false
        queue.isNotEmpty() -> true
        else -> visible && tooSoon(nowMs)
    }

    private fun tooSoon(nowMs: Long): Boolean = lastVisibleMs?.let { nowMs - it < tickMs } ?: false

    private fun budget(nowMs: Long): Int {
        val oldest = queue.firstOrNull { it.visible } ?: return 0
        val left = (windowMs - (nowMs - oldest.atMs)).coerceAtLeast(tickMs)
        val spread = ((visibleHeld * tickMs + left - 1) / left).toInt()
        return maxOf(floorPerTick, spread)
    }

    private fun isVisibleDelta(frame: String): Boolean =
        frame.startsWith(DELTA_EVENT) && (frame.contains(TEXT_DELTA_MARK) || frame.contains(THINKING_DELTA_MARK))
}
