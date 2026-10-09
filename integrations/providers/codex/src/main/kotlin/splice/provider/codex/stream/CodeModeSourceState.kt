// NEW: partial source is never a whole-script retry after the process that owned its reader is gone.
package splice.provider.codex.stream

import kotlinx.serialization.Serializable
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
) {
    fun value(): Usage = Usage(
        inputTokens,
        outputTokens,
        cachedTokens,
        reasoningTokens,
        cacheWriteTokens,
        origin = UsageOrigin(recordedOutputTokens = recordedOutputTokens),
        reported = reported,
    )
}
