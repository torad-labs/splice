// NEW: the gateway-side sink that CAN end a turn. WireSink (provider-spi) is deliberately
// terminal-less (L3: a provider translator cannot fake a clean stop); the two terminal verbs live
// only on the gateway's own sinks. Both the streaming SseEmitter and the non-stream
// CollectingTerminal implement this, so the honesty pipeline (promote/mirror/terminal) and the
// fold runner drive EITHER shape through one interface — the stream:false path reuses the exact
// same machinery as the stream:true path instead of a drifting parallel copy.
package splice.gateway.wire

import splice.core.turn.ErrorType
import splice.core.turn.Usage
import splice.spi.WireSink
import java.util.concurrent.atomic.AtomicLong

public interface TurnTerminal : WireSink {
    /** True once this turn's ending is SETTLED — a terminal or error durably reached the wire,
     *  abandon sealed it, or a failed error write made retrying pointless. NOT merely "attempted":
     *  implementations keep it false after a cancelled/failed terminal so the cancellation seal
     *  (TurnDriver.driveSealingCancellation) can still end the turn honestly (stranded-terminal /
     *  truncated-200 fix, review 2026-07-22 round 3). */
    public val hasEnded: Boolean

    /** The ONLY clean ending — implementors derive the stop_reason literal internally (L3). */
    public suspend fun emitTerminal(hasToolUse: Boolean, incomplete: Boolean, usage: Usage)

    /** The ONLY failure ending — a retryable, honestly-typed error the client can act on. */
    public suspend fun emitError(type: ErrorType, message: String)

    /** Client vanished before any ending: seal with nothing emitted (never an error/terminal). */
    public fun abandon()

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
}

// HEAD-001/HEAD-002: a bare "msg_${System.currentTimeMillis()}" collides whenever two turns start
// within the same millisecond, violating the unique-id invariant the client relies on. A
// process-wide monotonic sequence appended to the timestamp makes every id distinct regardless of
// concurrency; shared here (both terminal implementations already live in this file's package)
// rather than duplicated per sink.
private val messageIdSeq = AtomicLong(0)

/** A fresh Anthropic-shaped message id, unique even across turns starting in the same millisecond. */
public fun generateMessageId(): String = "msg_${System.currentTimeMillis()}_${messageIdSeq.incrementAndGet()}"
