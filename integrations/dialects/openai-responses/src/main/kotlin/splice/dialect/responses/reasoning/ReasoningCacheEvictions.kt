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
