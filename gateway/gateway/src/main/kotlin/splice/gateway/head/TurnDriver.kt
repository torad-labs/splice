// NEW: split from HeadServer (2026-07-18, the audit's god-file finding — done properly instead
// of suppressed): everything PER-TURN lives here. HeadServer owns the server shell + admission;
// this file owns the drive: SSE channel wiring → upstream POST → watchdog + client-liveness
// pinger → translator → honesty pipeline → sole terminal → telemetry. TurnTelemetry renders the
// per-turn log lines + the perf JSONL row so the driver stays drive-only.
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.perfLine
import splice.core.turn.ErrorType
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.gateway.perf.PerfRowMeta
import splice.gateway.perf.PerfStats
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.usage.TurnUsage
import splice.gateway.usage.buildUsagePayload
import splice.gateway.usage.cacheLogLine
import splice.gateway.usage.makeOutputClamp
import splice.gateway.wire.ImmediateSseWriter
import splice.gateway.wire.SseEmitter
import splice.spi.BuiltTurn
import splice.spi.FailureSource
import splice.spi.InflightGate
import splice.spi.Provider
import splice.spi.TurnSignals
import splice.spi.TurnWatchdog
import splice.spi.UpstreamAuthMissing
import splice.spi.UpstreamFailed
import splice.spi.UpstreamFailureClassifier
import splice.spi.sseJsonEvents
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The per-turn collaborators + data the drive needs, grouped so the drive signature stays one
 *  cohesive argument (they are all created together per request inside the SSE writer). */
internal data class TurnDrive(
    val bodyJson: String,
    val meta: TurnMeta,
    val emitter: SseEmitter,
    val watchdog: TurnWatchdog,
    val slot: InflightGate.Slot,
    val pipeline: TurnPipeline,
    val t0: Long,
    val upstreamModel: String,
    val perf: TurnPerf,
    /** Per-turn upstream headers from BuiltTurn (e.g. grok conv-id affinity). */
    val turnHeaders: Map<String, String>,
    /** The client-facing SSE channel: coalesced writer + write mutex + clientGone flag. */
    val channel: ClientChannel,
)

/** Per-turn client write surface: the coalesced writer, a mutex serializing the emitter vs the
 *  keepalive pinger, and the clientGone flag a failed write flips. */
internal data class ClientChannel(
    val coalesced: ImmediateSseWriter,
    val writeMutex: Mutex,
    val clientGone: AtomicBoolean,
)

/** Drives one streamed turn end-to-end. Owned by HeadServer; one instance per head. */
internal class TurnDriver(
    private val provider: Provider,
    private val deps: HeadDeps,
) {
    private val upstream get() = deps.upstream
    private val compactStats get() = deps.compactStats
    private val usageStore get() = deps.usageStore
    private val log get() = deps.log
    private val clock get() = deps.clock

    private val telemetry = TurnTelemetry(provider.key, deps.perfStats, deps.log, deps.clock)

    /** Open the SSE writer, wire the per-turn collaborators, run the single turn. */
    suspend fun stream(call: ApplicationCall, built: BuiltTurn, slot: InflightGate.Slot, t0: Long, perf: TurnPerf) {
        call.respondTextWriter(ContentType.Text.EventStream) {
            // Flush-per-frame: a frame buffered across an upstream lull is invisible to the
            // user exactly when responsiveness matters (see ImmediateSseWriter header).
            val channel = ClientChannel(
                coalesced = ImmediateSseWriter(writeRaw = { frame -> write(frame) }, flushRaw = { flush() }),
                writeMutex = Mutex(),
                clientGone = AtomicBoolean(false),
            )
            val drive = buildTurnDrive(built, slot, t0, perf, channel)
            try {
                // The 200 + SSE headers are committed once respondTextWriter opens, so any failure
                // must become an honest `event: error` frame — NOT escape and leave the client an
                // empty/truncated 200 (the "empty or malformed response (HTTP 200)" class).
                // catchingTurnFailure rethrows CancellationException (the repo's blessed
                // pattern — no generic catch in this file); runCatchingCancellable (splice.core.util)
                // doesn't fit here — its catch list is I/O + (de)serialization for local best-effort
                // work, not the turn-transport failure classes emitFailure's `when` dispatches on.
                catchingTurnFailure { driveOneTurn(drive) }
                    .onFailure { e -> emitFailure(drive, e) }
            } finally {
                // Terminal frames force-flush already; this covers abandon / exception paths.
                channel.coalesced.flush()
            }
        }
    }

    /** The per-turn error boundary — catches exactly the failure classes [emitFailure] dispatches
     *  on (custom transport signals + I/O) as a [Result]; anything else, including
     *  CancellationException, propagates uncaught (structured concurrency: a cancelled turn must
     *  actually stop, not get funneled into an error frame). */
    private inline fun <R> catchingTurnFailure(block: () -> R): Result<R> =
        try {
            Result.success(block())
        } catch (e: UpstreamAuthMissing) {
            Result.failure(e)
        } catch (e: UpstreamFailed) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        }

    private fun buildTurnDrive(
        built: BuiltTurn,
        slot: InflightGate.Slot,
        t0: Long,
        perf: TurnPerf,
        channel: ClientChannel,
    ): TurnDrive {
        val meta = built.meta
        val emitter = SseEmitter.create(
            write = { frame ->
                channel.writeMutex.withLock {
                    timedClientWrite(channel.coalesced, frame, perf, channel.clientGone)
                }
            },
            model = meta.originalModel,
            usagePayload = { usage ->
                // Anthropic convention (Claude Code HUD/autocompact): input_tokens and
                // cache_read_input_tokens are DISJOINT. OpenAI's input_tokens INCLUDES the cached
                // portion, so subtract it — else input+cache_read double-counts and the context
                // bar/autocompact fire ~2x early (the "compaction ate my quota" class).
                val cached = usage?.cachedTokens ?: 0
                val nonCachedInput = ((usage?.inputTokens ?: 0) - cached).coerceAtLeast(0)
                buildUsagePayload(
                    TurnUsage(nonCachedInput, usage?.outputTokens ?: 0, 0, cached),
                    provider.catalog.contextWindowFor(meta.upstreamModel),
                )
            },
        )
        val bodyJson = built.requestBody.toString()
        perf.setCount(PerfKeys.UPSTREAM_REQ_BYTES, bodyJson.length.toLong())
        return TurnDrive(
            bodyJson = bodyJson,
            meta = meta,
            emitter = emitter,
            watchdog = TurnWatchdog(provider.watchdog, clock),
            slot = slot,
            pipeline = TurnPipeline(
                compactStats,
                log,
                makeOutputClamp(meta.clientMaxTokens, meta.compact, provider.key, log),
            ),
            t0 = t0,
            upstreamModel = meta.upstreamModel,
            perf = perf,
            turnHeaders = built.extraHeaders,
            channel = channel,
        )
    }

    /** Client-side write instrumented: frame counts/bytes, first-frame/first-delta marks, and the
     *  summed write+flush time (a slow reader shows up as write_ms, not as fake stream time).
     *  A failed write flips [clientGone] BEFORE rethrowing — the translator's terminal decision
     *  reads it to classify the ending as ClientAbandoned instead of upstream truncation. */
    private fun timedClientWrite(
        coalesced: ImmediateSseWriter,
        frame: String,
        perf: TurnPerf,
        clientGone: AtomicBoolean,
    ) {
        val t = clock()
        try {
            coalesced.write(frame)
        } catch (e: IOException) {
            clientGone.set(true)
            throw e
        }
        perf.add(PerfKeys.WRITE_MS, clock() - t)
        perf.add(PerfKeys.FRAMES_OUT, 1)
        perf.add(PerfKeys.BYTES_OUT, frame.length.toLong())
        perf.markOnce(PerfKeys.FIRST_FRAME)
        if (frame.startsWith(DELTA_FRAME_PREFIX)) perf.markOnce(PerfKeys.FIRST_DELTA)
    }

    /** One honest error frame per failure class; anything that is not a known turn failure and
     *  not a RuntimeException (i.e. an Error) rethrows — never swallowed. */
    private suspend fun emitFailure(drive: TurnDrive, e: Throwable) {
        when (e) {
            is UpstreamAuthMissing -> {
                log(telemetry.errTurn("auth-missing", drive, ": ${e.message}"))
                drive.emitter.emitError(
                    ErrorType.AUTHENTICATION,
                    "claudex: no upstream credentials — run: claudex login",
                )
                telemetry.recordPerf(drive, "error:auth-missing")
            }
            is UpstreamFailed -> {
                val failure = UpstreamFailureClassifier.classify(FailureSource.HTTP, e.body, e.status)
                val detail = "type=${failure.type.wireName} status=${e.status} msg=${failure.message.take(ERR_SNIPPET)}"
                log(telemetry.errTurn("upstream-failed", drive, detail))
                val message = if (failure.type == ErrorType.AUTHENTICATION && provider.loginCommand.isNotEmpty()) {
                    "${failure.message} — run: ${provider.loginCommand}"
                } else {
                    failure.message
                }
                drive.emitter.emitError(failure.type, message)
                telemetry.recordPerf(drive, "error:upstream-failed")
            }
            is IOException -> {
                log(telemetry.errTurn("conn-reset", drive, ": ${e.message}"))
                drive.emitter.emitError(
                    ErrorType.OVERLOADED,
                    "claudex: upstream connection failed (${e.message}) — retry",
                )
                telemetry.recordPerf(drive, "error:conn-reset")
            }
            is RuntimeException -> {
                // e.g. a URL-parse error from a bad base_url, an IllegalState out of Ktor
                // internals. Previously ESCAPED: truncated 200, no error frame, no perf row.
                log(telemetry.errTurn("unexpected", drive, ": ${e.javaClass.simpleName} ${e.message}"))
                drive.emitter.emitError(ErrorType.API_ERROR, "claudex: internal gateway error (${e.message}) — retry")
                telemetry.recordPerf(drive, "error:unexpected")
            }
            else -> throw e // Errors (OOM etc.) are not turn failures — never masked
        }
    }

    // The turn coroutine is a CHILD job: the watchdog cancels just the turn subtree (then the
    // blocking Writer still lets the honest error frame out), while a client disconnect cancels
    // the PARENT call and propagates DOWN into the turn — a parentless Job() severed that, so
    // Esc'd turns kept streaming upstream and pinning gate slots until the watchdog cap
    // (the audit's top concurrency finding, 2026-07-18).
    private suspend fun driveOneTurn(drive: TurnDrive) {
        val parent = currentCoroutineContext()[Job]
        // CompletableJob completed in finally: a plain child Job never completes on its own and
        // would park the PARENT call forever after the turn returns.
        val turnJob = Job(parent)
        try {
            withContext(turnJob) {
                driveWithinTurnJob(drive, this)
            }
        } finally {
            turnJob.complete()
        }
    }

    private suspend fun driveWithinTurnJob(drive: TurnDrive, self: CoroutineScope) {
        val turnJob: Job = self.coroutineContext[Job]!!
        upstream.post(
            url = provider.upstreamUrl,
            bodyJson = drive.bodyJson,
            auth = provider.auth,
            extraHeaders = { creds -> provider.extraHeaders(creds) + drive.turnHeaders },
            onRetry = { log("[${provider.key}] $it\n") },
            perf = drive.perf,
            clientFrameEmitted = { drive.perf.hasMark(PerfKeys.FIRST_FRAME) },
        ) { resp ->
            drive.slot.touch()
            val poller = drive.watchdog.launchIn(self, drive.slot, turnJob)
            val pinger = self.launchClientPinger(drive, turnJob)
            var sawEvent = false
            var malformedLogged = false
            val zeroEventSnippet = StringBuilder(ZERO_EVENT_SNIPPET_CHARS)
            val events = sseJsonEvents(
                resp.bodyChannel(),
                onBytes = { chunkBytes ->
                    drive.slot.touch()
                    drive.watchdog.markByte()
                    drive.perf.markOnce(PerfKeys.FIRST_BYTE)
                    drive.perf.add(PerfKeys.SSE_BYTES_IN, chunkBytes.toLong())
                },
                onMalformed = { snippet -> malformedLogged = onMalformedFrame(drive, snippet, malformedLogged) },
                onRawText = { text ->
                    val room = ZERO_EVENT_SNIPPET_CHARS - zeroEventSnippet.length
                    if (!sawEvent && room > 0) {
                        zeroEventSnippet.append(text, 0, minOf(text.length, room))
                    }
                },
            ).onEach {
                sawEvent = true
                drive.perf.add(PerfKeys.EVENTS_IN, 1)
            }
            val signals = TurnSignals(
                watchdogFired = { drive.watchdog.fired },
                clientGone = { drive.channel.clientGone.get() },
            )
            val rawOutcome = provider.streamTranslator(drive.meta, signals)
                .driveTurn(events, drive.emitter)
            poller.cancel()
            pinger.cancel()
            drive.perf.mark(PerfKeys.STREAM_END)
            val outcome = classifyZeroEventFailure(drive, rawOutcome, zeroEventSnippet.toString())
            finishTurn(drive, outcome)
        }
    }

    /** G9: malformed SSE frames were dropped with zero telemetry. Counts every skip; logs the
     *  first offending snippet once per turn (truncated) — never influences [TurnOutcome], the
     *  skip stays silent to the client (L3 stays intact). Returns the updated logged flag. */
    private fun onMalformedFrame(drive: TurnDrive, snippet: String, alreadyLogged: Boolean): Boolean {
        drive.perf.add(PerfKeys.FRAMES_SKIPPED, 1)
        if (alreadyLogged) return true
        log("[${provider.key}] malformed SSE frame skipped: ${snippet.take(ERR_SNIPPET)}\n")
        return true
    }

    /** G2: a zero-event HTTP-200 stream was hardcoded OVERLOADED — undiagnosable, and Claude Code
     *  retries a dead head forever. When the SSE reader parsed literally zero JSON frames from the
     *  body (events_in == 0) and non-blank raw text was captured before that, classify it via
     *  UpstreamFailureClassifier instead of trusting the translator's generic truncation verdict —
     *  an auth-shaped dead-head body (HTML login page, "unauthorized"/"token expired" JSON) now
     *  surfaces AUTHENTICATION with a login hint instead of a retryable OVERLOADED that spins
     *  forever. A genuinely empty body (no bytes at all — a real stall) has nothing to classify and
     *  keeps the translator's original verdict. */
    private fun classifyZeroEventFailure(drive: TurnDrive, outcome: TurnOutcome, snippet: String): TurnOutcome {
        if (outcome !is TurnOutcome.Failure) return outcome
        // events_in == 0 means zero JSON frames parsed; a blank body is a true stall (nothing to
        // classify) — either case keeps the translator's original verdict.
        val eventsIn = drive.perf.snapshot().counters[PerfKeys.EVENTS_IN] ?: 0L
        if (eventsIn != 0L || snippet.isBlank()) return outcome
        val classified = UpstreamFailureClassifier.classify(FailureSource.SSE, snippet)
        log(
            telemetry.errTurn(
                "zero-event",
                drive,
                "was=${outcome.type.wireName} classified=${classified.type.wireName} " +
                    "snippet=\"${snippet.take(ERR_SNIPPET).replace("\n", "\\n").replace("\r", "")}\"",
            ),
        )
        val message = if (classified.type == ErrorType.AUTHENTICATION) {
            "${classified.message} — run: claudex login"
        } else {
            classified.message
        }
        return TurnOutcome.Failure(classified.type, message)
    }

    /** Terminal frames FIRST (finishStream), stats after: the usage-file rewrite and the cache
     *  log line must never sit between the last delta and message_stop on the wire. */
    private suspend fun finishTurn(drive: TurnDrive, outcome: TurnOutcome) {
        val latencyMs = clock() - drive.t0
        log(telemetry.turnLine(drive.meta, drive.upstreamModel, outcome, latencyMs))
        val outcomeTag = drive.pipeline.finishStream(drive.emitter, outcome, drive.meta, latencyMs)
        drive.perf.mark(PerfKeys.FINISH)
        (outcome as? TurnOutcome.Success)?.let { s ->
            drive.perf.setCount(PerfKeys.IN_TOKENS, s.usage.inputTokens)
            drive.perf.setCount(PerfKeys.OUT_TOKENS, s.usage.outputTokens)
            drive.perf.setCount(PerfKeys.CACHED_TOKENS, s.usage.cachedTokens)
            drive.perf.timed(PerfKeys.USAGE_MS) { usageStore.appendOutputTokens(s.usage.outputTokens) }
            val usageObj = buildJsonObject {
                put("input_tokens", s.usage.inputTokens)
                put("output_tokens", s.usage.outputTokens)
                put("input_tokens_details", buildJsonObject { put("cached_tokens", s.usage.cachedTokens) })
            }
            log(cacheLogLine(provider.key, drive.upstreamModel, usageObj, drive.meta.compact))
        }
        telemetry.recordPerf(drive, outcomeTag)
    }

    // Client-liveness pinger: an SSE COMMENT (spec-legal, ignored by every parser) every interval.
    // With NO downstream writes flowing (prefill, thinking pause) a dead client is otherwise
    // invisible — the disconnect load test measured slots pinned for the whole watchdog budget.
    // A failed ping flips clientGone and cancels just the turn.
    private fun CoroutineScope.launchClientPinger(drive: TurnDrive, turnJob: Job) =
        launch {
            while (isActive) {
                delay(CLIENT_PING_INTERVAL_MS)
                try {
                    drive.channel.writeMutex.withLock { drive.channel.coalesced.write(SSE_KEEPALIVE_COMMENT) }
                } catch (e: IOException) {
                    drive.channel.clientGone.set(true)
                    log("[${provider.key}] client gone (keepalive write failed: ${e.message}) — cancelling turn\n")
                    turnJob.cancel()
                    return@launch
                }
            }
        }

    private companion object {
        const val ERR_SNIPPET = 200

        // G2: cap the raw pre-JSON body buffered for zero-event classification (~1KB).
        const val ZERO_EVENT_SNIPPET_CHARS = 1024

        // first_delta detection reads the frame prefix — the emitter's event name, not a literal
        // stop-reason (L3 walls stay intact; this only OBSERVES the already-built frame).
        const val DELTA_FRAME_PREFIX = "event: content_block_delta"

        // SSE comment keepalive: pure transport, invisible to SSE parsers (spec: lines starting
        // with ':' are comments) — exists ONLY so a dead client fails a write promptly.
        const val SSE_KEEPALIVE_COMMENT = ": ping\n\n"
        const val CLIENT_PING_INTERVAL_MS = 10_000L
    }
}

/** Renders the per-turn observability: the turn line, error lines, and the perf row+line.
 *  Split out so the driver stays drive-only (the audit's god-file finding). */
internal class TurnTelemetry(
    private val headKey: String,
    private val perfStats: PerfStats,
    private val log: (String) -> Unit,
    private val clock: () -> Long,
) {
    /** The sole perf-row emitter: total mark, one JSONL row, one log line. Never throws. */
    fun recordPerf(drive: TurnDrive, outcomeTag: String) {
        drive.perf.mark(PerfKeys.TOTAL)
        val snap = drive.perf.snapshot()
        perfStats.record(PerfRowMeta(drive.upstreamModel, outcomeTag, drive.meta.compact), snap)
        log(perfLine(headKey, outcomeTag, drive.meta.compact, drive.upstreamModel, snap))
    }

    fun errTurn(kind: String, drive: TurnDrive, detail: String): String =
        "[$headKey] turn ERROR $kind compact=${drive.meta.compact} latency=${clock() - drive.t0}ms $detail\n"

    // Per-turn telemetry: outcome + latency (+ tokens/type). The compaction-stall and API-error
    // signals live here — a compact turn that FAILUREs or runs many seconds is now visible.
    fun turnLine(meta: TurnMeta, model: String, outcome: TurnOutcome, latencyMs: Long): String {
        val base = "[$headKey] turn compact=${meta.compact} model=$model latency=${latencyMs}ms"
        return base + when (outcome) {
            is TurnOutcome.Success ->
                " ok out=${outcome.usage.outputTokens} tool=${outcome.hasToolUse} incomplete=${outcome.incomplete}\n"
            is TurnOutcome.Failure ->
                " FAILURE type=${outcome.type.wireName} msg=${outcome.message.take(ERR_SNIPPET)}\n"
            TurnOutcome.ClientAbandoned -> " client-abandoned\n"
        }
    }

    private companion object {
        const val ERR_SNIPPET = 200
    }
}
