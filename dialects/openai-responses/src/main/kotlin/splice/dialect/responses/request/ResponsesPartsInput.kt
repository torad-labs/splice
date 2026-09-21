// NEW: the construction bag for ResponsesParts. Split so the wiring
// collaborator stays under the constructor-arity wall (concentration, 2026-08-19).
package splice.dialect.responses.request

import splice.core.turn.ReasoningDisplay
import splice.core.util.LogSink
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.stream.FoldConfig
import splice.upstream.ProviderTuning

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
