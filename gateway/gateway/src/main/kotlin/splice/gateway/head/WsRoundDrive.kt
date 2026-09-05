// NEW: per-round WS bookkeeping (slot/watchdog/perf/translator/zero-event).
// Split from WsRoundDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.gateway.head

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.spi.Provider
import splice.spi.TurnSignals
import splice.spi.WsRoundNeedsSse
import splice.spi.WsRoundRunner

internal class WsRoundDrive(
    private val provider: Provider,
    private val classifyZeroEvent: ZeroEventClassifier,
) {
    /** The per-round bookkeeping mirrors the SSE path exactly — slot touch, first-byte/events
     *  perf, zero-event classification — because the client must not be able to tell which
     *  transport served its turn. An EVENT touches the slot (liveness) but never picks the watchdog
     *  tier: response.created is the handshake, not output, and this is the transport on which the
     *  2026-09-01 compaction stalls were measured — see Watchdog.kt. */
    suspend fun drive(
        inputs: WsRoundInputs,
        runner: WsRoundRunner,
        events: Flow<JsonObject>,
    ): TurnOutcome {
        val drive = inputs.drive
        // No raw-text capture on this path: ZeroEventCapture's snippet exists to classify a
        // non-SSE dead-head BODY (an HTML login page arriving where SSE was expected), and a
        // WebSocket round has no body to misread. An empty snippet makes the classifier keep the
        // translator's own verdict, which is the honest answer here.
        val instrumented = events.onEach { evt ->
            if (runner.isFailureTerminal(evt) && !inputs.frameEmittedThisRound()) {
                throw WsRoundNeedsSse(failureDetail(evt))
            }
            drive.slot.touch()
            drive.perf.markOnce(PerfKeys.FIRST_BYTE)
            drive.perf.add(PerfKeys.EVENTS_IN, 1)
        }
        val signals = TurnSignals(
            watchdogFired = { drive.watchdog.fired },
            clientGone = { drive.channel.clientGone.get() },
        )
        val raw = provider.streamTranslator(drive.meta, signals).driveTurn(instrumented, inputs.sink)
        drive.perf.mark(PerfKeys.STREAM_END)
        // The report is the LAST statement, so it and the return are atomic from WsRoundDriver's
        // point of view: that caller sets `reported` only once drive() returns, and its finally
        // reports ok=false when the flag is unset. Anything thrown between a report and the return
        // would therefore report the round twice — ok=true, then ok=false clearing the chaining
        // state for a round that completed (review 2026-08-28, PR 99). Unreachable today, since
        // classifyZeroEvent returns early on the blank snippet this path always passes; the next
        // edit to it is what would make the gap real. The WsRoundNeedsSse path is unaffected: that
        // throw fires inside driveTurn's onEach, upstream of both statements either way.
        val outcome = classifyZeroEvent(drive, raw, "", inputs.eventsBase)
        // ok means "a clean, fully-consumed terminal" — WsRoundRunner.roundEnded's own words — and
        // this used to report it for ANY return, a FAILURE outcome included. A round that ended in
        // a failure terminal, or that the zero-event classifier reclassified into one, still
        // committed its chain, so the next turn anchored onto a response the server never finished
        // building. That is the exact corruption the ok flag exists to prevent, reported by the one
        // caller that always said yes (DR-7, grok-splice's WS map). A Success with incomplete=true
        // is still clean: the server finished the response object, it just stopped early.
        runner.roundEnded(drive.meta, ok = outcome is TurnOutcome.Success)
        return outcome
    }

    /** The failure terminal's type and the error it carried, in every shape the dialect's reducer
     *  reads (ResponsesEventReducer.onFailure): an object under `response.error` or `error`, the
     *  flat event whose own `code`/`message` are the error, or a plain-string `error` (DR-109).
     *  Folded onto one line and clipped so a verbose server message cannot flood the log. */
    private fun failureDetail(evt: JsonObject): String {
        val carried = (evt["response"] as? JsonObject)?.get("error") ?: evt["error"]
        val error = carried as? JsonObject ?: evt
        val code = JsonScalars.strOrEmpty(error["code"])
            .ifEmpty { if (error === evt) "" else JsonScalars.strOrEmpty(error["type"]) }
        val message = JsonScalars.strOrEmpty(error["message"]).ifEmpty { JsonScalars.strOrEmpty(carried) }
        return listOf(JsonScalars.strOrEmpty(evt["type"]), code, message)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(oneLine, " ")
            .take(FAILURE_DETAIL_MAX_CHARS)
    }

    private val oneLine = Regex("\\s+")
}

/** Long enough for a code and a sentence, short enough that one server message stays one line. */
private const val FAILURE_DETAIL_MAX_CHARS = 240
