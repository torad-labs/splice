// NEW: the construction bag for ResponsesParts. Split so the wiring
// collaborator stays under the constructor-arity wall (concentration, 2026-08-19).
package splice.dialect.responses

import splice.core.turn.ReasoningDisplay
import splice.core.util.LogSink
import splice.spi.ProviderTuning

internal data class ResponsesPartsInput(
    val tuning: ProviderTuning,
    val showReasoning: ReasoningDisplay,
    val replayReasoning: Boolean,
    val configEffort: String?,
    val configSummary: String?,
    val quirks: ResponsesQuirks,
    val foldConfig: FoldConfig?,
    val log: LogSink,
    val streamIdleMs: Long,
    val upstreamTimeoutMs: Long,
)
