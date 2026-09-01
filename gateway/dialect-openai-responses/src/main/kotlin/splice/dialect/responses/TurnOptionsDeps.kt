// NEW: the construction bag for ResponsesTurnOptions. Split from
// ResponsesTurnOptions.kt so the builder is not billed for the deps
// DTO (concentration, 2026-08-19).
package splice.dialect.responses

import splice.core.model.ModelCatalog
import splice.core.turn.ReasoningDisplay
import splice.core.util.LogSink

/** Everything turn-option construction reads from the provider. A data-class
 *  holder so the collaborator stays under the constructor-arity wall. */
internal data class TurnOptionsDeps(
    val showReasoning: ReasoningDisplay,
    val replayReasoning: Boolean,
    val configEffort: String?,
    val configSummary: String?,
    val quirks: ResponsesQuirks,
    val cachePolicy: ReasoningCachePolicy,
    val ids: ResponsesStableIds,
    val catalog: ModelCatalog,
    val log: LogSink,
    val reasoningCache: ReasoningCache,
    val toolSurfaceLatch: ToolSurfaceLatch,
)
