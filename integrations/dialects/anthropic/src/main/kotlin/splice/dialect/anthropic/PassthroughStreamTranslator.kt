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
package splice.dialect.anthropic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.JsonObject
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.upstream.StreamTranslator
import splice.upstream.failure.SseFrameTooLargeException
import splice.upstream.failure.TerminalStates
import splice.upstream.retry.FIRST_OUTPUT_TIER
import splice.upstream.retry.MID_OUTPUT_TIER
import splice.upstream.retry.MS_PER_S
import splice.upstream.retry.WatchdogFired
import splice.upstream.sse.WireSink
import splice.upstream.transport.BufferCapacity
import splice.upstream.transport.StreamTornBeforeClient
import java.io.IOException
import java.util.concurrent.CancellationException

public class PassthroughStreamTranslator(
    private val ctx: PassthroughTurnContext,
    private val quirks: PassthroughQuirks,
    names: ToolNameShortener = ToolNameShortener(),
) : StreamTranslator {

    /** V4-116: the unrecognised throwable the generic catch swallowed, if any. Recorded rather than
     *  turned into an outcome on the spot because the terminal decision must run AFTER `closeAll()`
     *  — the wire has to be at a clean block boundary before anything says what happened. */
    private var unexpected: RuntimeException? = null

    private val channels = PassthroughProseChannels()
    private val blocks = PassthroughBlockRegistry(ctx, quirks, channels, names)
    private val terminal = PassthroughTerminalState(quirks, blocks)
    private val usage = PassthroughUsage()
    private val router = PassthroughEventRouter(blocks, terminal, usage, ctx.log, quirks.providerTag)

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
        } catch (ignored: RuntimeException) {
            // V4-116, OPERATOR RULING 2026-09-18 "RETRY DEFAULT IS TOTAL": THE GENERIC FALLTHROUGH.
            //
            // The arms above are a classifier with only KNOWN cells, and two of the cells it did
            // name are gone because they were never cells of their own: SerializationException and
            // IllegalArgumentException both extend RuntimeException, so one arm covers them plus
            // every engine, codec or vendor failure this dialect has not seen. An unknown failure
            // is not a reason to stop retrying — we cannot enumerate the upstreams, so the default
            // has to be total.
            //
            // MEASURED, and this is what the arm replaces: an unrecognised throwable ESCAPED the
            // translator, so it reached the turn boundary with no partial at all — the same
            // unrecoverable shape as the stall scar, for a failure class nobody had enumerated.
            // Once content is on the wire the salvage is the only thing that makes the turn
            // resumable, so it rides the outcome regardless of which class threw.
            //
            // Nothing is MISLABELLED by swallowing it: the message names the throwable's own class
            // and text (the Throwable.toString() idiom TurnEnding already uses for exactly this
            // reason), so an unknown upstream failure stays tellable from a bug of ours, and
            // providerReported keeps its default false — the same local attribution this failure
            // had when it escaped.
            //
            // AND IT DOES NOT EAT THE SPI'S OWN SIGNALS, which is the half of "generic" that is
            // easy to get wrong. StreamTornBeforeClient is a plain RuntimeException ON PURPOSE so
            // that "no translator catch matches" (UpstreamErrors.kt) and the pre-content tear stays
            // reachable by G5; SseFrameTooLargeException likewise has an owner at the turn boundary
            // (TurnConnEnd's UPSTREAM_FRAME_TOO_LARGE arm). Swallowing either reclassifies a named
            // condition as an anonymous truncation and starves the surface that already knows what
            // to do with it. Specific cells stay specific; this arm is for everything else.
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
        onWatchdog = ::stalledOutcome,
        onUnfinished = ::unfinishedOutcome,
    )

    /** V4-116 (1): A STALL CARRIES THE SAME SALVAGE A TRUNCATION DOES.
     *
     *  This is the whole scar. The watchdog branch used to build a Failure with no `partial`, so
     *  [splice.upstream.ReanchorController.continuationForFailure]'s first line (`round.failure.partial
     *  ?: return null`) answered before any eligibility rule was even read — a stalled round was
     *  unrecoverable BY CONSTRUCTION, on every head, even one measured to continue from a prefill
     *  and even mid-answer with real text already in the client's hands. Measured: claude-deepseek
     *  session b10459ba streamed 3810 content frames, went silent, sat the full 300s mid-output tier
     *  and then ended the turn as an error the client could do nothing with.
     *
     *  A stall and a truncation are the same fact about a round — the upstream stopped talking after
     *  delivering content — so they are the same Failure, and recoverability stays the controller's
     *  decision rather than this branch's. [partialRound] is deliberately the SAME builder
     *  [unfinishedOutcome] uses, so a continuation and a success read identical buffers. */
    private fun stalledOutcome(fired: WatchdogFired): TurnOutcome = TurnOutcome.Failure(
        "${quirks.providerTag}: ${stallDetail(fired)}; retry",
        partial = partialRound(),
        cause = FailureCause.UPSTREAM_STALLED,
        phase = FailurePhase.MID_OUTPUT,
    )

    /** The stall line names the TIER that fired and the number it compared, never a generic
     *  "upstream stalled": an operator reading "300s idle cap" on a stall judged by a 20s tier is
     *  reading a lie, which is the same defect the responses twin already fixed for its own two
     *  tiers. [WatchdogFired.TotalCap] is the whole-turn wall and names its own elapsed figure —
     *  it reaches here only when no round-level verdict won the precedence. */
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

    private fun unfinishedOutcome(): TurnOutcome =
        if (ctx.clientGone()) {
            TurnOutcome.ClientAbandoned()
        } else {
            TurnOutcome.Failure(
                // An UNRECOGNISED throwable says so in its own words rather than borrowing the
                // truncation sentence: "truncated" is a diagnosis, and reporting a failure we did
                // not diagnose under a diagnosis is the mislabelling the generic arm exists to
                // avoid. Both endings are the same Failure and both carry the salvage — only the
                // sentence the client reads differs, and it differs by telling the truth.
                unexpected?.let { "${quirks.providerTag}: upstream stream failed ($it)$UNEXPECTED_TAIL" }
                    ?: "${quirks.providerTag}: stream ended without a terminal event (truncated); retry",
                // The SALVAGE, and the reason this failure is now recoverable at all. Without it
                // `partial` defaulted to null, which Provider.reanchorController reads as "this
                // dialect cannot continue" — so a truncated stream on THIS dialect (every OAuth
                // head, kimi, muse, deepseek) ended the turn with attempts=1 while the connect-phase
                // and G5 budgets sat unused, because neither can see a 2xx that EOFs early. The
                // wire is at a clean block boundary here (closeAll ran above), so a continuation
                // may APPEND. Measured 2026-09-16: three deepseek truncations in one minute, at
                // 196, 44 and 989 content frames already delivered, every one of them terminal.
                partial = partialRound(),
                // V4-117: two shapes in one expression, so two causes — the same split its chat twin
                // makes. A TRUNCATION is the upstream going quiet; an UNRECOGNISED throwable is not
                // attributed to the upstream at all, because the comment above already refuses to
                // report an undiagnosed failure under a diagnosis.
                cause = if (unexpected != null) FailureCause.INTERNAL else FailureCause.UPSTREAM_TRUNCATED,
                phase = FailurePhase.MID_OUTPUT,
            )
        }

    /** What this round produced before it died, for [ReanchorController]. Mirrors successOutcome's
     *  reads so a continuation and a success see the SAME buffers. */
    private fun partialRound(): TurnOutcome.PartialRound = TurnOutcome.PartialRound(
        thinkingText = channels.thinkingBuf.toString(),
        bodyText = channels.textBuf.toString(),
        emittedText = channels.emittedText,
        emittedThinking = channels.emittedThinking,
        hasToolUse = blocks.hasToolUse,
        toolTearOpen = blocks.toolTearOpen,
        usage = usage.toUsage(),
    )

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

// The tail of an unrecognised-throwable sentence: it is still retryable (the operator's default),
// and the throwable's own rendering is what makes the failure identifiable next time.
private const val UNEXPECTED_TAIL = " — retry"
