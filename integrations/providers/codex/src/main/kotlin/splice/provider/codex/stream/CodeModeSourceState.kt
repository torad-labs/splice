// NEW: partial source is never a whole-script retry after the process that owned its reader is gone.
package splice.provider.codex.stream

import kotlinx.serialization.Serializable
import splice.core.turn.CacheWrite
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.turn.UsageOrigin

@Serializable
internal data class CodeModeSourceState(
    val complete: Boolean = false,
    val usage: CodeModeSourceUsage? = null,
    val consumed: Boolean = false,
)

@Serializable
internal data class CodeModeSourceUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val reasoningTokens: Long,
    val cacheWriteTokens: Long,
    val recordedOutputTokens: Long = 0,
    val reported: Set<UsageField> = UsageField.entries.toSet(),
    /** The share of [cacheWriteTokens] held for an hour, which bills above a five-minute write. Last and
     *  defaulted so a state file written before it reads as a round with no hourly write, which is how
     *  every such round was already billed. */
    val cacheWriteHourlyTokens: Long = 0,
) {
    fun value(): Usage = Usage(
        inputTokens,
        outputTokens,
        cachedTokens,
        reasoningTokens,
        CacheWrite(cacheWriteTokens, cacheWriteHourlyTokens),
        origin = UsageOrigin(recordedOutputTokens = recordedOutputTokens),
        reported = reported,
    )
}
