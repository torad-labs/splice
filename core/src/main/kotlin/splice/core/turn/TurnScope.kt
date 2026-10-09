package splice.core.turn

/** The conversation and session a turn belongs to, and the summary-dedup state its rounds share. */
public data class TurnScope(
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
