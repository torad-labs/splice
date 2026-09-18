// NEW: (no Node source): OpenAI Chat Completions SSE → Anthropic SSE via the shared WireSink.
// Chat streaming shape: each frame is {choices:[{delta:{content?, reasoning_content?,
// tool_calls?}, finish_reason?}], usage?}. Text opens lazily on first content delta; a
// reasoning_content field (DeepSeek-style) opens a thinking block; tool_calls stream by index
// (delta.tool_calls[i].function.arguments). Same honesty gates as Responses: no clean end on a
// failure, ClientAbandoned when the client is gone before any finish. finish_reason maps:
// tool_calls→hasToolUse, length→incomplete, stop→end_turn.
//
// HD-24 decomposition (2026-08-17): the frame-shape knowledge, honesty state machine, tool-call
// buffering and usage accounting now live on collaborators (ChatEventRouter, ChatTerminalState,
// ChatToolCalls/ChatToolFrame/ChatFinalToolFold, ChatUsage, ChatProseChannels/ChatProseFold) in
// this same package. This file keeps exactly what the StreamTranslator contract owns: collect the
// flow, run the NF-06 buffer check, flush pending tools, close the sink, and map the terminal.
package splice.dialect.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.JsonObject
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.spi.BufferCapacity
import splice.spi.SseFrameTooLargeException
import splice.spi.StreamTornBeforeClient
import splice.spi.StreamTranslator
import splice.spi.TerminalStates
import splice.spi.WatchdogFired
import splice.spi.WireSink
import java.io.IOException
import java.util.concurrent.CancellationException

// WIRE-2/3/6 runaway-upstream guard message; the cap itself lives in spi.BufferCapacity (NF-06:
// one definition, three dialects).
private const val RUNAWAY_GUARD_MESSAGE = "chat backend: response exceeded max buffered size — aborting"

public class ChatStreamTranslator(private val ctx: ChatTurnContext) : StreamTranslator {

    /** V4-116: the unrecognised throwable the generic catch swallowed, if any. Recorded rather than
     *  turned into an outcome on the spot because the terminal decision must run AFTER the sink is
     *  closed — the wire has to be at a clean block boundary before anything says what happened. */
    private var unexpected: RuntimeException? = null

    private val channels = ChatProseChannels()
    private val toolCalls = ChatToolCalls(ChatToolFrame(), channels)
    private val terminal = ChatTerminalState(toolCalls)
    private val usage = ChatUsage()
    private val router = ChatEventRouter(channels, toolCalls, ChatFinalToolFold(toolCalls), terminal, usage)

    override suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome {
        try {
            upstream
                .takeWhile {
                    // Tool args accumulate both before deferred opens and after explicit-index tools
                    // open; the guard counts both retained surfaces. Stop collection at the cap so
                    // the producer and its upstream response unwind too.
                    val withinCapacity = !BufferCapacity.over(
                        channels.textBuf.length,
                        channels.thinkingBuf.length,
                        toolIndexCount = toolCalls.retainedIndexEntryCount,
                        pendingArgsLen = toolCalls.bufferedArgsChars,
                    )
                    if (!withinCapacity) terminal.runawayGuard = RUNAWAY_GUARD_MESSAGE
                    withinCapacity
                }
                .collect { evt -> router.onEvent(evt, sink) }
        } catch (e: CancellationException) {
            // Only a watchdog fire may swallow cancellation; a real cancel propagates.
            if (ctx.watchdogFired() == null) throw e
        } catch (ignored: IOException) {
            // stream read error: surface via the honest terminal decision, never a crash
        } catch (ignored: RuntimeException) {
            // V4-116, OPERATOR RULING 2026-09-18 "RETRY DEFAULT IS TOTAL": THE GENERIC FALLTHROUGH.
            // The named arms above are a classifier with only KNOWN cells; SerializationException
            // and IllegalArgumentException had arms of their own and are both RuntimeExceptions, so
            // one arm covers them plus every failure class this dialect has never seen. An
            // unrecognised throwable used to ESCAPE the translator, which lost the salvage and made
            // the round unrecoverable by construction — and the salvage is the only thing that
            // makes a post-content failure resumable. Nothing is mislabelled: the sentence names the
            // throwable's own class and text (Throwable.toString(), the TurnEnding idiom), and
            // providerReported keeps its default false, the attribution it had when it escaped.
            // AND IT ONLY APPLIES MID-STREAM. Before the client has seen content there is nothing
            // to salvage, and an escaping throwable already gets MORE retry than this arm can give
            // it: the G5 reissue budget, the WS overlay's NeedsSse fallback and the connect-phase
            // budgets all live above this seam and key off the exception class. Swallowing a
            // pre-content throw would starve every one of them, and would also lose the
            // conn-reset provenance SseRoundDriver.tearOutcome and TurnConnEnd exist to carry —
            // which is what a first pass at this arm did, and what its tests caught.
            if (isSpiTransportSignal(ignored) || !clientSawContent()) throw ignored
            unexpected = ignored
        }
        toolCalls.flushPendingTools(sink)
        // CX-01: parse each opened tool's accumulated args at terminal; a corrupt/empty tool call
        // becomes a Failure in terminalOutcome, never a Success with a malformed tool_use.
        if (toolCalls.toolArgsInvalid == null) toolCalls.toolArgsInvalid = toolCalls.firstInvalidToolArgs()
        sink.closeAll()
        return terminalOutcome()
    }

    // Ordering enforced by the shared spi.terminalPrecedence (a FINISHED turn beats a late
    // watchdog fire — the poller can sit on the socket-EOF read AFTER finish_reason arrived).
    private fun terminalOutcome(): TurnOutcome = TerminalStates(
        providerFailure = terminal.providerFailure(),
        finished = terminal.finished,
        watchdogFired = ctx.watchdogFired(),
    ).terminalPrecedence(
        onFinished = ::successOutcome,
        onWatchdog = ::stalledOutcome,
        onUnfinished = ::unfinishedOutcome,
    )

    /** V4-116 (1): A STALL CARRIES THE SAME SALVAGE A TRUNCATION DOES — this dialect's half of the
     *  scar. The watchdog branch used to build a Failure with a bare message and no `partial`, so
     *  `ReanchorController.continuationForFailure` answered null at its first line before reading a
     *  single eligibility rule, and a stalled round was unrecoverable BY CONSTRUCTION. A stall and a
     *  truncation are the same fact (the upstream stopped talking after delivering content), so they
     *  are the same Failure, and the controller decides recoverability — never this branch.
     *
     *  The stall line names the TIER and the number it compared; "upstream stalled" alone left the
     *  operator unable to tell a 300s mid-output verdict from a 20s stall-tier one. */
    private fun stalledOutcome(fired: WatchdogFired): TurnOutcome = TurnOutcome.Failure(
        ErrorType.OVERLOADED,
        "chat: ${stallDetail(fired)}; retry",
        partial = partialRound(),
    )

    private fun unfinishedOutcome(): TurnOutcome =
        if (ctx.clientGone()) {
            TurnOutcome.ClientAbandoned()
        } else {
            // The SALVAGE, and the reason a truncation is recoverable at all: without it `partial`
            // defaults to null, which reads as "this dialect cannot continue" — so the connect-phase
            // and G5 budgets sat unused behind a 2xx that EOFed early.
            TurnOutcome.Failure(
                ErrorType.OVERLOADED,
                // An UNRECOGNISED throwable says so in its own words rather than borrowing the
                // truncation sentence: "truncated" is a diagnosis, and reporting an undiagnosed
                // failure under one is the mislabelling the generic arm exists to avoid.
                unexpected?.let { "chat: upstream stream failed ($it) — retry" }
                    ?: "chat: stream ended without a finish_reason (truncated); retry",
                partial = partialRound(),
            )
        }

    /** The SPI failures that already have a turn-boundary owner, so the generic catch must pass
     *  them through untouched (see that arm for why each one is in the set). ONE definition per
     *  dialect so the set cannot be widened in one arm and forgotten in the next. */
    /** V4-116: has the client already been shown content this round? The generic catch is
     *  mid-stream-only, so this is the gate that decides whether a failure is OURS to
     *  salvage or the upper layers' to retry. Mirrors what the partial carries. */
    private fun clientSawContent(): Boolean = channels.emittedText || channels.emittedThinking

    private fun isSpiTransportSignal(e: RuntimeException): Boolean =
        e is StreamTornBeforeClient || e is SseFrameTooLargeException

    private fun stallDetail(fired: WatchdogFired): String = when (fired) {
        is WatchdogFired.Idle ->
            "upstream silent ${fired.idleMs / MS_PER_S}s past the ${fired.limitMs / MS_PER_S}s " +
                "${if (fired.sawClientFrame) MID_OUTPUT_TIER else FIRST_OUTPUT_TIER} tier"
        is WatchdogFired.TotalCap ->
            "upstream silent past the ${fired.elapsedMs / MS_PER_S}s total cap"
    }

    /** What this round produced before it died, for [splice.spi.ReanchorController]. Mirrors
     *  [successOutcome]'s reads so a continuation and a success see the SAME buffers. [toolTearOpen]
     *  is left false on purpose: this dialect buffers tool arguments and flushes them before the
     *  terminal, so a raised [ChatToolCalls.hasToolUse] is already the fact that refuses a
     *  continuation (the controller refuses an open OR committed tool round both ways). */
    private fun partialRound(): TurnOutcome.PartialRound = TurnOutcome.PartialRound(
        thinkingText = channels.thinkingBuf.toString(),
        bodyText = channels.textBuf.toString(),
        emittedText = channels.emittedText,
        emittedThinking = channels.emittedThinking,
        hasToolUse = toolCalls.hasToolUse,
        usage = usage.toUsage(),
    )

    private fun successOutcome(): TurnOutcome = TurnOutcome.Success(
        hasToolUse = toolCalls.hasToolUse,
        incomplete = terminal.incomplete,
        usage = usage.toUsage(),
        thinkingText = channels.thinkingBuf.toString(),
        bodyText = channels.textBuf.toString(),
        emittedText = channels.emittedText,
        emittedThinking = channels.emittedThinking,
    )
}

// The two tier names this translator can be judged by (see [stallDetail]). FILE SCOPE ON PURPOSE:
// one spelling each, so a log line and a client-visible sentence cannot name the same tier twice.
private const val MID_OUTPUT_TIER = "mid-output"
private const val FIRST_OUTPUT_TIER = "first-output"
private const val MS_PER_S = 1000L
