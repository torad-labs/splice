// PORT-OF: server/src/codex/stream.mjs runStreamTurn @ pre-public-port-baseline, event-for-event — its comments
// are the spec; every rule below pins a shipped bug:
//   - tool_use opens EAGERLY on output_item.added; text/reasoning open LAZILY on first delta
//     (empty thinking widgets otherwise);
//   - reasoning summary PARTS join with "\n\n" into ONE thinking block; closing per part was
//     v24's truncation bug — blocks close only on output_item.done / the end sweep;
//   - outside sequential_cutoff, *_text.done / *_part.done are IGNORED (fire per part); cutoff
//     renders reasoning_summary_text.done atomically and ignores its deltas;
//   - tool args stream as input_json_delta on the SAME wire block index;
//   - failure events are captured and the loop CONTINUES (the terminal decision happens after);
//   - replay (gated) emits redacted_thinking IN POSITION right after its item closes;
//   - harvest fallback merges the terminal object's text/thinking when deltas were sparse
//     (weak-text preference rules);
//   - honest failures: upstreamFailure -> classified; watchdog-fired -> overloaded; stream end
//     without response.completed -> ClientAbandoned if the client is gone, else truncated.
// RESPONSIBILITY SPLIT (pinned P2-MACH slot note): promote-to-text, empty-compact/empty-model
// honesty, mirror, and terminal emission live in the GATEWAY pipeline; buffers ride
// TurnOutcome.Success.
//
// DECOMPOSITION (HD-24, f875801): the event fold, the terminal decision and the per-turn state
// moved to siblings in this package (ResponsesEventReducer, ResponsesItemFold,
// ResponsesReasoningFold, ResponsesReasoningReplay, ResponsesTerminalDecision,
// ResponsesOutcomePayload, ResponsesTerminalBackfill, ResponsesTurnState, ResponsesBlocks,
// SummaryDedup, ResponsesFrameParse, ResponsesToolSearchParse, StreamTurnContext). This file keeps
// only the SPI entry point: drive the stream, then hand the accumulated state to the terminal
// decision.
package splice.dialect.responses.stream

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.JsonObject
import splice.core.turn.SharedSummaryParts
import splice.core.turn.TurnOutcome
import splice.dialect.responses.ResponsesTurnState
import splice.dialect.responses.StreamTurnContext
import splice.dialect.responses.reasoning.ResponsesReasoningFold
import splice.dialect.responses.reasoning.ResponsesReasoningReplay
import splice.upstream.StreamTranslator
import splice.upstream.failure.SseFrameTooLargeException
import splice.upstream.sse.WireSink
import splice.upstream.transport.BufferCapacity
import splice.upstream.transport.StreamTornBeforeClient
import java.io.IOException
import java.util.concurrent.CancellationException

// NF-06 runaway-upstream guard message; the cap lives in spi.BufferCapacity (one source, three dialects).
private const val RUNAWAY_GUARD_MESSAGE = "upstream: response exceeded max buffered size — aborting"

public class ResponsesStreamTranslator(private val ctx: StreamTurnContext) : StreamTranslator {

    // NF-06: latched when BufferCapacity trips; never provider-reported (the verdict is local).
    private var runawayGuard: String? = null

    /** V4-116: the unrecognised throwable the generic catch swallowed, if any — see [relabelUnrecognised]
     *  for why this dialect records it and rewrites the sentence afterwards instead of branching
     *  inside its own terminal decision the way the chat and passthrough twins do. */
    private var unexpected: RuntimeException? = null

    override suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome =
        if (ctx.dedupeRepeatedSummaryParts) {
            // One lease + lock for the COMPLETE translator round, never the delta hot loop. A
            // conversation entry cannot expire while this round waits or runs.
            ctx.summaryRoundScope.withRound { summaryParts -> driveRound(upstream, sink, summaryParts) }
        } else {
            driveRound(upstream, sink, ctx.summaryPartsShared)
        }

    private suspend fun driveRound(
        upstream: Flow<JsonObject>,
        sink: WireSink,
        summaryParts: SharedSummaryParts,
    ): TurnOutcome {
        val state = ResponsesTurnState()
        val reasoningFold = ResponsesReasoningFold(ctx, state, summaryParts)
        val replay = ResponsesReasoningReplay(ctx, state)
        val itemFold = ResponsesItemFold(state, reasoningFold, replay)
        val reducer = ResponsesEventReducer(state, itemFold, reasoningFold)
        // Stream read errors surface via the terminal decision, never a crash; only a genuine
        // cancellation (no watchdog fire) is allowed to propagate.
        try {
            upstream
                .takeWhile {
                    // NF-06: reasoning envelopes and function arguments accumulate outside the
                    // rendered text buffers. Saturate their sum before the Int-shaped shared guard;
                    // block count separately bounds an upstream that opens indexes without closing.
                    val envelopeChars = state.reasoningEnvelopes.sumOf { it.length.toLong() }
                    val pendingArgsChars = minOf(
                        Int.MAX_VALUE.toLong(),
                        envelopeChars + state.bufferedToolArgsChars,
                    ).toInt()
                    val withinCapacity = !BufferCapacity.over(
                        state.textBuf.length,
                        state.thinkingBuf.length,
                        toolIndexCount = state.blocks.size,
                        pendingArgsLen = pendingArgsChars,
                    )
                    if (!withinCapacity) runawayGuard = RUNAWAY_GUARD_MESSAGE
                    withinCapacity
                }
                .collect { evt -> reducer.onEvent(evt, sink) }
        } catch (e: CancellationException) {
            if (ctx.watchdogFired() == null) throw e
        } catch (ignored: IOException) {
            // upstream read error: fall through to the honest terminal decision
        } catch (ignored: RuntimeException) {
            // V4-116, OPERATOR RULING 2026-09-18 "RETRY DEFAULT IS TOTAL": THE GENERIC FALLTHROUGH.
            // The named arms above are a classifier with only KNOWN cells; SerializationException
            // and IllegalArgumentException both extend RuntimeException, so one arm covers them plus
            // every failure class this dialect has never seen. An unrecognised throwable used to
            // ESCAPE, reaching the turn boundary with no partial — the same unrecoverable shape as
            // the stall scar, for a class nobody had enumerated. Once content is on the wire the
            // salvage is the only thing that makes a round resumable, so this arm keeps the collect
            // loop's failure INSIDE the round and lets the same terminal decision judge it.
            // AND IT ONLY APPLIES MID-STREAM. Before the client has seen content there is nothing
            // to salvage, and an escaping throwable already gets MORE retry than this arm can give
            // it: the G5 reissue budget, the WS overlay's NeedsSse fallback and the connect-phase
            // budgets all live above this seam and key off the exception class. Swallowing a
            // pre-content throw would starve every one of them, and would also lose the
            // conn-reset provenance SseRoundDriver.tearOutcome and TurnConnEnd exist to carry —
            // which is what a first pass at this arm did, and what its tests caught.
            if (isSpiTransportSignal(ignored) || !clientSawContent(state)) throw ignored
            unexpected = ignored
        }

        latchSweptToolBlocks(state)
        sink.closeAll()
        ResponsesTerminalBackfill().harvestFallback(state)
        val outcome = ResponsesTerminalDecision(ctx, ResponsesOutcomePayload(ctx)).terminalOutcome(state, runawayGuard)
        captureTurnReasoning(state, outcome)
        return relabelUnrecognised(outcome)
    }

    /** V4-116: an unrecognised throwable says so in its own words.
     *
     *  The chat and passthrough twins branch inside their own `unfinishedOutcome`, which they own.
     *  This dialect does not own its version of that sentence — the terminal decision lives in
     *  ResponsesTerminalDecision — so the same correction is applied here, one step later and
     *  WITHOUT moving the decision: the round's states, precedence and salvage are untouched, and
     *  only the words the client reads are repaired.
     *
     *  "truncated" is a DIAGNOSIS, and reporting an undiagnosed failure under one is exactly the
     *  mislabelling the generic arm exists to avoid. The gate is [ctx.watchdogFired] being null: a
     *  fired watchdog owns its own verdict and its own sentence (see the idle/total-cap split in
     *  ResponsesTerminalDecision.watchdogOutcome), and rewriting that one would hide why the turn
     *  really ended. `Throwable.toString()` is the repo's own diagnostic rendering (TurnEnding uses
     *  the same form), so a bug of ours stays tellable from an upstream failure we have never seen.
     *
     *  Two guard clauses rather than one joined condition: each names a single way the sentence must
     *  be left alone, which is also what keeps the condition under the complexity wall. */
    /** The SPI failures that already have a turn-boundary owner, so the generic catch must pass
     *  them through untouched (see that arm for why each one is in the set). ONE definition per
     *  dialect so the set cannot be widened in one arm and forgotten in the next. */
    /** V4-116: has the client already been shown content this round? The generic catch is
     *  mid-stream-only, so this is the gate that decides whether a failure is OURS to
     *  salvage or the upper layers' to retry. Mirrors what the partial carries. */
    private fun clientSawContent(state: ResponsesTurnState): Boolean =
        state.emittedText || state.emittedThinking

    private fun isSpiTransportSignal(e: RuntimeException): Boolean =
        e is StreamTornBeforeClient || e is SseFrameTooLargeException

    private fun relabelUnrecognised(outcome: TurnOutcome): TurnOutcome {
        val failure = outcome as? TurnOutcome.Failure ?: return outcome
        val e = unexpected ?: return outcome
        return if (ctx.watchdogFired() == null) {
            failure.copy(message = "splice: upstream stream failed ($e) — retry")
        } else {
            outcome
        }
    }

    /** DR-106: the closeAll sweep is a THIRD tool-block close path — an upstream that streamed
     *  partial arguments and then went straight to response.completed (no arguments.done, no
     *  output_item.done) had the block swept closed with NO CX-01 validation, grading a clean
     *  Success that hands the client truncated tool JSON. Latch before the sweep; same latch,
     *  same first-reason-wins as the arguments.done and output_item.done paths. Two scope gates,
     *  each pinned by a neighbor: a stream with no completed response is the TEAR path
     *  (ToolSalvage owns it — latching here nulled the poison-tear partial), and a completed
     *  block that never saw an args event keeps its pre-fix Success (the undeclared-tool capture
     *  pin; the .done paths still latch {} when a done event arrives). */
    private fun latchSweptToolBlocks(state: ResponsesTurnState) {
        if (state.finalResponse == null) return
        val frames = ResponsesFrameParse()
        for (open in state.blocks.values) {
            val sweptWithArgs = open.tool && open.sawDelta
            if (sweptWithArgs && state.toolArgsInvalid == null) {
                state.toolArgsInvalid = frames.invalidToolArgsReason(open.args.toString())
            }
        }
    }

    /** RC-1 capture: only a SUCCESSFUL tool-use round seeds the reasoning cache — the client
     *  will come back with these tool ids and the injection needs the plan that produced them. */
    private fun captureTurnReasoning(state: ResponsesTurnState, outcome: TurnOutcome) {
        if (outcome !is TurnOutcome.Success || !outcome.hasToolUse) return
        if (state.turnToolIds.isEmpty() || state.reasoningEnvelopes.isEmpty()) return
        ctx.onTurnReasoning(state.turnToolIds.toList(), state.reasoningEnvelopes.toList())
    }
}
