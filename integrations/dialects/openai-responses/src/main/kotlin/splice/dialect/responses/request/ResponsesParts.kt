// NEW: collaborator wiring for ResponsesProvider (concentration, 2026-08-19).
// Same-package; the provider keeps the SPI overrides and the WS lazy arm.
package splice.dialect.responses.request

import splice.core.config.REASONING_DIR
import splice.core.util.LogSink
import splice.dialect.responses.ReasoningContinuity
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesTurnOptions
import splice.dialect.responses.ResponsesTurnSeams
import splice.dialect.responses.ResponsesTurnSeamsDeps
import splice.dialect.responses.RoundCarry
import splice.dialect.responses.TranslatorServices
import splice.dialect.responses.WatchdogCaps
import splice.dialect.responses.reasoning.ReasoningCache
import splice.dialect.responses.reasoning.ReasoningCacheFiles
import splice.dialect.responses.reasoning.ReasoningCachePolicy
import splice.dialect.responses.stream.ConversationSummaryParts
import splice.dialect.responses.stream.FoldConfig
import splice.dialect.responses.stream.ResponsesFailureAmend
import splice.dialect.responses.tools.ToolSurfaceLatch
import splice.dialect.responses.tools.ToolSurfaceRecovery
import splice.upstream.ProviderTuning
import splice.upstream.ToolNameShortener

internal class ResponsesParts(
    tuning: ProviderTuning,
    reasoning: ReasoningSettings,
    quirks: ResponsesQuirks,
    foldConfig: FoldConfig?,
    log: LogSink,
    toolNames: ToolNameShortener,
) {
    val builder = ResponsesRequestBuilder(quirks, toolNames)
    private val cachePolicy = ReasoningCachePolicy()
    private val surfaceRecovery = ToolSurfaceRecovery()
    private val ids = ResponsesStableIds()

    // V4-334: the head's state dir keeps each conversation's reasoning across a restart, while the quirk
    // runs the cache; a head that turned it off drops what an earlier start kept (V4-260: kept files go
    // when their use ends).
    private val reasoningFiles =
        tuning.locations.stateDir?.let { ReasoningCacheFiles(it.resolve(REASONING_DIR), log) }
    private val reasoningCache = ReasoningCache(
        log = log,
        files = reasoningFiles.takeIf { quirks.roundTrip.reasoningCache },
    )

    init {
        if (!quirks.roundTrip.reasoningCache) reasoningFiles?.purge()
    }
    private val continuity = ReasoningContinuity(reasoningCache, cachePolicy, ids)
    private val summaryParts = ConversationSummaryParts()
    private val toolSurfaceLatch = ToolSurfaceLatch()
    val turnOptions = ResponsesTurnOptions(
        reasoning,
        quirks,
        tuning.catalog,
        log,
        continuity,
        toolSurfaceLatch,
    )
    val turnSeams = ResponsesTurnSeams(
        ResponsesTurnSeamsDeps(
            quirks = quirks,
            continuity = continuity,
            summaryParts = summaryParts,
            turnOptions = turnOptions,
            carry = RoundCarry(foldConfig = foldConfig, replayReasoning = reasoning.replay),
            caps = WatchdogCaps(
                streamIdleMs = tuning.watchdog.streamIdle.inWholeMilliseconds,
                upstreamTimeoutMs = tuning.watchdog.totalCap.inWholeMilliseconds,
            ),
            services = TranslatorServices(toolNames = toolNames, log = log),
        ),
    )
    val failureAmend = ResponsesFailureAmend(
        quirks,
        cachePolicy,
        reasoningCache,
        surfaceRecovery,
        toolSurfaceLatch,
        log,
    )
}
