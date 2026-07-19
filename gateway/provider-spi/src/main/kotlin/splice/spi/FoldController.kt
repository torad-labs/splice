// NEW: reasoning-continuation folding SPI (the codex 518n-2 "dumbing down" fix). The generic head
// (:gateway) drives THIS; the codex-specific detection + continuation-request construction live in
// the openai-responses dialect. Keeping the contract here (not in the dialect) is why :gateway can
// fold a truncated round without ever importing a concrete dialect — the module law again.
package splice.spi

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome

/** Per-turn continuation policy. A provider returns one from [Provider.foldController] when the
 *  turn's model is fold-eligible; null means pure passthrough (no buffering, no loop — byte-for-byte
 *  the pre-fold behaviour). Called by the gateway after each completed upstream round. */
public fun interface FoldController {
    /**
     * Decide whether this round's reasoning was truncated and, if so, return the NEXT upstream
     * request body (this round's input + replayed reasoning + a continuation marker) to re-POST.
     * Returns null to STOP folding (a clean finish, a cap hit, or no encrypted reasoning to replay)
     * — the gateway then flushes the buffered output and emits the single honest terminal.
     */
    public fun continuation(round: FoldRound): JsonObject?
}

/** One completed fold round: the request that produced it, its outcome (usage + reasoning
 *  envelopes), and how many rounds already folded before it (0-based). */
public data class FoldRound(
    val requestBody: JsonObject,
    val outcome: TurnOutcome.Success,
    val roundIndex: Int,
)
