// NEW: the construction bag for ResponsesParts. Split so the wiring
// collaborator stays under the constructor-arity wall (concentration, 2026-08-19).
package splice.dialect.responses.request

import splice.core.util.LogSink
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.stream.FoldConfig
import splice.upstream.ProviderTuning
import splice.upstream.ToolNameShortener

internal data class ResponsesPartsInput(
    val tuning: ProviderTuning,
    val reasoning: ReasoningSettings,
    val quirks: ResponsesQuirks,
    val foldConfig: FoldConfig?,
    val log: LogSink,
    val streamIdleMs: Long,
    val upstreamTimeoutMs: Long,
    val toolNames: ToolNameShortener = ToolNameShortener(),
)
