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
     *  NON-NULL WITH A FRESH DEFAULT ON PURPOSE (2026-07-26): no caller passes this argument, so
     *  there is no per-round construction to get wrong, and `copy()` — which every continuation
     *  path uses — preserves the reference. Dialects that render no reasoning summary simply never
     *  read it (two empty collections). The responses dialect substitutes a CONVERSATION-lifetime
     *  instance only when the turn has both session and conversation identities (the cross-turn
     *  recap staircase, 2026-08-26); this default remains the state otherwise. */
    val summaryParts: SharedSummaryParts = SharedSummaryParts(),
)

private const val DEFAULT_SUMMARY_PARTS = 512
private const val DEFAULT_SUMMARY_BYTES = 1_048_576L

/** Conversation-capable summary-dedup state. The responses dialect owns one COMPLETE translator
 *  round around this state: overlapping POSTs for the same conversation wait on one coroutine mutex,
 *  so no expiry or second translator can shift indices under a live recap cursor. Per-event
 *  operations remain ordinary synchronized list reads/writes; the mutex is never acquired here.
 *
 *  The active window is the immediately preceding logical summary chain plus genuinely-new parts
 *  accepted this round. Anchoring extends that chain; unrelated leading text replaces it. Parts are
 *  occurrence records (text + output slot), so bounding one duplicate never erases an equal value
 *  from another item. Losing records to either bound degrades to duplicate display, never data loss. */
public class SharedSummaryParts(
    private val maxParts: Int = DEFAULT_SUMMARY_PARTS,
    private val maxBytes: Long = DEFAULT_SUMMARY_BYTES,
) {
    private data class RecordedPart(val outputIndex: Int, val text: String) {
        val bytes: Long = text.encodeToByteArray().size.toLong()
    }

    private var retainedParts = mutableListOf<RecordedPart>()
    private val roundParts = mutableListOf<RecordedPart>()
    private var previousRoundItems = emptyList<RecordedPart>()
    private val currentRoundItems = mutableListOf<RecordedPart>()
    private var currentRoundBytes = 0L
    private var roundTrackingDisabled = false
    private var retainPriorRound = false

    /** Rotates into a new translator round. SummaryRoundScope owns the whole-round mutex. */
    @Synchronized
    public fun beginRound() {
        finishRoundLocked()
    }

    /** Commits and bounds this translator round. Called from SummaryRoundScope's finally block. */
    @Synchronized
    public fun endRound() {
        finishRoundLocked()
    }

    /** The part at [cursor] in the active recap window, or null past either end. */
    @Synchronized
    public fun partAt(cursor: Int): String? = when {
        cursor < 0 -> null
        cursor < retainedParts.size -> retainedParts[cursor].text
        else -> roundParts.getOrNull(cursor - retainedParts.size)?.text
    }

    /** Whether one more decision can be tracked without crossing the live round's count/byte cap.
     *  False degrades this and all later parts to display-without-dedup; active cursors never shift. */
    @Synchronized
    public fun canTrack(outputIndex: Int, part: String): Boolean {
        val recorded = RecordedPart(outputIndex, part)
        if (currentRoundItems.contains(recorded)) return true
        if (roundTrackingDisabled) return false
        val within = currentRoundItems.size < maxParts && currentRoundBytes + recorded.bytes <= maxBytes
        if (!within) roundTrackingDisabled = true
        return within
    }

    /** Every possible anchor for [part]. Current-round occurrences outrank retained ones: matching a
     *  part already accepted in this round never needs to keep an unrelated older chain alive. */
    @Synchronized
    public fun anchorsOf(part: String): IntArray {
        val current = roundParts.indices.filter { roundParts[it].text == part }
        if (current.isNotEmpty()) return current.map { retainedParts.size + it }.toIntArray()
        val retained = retainedParts.indices.filter { retainedParts[it].text == part }.toIntArray()
        if (retained.isNotEmpty()) retainPriorRound = true
        return retained
    }

    /** Compatibility probe used by registry tests; production recap matching keeps every candidate. */
    @Synchronized
    public fun anchorOf(part: String): Int = anchorsOf(part).firstOrNull() ?: -1

    /** Records a recap match without extending the logical chain. False means the live cap was hit. */
    @Synchronized
    public fun markRecap(outputIndex: Int, part: String): Boolean {
        val recorded = RecordedPart(outputIndex, part)
        if (currentRoundItems.contains(recorded)) return true
        if (!canTrack(outputIndex, part)) return false
        currentRoundItems.add(recorded)
        currentRoundBytes += recorded.bytes
        return true
    }

    /** Records a genuinely-new [part]. False means an exact repeat in this item or the same slot of
     *  the immediately previous round. A cap miss returns true so the part displays without state. */
    @Synchronized
    public fun markEmitted(outputIndex: Int, part: String): Boolean {
        val recorded = RecordedPart(outputIndex, part)
        if (currentRoundItems.contains(recorded)) return false
        if (!canTrack(outputIndex, part)) return true
        currentRoundItems.add(recorded)
        currentRoundBytes += recorded.bytes
        roundParts.add(recorded)
        return !previousRoundItems.contains(recorded)
    }

    /** Explicit occurrence-safe count trim for tests and non-registry callers. Production instances
     *  apply both constructor bounds at every [endRound]. */
    @Synchronized
    public fun trimToLast(n: Int) {
        finishRoundLocked()
        retainedParts = trimRecords(retainedParts, minOf(n, maxParts))
        previousRoundItems = previousRoundItems.mapNotNull { previous ->
            retainedParts.lastOrNull { it == previous }
        }.distinct()
    }

    private fun finishRoundLocked() {
        if (roundParts.isNotEmpty()) {
            retainedParts = if (retainPriorRound) {
                (retainedParts + roundParts).toMutableList()
            } else {
                roundParts.toMutableList()
            }
        }
        retainedParts = trimRecords(retainedParts, maxParts)
        if (currentRoundItems.isNotEmpty()) {
            // Exact-repeat state cannot outlive the logical-chain occurrence that justifies it.
            // Rebind to the retained record so equal event text is not stored a second time.
            previousRoundItems = currentRoundItems.mapNotNull { observed ->
                retainedParts.lastOrNull { it == observed }
            }.distinct()
        } else {
            previousRoundItems = previousRoundItems.mapNotNull { previous ->
                retainedParts.lastOrNull { it == previous }
            }.distinct()
        }
        roundParts.clear()
        currentRoundItems.clear()
        currentRoundBytes = 0
        roundTrackingDisabled = false
        retainPriorRound = false
    }

    private fun trimRecords(records: List<RecordedPart>, countLimit: Int): MutableList<RecordedPart> {
        var bytes = 0L
        val newestFirst = mutableListOf<RecordedPart>()
        for (index in records.lastIndex downTo 0) {
            val record = records[index]
            if (newestFirst.size >= countLimit || bytes + record.bytes > maxBytes) break
            newestFirst.add(record)
            bytes += record.bytes
        }
        newestFirst.reverse()
        return newestFirst
    }
}

/** The two-tier watchdog knobs (v35 doctrine): before the client has seen output the idle limit
 *  is firstByteTimeout (prefill is legitimately silent for minutes); after, streamIdle;
 *  totalCap bounds the whole turn. */
public data class WatchdogBudget(
    val firstByteTimeout: Duration,
    val streamIdle: Duration,
    val totalCap: Duration,
) {
    /** The budget a COMPACT turn runs under: its pre-output silence is bounded by [totalCap] alone.
     *  A compaction's prefill + reasoning over the whole transcript is the case the v35 doctrine
     *  calls legitimately silent for minutes, and once the watchdog tier was corrected to key on
     *  the first client frame (2026-09-01) the very first compaction on the corrected tier still
     *  died at "no first output within the 300s first-output cap" — 1.13MB body, first byte at 3s,
     *  then silence past five minutes, the same session that had looped all day. Idle is a stall
     *  detector, not a budget (operator, DR-7): the one wall before a compaction's first output is
     *  the whole-turn cap. Normal turns keep [firstByteTimeout].
     *
     *  The tier is OFF ([Duration.INFINITE]), not merely raised to [totalCap]: two pollers on one
     *  deadline are a coin flip. TurnWatchdog's idle poller and its cap poller sample on the same
     *  ~cap/3 cadence, so at the tick past the wall whichever coroutine is dispatched first names
     *  the verdict — "first-output cap" (a ROUND reaped, salvage invited) or "total cap" (the TURN
     *  ended). Gate run 33575037270 lost that flip on a loaded runner; production would have lost
     *  it the same way at 300s. With no pre-output tier there is nothing to race: the wall alone
     *  ends a silent compaction, and [streamIdle] still reaps a stall once output has begun. */
    public fun forCompact(): WatchdogBudget = copy(firstByteTimeout = Duration.INFINITE)
}
