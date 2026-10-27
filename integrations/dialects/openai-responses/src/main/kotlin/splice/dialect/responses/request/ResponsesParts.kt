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
import splice.dialect.responses.reasoning.ReasoningCacheWrites
import splice.dialect.responses.stream.ConversationSummaryParts
import splice.dialect.responses.stream.LiveFoldConfig
import splice.dialect.responses.stream.ResponsesFailureAmend
import splice.dialect.responses.tools.ToolSurfaceLatch
import splice.dialect.responses.tools.ToolSurfaceRecovery
import splice.upstream.ProviderTuning
import splice.upstream.ToolNameShortener

internal class ResponsesParts(
    tuning: ProviderTuning,
    reasoning: ReasoningSettings,
    quirks: ResponsesQuirks,
    foldConfig: LiveFoldConfig?,
    private val log: LogSink,
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
        tuning.locations.stateDir?.let { ReasoningCacheFiles(it.resolve(REASONING_DIR), log = log) }

    // Fix 3 (2026-10-26): the cache's records reach those files on this lane, behind the turns that made them, and
    // the head's stop drains it ([onHeadStop]).
    private val reasoningWrites = ReasoningCacheWrites(reasoningFiles.takeIf { quirks.roundTrip.reasoningCache })
    private val reasoningCache = ReasoningCache(log = log, writes = reasoningWrites)

    // Native phases remain meaningful when encrypted-reasoning replay is off.
    private val phaseWrites = ReasoningCacheWrites(
        tuning.locations.stateDir?.let { ReasoningCacheFiles(it.resolve("assistant-phases"), log = log) },
    )
    private val phaseCache = ReasoningCache(log = log, writes = phaseWrites)

    init {
        if (!quirks.roundTrip.reasoningCache) reasoningFiles?.purge()
    }
    private val continuity = ReasoningContinuity(reasoningCache, cachePolicy, ids, phaseCache)
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
            carry = RoundCarry(foldConfig = foldConfig),
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

    /** The head is stopping: land the reasoning records still queued for the disk, waiting at most [STOP_WRITES_MS],
     *  so the next start restores them. They are written behind the turns that made them (fix 3, 2026-10-26), so a
     *  stop that did not wait could end the process with the last ones still queued. A disk that did not keep up is
     *  said in daemon.log; the records it had not taken still land if the process lives on. */
    fun onHeadStop() {
        val deadline = splice.core.util.MonoClock.nowMs() + STOP_WRITES_MS
        val reasoningDrained = reasoningWrites.drain(STOP_WRITES_MS)
        val phasesDrained = phaseWrites.drain((deadline - splice.core.util.MonoClock.nowMs()).coerceAtLeast(0L))
        if (!reasoningDrained || !phasesDrained) {
            log(
                "[reasoning-cache] stop: the disk had not taken every queued record after ${STOP_WRITES_MS}ms; " +
                    "the rest are lost if the daemon exits now",
            )
        }
    }
}

// How long a head's stop waits for the reasoning records still queued. Page-cache appends land in microseconds, so
// this binds only on a disk that has stalled; it stays inside the 5 s the daemon gives a head's close phase
// (HeadShutdown's CLOSE_BUDGET_MS) beside the engine stop's 2.5 s, so the usage and perf flushes after it still run.
private const val STOP_WRITES_MS: Long = 2_000L
