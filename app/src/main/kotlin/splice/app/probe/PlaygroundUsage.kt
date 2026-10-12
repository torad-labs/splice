// NEW: Oct 12, 2026 — what a Compare models send cost, read the way a turn is read. The playground posts its own
// request and keeps the stream as text; to bill it, that text is handed to the head's OWN stream translator, the one
// that reads this provider's dialect for every turn, so Anthropic, Chat and Responses upstreams are each read by the
// code that already knows them and no dialect module learns about Compare. A stream that reported no usage leaves
// none (the row then reads "Uncounted", the way a turn's does), never a zero.
package splice.app.probe

import io.ktor.utils.io.ByteReadChannel
import splice.core.perf.OutcomeTag
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.Cancellables
import splice.upstream.BuiltTurn
import splice.upstream.ClientGone
import splice.upstream.Provider
import splice.upstream.TurnSignals
import splice.upstream.WatchdogProbe
import splice.upstream.sse.SseReader

/** What the translator made of one stream: the usage it reported, or null, and the outcome tag the row carries. */
internal data class ReadSend(val usage: Usage?, val outcome: String)

internal class PlaygroundUsage {
    /** [stream] is the whole body of a successful send. A translator that throws is a send whose tokens are unknown:
     *  its row is written without them and says the upstream failed, since nothing it said could be read. */
    suspend fun read(provider: Provider, turn: BuiltTurn, stream: String): ReadSend {
        val events = SseReader().sseJsonEvents(ByteReadChannel(stream.toByteArray()))
        val signals = TurnSignals(WatchdogProbe { null }, ClientGone { false })
        return Cancellables.runCatchingCancellable {
            provider.streamTranslator(turn.meta, signals).driveTurn(events, DiscardingSink())
        }.fold(onSuccess = ::readOf, onFailure = { ReadSend(null, OutcomeTag.UPSTREAM_FAILED.wire) })
    }

    private fun readOf(outcome: TurnOutcome): ReadSend = when (outcome) {
        is TurnOutcome.Success -> ReadSend(reported(outcome.usage), OutcomeTag.OK.wire)
        is TurnOutcome.Failure -> ReadSend(reported(outcome.salvagedUsage), OutcomeTag.UPSTREAM_FAILED.wire)
        is TurnOutcome.ClientAbandoned -> ReadSend(reported(outcome.salvagedUsage), OutcomeTag.CLIENT_ABORT.wire)
        else -> ReadSend(null, OutcomeTag.OK.wire)
    }

    private fun reported(usage: Usage): Usage? = usage.takeIf { it.reported.isNotEmpty() }
}
