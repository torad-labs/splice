// NEW: provider-neutral turn contracts (plan SPI shapes). TurnOutcome deliberately carries
// NO stop-reason string — hasToolUse/incomplete booleans only; the wire literal derivation
// (tool_use > max_tokens > end_turn) is sealed inside the gateway's SseEmitter (L3-as-types).
package splice.core.turn

import kotlin.time.Duration

// Usage / ErrorType / ToolSearchCall* / TurnOutcome live in TurnOutcome.kt
// (concentration, 2026-08-19). Same-package FQCNs are unchanged.

/** Per-turn facts the pipeline threads through translation and streaming (meta replaces
 *  the v29 body.__claudex* side channel — pure data, never smuggled on the request). */
public data class TurnMeta(
    val compact: Boolean,
    val showReasoning: ReasoningDisplay,
    val stream: Boolean,
    val originalModel: String,
    val upstreamModel: String,
    val clientMaxTokens: Long?,
    val effort: String,
    val summary: String?,
    val budgetTokens: Long?,
    /** Stable per-conversation scope key (responses dialect: first-message hash) — partitions the
     *  gateway reasoning cache so concurrent conversations on one head can never cross-inject
     *  (review 2026-07-24, RC-2's eli-risk-8 keying). Null (chat/passthrough) = one shared scope. */
    val conversationKey: String? = null,
    /** The client's session id when it sent one (ws-transport WS-3). [conversationKey] alone is a
     *  hash of the first user message's TEXT, so two conversations that open with identical words
     *  share it BY DESIGN — harmless for a cache miss, but fatal as a previous_response_id chain
     *  anchor, where it would hand one conversation's server-side context to another. Null when
     *  the client sends no session id; consumers must mix BOTH, never either alone. */
    val sessionId: String? = null,
    /** Tool-surface partition sizes for THIS turn's request; null when deferral was not in play.
     *  Non-null stamps the perf counters even at zero — a deploy where tools_deferred stays 0 is a
     *  false landing, and it must be visible in one grep of the perf JSONL. */
    val toolsEager: Int? = null,
    val toolsDeferred: Int? = null,
    /** Turn-scoped summary-dedup state shared by every continuation round's translator (rounds
     *  build fresh translators; without a shared set, a section re-titled by a continuation round
     *  passes each round's per-instance dedup and lands as a duplicate — the 2026-07-26 mirror
     *  duplication).
     *
     *  NON-NULL WITH A FRESH DEFAULT ON PURPOSE (2026-07-26): this is the ONLY sanctioned
     *  construction site. No caller passes this argument, so there is no per-round construction to
     *  get wrong, and `copy()` — which every continuation path uses — preserves the reference.
     *  Dialects that render no reasoning summary simply never read it (two empty collections). */
    val summaryParts: SharedSummaryParts = SharedSummaryParts(),
)

/** The shared state behind TurnMeta.summaryParts: the ordered parts already emitted to the
 *  client this turn, plus the per-item exact set the dedup's within-item arm matches against.
 *  Mutable per-turn coordination, never compared by value.
 *
 *  ACCESS DISCIPLINE (2026-07-26 review): mutated by ONE round's translator at a time. The
 *  fold/re-anchor/tool_search loops drive rounds strictly sequentially (`FoldRunner.run` is a
 *  plain `while (true) { postRound(...) }` — no launch/async around a round), so the absence of
 *  synchronization here is deliberate, not an oversight. A future round loop that overlaps rounds
 *  must add synchronization before sharing this. Public, not internal: the dialect module reads
 *  these across a module boundary.
 *
 *  APPEND-ONLY BY TYPE, not by comment (2026-07-27 review): the collections used to be public
 *  `MutableList`/`MutableMap`, so the discipline above was documentary — any in-repo caller could
 *  `clear()`, reorder or truncate the list with no compile error and no test to catch it, and the
 *  translator's recap cursor trusts this list's ORDER and LENGTH. The surface is now exactly the
 *  two operations the dedup dialect performs; the collections cannot be reached to be reordered. */
public class SharedSummaryParts {
    private val emittedParts = mutableListOf<String>()
    private val itemEmitted = mutableMapOf<Int, MutableSet<String>>()

    /** The part at [cursor] in emission order, or null past either end (the recap arm passes
     *  RECAP_DONE = -1 once an item's leading recap is finished, which must not match). */
    public fun partAt(cursor: Int): String? = emittedParts.getOrNull(cursor)

    /** Records [part] as emitted for item [outputIndex]. Returns false when it was ALREADY
     *  emitted for that item — a dedup hit — in which case nothing is appended. */
    public fun markEmitted(outputIndex: Int, part: String): Boolean {
        val fresh = itemEmitted.getOrPut(outputIndex) { mutableSetOf() }.add(part)
        if (fresh) emittedParts.add(part)
        return fresh
    }
}

/** The two-tier watchdog knobs (v35 doctrine): before first byte the idle limit is
 *  firstByteTimeout (prefill is legitimately silent for minutes); after, streamIdle;
 *  totalCap bounds the whole turn. */
public data class WatchdogBudget(
    val firstByteTimeout: Duration,
    val streamIdle: Duration,
    val totalCap: Duration,
)
