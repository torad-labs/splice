// NEW: split from HeadServer (2026-07-18, the audit's god-file finding — done properly instead
// of suppressed): everything PER-TURN lives here. HeadServer owns the server shell + admission;
// this file owns the drive: SSE channel wiring → upstream POST → watchdog + client-liveness
// pinger → translator → honesty pipeline → sole terminal → telemetry. TurnTelemetry renders the
// per-turn log lines + the perf JSONL row so the driver stays drive-only.
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.model.ModelCatalog
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.perf.perfLine
import splice.core.turn.ErrorType
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.gateway.perf.PerfRowMeta
import splice.gateway.perf.PerfStats
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.usage.TurnUsage
import splice.gateway.usage.buildUsagePayload
import splice.gateway.usage.cacheLogLine
import splice.gateway.usage.makeOutputClamp
import splice.gateway.wire.BufferingWireSink
import splice.gateway.wire.CollectingTerminal
import splice.gateway.wire.ImmediateSseWriter
import splice.gateway.wire.SseEmitter
import splice.gateway.wire.TurnTerminal
import splice.gateway.wire.UsagePayloadBuilder
import splice.spi.BuiltTurn
import splice.spi.FailureSource
import splice.spi.FoldController
import splice.spi.FoldRound
import splice.spi.InflightGate
import splice.spi.Provider
import splice.spi.ReanchorController
import splice.spi.ReanchorRound
import splice.spi.SseFrameTooLargeException
import splice.spi.StreamTornBeforeClient
import splice.spi.TurnSignals
import splice.spi.TurnWatchdog
import splice.spi.UpstreamAuthMissing
import splice.spi.UpstreamFailed
import splice.spi.UpstreamFailureClassifier
import splice.spi.UpstreamResponse
import splice.spi.WireSink
import splice.spi.sseJsonEvents
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The per-turn collaborators + data the drive needs, grouped so the drive signature stays one
 *  cohesive argument (they are all created together per request inside the SSE writer). */
internal data class TurnDrive(
    val bodyJson: String,
    /** The same request as [bodyJson], kept typed so reasoning-continuation folding can extend its
     *  `input` and re-POST without re-parsing (non-fold turns never read it). */
    val requestBody: JsonObject,
    val meta: TurnMeta,
    val emitter: TurnTerminal,
    val watchdog: TurnWatchdog,
    val slot: InflightGate.Slot,
    val pipeline: TurnPipeline,
    val t0: Long,
    val upstreamModel: String,
    val perf: TurnPerf,
    /** Per-turn upstream headers from BuiltTurn (e.g. grok conv-id affinity). */
    val turnHeaders: Map<String, String>,
    /** Runner liveness gates + the health hook for absorbed round failures (built once in
     *  assembleDrive; one construction site, the policies never drift). */
    val signals: RunnerSignals,
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

/** Admission-time inputs threaded into a drive — grouped so the drive assembler stays one
 *  cohesive argument across the stream and collect entries. */
internal data class TurnInputs(
    val built: BuiltTurn,
    val slot: InflightGate.Slot,
    val t0: Long,
    val perf: TurnPerf,
)

private fun connectionResetMessage(error: Throwable): String? =
    if (error is StreamTornBeforeClient) error.cause?.message else error.message

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
    private val health = HeadHealthCounters()

    /** G20: passive health snapshot for HeadServer.healthSnapshot() — the control-plane's
     *  /api/heads aggregation, never the per-head /health liveness route (external contract). */
    internal fun healthCounters(): HeadHealthCounts = health.snapshot()

    /** Open the SSE writer, wire the per-turn collaborators, run the single turn. */
    suspend fun stream(call: ApplicationCall, built: BuiltTurn, slot: InflightGate.Slot, t0: Long, perf: TurnPerf) {
        val inputs = TurnInputs(built, slot, t0, perf)
        call.respondTextWriter(ContentType.Text.EventStream) {
            // Flush-per-frame: a frame buffered across an upstream lull is invisible to the
            // user exactly when responsiveness matters (see ImmediateSseWriter header).
            val channel = ClientChannel(
                coalesced = ImmediateSseWriter(writeRaw = { frame -> write(frame) }, flushRaw = { flush() }),
                writeMutex = Mutex(),
                clientGone = AtomicBoolean(false),
            )
            val emitter = SseEmitter.create(
                write = { frame ->
                    channel.writeMutex.withLock {
                        timedClientWrite(channel.coalesced, frame, perf, channel.clientGone, clock)
                    }
                },
                model = built.meta.originalModel,
                usagePayload = usagePayloadBuilder(provider.catalog, built.meta),
            )
            val drive = assembleDrive(inputs, emitter, channel)
            try {
                // The 200 + SSE headers are committed once respondTextWriter opens, so any failure
                // must become an honest `event: error` frame — NOT escape and leave the client an
                // empty/truncated 200 (the "empty or malformed response (HTTP 200)" class).
                driveSealingCancellation(drive)
            } finally {
                // Terminal frames force-flush already; this covers abandon / exception paths.
                channel.coalesced.flush()
            }
        }
    }

    /** Drive one turn, emit classified failures, and — if a cancellation lands (head stop,
     *  write-timeout, parent cancel) — seal the terminal honestly before rethrowing: client-gone
     *  → abandon (nothing on the wire); still-connected → an honest error frame so Claude Code
     *  retries instead of seeing a truncated HTTP 200. ONE copy, shared by [stream] and
     *  [collect], so the sealing contract cannot drift between them.
     *
     *  [seal] gates the cancellation seal to the STREAM path only: [collect] passes seal=false —
     *  it never commits a 200 before its terminal respondText, so a cancelled collect has no
     *  half-open response to rescue; sealing there only wrote an error body nobody reads while
     *  polluting localOriginErrors (review 2026-07-22 round 3).
     *
     *  catchingTurnFailure rethrows CancellationException (caught HERE, after sealing);
     *  runCatchingCancellable (splice.core.util) doesn't fit — its catch list is I/O +
     *  (de)serialization for local best-effort work, not the turn-transport failure classes
     *  emitFailure dispatches on. */
    private suspend fun driveSealingCancellation(
        drive: TurnDrive,
        pingClient: Boolean = true,
        seal: Boolean = true,
    ) {
        try {
            catchingTurnFailure { driveOneTurn(drive, pingClient) }
                .onFailure { e -> emitFailure(drive, e) }
        } catch (e: CancellationException) {
            // Flat when (not nested if) so the still-connected try/catch stays at nesting depth 3:
            // catch → if(seal) → if(clientGone) → try would trip NestedBlockDepth's depth-4 ceiling.
            when {
                !seal || drive.emitter.hasEnded -> Unit
                drive.channel.clientGone.get() -> {
                    drive.emitter.abandon()
                    telemetry.recordPerf(drive, "client_abort")
                }
                // clientGone flips only on a FAILED write, but Ktor/Netty cancels on
                // channel-inactive with no write having failed — a user abort mid-lull reaches here
                // still "connected". Emit FIRST; if the error frame can't reach the wire the cancel
                // WAS a client disconnect the ping/write path hadn't flagged, so reclassify it as an
                // abandon, not an error:cancelled (review 2026-07-22 round 3).
                else ->
                    try {
                        drive.emitter.emitError(
                            ErrorType.OVERLOADED,
                            "${provider.key}: turn cancelled — retry",
                        )
                        log(telemetry.errTurn("cancelled", drive, ": turn cancelled before terminal"))
                        telemetry.recordPerf(drive, "error:cancelled")
                        health.local()
                    } catch (io: IOException) {
                        // emitError's error frame could not reach the wire — the cancel WAS a client
                        // disconnect the ping/write path hadn't flagged. Reclassify as a benign
                        // abandon (emitError already sealed on IOException; the set is idempotent),
                        // NOT an error:cancelled — no health bump (review 2026-07-22 round 3).
                        log("[${provider.key}] turn cancelled + error frame unwritable (${io.message}) — client gone\n")
                        drive.emitter.abandon()
                        telemetry.recordPerf(drive, "client_abort")
                    }
            }
            throw e
        }
    }

    /** Non-stream sibling of [stream]: Claude Code sends stream:false on some internal calls (the
     *  Node predecessor served them by collecting the terminal object). Drives the SAME
     *  fold/translator/honesty machinery into a [CollectingTerminal], then writes ONE Anthropic
     *  Messages JSON body — no SSE channel, no liveness pinger. */
    suspend fun collect(call: ApplicationCall, built: BuiltTurn, slot: InflightGate.Slot, t0: Long, perf: TurnPerf) {
        val terminal = CollectingTerminal(built.meta.originalModel, usagePayloadBuilder(provider.catalog, built.meta))
        // Inert channel: the collect path never writes SSE frames, but postRound reads clientGone
        // (stays false — a buffered client can't be observed gone mid-turn) and the drive needs one.
        val channel = ClientChannel(
            coalesced = ImmediateSseWriter(writeRaw = {}, flushRaw = {}),
            writeMutex = Mutex(),
            clientGone = AtomicBoolean(false),
        )
        val drive = assembleDrive(TurnInputs(built, slot, t0, perf), terminal, channel)
        // collect never commits a 200 before its terminal respondText — a cancelled collect is a
        // native connection abort client-side, and sealing there only wrote an error body nobody
        // reads while polluting localOriginErrors (review 2026-07-22 round 3).
        driveSealingCancellation(drive, pingClient = false, seal = false)
        call.respondText(
            terminal.responseBody().toString(),
            ContentType.Application.Json,
            HttpStatusCode.fromValue(terminal.httpStatus()),
        )
    }

    /** Assemble the per-turn drive around a terminal (SseEmitter for stream, CollectingTerminal for
     *  collect) and its channel — everything else (watchdog, pipeline, headers) is shape-neutral. */
    private fun assembleDrive(
        inputs: TurnInputs,
        emitter: TurnTerminal,
        channel: ClientChannel,
    ): TurnDrive {
        val built = inputs.built
        val perf = inputs.perf
        val meta = built.meta
        val bodyJson = built.requestBody.toString()
        perf.setCount(PerfKeys.UPSTREAM_REQ_BYTES, bodyJson.length.toLong())
        val watchdog = TurnWatchdog(provider.watchdog, clock)
        // Absorbed round failures still count for the G20 health split (code-review 2026-07-24).
        val signals = RunnerSignals(
            watchdogFired = { watchdog.fired != null },
            clientGone = { channel.clientGone.get() },
            onRoundFailure = { f ->
                log(
                    "[${provider.key}] mid-stream ${f.type.wireName} absorbed by " +
                        "re-anchor: ${f.message.take(ROUND_FAILURE_SNIPPET)}\n",
                )
                if (f.providerReported) health.provider() else health.local()
            },
        )
        return TurnDrive(
            bodyJson = bodyJson,
            requestBody = built.requestBody,
            meta = meta,
            emitter = emitter,
            watchdog = watchdog,
            slot = inputs.slot,
            pipeline = TurnPipeline(
                compactStats,
                log,
                makeOutputClamp(meta.clientMaxTokens, meta.compact, provider.key, log),
                mirrorReasoning = deps.mirrorReasoning,
            ),
            t0 = inputs.t0,
            upstreamModel = meta.upstreamModel,
            perf = perf,
            turnHeaders = built.extraHeaders,
            channel = channel,
            signals = signals,
        )
    }

    /** One honest error frame per failure class; anything that is not a known turn failure and
     *  not a RuntimeException (i.e. an Error) rethrows — never swallowed. */
    private suspend fun emitFailure(drive: TurnDrive, e: Throwable) {
        when (e) {
            is UpstreamAuthMissing -> {
                log(telemetry.errTurn("auth-missing", drive, ": ${e.message}"))
                drive.emitter.emitError(
                    ErrorType.AUTHENTICATION,
                    "${provider.key}: no upstream credentials${provider.loginHint()}",
                )
                telemetry.recordPerf(drive, "error:auth-missing")
                health.local() // no upstream call ever happened: missing local credentials
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
                health.provider() // e.status/e.body are the literal HTTP response the upstream host gave
            }
            // reissue budget exhausted (or non-retryable tear) before any client frame — an
            // upstream connection failure, honestly retryable; never "internal gateway error".
            // post-handoff socket failure: our side of the wire
            is StreamTornBeforeClient, is IOException -> emitConnReset(drive, connectionResetMessage(e))
            is SseFrameTooLargeException -> {
                log(telemetry.errTurn("upstream-frame-too-large", drive, ": ${e.message}"))
                drive.emitter.emitError(ErrorType.API_ERROR, "upstream sent an oversized streaming event — retry")
                telemetry.recordPerf(drive, "error:upstream-frame-too-large")
                health.provider()
            }
            is RuntimeException -> {
                // e.g. a URL-parse error from a bad base_url, an IllegalState out of Ktor
                // internals. Previously ESCAPED: truncated 200, no error frame, no perf row.
                log(telemetry.errTurn("unexpected", drive, ": ${e.javaClass.simpleName} ${e.message}"))
                drive.emitter.emitError(ErrorType.API_ERROR, "claudex: internal gateway error (${e.message}) — retry")
                telemetry.recordPerf(drive, "error:unexpected")
                health.local() // internal gateway bug (e.g. bad base_url parse)
            }
            else -> throw e // Errors (OOM etc.) are not turn failures — never masked
        }
    }

    // The turn coroutine is a CHILD job: the watchdog cancels just the turn subtree (then the
    // blocking Writer still lets the honest error frame out), while a client disconnect cancels
    // the PARENT call and propagates DOWN into the turn — a parentless Job() severed that, so
    // Esc'd turns kept streaming upstream and pinning gate slots until the watchdog cap
    // (the audit's top concurrency finding, 2026-07-18).
    private suspend fun driveOneTurn(drive: TurnDrive, pingClient: Boolean = true) {
        val parent = currentCoroutineContext()[Job]
        // CompletableJob completed in finally: a plain child Job never completes on its own and
        // would park the PARENT call forever after the turn returns.
        val turnJob = Job(parent)
        try {
            withContext(turnJob) {
                val self = this
                // Whole-turn client-liveness pinger (2026-07-19 storm): launched BEFORE the first
                // upstream attempt so the headers-wait (minutes on a long prefill) and the retry
                // backoffs are covered too — the per-attempt scope only started it after upstream
                // headers, so a client that hung up mid-retry left a zombie turn pinning its gate
                // slot and re-hammering the rate-limited account for a listener that was gone.
                // OFF for the non-stream collect path: there is no open SSE channel to ping (the
                // whole body is buffered and sent once), so liveness can't be probed mid-turn.
                val pinger = if (pingClient) self.launchClientPinger(drive, turnJob) else null
                try {
                    // Folding is null for sol / every non-codex head → the single-round path is
                    // byte-for-byte the pre-fold behaviour (drive straight to the real emitter,
                    // finish once). A fold-eligible turn hands the loop to FoldRunner.
                    val fold = provider.foldController(drive.meta)
                    val reanchor = provider.reanchorController(drive.meta)
                    if (fold != null) {
                        FoldRunner(
                            emitter = drive.emitter,
                            key = provider.key,
                            log = log,
                            postRound = { bodyJson, sink -> postRound(drive, bodyJson, sink, self, turnJob) },
                            finish = { outcome -> finishTurn(drive, outcome) },
                            reanchor = reanchor,
                            signals = drive.signals,
                        ).run(drive.requestBody, fold)
                    } else if (reanchor == null) {
                        finishTurn(drive, postRound(drive, drive.bodyJson, drive.emitter, self, turnJob))
                    } else {
                        ReanchorRunner(
                            key = provider.key,
                            log = log,
                            postRound = { bodyJson -> postRound(drive, bodyJson, drive.emitter, self, turnJob) },
                            finish = { outcome -> finishTurn(drive, outcome) },
                            signals = drive.signals,
                        ).run(drive.requestBody, reanchor)
                    }
                } finally {
                    pinger?.cancel()
                }
            }
        } finally {
            turnJob.complete()
        }
    }

    /** One upstream round driven into [sink]: POST → watchdog/pinger → translator → zero-event
     *  classify. The non-fold path and every fold round share this; [finishTurn] runs at the caller
     *  so the fold loop emits exactly ONE terminal across all rounds (L3). */
    private suspend fun postRound(
        drive: TurnDrive,
        bodyJson: String,
        sink: WireSink,
        self: CoroutineScope,
        turnJob: Job,
    ): TurnOutcome {
        // Per-ROUND baselines (code-review 2026-07-24): drive.perf is one cumulative TurnPerf
        // shared across re-anchor rounds — the global FIRST_FRAME mark and EVENTS_IN counter go
        // permanently stale after round 1, which (a) denied continuation rounds the safe
        // pre-frame reissue and (b) skipped the G2 zero-event reclassifier for them. Frame/event
        // facts for reissue and zero-event classification are judged against THIS round only.
        val framesBase = drive.perfCounter(PerfKeys.FRAMES_OUT)
        val eventsBase = drive.perfCounter(PerfKeys.EVENTS_IN)
        val frameEmittedThisRound = { drive.perfCounter(PerfKeys.FRAMES_OUT) > framesBase }
        return upstream.post(
            url = provider.upstreamUrl,
            bodyJson = bodyJson,
            auth = provider.auth,
            extraHeaders = { creds -> provider.extraHeaders(creds) + drive.turnHeaders },
            onRetry = { log("[${provider.key}] $it\n") },
            perf = drive.perf,
            clientFrameEmitted = frameEmittedThisRound,
        ) { resp ->
            drive.slot.touch()
            // Persist upstream rate-limit headers for /api/usage + statusline soft-warn (Node
            // codex-proxy wired this; the Kotlin split dropped the call site).
            usageStore.persistRateLimit { name -> resp.header(name) }
            // Fresh upstream round/attempt: reset the idle tier so this round's (possibly long,
            // silent) prefill is judged against firstByteTimeout, not the short streamIdle a prior
            // round's first byte would otherwise pin it to. totalCap still spans the whole turn.
            drive.watchdog.resetFirstByte()
            val poller = drive.watchdog.launchIn(self, drive.slot, turnJob)
            // Leak wall (review 2026-07-19): the attempt's poller dies on EVERY exit of this
            // block — a torn-then-reissued stream used to leak it into `self`, pinning the
            // admission slot ~streamIdle past turn completion. (The client pinger is whole-turn
            // now — launched once in driveOneTurn, cancelled there.)
            try {
                val capture = ZeroEventCapture()
                val events = tearAwareEvents(drive, resp, capture, frameEmittedThisRound)
                val signals = TurnSignals(
                    watchdogFired = { drive.watchdog.fired },
                    clientGone = { drive.channel.clientGone.get() },
                )
                val rawOutcome = provider.streamTranslator(drive.meta, signals).driveTurn(events, sink)
                drive.perf.mark(PerfKeys.STREAM_END)
                classifyZeroEventFailure(drive, rawOutcome, capture.snippet.toString(), eventsBase)
            } finally {
                poller.cancel()
            }
        }
    }

    /** Raw-body capture for the G2 zero-event classifier, threaded through [tearAwareEvents]. */
    private class ZeroEventCapture {
        var sawEvent = false
        var malformedLogged = false
        val snippet = StringBuilder(ZERO_EVENT_SNIPPET_CHARS)
    }

    /** The upstream SSE event flow with instrumentation + the G5 pre-frame tear rethrow: a
     *  transport tear BEFORE any client frame must reach the reissue machinery in UpstreamClient.
     *  The translators swallow IOException into the honest terminal — right for every post-frame
     *  case, but it made the pre-frame reissue unreachable (review 2026-07-19). Rethrown as
     *  [StreamTornBeforeClient] (plain RuntimeException) so no translator catch matches. */
    private suspend fun tearAwareEvents(
        drive: TurnDrive,
        resp: UpstreamResponse,
        capture: ZeroEventCapture,
        frameEmittedThisRound: () -> Boolean,
    ) =
        sseJsonEvents(
            resp.bodyChannel(),
            onBytes = { chunkBytes ->
                drive.slot.touch()
                drive.watchdog.markByte()
                drive.perf.markOnce(PerfKeys.FIRST_BYTE)
                drive.perf.add(PerfKeys.SSE_BYTES_IN, chunkBytes.toLong())
            },
            // G9: count every skipped malformed frame; log the first snippet once per turn —
            // never influences [TurnOutcome] (L3 stays intact; the skip is silent to the client).
            onMalformed = { sn ->
                drive.perf.add(PerfKeys.FRAMES_SKIPPED, 1)
                if (!capture.malformedLogged) {
                    log("[${provider.key}] malformed SSE frame skipped: ${sn.take(ERR_SNIPPET)}\n")
                }
                capture.malformedLogged = true
            },
            onRawText = { text ->
                val room = ZERO_EVENT_SNIPPET_CHARS - capture.snippet.length
                if (!capture.sawEvent && room > 0) {
                    capture.snippet.append(text, 0, minOf(text.length, room))
                }
                !capture.sawEvent && capture.snippet.length < ZERO_EVENT_SNIPPET_CHARS
            },
        ).onEach {
            capture.sawEvent = true
            drive.perf.add(PerfKeys.EVENTS_IN, 1)
        }.catch { e ->
            // Per-round (not per-turn) pre-frame test: a continuation round's early tear is as
            // safely reissuable as a first round's — its own body re-POSTs (code-review 2026-07-24).
            if (e is IOException && !frameEmittedThisRound()) {
                throw StreamTornBeforeClient(e)
            }
            throw e
        }

    /** G2: a zero-event HTTP-200 stream was hardcoded OVERLOADED — undiagnosable, and Claude Code
     *  retries a dead head forever. When the SSE reader parsed literally zero JSON frames from the
     *  body (events_in == 0) and non-blank raw text was captured before that, classify it via
     *  UpstreamFailureClassifier instead of trusting the translator's generic truncation verdict —
     *  an auth-shaped dead-head body (HTML login page, "unauthorized"/"token expired" JSON) now
     *  surfaces AUTHENTICATION with a login hint instead of a retryable OVERLOADED that spins
     *  forever. A genuinely empty body (no bytes at all — a real stall) has nothing to classify and
     *  keeps the translator's original verdict. */
    private fun classifyZeroEventFailure(
        drive: TurnDrive,
        outcome: TurnOutcome,
        snippet: String,
        eventsBase: Long,
    ): TurnOutcome {
        if (outcome !is TurnOutcome.Failure) return outcome
        // THIS ROUND's events (cumulative counter minus the round's baseline): zero JSON frames
        // parsed means a dead-head body; a blank body is a true stall (nothing to classify) —
        // either case keeps the translator's original verdict.
        val eventsIn = drive.perfCounter(PerfKeys.EVENTS_IN) - eventsBase
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
            "${classified.message}${provider.loginHint()}"
        } else {
            classified.message
        }
        // classified from a body the provider actually sent — a provider-reported failure (G20).
        // copy() preserves the (empty) partial: a full-constructor rebuild would silently drop
        // the field on any future broadening of this path (code-review 2026-07-24).
        return outcome.copy(type = classified.type, message = message, providerReported = true)
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
        // G20 (corrected, review 2026-07-19): attribution rides the outcome's provenance flag, not
        // the ErrorType — the old OVERLOADED-implies-local heuristic misfiled a passthrough
        // provider's genuine overloaded_error as local-origin. providerReported is set ONLY where a
        // translator parsed an error the upstream actually sent.
        if (outcome is TurnOutcome.Failure) {
            if (outcome.providerReported) health.provider() else health.local()
        }
        telemetry.recordPerf(drive, outcomeTag)
    }

    // Client-liveness pinger: an SSE COMMENT (spec-legal, ignored by every parser) every interval.
    // With NO downstream writes flowing (headers-wait on a long prefill, retry backoff, thinking
    // pause) a dead client is otherwise invisible — the disconnect load test measured slots pinned
    // for the whole watchdog budget, and the 2026-07-19 429 storm stacked ~650 zombie turns whose
    // clients had re-sent minutes earlier. Whole-turn scope (driveOneTurn), NOT per-attempt.
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

    /** One conn-reset surface for raw tears and reissue-exhausted [StreamTornBeforeClient]. */
    private suspend fun emitConnReset(drive: TurnDrive, detail: String?) {
        log(telemetry.errTurn("conn-reset", drive, ": $detail"))
        drive.emitter.emitError(
            ErrorType.OVERLOADED,
            "${provider.key}: upstream connection failed ($detail) — retry",
        )
        telemetry.recordPerf(drive, "error:conn-reset")
        health.local()
    }

    /** Head restart = fresh diagnostic baseline (the HeadHealth doc's promised behavior; the
     *  counters lived through control-plane restarts before — review 2026-07-19). */
    internal fun resetHealth() = health.reset()

    private companion object {
        const val ERR_SNIPPET = 200

        // G2: cap the raw pre-JSON body buffered for zero-event classification (~1KB).
        const val ZERO_EVENT_SNIPPET_CHARS = 1024

        // SSE comment keepalive: pure transport, invisible to SSE parsers (spec: lines starting
        // with ':' are comments) — exists ONLY so a dead client fails a write promptly.
        const val SSE_KEEPALIVE_COMMENT = ": ping\n\n"
        const val CLIENT_PING_INTERVAL_MS = 10_000L
    }
}

/** G19-consistent per-head hint — every AUTHENTICATION surface uses the SAME provider-threaded
 *  command (review 2026-07-19: two paths still hardcoded "claudex login" on non-codex heads). */
private fun Provider.loginHint(): String =
    if (loginCommand.isNotEmpty()) " — run: $loginCommand" else ""

// first_delta detection reads the frame prefix — the emitter's event name, not a literal
// stop-reason (L3 walls stay intact; this only OBSERVES the already-built frame).
private const val DELTA_FRAME_PREFIX = "event: content_block_delta"

/** Client-side write instrumented: frame counts/bytes, first-frame/first-delta marks, and the
 *  summed write+flush time (a slow reader shows up as write_ms, not as fake stream time).
 *  A failed write flips [clientGone] BEFORE rethrowing — the translator's terminal decision
 *  reads it to classify the ending as ClientAbandoned instead of upstream truncation.
 *  Top-level (not a TurnDriver method) so it doesn't grow the class's function budget. */
private fun timedClientWrite(
    coalesced: ImmediateSseWriter,
    frame: String,
    perf: TurnPerf,
    clientGone: AtomicBoolean,
    clock: () -> Long,
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

/** The per-turn error boundary — captures exactly the failure classes [TurnDriver.emitFailure]
 *  dispatches on: the custom transport signals, I/O, and the two documented gateway-bug classes
 *  (IllegalArgument/IllegalState — a bad base_url parse, a Ktor internal state error), which
 *  previously escaped as a truncated 200 with no error frame (review 2026-07-19). Top-level (not a
 *  TurnDriver method) so the stream and collect entries share one boundary without growing the
 *  class's function budget. */
private inline fun <R> catchingTurnFailure(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: UpstreamAuthMissing) {
        Result.failure(e)
    } catch (e: UpstreamFailed) {
        Result.failure(e)
    } catch (e: StreamTornBeforeClient) {
        // Plain RuntimeException (so translators don't swallow it into a terminal). After
        // UpstreamClient exhausts MAX_STREAM_REISSUES it rethrows here — must become emitConnReset,
        // not escape respondTextWriter as a truncated HTTP 200 SSE.
        Result.failure(e)
    } catch (e: SseFrameTooLargeException) {
        Result.failure(e)
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: CancellationException) {
        // CancellationException extends IllegalStateException — rethrown BEFORE it so a
        // cancelled turn actually stops; stream()/collect() seal the emitter then rethrow.
        throw e
    } catch (e: IllegalArgumentException) {
        // e.g. a URL-parse error from a bad base_url (review 2026-07-19: previously escaped
        // as a truncated 200 with no error frame — emitFailure's branch was unreachable)
        Result.failure(e)
    } catch (e: IllegalStateException) {
        // e.g. an IllegalState out of Ktor internals — same escape class as above
        Result.failure(e)
    }

/** The Anthropic usage payload builder — shared by the stream emitter and the non-stream collector
 *  so both report tokens identically. Top-level to keep it off TurnDriver's function budget. */
private fun usagePayloadBuilder(catalog: ModelCatalog, meta: TurnMeta): UsagePayloadBuilder = { usage ->
    // Anthropic convention (Claude Code HUD/autocompact): input_tokens and cache_read_input_tokens
    // are DISJOINT. OpenAI's input_tokens INCLUDES the cached portion, so subtract it — else
    // input+cache_read double-counts and the context bar/autocompact fire ~2x early (the
    // "compaction ate my quota" class).
    val cached = usage?.cachedTokens ?: 0
    val nonCachedInput = ((usage?.inputTokens ?: 0) - cached).coerceAtLeast(0)
    buildUsagePayload(
        TurnUsage(nonCachedInput, usage?.outputTokens ?: 0, 0, cached),
        catalog.contextWindowFor(meta.upstreamModel),
    )
}

/** The reasoning-continuation fold state machine (codex 518n-2), split from TurnDriver (its function
 *  budget). Drives rounds via [postRound], BUFFERING each round's tentative final output while
 *  reasoning streams live; a truncated round's output is DISCARDED and the next round re-POSTed with
 *  its reasoning replayed; the terminal round's output is FLUSHED and [finish] called exactly ONCE
 *  with usage summed across every round — one honest terminal downstream (L3). */
private fun TurnDrive.perfCounter(key: String): Long = perf.snapshot().counters[key] ?: 0L

private const val ROUND_FAILURE_SNIPPET = 160

internal class FoldRunner(
    // Only the buffer's `real` sink — never a terminal here (L3: FoldRunner finishes via [finish]).
    private val emitter: WireSink,
    private val key: String,
    private val log: (String) -> Unit,
    private val postRound: suspend (bodyJson: String, sink: WireSink) -> TurnOutcome,
    private val finish: suspend (TurnOutcome) -> Unit,
    private val reanchor: ReanchorController? = null,
    private val signals: RunnerSignals = RunnerSignals(),
) {
    suspend fun run(initialBody: JsonObject, fold: FoldController) {
        var body = initialBody
        var acc = RoundUsage()
        var roundIndex = 0
        var reanchorAttempt = 0
        val salvaged = mutableListOf<TurnOutcome.PartialRound>()
        val absorbedFailures = mutableListOf<TurnOutcome.Failure>()
        while (true) {
            val buffer = BufferingWireSink(emitter)
            val outcome = postRound(body.toString(), buffer)
            val success = outcome as? TurnOutcome.Success
            if (success != null) acc = acc.plusRound(success.usage)
            val next = success?.let { fold.continuation(FoldRound(body, it, roundIndex)) }
            if (next != null) {
                buffer.discard()
                val reasoningTokens = success.usage.reasoningTokens
                log("[$key] fold round ${roundIndex + 1}: reasoning truncated at $reasoningTokens tokens, continuing\n")
                body = next
                roundIndex++
                continue
            }
            // Trigger B (code-review 2026-07-24: fold-eligible models — the truncation-prone
            // ones — previously had NO re-anchor cover). The round's final output was BUFFERED,
            // never forwarded, so bodyText is stripped from the salvage: replaying
            // never-forwarded prose as "already written" would desync the client's wire; the
            // retried round re-answers cleanly from its reasoning envelopes. Live thinking
            // already on the wire stays (append-only).
            val retry = continuationForFailedRound(outcome, body, reanchorAttempt)
            if (retry == null) {
                // health for absorbed rounds only on ultimate success — see ReanchorRunner.
                if (outcome is TurnOutcome.Success) absorbedFailures.forEach(signals.onRoundFailure)
                finalize(outcome, buffer, salvaged, acc.toUsage())
                return
            }
            val failure = outcome as TurnOutcome.Failure
            absorbedFailures.add(failure)
            failure.partial?.let { p ->
                salvaged.add(p.copy(bodyText = ""))
                acc = acc.plusRound(p.usage)
            }
            buffer.discard()
            log("[$key] fold re-anchor ${reanchorAttempt + 1}: ${failure.type.wireName} mid-round — retrying\n")
            body = retry
            reanchorAttempt++
        }
    }

    private fun continuationForFailedRound(outcome: TurnOutcome, body: JsonObject, attempt: Int): JsonObject? =
        when {
            reanchor == null || outcome !is TurnOutcome.Failure -> null
            signals.watchdogFired() || signals.clientGone() -> null
            else -> reanchor.continuationForFailure(
                ReanchorRound(body, outcome.copy(partial = outcome.partial?.copy(bodyText = "")), attempt),
            )
        }

    private suspend fun finalize(
        outcome: TurnOutcome,
        buffer: BufferingWireSink,
        salvaged: List<TurnOutcome.PartialRound>,
        summed: Usage,
    ) {
        if (outcome is TurnOutcome.Success) {
            buffer.flush()
            finish(mergedAcrossRounds(outcome.copy(usage = summed), salvaged))
        } else {
            // a failed/abandoned round has no honest final output to flush — drop the buffer, then
            // emit the round's real (error/abandon) outcome. Never a fabricated clean stop (L3).
            buffer.discard()
            finish(outcome)
        }
    }
}

/** Shared per-loop collaborators for the round runners: liveness gates + the health hook for
 *  absorbed failures (one construction site in driveOneTurn — the policies never drift apart). */
internal data class RunnerSignals(
    val watchdogFired: () -> Boolean = { false },
    val clientGone: () -> Boolean = { false },
    val onRoundFailure: (TurnOutcome.Failure) -> Unit = {},
)

/** The round-usage law, ONE implementation for both runners (2026-07-20, unified in the
 *  code-review 2026-07-24): each continuation re-sends the ENTIRE conversation, so input/cached
 *  are CUMULATIVE — round N already includes round N-1's; summing them (the old `Usage.plus`)
 *  inflated the client-visible prompt up to ~Nx, firing the context bar / autocompact early.
 *  Only output/reasoning genuinely accrue per round. */
internal data class RoundUsage(
    val lastInput: Long = 0,
    val lastCached: Long = 0,
    val outSum: Long = 0,
    val reasoningSum: Long = 0,
) {
    fun plusRound(u: Usage) = RoundUsage(
        lastInput = u.inputTokens,
        lastCached = u.cachedTokens,
        outSum = outSum + u.outputTokens,
        reasoningSum = reasoningSum + u.reasoningTokens,
    )

    fun toUsage() = Usage(lastInput, outSum, lastCached, reasoningSum)
}

/** Cross-round merge (code-review 2026-07-24): the post-stream pipeline — empty-model honesty
 *  gate, promote-to-text, reasoning mirror — is round-blind; it sees ONE outcome. A spliced
 *  turn's Success must carry the WHOLE turn's facts, or a round-2 empty completion after a lost
 *  terminal frame turns an already-delivered answer into a client-visible error, and earlier
 *  rounds' reasoning vanishes from the mirror. Usage on [outcome] must already be round-summed. */
internal fun mergedAcrossRounds(
    outcome: TurnOutcome,
    salvaged: List<TurnOutcome.PartialRound>,
): TurnOutcome {
    if (outcome !is TurnOutcome.Success || salvaged.isEmpty()) return outcome
    return outcome.copy(
        hasToolUse = outcome.hasToolUse || salvaged.any { it.hasToolUse },
        emittedText = outcome.emittedText || salvaged.any { it.emittedText },
        thinkingText = (salvaged.map { it.thinkingText } + outcome.thinkingText)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n"),
        bodyText = (salvaged.map { it.bodyText } + outcome.bodyText).joinToString(""),
    )
}

/** Mid-stream re-anchoring loop (eli design 2026-07-24): the LIVE-emitter counterpart of
 *  [FoldRunner]. Rounds drive the real wire directly — committed blocks stay; a round that fails
 *  with a continuable partial re-POSTs the continuation and APPENDS; everything else finishes with
 *  the round's honest outcome. The emitter's seal + monotonic block indices make the spliced turn
 *  a single coherent Anthropic message ending in exactly ONE terminal (L3). A watchdog fire never
 *  continues — its cancellation owns the turn. */
internal class ReanchorRunner(
    private val key: String,
    private val log: (String) -> Unit,
    private val postRound: suspend (bodyJson: String) -> TurnOutcome,
    private val finish: suspend (TurnOutcome) -> Unit,
    private val signals: RunnerSignals,
) {
    suspend fun run(initialBody: JsonObject, reanchor: ReanchorController) {
        var body = initialBody
        var attempt = 0
        var acc = RoundUsage()
        val salvaged = mutableListOf<TurnOutcome.PartialRound>()
        val absorbedFailures = mutableListOf<TurnOutcome.Failure>()
        while (true) {
            val outcome = postRound(body.toString())
            val failure = outcome as? TurnOutcome.Failure
            val next = failure
                ?.takeIf { !signals.watchdogFired() && !signals.clientGone() }
                ?.let { reanchor.continuationForFailure(ReanchorRound(body, it, attempt)) }
            if (next == null) {
                // Absorbed failures hit the health split ONLY when the turn ultimately succeeds
                // (a rescued turn must not report a degraded provider as healthy); a turn that
                // ultimately FAILS is attributed exactly once by finishTurn — firing per absorbed
                // round would triple-count one logical failure (HeadServerIntegrationTest).
                if (outcome is TurnOutcome.Success) absorbedFailures.forEach(signals.onRoundFailure)
                finish(finalOutcome(outcome, salvaged, acc))
                return
            }
            absorbedFailures.add(failure)
            failure.partial?.let { p ->
                salvaged.add(p)
                acc = acc.plusRound(p.usage)
            }
            log(
                "[$key] re-anchor ${attempt + 1}: ${failure.type.wireName} mid-stream — " +
                    "continuing from partial output\n",
            )
            body = next
            attempt++
        }
    }

    private fun finalOutcome(
        outcome: TurnOutcome,
        salvaged: List<TurnOutcome.PartialRound>,
        acc: RoundUsage,
    ): TurnOutcome {
        if (outcome !is TurnOutcome.Success || salvaged.isEmpty()) return outcome
        return mergedAcrossRounds(outcome.copy(usage = acc.plusRound(outcome.usage).toUsage()), salvaged)
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
