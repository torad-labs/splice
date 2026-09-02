// NEW: (no Node source) upstream Anthropic Messages SSE -> shared WireSink. Kimi's /coding surface
// already speaks the Anthropic event grammar, so this is a near-passthrough that only re-indexes
// blocks onto the sink and enforces two subtle contracts:
//   1. SIGNATURE SYNTHESIS EXACTLY-ONCE, and only when the head asks for it
//      (PassthroughQuirks.synthesizeSignatures): Claude Code silently discards a response whose
//      thinking blocks never receive a signature_delta, and Kimi never sends one. We forward an
//      upstream signature if it arrives, else synthesize ONE at block close — never both. An
//      upstream that SIGNS and VERIFIES leaves this off: a block truncated before its signature
//      would otherwise persist a forged signature into the transcript and hand it back next turn.
//      (PassthroughBlockRegistry — registry, latch and open/close are one file for that reason.)
//   2. USAGE NORMALIZATION: Anthropic usage is already disjoint (input excludes cache), but
//      HeadServer's generic payload builder subtracts cachedTokens from inputTokens (OpenAI
//      inclusive convention). So we pre-add the cache buckets back into inputTokens and report
//      cachedTokens = cache_read, making the downstream subtraction reproduce the disjoint numbers.
//      (PassthroughUsage.)
// L3 honesty is identical to the chat translator: a truncated/failed stream is a retryable Failure,
// never a clean success; ClientAbandoned when the client vanished before any finish.
//
// HD-25 decomposition (2026-08-18): the frame-shape knowledge, block/signature invariant, honesty
// state machine, prose buffers and usage accounting now live on collaborators (PassthroughEventRouter,
// PassthroughBlockRegistry/PassthroughBlocks, PassthroughTerminalState, PassthroughProseChannels,
// PassthroughUsage) in this same package. This file keeps exactly what the StreamTranslator contract
// owns: collect the flow, run the NF-06 buffer check, close the sink, and map the terminal.
package splice.dialect.passthrough

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

public class PassthroughStreamTranslator(
    private val ctx: PassthroughTurnContext,
    private val quirks: PassthroughQuirks,
) : StreamTranslator {

    private val channels = PassthroughProseChannels()
    private val blocks = PassthroughBlockRegistry(ctx, quirks, channels)
    private val terminal = PassthroughTerminalState(quirks, blocks)
    private val usage = PassthroughUsage()
    private val router = PassthroughEventRouter(blocks, terminal, usage)

    override suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome {
        try {
            upstream
                // NF-06: the shared runaway valve Chat already had. takeWhile (not a skip inside
                // collect) so the first breach CANCELS collection and Flow unwinds the upstream —
                // otherwise a still-streaming upstream keeps the turn slot and quota live until it
                // chooses to close, with every later event consumed and thrown away. Count the block
                // registry and tool arguments too — neither lives in the prose buffers.
                .takeWhile {
                    val withinCapacity = !BufferCapacity.over(
                        channels.textBuf.length,
                        channels.thinkingBuf.length,
                        toolIndexCount = blocks.openBlockCount,
                        pendingArgsLen = blocks.bufferedToolArgsChars,
                    )
                    if (!withinCapacity) terminal.latchRunawayGuard()
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
        sink.closeAll()
        return terminalOutcome()
    }

    // Ordering enforced by the shared spi.terminalPrecedence (a FINISHED turn beats a late
    // watchdog fire — preferring watchdog here discarded successful kimi turns and burned quota).
    private fun terminalOutcome(): TurnOutcome = TerminalStates(
        providerFailure = terminal.providerFailure(),
        finished = terminal.finished,
        watchdogFired = ctx.watchdogFired(),
    ).terminalPrecedence(
        onFinished = ::successOutcome,
        onWatchdog = {
            TurnOutcome.Failure(ErrorType.OVERLOADED, "${quirks.providerTag}: upstream stalled — aborted; retry")
        },
        onUnfinished = ::unfinishedOutcome,
    )

    private fun unfinishedOutcome(): TurnOutcome =
        if (ctx.clientGone()) {
            TurnOutcome.ClientAbandoned()
        } else {
            TurnOutcome.Failure(
                ErrorType.OVERLOADED,
                "${quirks.providerTag}: stream ended without a terminal event (truncated); retry",
            )
        }

    private fun successOutcome(): TurnOutcome = TurnOutcome.Success(
        hasToolUse = blocks.hasToolUse,
        incomplete = terminal.incomplete,
        usage = usage.toUsage(),
        thinkingText = channels.thinkingBuf.toString(),
        bodyText = channels.textBuf.toString(),
        emittedText = channels.emittedText,
        emittedThinking = channels.emittedThinking,
    )
}
