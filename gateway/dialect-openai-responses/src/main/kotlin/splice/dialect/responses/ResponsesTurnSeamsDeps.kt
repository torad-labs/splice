// NEW: the construction bag for ResponsesTurnSeams. Split so the collaborator
// stays under the constructor-arity wall (concentration, 2026-08-19).
package splice.dialect.responses

/** Everything stream/fold/reanchor construction reads from the provider. */
internal data class ResponsesTurnSeamsDeps(
    val quirks: ResponsesQuirks,
    val cachePolicy: ReasoningCachePolicy,
    val ids: ResponsesStableIds,
    val reasoningCache: ReasoningCache,
    val turnOptions: ResponsesTurnOptions,
    val foldConfig: FoldConfig?,
    val replayReasoning: Boolean,
    val streamIdleMs: Long,
    val upstreamTimeoutMs: Long,
)
