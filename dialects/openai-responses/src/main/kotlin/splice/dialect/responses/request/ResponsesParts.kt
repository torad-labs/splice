// NEW: collaborator wiring for ResponsesProvider (concentration, 2026-08-19).
// Same-package; the provider keeps the SPI overrides and the WS lazy arm.
package splice.dialect.responses.request

import splice.dialect.responses.reasoning.ReasoningCache
import splice.dialect.responses.reasoning.ReasoningCachePolicy
import splice.dialect.responses.stream.ConversationSummaryParts
import splice.dialect.responses.stream.ResponsesFailureAmend
import splice.dialect.responses.tools.ToolSurfaceLatch
import splice.dialect.responses.tools.ToolSurfaceRecovery

internal class ResponsesParts(input: ResponsesPartsInput) {
    val builder = ResponsesRequestBuilder(input.quirks)
    private val cachePolicy = ReasoningCachePolicy()
    private val surfaceRecovery = ToolSurfaceRecovery()
    private val ids = ResponsesStableIds()
    private val reasoningCache = ReasoningCache(log = input.log)
    private val summaryParts = ConversationSummaryParts()
    private val toolSurfaceLatch = ToolSurfaceLatch()
    val turnOptions = ResponsesTurnOptions(
        TurnOptionsDeps(
            showReasoning = input.showReasoning,
            replayReasoning = input.replayReasoning,
            configEffort = input.configEffort,
            configSummary = input.configSummary,
            quirks = input.quirks,
            cachePolicy = cachePolicy,
            ids = ids,
            catalog = input.tuning.catalog,
            log = input.log,
            reasoningCache = reasoningCache,
            toolSurfaceLatch = toolSurfaceLatch,
        ),
    )
    val turnSeams = ResponsesTurnSeams(
        ResponsesTurnSeamsDeps(
            quirks = input.quirks,
            cachePolicy = cachePolicy,
            ids = ids,
            reasoningCache = reasoningCache,
            summaryParts = summaryParts,
            turnOptions = turnOptions,
            foldConfig = input.foldConfig,
            replayReasoning = input.replayReasoning,
            streamIdleMs = input.streamIdleMs,
            upstreamTimeoutMs = input.upstreamTimeoutMs,
        ),
    )
    val failureAmend = ResponsesFailureAmend(
        input.quirks,
        cachePolicy,
        reasoningCache,
        surfaceRecovery,
        toolSurfaceLatch,
        input.log,
    )
}
