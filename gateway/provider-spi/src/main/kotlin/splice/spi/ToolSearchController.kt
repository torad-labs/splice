// NEW: the answer-the-search-and-continue SPI — the SUCCESS-side sibling of ReanchorController
// (which continues FAILED rounds) and FoldController (which continues TRUNCATED rounds). The
// Responses `tool_search` tool declares execution:"client", so the GATEWAY answers it, never
// Claude Code: the model emits a tool_search_call item and blocks until a tool_search_output
// item appears in input. Keeping the contract HERE (not in the dialect) is why :gateway can run
// the extra round without importing a concrete dialect — the module law again. Invariants:
//   - request-scoped, NOT meta-scoped: the answer is a function of THIS request's deferred
//     inventory, which TurnMeta does not and must not carry; it rides BuiltTurn and is garbage
//     collected with the turn, so the provider stays immutable across concurrent turns;
//   - returns a request BODY, never a terminal — a search round is invisible to Claude Code and
//     can never reach WireSink (L3 stays a property of SseEmitter);
//   - null = STOP: the gateway finishes the turn with the round's own outcome.
package splice.spi

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome

public fun interface ToolSearchController {
    public fun continuationForSearch(round: ToolSearchRound): JsonObject?
}

/** One completed round that asked the gateway a question: the request that produced it, its
 *  outcome (carrying the calls + this round's reasoning envelopes), and how many search
 *  continuations this turn already spent (0-based, counted SEPARATELY from the re-anchor budget —
 *  a search is not a failure and must never consume re-anchor attempts). */
public data class ToolSearchRound(
    val requestBody: JsonObject,
    val outcome: TurnOutcome.Success,
    val roundIndex: Int,
)
