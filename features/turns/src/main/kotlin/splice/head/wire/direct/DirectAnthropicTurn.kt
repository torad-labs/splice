// NEW: desktop-antigravity (door chat). Streams or collects a directly-driven Anthropic Messages
// turn through the sole L3 wire terminals (SseEmitter / CollectingTerminal) so non-HTTP providers
// like Antigravity's `agy acp` speak exact Anthropic SSE/JSON without duplicating wire framing or
// terminal literals.
package splice.head.wire.direct

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import splice.core.failure.FailureCause
import splice.core.failure.HeadVoice
import splice.core.model.ModelCatalog
import splice.core.model.UsageSizing
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.util.JsonWire
import splice.head.ClientWindowWitness
import splice.head.GatewayTurn
import splice.head.wire.CollectingTerminal
import splice.head.wire.FrameWrite
import splice.head.wire.SseEmitterFactory
import splice.head.wire.TurnTerminal
import splice.head.wire.TurnWiring
import splice.head.wire.UsagePayloadBuilder
import splice.http.JsonReply
import splice.http.SseResponse
import splice.upstream.sse.WireSink
import java.io.IOException

/** Terminal outcome of a turn driven directly into a [WireSink]. */
public data class DirectTurnOutcome(
    val hasToolUse: Boolean = false,
    val incomplete: Boolean = false,
    val usage: Usage = Usage(reported = UsageField.entries.toSet()),
    val error: FailureCause? = null,
)

/** One turn driver emitting blocks into [sink] and returning its terminal outcome. */
public fun interface DirectTurnDrive {
    public suspend operator fun invoke(sink: WireSink): DirectTurnOutcome
}

/** Serves an Anthropic streaming or collected Messages turn via [SseEmitter] / [CollectingTerminal]. */
public class DirectAnthropicTurn(
    private val catalog: ModelCatalog,
    private val voice: HeadVoice,
) {
    private val emitters = SseEmitterFactory()
    private val wiring = TurnWiring()
    private val clientWindow = ClientWindowWitness()

    /** [model]'s turn served on [call], its counts scaled to [turn]'s windows: the one its session holds and the one
     *  its gateway read off the request. */
    public suspend fun serve(
        call: ApplicationCall,
        model: String,
        stream: Boolean,
        turn: GatewayTurn,
        drive: DirectTurnDrive,
    ) {
        val usagePayload = wiring.usagePayloadBuilder(model, sizing(call, model, turn))
        val replyModel = turn.asked ?: model
        if (stream) {
            serveStream(call, replyModel, usagePayload, drive)
        } else {
            serveCollected(call, replyModel, usagePayload, drive)
        }
    }

    /** A local count in the same input units as a Messages response. */
    public fun countInput(call: ApplicationCall, model: String, turn: GatewayTurn, estimate: Long): Long =
        sizing(call, model, turn).scale(estimate)

    private fun sizing(call: ApplicationCall, model: String, turn: GatewayTurn): UsageSizing =
        clientWindow.sizing(call, catalog, model, sessionId = null, turn = turn)

    private suspend fun serveStream(
        call: ApplicationCall,
        model: String,
        usagePayload: UsagePayloadBuilder,
        drive: DirectTurnDrive,
    ) {
        val response = SseResponse { out ->
            val write = FrameWrite { frame ->
                out.write(frame)
                out.flush()
            }
            val emitter = emitters.create(
                write = write,
                model = model,
                usagePayload = usagePayload,
                voice = voice.anyTurn,
            )
            try {
                finish(emitter, drive(emitter))
            } catch (_: IOException) {
                emitter.abandon()
            } catch (_: IllegalStateException) {
                emitter.abandon()
            }
        }
        call.respond(response)
    }

    private suspend fun serveCollected(
        call: ApplicationCall,
        model: String,
        usagePayload: UsagePayloadBuilder,
        drive: DirectTurnDrive,
    ) {
        val terminal = CollectingTerminal(model, usagePayload, voice = voice.anyTurn)
        finish(terminal, drive(terminal))
        val status = HttpStatusCode.fromValue(terminal.httpStatus())
        JsonReply(status, JsonWire.string(terminal.responseBody())).send(call)
    }

    private suspend fun finish(terminal: TurnTerminal, outcome: DirectTurnOutcome) {
        val error = outcome.error
        if (error != null) {
            terminal.closeAll()
            terminal.emitError(error)
            return
        }
        terminal.ensureStarted()
        terminal.closeAll()
        terminal.emitTerminal(
            hasToolUse = outcome.hasToolUse,
            incomplete = outcome.incomplete,
            usage = outcome.usage,
        )
    }
}
