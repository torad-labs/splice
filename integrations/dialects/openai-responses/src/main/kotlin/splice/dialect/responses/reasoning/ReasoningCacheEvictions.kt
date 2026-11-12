package splice.dialect.responses.reasoning

import splice.core.reasoning.ReasoningReplay
import splice.core.util.JsonScalars
import splice.core.util.LogSink

internal fun interface CachedReasoningMatch {
    operator fun invoke(round: ReasoningCacheRound): Boolean
}

/** Rejection policy shared by legacy tool indexes and positioned reasoning. The store owns removal. */
internal interface ReasoningCacheEvictions {
    fun evictRejectedRounds(match: CachedReasoningMatch)

    fun evictByToolId(toolId: String): Unit = evictRejectedRounds(CachedReasoningMatch { toolId in it.toolIds })

    fun evictByReasoningIds(ids: Set<String>) {
        if (ids.isEmpty()) return
        evictRejectedRounds(
            CachedReasoningMatch { round ->
                round.envelopes.any { envelope ->
                    val item = ReasoningReplay.decodeReasoningEnvelope(envelope, LogSink {})
                    JsonScalars.str(item, "id") in ids
                }
            },
        )
    }
}

// Moved out of ReasoningCache.kt on Nov 10 (concentration), beside the rejection policy.
// One conversation may hold a quarter of each bound (16 MiB, 8192 rounds), several times the reasoning a
// conversation can carry inside one context window before it compacts (the largest healthy record on
// 2026-11-09 was 0.9 MB). A conversation past its share freezes alone; it never evicts a neighbor to grow.
private const val CONVERSATION_SHARE: Int = 4

/** One conversation's share of the head's reasoning bounds. Defaults to [CONVERSATION_SHARE] of the head's own. */
internal data class ReasoningConversationBound(
    val rounds: Int = MAX_ENTRIES / CONVERSATION_SHARE,
    val bytes: Long = REASONING_BOUND_BYTES / CONVERSATION_SHARE,
) {
    /** Whether [convo] holds more than its share; no conversation (the null-key class, a restore) never does. */
    fun exceeded(convo: ReasoningCacheConvo?): Boolean =
        convo != null && (convo.rounds.size > rounds || convo.bytes > bytes)

    /** Bytes an offered round may still take: what is left of the head's bound and of [convo]'s share. */
    fun available(convo: ReasoningCacheConvo, headRemaining: Long): Long = minOf(headRemaining, bytes - convo.bytes)
}
