// NEW 2026-11-19 (reply-stream): one turn as its session's followers see it. Built at admission for a turn that names
// a session (HeadAdmission), handed to its ClientChannel, which taps every frame bound for the client here; it shows
// when its message_start is written, ends for its followers on the stop or error event the client was sent, and is
// closed where every ending goes through (TurnTelemetry.recordPerf) and settled when its admission slot is released.
package splice.head.live

import kotlinx.serialization.json.JsonObject

/** Where a frame that opens a response begins. Read off the frame's first bytes: no parsing while nobody follows. */
private const val LIVE_OPENING_FRAME = "event: message_start"

/** What a turn is, for its followers: the session it belongs to, the subagent that sent it (null on the main thread),
 *  the model the client asked for, the head serving it, and whether it is a compaction. */
internal data class LiveTurnFacts(
    val session: String,
    val agent: String?,
    val model: String,
    val head: String,
    val compact: Boolean,
)

/** A non-stream turn's whole answer, read once it is final. */
internal fun interface LiveAnswer {
    fun body(): JsonObject
}

/** One turn's live handle. [frame] is the tap; [close], [settle] and [answeredBy] are its endings. The state below the
 *  session's lock is touched only under it, once the turn has started. */
internal class LiveReply(
    private val hub: LiveReplies,
    /** The turn's id, unique within the daemon's life. */
    val id: String,
    val facts: LiveTurnFacts,
    /** When the head admitted the request, in epoch milliseconds. */
    val at: Long,
    private val held: HeldFrames,
) {
    private val gate = Any()

    @Volatile private var session: LiveSession? = null

    private var settled = false
    private var opening: String? = null

    @Volatile private var answer: LiveAnswer? = null

    private val reading = ReplyReading(id)

    /** Whether the client was sent the response's end, as this turn's frames read so far. */
    val ended: Boolean get() = reading.ended

    /** Every frame written toward the client, in order: what [ClientChannel][splice.head.wire.ClientChannel] writes,
     *  detached or not. The turn shows from its message_start; a frame before it shows nothing. */
    fun frame(frame: String) {
        val started = session ?: start(frame) ?: return
        started.written(this, frame)
    }

    /** A non-stream turn's answer, read when the turn settles: its ClientChannel carries no frames. */
    fun answeredBy(source: LiveAnswer) {
        answer = source
    }

    /** The drive ended (TurnTelemetry.recordPerf). A response that got neither a stop nor an error event is cut here,
     *  unless [errorFollows]: the turn's error frame is written after its row, and [settle] then ends it. */
    fun close(errorFollows: Boolean) {
        if (answer != null || errorFollows) return
        session?.cut(this)
    }

    /** The turn's admission slot was released, which every ending reaches after its last frame: a turn still in flight
     *  ends as cut, and a non-stream turn's answer goes to its followers. */
    fun settle() {
        val started = synchronized(gate) {
            settled = true
            session
        }
        val whole = answer
        when {
            started != null -> started.cut(this)
            whole != null -> hub.answered(this, whole)
        }
    }

    private fun start(frame: String): LiveSession? {
        if (!frame.startsWith(LIVE_OPENING_FRAME)) return null
        return synchronized(gate) {
            if (settled) {
                null
            } else {
                opening = frame
                hub.started(this).also { session = it }
            }
        }
    }

    // Under the session's lock from here on.

    fun hold(frame: String) {
        held.add(frame)
    }

    fun follow(frame: String): List<String> = reading.follow(frame)

    /** The turn's `turn` event. */
    fun opening(): String {
        if (reading.message == null) opening?.let(reading::opening)
        return LiveWire.turn(this, reading.message)
    }

    /** What a follower joining now is sent of this turn: its `turn` event, then one event per block written so far.
     *  Only the `turn` event when the frames are no longer held; null when they hold the response's end. */
    fun snapshot(): List<String>? {
        val frames = held.all() ?: return listOf(opening())
        val gathered = ReplyReading(id)
        frames.forEach(gathered::gather)
        if (gathered.ended) return null
        reading.adopt(gathered)
        return listOf(opening()) + gathered.snapshot()
    }

    fun cutEnding(): String = LiveWire.end(id, reading.stopReason, LiveWire.CUT_ENDING)

    /** A non-stream turn's events: `turn`, one per content block, `end`. */
    fun answered(source: LiveAnswer): List<String> {
        val blocks = reading.answer(source.body())
        return listOf(LiveWire.turn(this, reading.message)) + blocks
    }

    fun release() {
        held.drop()
    }
}
