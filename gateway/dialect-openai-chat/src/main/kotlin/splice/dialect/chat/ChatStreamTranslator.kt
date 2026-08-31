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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.spi.BufferCapacity
import splice.spi.StreamTranslator
import splice.spi.TerminalStates
import splice.spi.WireSink
import java.io.IOException
import java.util.concurrent.CancellationException

// WIRE-2/3/6 runaway-upstream guard message; the cap itself lives in spi.BufferCapacity (NF-06:
// one definition, three dialects).
private const val RUNAWAY_GUARD_MESSAGE = "chat backend: response exceeded max buffered size — aborting"

public class ChatStreamTranslator(private val ctx: ChatTurnContext) : StreamTranslator {

    private val channels = ChatProseChannels()
    private val toolCalls = ChatToolCalls(ChatToolFrame())
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
        } catch (ignored: SerializationException) {
            // malformed upstream frame: surface via the honest terminal decision
        } catch (ignored: IllegalArgumentException) {
            // malformed value in a frame: surface via the honest terminal decision
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
        onWatchdog = { TurnOutcome.Failure(ErrorType.OVERLOADED, "chat: upstream stalled — aborted; retry") },
        onUnfinished = {
            if (ctx.clientGone()) {
                TurnOutcome.ClientAbandoned()
            } else {
                TurnOutcome.Failure(
                    ErrorType.OVERLOADED,
                    "chat: stream ended without a finish_reason (truncated); retry",
                )
            }
        },
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
