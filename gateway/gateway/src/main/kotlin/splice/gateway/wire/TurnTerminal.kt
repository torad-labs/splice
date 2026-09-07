// NEW: the gateway-side sink that CAN end a turn. WireSink (provider-spi) is deliberately
// terminal-less (L3: a provider translator cannot fake a clean stop); the two terminal verbs live
// only on the gateway's own sinks. Both the streaming SseEmitter and the non-stream
// CollectingTerminal implement this, so the honesty pipeline (promote/mirror/terminal) and the
// fold runner drive EITHER shape through one interface — the stream:false path reuses the exact
// same machinery as the stream:true path instead of a drifting parallel copy.
package splice.gateway.wire

import kotlinx.serialization.json.JsonObject
import splice.core.turn.ErrorType
import splice.core.turn.Usage
import splice.spi.WireSink
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds the (non-standard) usage payload Claude Code reads from gateways.
 *
 * Injected so the emitter stays hud-agnostic. Was a `typealias` for the raw function type, which is
 * a NAME for a shape and not a type of its own: any `(Usage?) -> JsonObject` satisfied it, and the
 * alias bought documentation without buying non-transposability (HD-22). Null usage is the ordinary
 * "no usage known for this turn" case, not an error.
 *
 * Held here (both terminal implementations already live in this file's package) rather than
 * duplicated per sink, the same reasoning [MessageIds] below states for the message-id minter.
 */
public fun interface UsagePayloadBuilder {
    public operator fun invoke(usage: Usage?): JsonObject
}

/** One status line, composed at the instant the wire takes it. A seam rather than a String because
 *  composing a line CONSUMES the caller's ticker state and reads its live clock — see
 *  [TurnTerminal.progress]. `operator fun invoke` keeps every call site a plain lambda. */
public fun interface ProgressLine {
    public operator fun invoke(): String
}

public interface TurnTerminal : WireSink {
    /** True once this turn's ending is SETTLED — a terminal or error durably reached the wire,
     *  abandon sealed it, or a failed error write made retrying pointless. NOT merely "attempted":
     *  implementations keep it false after a cancelled/failed terminal so the cancellation seal
     *  (TurnDriver.driveSealingCancellation) can still end the turn honestly (stranded-terminal /
     *  truncated-200 fix, review 2026-07-22 round 3). */
    public val hasEnded: Boolean

    /** True once [emitTerminal] delivered the clean ending — never for an error or an abandon.
     *  What tells a detached compaction's recording (TurnStreamer) apart from a truncated or
     *  failed one without any consumer reading terminal literals off the frames (L3). Default
     *  false: the collecting sink has no detached consumer. */
    public val endedCleanly: Boolean get() = false

    /** The ONLY clean ending — implementors derive the stop_reason literal internally (L3). */
    public suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage)

    /** The ONLY failure ending — a retryable, honestly-typed error the client can act on. */
    public suspend fun emitError(type: ErrorType, message: String)

    /** The ending for a failure NO retry can change (TurnOutcome.Failure.deterministic): the
     *  explanation as a text block the client renders verbatim, then the clean terminal. An error
     *  event here is worse than useless — Claude Code re-sends it identically before content and
     *  hides the message behind "Server error mid-response" after content — so the wire says what
     *  happened in words instead. Default composes the two verbs every sink already has. */
    public suspend fun emitExplained(message: String, usage: Usage) {
        addTextBlock(message)
        emitTerminal(hasToolUse = false, incomplete = false, usage = usage)
    }

    /** Client vanished before any ending: seal with nothing emitted (never an error/terminal). */
    public fun abandon()

    /** DR-87: non-null when THIS terminal downgraded a Success emit into an error envelope at
     *  emit time (the collect-path malformed-tool/capacity rewrite) — the short reason tag the
     *  perf row carries. Default null: the streaming emitter never rewrites at the terminal, and
     *  a downgrade the caller cannot see is exactly how a client-facing 502 read "ok" in every
     *  instrument. */
    public val degradedReason: String? get() = null

    /** Open the turn on the wire NOW, before any content exists.
     *
     * message_start needs nothing from upstream — the id, model and a zeroed usage payload are all
     * known at build time — so holding it until the first content block buys nothing and costs the
     * whole upstream reasoning phase in dead air. Measured on the codex head (perf jsonl,
     * 2026-07-26): first_byte -> first_frame p50 2840ms / p90 5819ms, i.e. 37% of a median 7658ms
     * turn spent writing NOTHING to a client that renders a frozen screen. kimi and grok measure
     * 0ms because their upstreams emit a content-bearing first event; only the Responses dialect
     * has a long content-free reasoning phase in front of it.
     *
     * Frame ORDER is unchanged (message_start + ping were always first) — only their timing moves,
     * so the golden differential byte-diffs still hold. Idempotent: later content/terminal paths
     * still call it, and re-anchor rounds are no-ops. No-op by default for the non-stream sink,
     * which has no incremental wire to open. */
    public suspend fun ensureStarted() {}

    /** A `ping` event on a wire that has gone silent (ClientChannel's pinger, every 30 s without a
     *  frame): the one frame Claude Code's query loop yields as progress that carries no content,
     *  so its async-agent stall watchdog (600 s of no yielded event) cannot fire under a long
     *  reasoning phase or a slow compaction. Written only after message_start and only while the
     *  turn is still open; the non-stream sink has no incremental wire, so its default is a no-op. */
    public suspend fun heartbeat() {}

    /** splice's own status line for a turn that has gone quiet: a short sentence about the wait,
     *  appended to one thinking block so a user watching a long silent turn can see it is being
     *  HELD rather than hung (gpt-6-astra reasons for 5-12 minutes before its first token). It is
     *  the proxy speaking, not the model, and it is never counted as model output — the pinger's
     *  write port decides that. Written only after message_start and only while the turn is open;
     *  the non-stream sink has no incremental wire, so its default is a no-op.
     *
     *  [line] is a PRODUCER, not a string, because composing the line CONSUMES state — the caller's
     *  ticker only says "holding this turn open" once, and reads elapsed and whether the model has
     *  output yet as it goes. Building it for a write that the guards then drop spends the intro on
     *  a line no client ever sees, and the next tick silently degrades to the ticker form. So the
     *  producer runs where the write happens and nowhere else, and this default never runs it. */
    public suspend fun progress(line: ProgressLine) {}
}

// HEAD-001/HEAD-002: a bare "msg_${System.currentTimeMillis()}" collides whenever two turns start
// within the same millisecond, violating the unique-id invariant the client relies on. A
// process-wide monotonic sequence appended to the timestamp makes every id distinct regardless of
// concurrency; shared here (both terminal implementations already live in this file's package)
// rather than duplicated per sink.
// FILE SCOPE ON PURPOSE: the sequence must be PROCESS-wide. Held on MessageIds it would restart at
// 0 per instance, and the two sinks below each construct their own — reintroducing HEAD-001.
private val messageIdSeq = AtomicLong(0)

/** The message-id minter. A class, not a file-scope function: both terminal sinks read it from a
 *  constructor DEFAULT (so it cannot be a member of either), and it stays stateless — the
 *  process-wide sequence above is what actually carries the uniqueness. */
public class MessageIds {
    /** A fresh Anthropic-shaped message id, unique even across turns starting in the same ms. */
    public fun generateMessageId(): String = "msg_${System.currentTimeMillis()}_${messageIdSeq.incrementAndGet()}"
}
