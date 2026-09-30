// NEW: V4-446 — one bounded decision before a streaming turn commits its HTTP status.
package splice.head.turn.stream

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.perf.TurnPerf
import splice.core.util.ElapsedClock
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
) {
    internal sealed class Decision {
        data object Stream : Decision()
        data class Overflow(val body: String) : Decision()
    }

    private val choice = CompletableDeferred<Decision>()
    private val output = PendingSseWriter(perf, clock, trace, recording, choice)

    val channel get() = output.channel

    suspend fun model(frame: String) {
        if (structural(frame) && output.stageModel(frame)) return
        val overflow = OverflowFrame.body(frame)
        if (overflow == null) {
            choice.complete(Decision.Stream)
        } else if (choice.complete(Decision.Overflow(overflow))) {
            trace?.collectedAnswer { ClientAnswer(HttpStatusCode.BadRequest.value, overflow) }
        }
        if (choice.await() is Decision.Overflow) return
        output.writeModel(frame)
    }

    suspend fun progress(frame: String) {
        // The first status-line thinking delta is client-visible output. Open SSE now so a
        // silent model still speaks at the heartbeat; structural pings and comments stay held.
        if (frame.startsWith("event: content_block_delta\n") && frame.contains("\"type\":\"thinking_delta\"")) {
            choice.complete(Decision.Stream)
        }
        if (output.stageProgress(frame)) return
        if (choice.await() is Decision.Overflow) return
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
}
