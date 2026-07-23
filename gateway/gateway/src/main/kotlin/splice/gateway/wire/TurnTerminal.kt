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
}
