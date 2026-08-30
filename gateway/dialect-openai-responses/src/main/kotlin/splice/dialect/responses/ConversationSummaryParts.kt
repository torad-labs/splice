// NEW: conversation-lifetime summary-dedup state (2026-08-26). The sequential_cutoff recap
// staircase spans CLIENT turns, not just rounds: every Claude Code tool round-trip is a separate
// POST, the backend opens the new response by restating the tail of the previous emission (live
// claudex scan: 244/803 thinking messages, all strictly leading), and TurnMeta's per-turn
// SharedSummaryParts resets on every POST — so the recap re-landed on every tool round-trip, the
// "duplicated reasoning" report standing since the proxy's beginning. codex-rs never shows it
// because its dedup state lives for the whole session process (client-side, conversation-scoped);
// this registry gives splice the same lifetime, keyed by the SAME conversation identity the
// reasoning cache uses. Losing a conversation (eviction, restart) degrades to the per-turn status
// quo, never to an error.
package splice.dialect.responses

import splice.core.turn.SharedSummaryParts

// LRU bound on remembered conversations; a head talks to a handful of live Claude Code sessions,
// so 64 is generous. Worst-case memory 64 * 512 parts * ~100 chars ≈ 3 MB per head.
private const val MAX_CONVERSATIONS = 64

// Per-conversation part bound, trimmed BETWEEN rounds (fetch time — no recap cursor survives a
// round, so the index shift is unobservable). Recaps restate only the recent tail; the live
// multi-day session accumulated ~1600 parts, so 512 covers any plausible recap depth many times.
private const val MAX_PARTS_PER_CONVERSATION = 512

/** One [SharedSummaryParts] per conversation, LRU-bounded. Null key (no hashable first message —
 *  image-first or tool_result-first openers) has no cross-turn identity and returns null: the
 *  caller falls back to the TURN's own instance (TurnMeta.summaryParts), the exact
 *  pre-2026-08-26 behavior. */
internal class ConversationSummaryParts(
    private val maxConversations: Int = MAX_CONVERSATIONS,
    private val maxPartsPerConversation: Int = MAX_PARTS_PER_CONVERSATION,
) {
    // Iteration order = least-recently-fetched first (fetch re-inserts).
    private val convos = LinkedHashMap<String, SharedSummaryParts>()
    private val lock = Any()

    /** Scoped by BOTH identities when the client sends a session id, per TurnMeta.sessionId's own
     *  law ("consumers must mix BOTH, never either alone"): [conversationKey] alone is a hash of
     *  the first user message's TEXT, so two conversations opening with identical words shared one
     *  dedup instance, and a false dedup hit SUPPRESSES a reasoning part rather than merely missing
     *  a cache. ResponsesWsIdentity already mixes both; this was the one consumer that did not
     *  (review 2026-08-28, PR 99 comment 1). A client that sends no session id keeps the
     *  conversation-only scope, because refusing to scope at all would silently drop this dialect
     *  back to per-turn dedup and undo the cross-turn recap fix. */
    fun forConversation(sessionId: String?, conversationKey: String?): SharedSummaryParts? {
        val convo = conversationKey?.takeIf { it.isNotEmpty() } ?: return null
        val key = sessionId?.takeIf { it.isNotEmpty() }?.let { "$it\u0000$convo" } ?: convo
        return synchronized(lock) {
            // Deliberate second construction site: this instance is CONVERSATION-lifetime —
            // strictly longer-lived than the turn — and every round of every turn of the
            // conversation fetches the SAME one, so it cannot re-open round-private dedup state.
            // ast-grep-ignore: kt-shared-summary-parts-single-source
            val parts = convos.remove(key) ?: SharedSummaryParts()
            convos[key] = parts
            while (convos.size > maxConversations) convos.remove(convos.keys.first())
            parts.trimToLast(maxPartsPerConversation)
            parts
        }
    }
}
