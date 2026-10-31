// NEW: V4-446 — one bounded decision before a streaming turn commits its HTTP status.
package splice.head.turn.stream

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.failure.ClientWire
import splice.core.failure.FailureCause
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.WireAnswer
import splice.core.perf.TurnPerf
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.head.hold.HandOverHeaders
import splice.head.wire.ClientAnswer
import splice.head.wire.FrameRecording
import splice.head.wire.TurnTrace
import java.io.Writer

/** Hold structural opening and progress until a model frame, a failure, or the deadline.
 * The first client-visible model frame commits immediately. The bounded wait stays below the
 * client's stall watchdog; a vanished client without FIN can retain its slot until the bound. */
internal class PendingSse(
    private val perf: TurnPerf,
    private val clock: ElapsedClock,
    private val trace: TurnTrace?,
    recording: FrameRecording?,
    private val holdMs: Long = 120_000L,
    private val wall: WallClock = WallClock(System::currentTimeMillis),
) {
    internal sealed class Decision {
        data object Stream : Decision()

        /** An answer while the status is still splice's: [headers] carry a handed-over limit's reset (HandOverHeaders). */
        data class Overflow(
            val body: String,
            val status: Int,
            val shouldRetry: Boolean?,
            val headers: Map<String, String> = emptyMap(),
        ) : Decision()
    }

    private val choice = CompletableDeferred<Decision>()
    private val output = PendingSseWriter(perf, clock, trace, recording, choice)

    val channel get() = output.channel

    /** A frame from the model's side. A frame that is not the emitter's own ending, a relayed provider frame among
     *  them, still answers an overflow by its words (OverflowFrame). */
    suspend fun model(frame: String) {
        if (structural(frame) && output.stageModel(frame)) return
        settle(frame, OverflowFrame.body(frame)?.let(::overflowAnswer))
    }

    /** The emitter's error ending (failure-handling slice 2). [unsent] is what the failure is while the status is still
     *  splice's to choose, so a prompt that outgrew the window answers with its own status and [body] whatever its line
     *  says: a compaction's and a first message's lines leave out the words a client compacts on (final-design.md
     *  section 11), and they keep today's 400. Once the stream is chosen, [frame] goes out in band like any other. */
    suspend fun ending(frame: String, unsent: WireAnswer, body: String) {
        settle(
            frame,
            unsent.status?.let { Decision.Overflow(body, it, unsent.shouldRetry, HandOverHeaders.of(unsent, wall())) },
        )
    }

    /** Chooses the stream unless [overflow] answers first, and writes [frame] when the stream is the answer. */
    private suspend fun settle(frame: String, overflow: Decision.Overflow?) {
        if (overflow == null) {
            choice.complete(Decision.Stream)
        } else if (choice.complete(overflow)) {
            trace?.collectedAnswer { ClientAnswer(overflow.status, overflow.body) }
        }
        if (choice.await() !is Decision.Stream) return
        output.writeModel(frame)
    }

    suspend fun progress(frame: String) {
        // The first status-line thinking delta is client-visible output. Open SSE now so a
        // silent model still speaks at the heartbeat; structural pings and comments stay held.
        val visible = frame.startsWith("event: content_block_delta\n") &&
            frame.contains("\"type\":\"thinking_delta\"")
        if (visible) choice.complete(Decision.Stream)
        if (output.stageProgress(frame)) return
        if (choice.await() !is Decision.Stream) return
        output.writeProgress(frame)
    }

    /** A recovery line, a visible wait's or a hold's (failure-handling slice 5): it commits the stream at once, so
     *  the client reads it while the wait it names runs. */
    suspend fun retryProgress(frame: String) {
        choice.complete(Decision.Stream)
        if (choice.await() !is Decision.Stream) return
        output.writeProgress(frame)
    }

    suspend fun decide(): Decision {
        withTimeoutOrNull(holdMs) { choice.await() }?.let { return it }
        finish()
        return choice.await()
    }

    /** A drive ending without a client frame cannot leave the HTTP status undecided. */
    fun finish() {
        choice.complete(Decision.Stream)
    }

    suspend fun attach(out: Writer) = output.attach(out)

    fun abortClient() = output.abortClient()

    suspend fun detachForRecording(): Boolean = output.detachForRecording()

    private fun structural(frame: String): Boolean =
        frame.startsWith("event: message_start\n") || frame.startsWith("event: ping\n")

    /** A prompt that outgrew the window, met while the status is still splice's to choose, answers as ClientWire
     *  says: with the status Claude Code compacts on where it gives one, or else in band like any other frame. */
    private fun overflowAnswer(body: String): Decision.Overflow? {
        val answer = ClientWire.answer(
            FailureCause.Provider.TooLong(limitTokens = null, whileAnswering = false),
            Position(Moment.BEFORE_COMMIT, streamBegan = true),
        )
        return answer.status?.let { Decision.Overflow(body, it, answer.shouldRetry) }
    }
}
