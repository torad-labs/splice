// NEW: collaborator wiring for ResponsesProvider (concentration, 2026-08-19).
// Same-package; the provider keeps the SPI overrides and the WS lazy arm.
package splice.dialect.responses.request

import splice.dialect.responses.ResponsesTurnOptions
import splice.dialect.responses.ResponsesTurnSeams
import splice.dialect.responses.ResponsesTurnSeamsDeps
import splice.dialect.responses.TurnOptionsDeps
import splice.dialect.responses.reasoning.ReasoningCache
import splice.dialect.responses.reasoning.ReasoningCacheFiles
import splice.dialect.responses.reasoning.ReasoningCachePolicy
import splice.dialect.responses.stream.ConversationSummaryParts
import splice.dialect.responses.stream.ResponsesFailureAmend
import splice.dialect.responses.tools.ToolSurfaceLatch
import splice.dialect.responses.tools.ToolSurfaceRecovery

internal class ResponsesParts(input: ResponsesPartsInput) {
    val builder = ResponsesRequestBuilder(input.quirks, input.toolNames)
    private val cachePolicy = ReasoningCachePolicy()
    private val surfaceRecovery = ToolSurfaceRecovery()
    private val ids = ResponsesStableIds()

    // V4-334: the head's state dir keeps each conversation's reasoning across a restart, while the quirk
    // runs the cache; a head that turned it off drops what an earlier start kept (V4-260: kept files go
    // when their use ends).
    private val reasoningFiles =
        input.tuning.stateDir?.let { ReasoningCacheFiles(it.resolve(REASONING_DIR), input.log) }
    private val reasoningCache = ReasoningCache(
        log = input.log,
        files = reasoningFiles.takeIf { input.quirks.reasoningCache },
    )

    init {
        if (!input.quirks.reasoningCache) reasoningFiles?.purge()
    }
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
            toolNames = input.toolNames,
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

// Under the head's own state dir (ProviderTuning.stateDir).
private const val REASONING_DIR = "reasoning"
