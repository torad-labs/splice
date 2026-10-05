// NEW: selected trace facts and read-time failure wording, separate from reply body encoding.
package splice.head.trace

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.FailureCause
import splice.head.turn.OutcomeSentences

/** The verb's closing columns, or the attempts so far when no ending exists. Raw records stay untouched. */
internal object TraceTurnSummary {
    fun of(turn: TracedTurn, cause: FailureCause? = null): JsonObject = buildJsonObject {
        val ending = turn.ending
        val open = turn.attempts.size
        put("id", turn.id)
        put("ts", turn.ts)
        put("session", turn.session)
        put("model", turn.model)
        put("compact", turn.compact)
        put("open", ending == null)
        put("outcome", ending?.outcome)
        put("cause", cause?.name)
        put(
            "failure_sentence",
            ending?.let {
                TracePolicyRefusal.sentence(turn, cause) ?: it.failureSentence ?: OutcomeSentences.of(it.outcome)
            },
        )
        put("rounds", if (ending == null) open.toLong() else ending.rounds.toLongOrNull())
        put("attempts", if (ending == null) open.toLong() else ending.attempts.toLongOrNull())
        put("total_ms", ending?.totalMs?.toLongOrNull())
    }
}
