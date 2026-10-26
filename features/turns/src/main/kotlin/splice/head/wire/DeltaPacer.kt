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
//   - Only a visible model delta (text or thinking) ever starts a wait: when it arrives within one tick
//     of the last visible delta, or carries a large update that needs subdivision. Ordinary token deltas
//     after a quiet stretch leave at once. Large updates release slices over successive ticks.
//   - Once anything is held, every later frame queues behind it, so the wire order is the emitter's order:
//     a content_block_stop never passes a held delta.
//   - A frame that starts a tool call (a tool_use or server_tool_use block) or ends the message (a message_delta
//     carrying a stop_reason, or message_stop) never waits: everything held leaves at once, ahead of it, so the order
//     still holds and Claude Code starts the tool and ends the turn the moment splice has the frame. Fix 2, Oct 26:
//     on claude-splice, Oct 25, 6 to 11 PM CT (21,215 turns), a turn ended 67 ms after the upstream's last byte at
//     p50 and 923 ms at p99, while the pacer's longest hold per turn was 131 ms and 944 ms. A burst that more text
//     or thinking follows is paced as before.
//   - Each tick releases enough visible deltas to finish what is held within [windowMs] of the oldest
//     one's arrival, and never fewer than [floorPerTick]. A burst spreads evenly over the window;
//     a steady stream up to floorPerTick deltas a tick is never lagged by more than a tick; a held
//     delta never waits longer than the window plus one tick (the catch-up gear: once the oldest is
//     within a tick of the window, everything held goes).
//   - Frames that are not visible deltas ride free: they leave as soon as the deltas ahead of them do.
package splice.head.wire

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.JsonScalars

/** One release step per frame tick, about sixty a second. */
internal const val PACE_TICK_MS = 16L

/** The longest a held delta waits, the tick it is released in aside. */
private const val PACE_WINDOW_MS = 1_000L

/** Deltas a tick releases whatever is held: four a tick is 250 a second, faster than any token stream
 *  measured on this proxy, so pacing only ever stretches a batch. */
private const val PACE_FLOOR_PER_TICK = 4

private const val DELTA_EVENT = "event: content_block_delta\n"

private const val TEXT_DELTA = "text_delta"
private const val THINKING_DELTA = "thinking_delta"
private val VISIBLE_TYPES = setOf(TEXT_DELTA, THINKING_DELTA)

// The frames that release everything held: a tool call's block start and the message's end.
private const val BLOCK_START_EVENT = "event: content_block_start\n"
private const val MESSAGE_DELTA_EVENT = "event: $MESSAGE_DELTA\n"
private const val MESSAGE_STOP_EVENT = "event: $MESSAGE_STOP\n"
private val TOOL_BLOCK_TYPES = setOf("tool_use", "server_tool_use")

/** Holds the frames a burst queues and decides which leave each tick. Every member except
 *  [awaitSignal] and [finish] runs under the channel's writeMutex, which is what makes the queue safe. */
internal class DeltaPacer(
    private val windowMs: Long = PACE_WINDOW_MS,
    private val floorPerTick: Int = PACE_FLOOR_PER_TICK,
    private val tickMs: Long = PACE_TICK_MS,
) {
    /** A frame waiting its turn, with the perf ledger its socket write is counted in. */
    internal data class Held(val frame: String, val perf: TurnPerf, val visible: Boolean, val atMs: Long)

    private class Queued(val held: Held, private val slices: VisibleDeltaSlices?) {
        private var delivered = false
        val done: Boolean get() = slices?.let { it.remaining == 0 } ?: delivered
        val weight: Int get() = if (!held.visible || done) 0 else slices?.remaining ?: 1

        fun next(): Held = slices?.let { held.copy(frame = it.next()) }
            ?: held.also { delivered = true }

        fun remainder(): Held = slices?.let { held.copy(frame = it.remainder()) } ?: held
    }

    private val queue = ArrayDeque<Queued>()
    private var visibleHeld = 0
    private var lastVisibleMs: Long? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** True while a release loop runs for the turn: only then may a frame wait. */
    @Volatile var active: Boolean = false

    /** Set when the turn's last frame is written: the loop releases what is held, then stops. */
    @Volatile private var finishing = false

    /** Queues [frame] when it has to wait and answers true; false means write it now, after whatever [takeAll] still
     *  holds. Only a model frame ([modelOutput]) can be a visible delta: the pinger's status line never starts a wait.
     *  A frame that starts a tool call or ends the message never waits behind a burst: it answers false, so the
     *  caller writes everything held at once, then it. */
    fun hold(frame: String, perf: TurnPerf, modelOutput: Boolean, nowMs: Long): Boolean {
        val visible = modelOutput && isVisibleDelta(frame)
        val slices = if (active && visible) VisibleDeltaSplitter.split(frame) else null
        if (slices == null && !mustWait(frame, visible, nowMs)) {
            if (visible) lastVisibleMs = nowMs
            return false
        }
        if (queue.isEmpty()) signal()
        val queued = Queued(Held(frame, perf, visible, nowMs), slices)
        queue.addLast(queued)
        visibleHeld += queued.weight
        return true
    }

    /** The frames that leave at [nowMs], in order: every visible delta the budget allows and every
     *  other frame up to the first delta it does not. */
    fun due(nowMs: Long): List<Held> {
        val budget = budget(nowMs)
        val out = ArrayList<Held>()
        var spent = 0
        while (queue.isNotEmpty() && leaves(queue.first().held, spent, budget)) {
            val queued = queue.first()
            val before = queued.weight
            val next = released(queued.next(), nowMs)
            if (queued.done) queue.removeFirst()
            if (next.visible) {
                spent += 1
                visibleHeld -= before - queued.weight
                lastVisibleMs = nowMs
            }
            out += next
        }
        return out
    }

    /** Everything held, in order, for a write that cannot wait for the loop (it stopped, or the turn
     *  is ending without it). */
    fun takeAll(nowMs: Long): List<Held> = queue.map { released(it.remainder(), nowMs) }.also {
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

    private fun mustWait(frame: String, visible: Boolean, nowMs: Long): Boolean = when {
        !active -> false
        queue.isNotEmpty() -> !EndingFields.releases(frame)
        else -> visible && tooSoon(nowMs)
    }

    private fun tooSoon(nowMs: Long): Boolean = lastVisibleMs?.let { nowMs - it < tickMs } ?: false

    private fun budget(nowMs: Long): Int {
        val oldest = queue.firstOrNull { it.held.visible }?.held ?: return 0
        val left = (windowMs - (nowMs - oldest.atMs)).coerceAtLeast(tickMs)
        val spread = ((visibleHeld * tickMs + left - 1) / left).toInt()
        return maxOf(floorPerTick, spread)
    }

    /** A text or thinking delta. Read from the frame's own `delta` object, so a native source's key order and
     *  whatever else the frame carries cannot change the answer. */
    fun isVisibleDelta(frame: String): Boolean = DeltaFields.visible(frame) != null

    /** A visible delta that carries characters: the client has nothing new to read from an empty one. */
    fun isArrivingDelta(frame: String): Boolean {
        val delta = DeltaFields.visible(frame) ?: return false
        val field = if (JsonScalars.str(delta.type) == TEXT_DELTA) delta.text else delta.thinking
        return !JsonScalars.str(field).isNullOrEmpty()
    }
}

/** The three fields of a frame's `delta` the pacer reads. Decoded straight from the frame's text, every other key
 *  skipped, so a streamed delta builds no JsonObject: parsing each one into a tree was 345 of 632 MB/s sampled on the
 *  live daemon (Oct 20, 01:28 CT), its HashMap tables alone 333. Elements, read by JsonScalars.str as the tree was,
 *  so a number or null in any of them answers as before. */
@Serializable
private data class DeltaRead(
    val type: JsonElement? = null,
    val text: JsonElement? = null,
    val thinking: JsonElement? = null,
)

@Serializable
private data class DeltaFrame(val delta: DeltaRead? = null)

private val DELTA_JSON = Json { ignoreUnknownKeys = true }

/** Reads a frame's `delta` object. */
private object DeltaFields {
    /** The frame's `delta` when it is a text or thinking delta, read from the object itself. */
    fun visible(frame: String): DeltaRead? {
        val data = if (frame.startsWith(DELTA_EVENT)) frame.substringAfter("data:", "") else ""
        if (data.isBlank()) return null
        return parsed(data)?.takeIf { JsonScalars.str(it.type) in VISIBLE_TYPES }
    }

    // A frame whose data is not JSON, or whose delta is not an object, is not a delta; pacing never fails a turn
    // over what it cannot read.
    private fun parsed(data: String): DeltaRead? = try {
        DELTA_JSON.decodeFromString(DeltaFrame.serializer(), data).delta
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** A block start's `content_block.type`, decoded like [DeltaRead]. */
@Serializable
private data class BlockTypeRead(val type: JsonElement? = null)

/** A message delta's `delta.stop_reason`, decoded like [DeltaRead]. */
@Serializable
private data class StopReasonRead(@SerialName("stop_reason") val stopReason: JsonElement? = null)

/** The two fields of a frame the release rule reads; every other key is skipped. */
@Serializable
private data class EndingFrame(
    @SerialName("content_block") val contentBlock: BlockTypeRead? = null,
    val delta: StopReasonRead? = null,
)

/** Reads whether a frame starts a tool call or ends the message: the frames that release everything held. Only a
 *  block start or a message delta is decoded, and only while something is held. */
private object EndingFields {
    fun releases(frame: String): Boolean = when {
        frame.startsWith(MESSAGE_STOP_EVENT) -> true
        frame.startsWith(BLOCK_START_EVENT) -> JsonScalars.str(read(frame)?.contentBlock?.type) in TOOL_BLOCK_TYPES
        frame.startsWith(MESSAGE_DELTA_EVENT) -> !JsonScalars.str(read(frame)?.delta?.stopReason).isNullOrEmpty()
        else -> false
    }

    // Data that is not JSON, or fields of another shape, release nothing: the frame queues as every frame did.
    private fun read(frame: String): EndingFrame? = try {
        DELTA_JSON.decodeFromString(EndingFrame.serializer(), frame.substringAfter("data:", ""))
    } catch (_: IllegalArgumentException) {
        null
    }
}
