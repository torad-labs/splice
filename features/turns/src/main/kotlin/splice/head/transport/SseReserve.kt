// NEW: V4-114 (2026-10-08) — whether a websocket round is re-served over SSE, as a value (kt-no-exception-as-outcome).
//
// The decision used to be a RoundNeedsSse thrown inside the round's collection and caught one frame below. It is taken
// here instead, inside the collection, which then ends without handing the translator the deciding event, and read after
// the collection ends. Its own unit so the policy and its imports stay out of WsRoundDrive's bill (ConcentrationLawTest).
package splice.head.transport

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.upstream.WsRoundRunner
import splice.upstream.sse.WireSink

/** Long enough for a code and a sentence, short enough that one server message stays one line. */
private const val FAILURE_DETAIL_MAX_CHARS = 240

private val oneLine = Regex("\\s+")

/** Whether one round is sent to SSE, and why. Taken once, inside the round's collection, and read after it ends. */
internal class SseReserve {
    var detail: String? = null
        private set

    /** A failure terminal before any client frame sends the round to SSE; true when [evt] did. A policy refusal is
     *  not re-served: over SSE the identical context met the identical refusal, so the round's translator ends the
     *  turn on it instead (WsFailureTerminal). */
    fun event(evt: JsonObject, runner: WsRoundRunner, inputs: WsRoundInputs): Boolean {
        if (!runner.isFailureTerminal(evt) || inputs.frameEmittedThisRound()) return false
        val failure = WsFailureTerminal(evt)
        if (failure.policyRefusal()) return false
        detail = oneLined(
            listOf(JsonScalars.strOrEmpty(evt["type"]), failure.code, failure.message)
                .filter { it.isNotEmpty() }
                .joinToString(" "),
        )
        return true
    }

    /** V4-242: a round [torn] before the client saw anything of it is re-served too, named by the tear's words
     *  (PreContentTear); true when it is one. */
    fun tear(torn: Throwable, inputs: WsRoundInputs): Boolean {
        val words = PreContentTear.words(torn, inputs) ?: return false
        detail = oneLined(words)
        return true
    }

    /** The client sink for a round that may be re-served: closing is the one write a translator makes on a flow
     *  that ended with no terminal, and it must not reach a client whose turn SSE is about to serve. */
    fun gate(inner: WireSink): WireSink = GatedSink(inner, this)

    private fun oneLined(text: String): String = text.replace(oneLine, " ").take(FAILURE_DETAIL_MAX_CHARS)
}

private class GatedSink(private val inner: WireSink, private val reserve: SseReserve) : WireSink by inner {
    override suspend fun closeAll() {
        if (reserve.detail == null) inner.closeAll()
    }
}
