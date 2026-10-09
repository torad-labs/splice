// NEW: per-round WS bookkeeping (slot/watchdog/perf/translator/zero-event).
// Split from WsRoundDriver (concentration, 2026-08-19) so neither file is
// billed for the other's subsystems. Same-package.
package splice.head.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.JsonObject
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.head.turn.TurnDrive
import splice.head.turn.ZeroEventClassifier
import splice.upstream.Provider
import splice.upstream.TurnSignals
import splice.upstream.WsRound
import splice.upstream.WsRoundRunner

internal class WsRoundDrive(
    private val provider: Provider,
    private val classifyZeroEvent: ZeroEventClassifier,
) {
    /** Observe one response boundary, never update readiness for each streamed delta or body completion. */
    fun startingEvents(
        round: WsRound,
        runner: WsRoundRunner,
        drive: TurnDrive,
        answer: WsRoundAnswer,
    ): Flow<JsonObject> {
        var observed = false
        return round.events.onEach { event ->
            if (!observed) {
                val accepted = when {
                    runner.isFailureTerminal(event) -> false
                    JsonScalars.strOrEmpty(event["type"]) == "response.created" -> true
                    else -> null
                }
                if (accepted != null) {
                    observed = true
                    answer.observed(accepted)
                }
            }
            drive.emitter.ensureStarted()
            drive.trace?.responseText(JsonWire.string(event) + "\n")
        }
    }

    /** The per-round bookkeeping mirrors the SSE path exactly — slot touch, first-byte/events
     *  perf, zero-event classification — because the client must not be able to tell which
     *  transport served its turn. An EVENT touches the slot (liveness) but never picks the watchdog
     *  tier: response.created is the handshake, not output, and this is the transport on which the
     *  2026-09-01 compaction stalls were measured — see Watchdog.kt. */
    suspend fun drive(
        inputs: WsRoundInputs,
        runner: WsRoundRunner,
        events: Flow<JsonObject>,
    ): WsRoundResult {
        val drive = inputs.drive
        // No raw-text capture on this path: ZeroEventCapture's snippet exists to classify a
        // non-SSE dead-head BODY (an HTML login page arriving where SSE was expected), and a
        // WebSocket round has no body to misread. An empty snippet makes the classifier keep the
        // translator's own verdict, which is the honest answer here.
        // V4-242: a round the upstream TORE before any client frame is re-served over SSE too
        // (PreContentTear). Caught here, upstream of the translator, because the translator folds an
        // I/O failure into its honest terminal.
        val reserve = SseReserve()
        val instrumented = events
            .catch { torn -> if (!reserve.tear(torn, inputs)) throw torn }
            .transformWhile { evt ->
                drive.slot.received()
                UpstreamProgress.observe(evt, drive.watchdog)
                val reserved = reserve.event(evt, runner, inputs)
                if (!reserved) {
                    drive.perf.markOnce(PerfKeys.FIRST_BYTE)
                    drive.perf.add(PerfKeys.EVENTS_IN, 1)
                    emit(evt)
                }
                !reserved
            }
        val signals = TurnSignals(
            watchdogFired = { drive.watchdog.fired },
            clientGone = { inputs.clientGone() },
        )
        // V4-114: the re-serve decision is a VALUE ([SseReserve]), taken inside the collection, which then
        // ends without handing the translator the deciding event. The three statements after driveTurn are
        // exactly the ones a round about to be re-served over SSE must NOT run: the perf mark, the zero-event
        // classification and roundEnded, which commits the chaining state. The translator still sees an
        // ended flow, so [SseReserve.gate] keeps its closing call off the client: it must author no terminal, or
        // the client would see that terminal followed by the SSE round's content.
        val raw = provider.streamTranslator(drive.meta, signals).driveTurn(instrumented, reserve.gate(inputs.sink))
        reserve.detail?.let { return WsRoundResult.NeedsSse(it) }
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
        return WsRoundResult.Streamed(outcome)
    }
}

/**
 * How a WebSocket round ended, as a value [WsRoundDriver] branches on.
 *
 * V4-114 (kt-no-exception-as-outcome): [NeedsSse] used to be a thrown `splice.spi.WsRoundNeedsSse`
 * — a head-internal control-flow signal declared in the provider SPI, where nothing threw or caught
 * it, travelling up through every broad catch on the turn path with no signature announcing it. The
 * fallback is an ORDINARY, expected ending of this round, so it rides the return type.
 */
internal sealed class WsRoundResult {
    /** The round was served over the WebSocket; [outcome] is the turn's answer. */
    data class Streamed(val outcome: TurnOutcome) : WsRoundResult()

    /** The round failed before the client saw any frame, so it is re-served over SSE with the full
     *  recovery machinery. [detail] is the server's failure terminal (type, code, message), or the
     *  transport's tear in its deepest words (V4-242). */
    data class NeedsSse(val detail: String) : WsRoundResult()
}
