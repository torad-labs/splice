// PORT-OF: server/src/codex-proxy.mjs createServer/handleMessages @ 4ca99f7, GENERIC over the
// Provider SPI (the module law keeps concrete dialects out of :gateway). A Ktor Netty embedded
// server on loopback per head. Routes: POST /v1/messages EXACTLY (count_tokens gets its own
// cheap handler — the named change: Node forwarded it as a real turn and burned quota), GET
// /v1/models (discovery-wrapped, pinned model EXCLUDED), GET /health {ok,port,version}. Turn
// pipeline: parse → classify compact → shadow → build → gate slot → upstream POST w/ retry +
// single-flight-refresh-on-401 → watchdogged stream → provider translator → pipeline
// honesty/promote/mirror → sole terminal. Slot release is NonCancellable (leak-safe teardown).
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.GATEWAY_VERSION
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.model.DiscoveryRow
import splice.core.parse.parseAnthropicBody
import splice.core.turn.ErrorType
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
import splice.core.util.runCatchingCancellable
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.compact.classifyCompact
import splice.gateway.pipeline.TurnPipeline
import splice.gateway.usage.TurnUsage
import splice.gateway.usage.UsageStore
import splice.gateway.usage.buildUsagePayload
import splice.gateway.usage.cacheLogLine
import splice.gateway.usage.makeOutputClamp
import splice.gateway.wire.SseEmitter
import splice.spi.BuiltTurn
import splice.spi.FailureSource
import splice.spi.InflightGate
import splice.spi.Provider
import splice.spi.TurnWatchdog
import splice.spi.UpstreamAuthMissing
import splice.spi.UpstreamClient
import splice.spi.UpstreamFailed
import splice.spi.UpstreamFailureClassifier
import splice.spi.sseJsonEvents
import java.io.IOException

/** Collaborators the head needs, bundled to keep the constructor lean. */
public data class HeadDeps(
    val upstream: UpstreamClient,
    val gate: InflightGate,
    val shadow: ShadowClassifier,
    val compactStats: CompactStats,
    val usageStore: UsageStore,
    val log: (String) -> Unit,
    val clock: () -> Long = System::currentTimeMillis,
)

/** The per-turn collaborators + data driveOneTurn needs, grouped so the drive signature stays one
 *  cohesive argument (they are all created together per request inside the SSE writer). */
private data class TurnDrive(
    val bodyJson: String,
    val meta: TurnMeta,
    val emitter: SseEmitter,
    val watchdog: TurnWatchdog,
    val slot: InflightGate.Slot,
    val pipeline: TurnPipeline,
    val t0: Long,
    val upstreamModel: String,
)

public class HeadServer(
    private val provider: Provider,
    private val listenPort: Int,
    private val deps: HeadDeps,
) : Head {

    private val upstream get() = deps.upstream
    private val gate get() = deps.gate
    private val shadow get() = deps.shadow
    private val compactStats get() = deps.compactStats
    private val usageStore get() = deps.usageStore
    private val log get() = deps.log
    private val clock get() = deps.clock

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var server: EmbeddedServer<NettyApplicationEngine, *>? = null

    override val key: String get() = provider.key
    override val label: String get() = provider.label
    override val port: Int get() = listenPort

    override suspend fun start() {
        if (server != null) return
        val engine = embeddedServer(Netty, port = listenPort, host = "127.0.0.1") {
            install(SSE)
            routing {
                get("/health") { call.respondText(healthJson(), ContentType.Application.Json) }
                get("/v1/models") { call.respondText(modelsJson(), ContentType.Application.Json) }
                post("/v1/messages") { handleMessages(call) }
                // NAMED CHANGE: count_tokens gets a cheap dedicated handler, not the Node
                // behavior (a real quota-burning turn). Local estimate keeps pre-flight cheap.
                post("/v1/messages/count_tokens") { handleCountTokens(call) }
            }
        }
        engine.start(wait = false)
        server = engine
    }

    override suspend fun stop() {
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
    }

    override fun healthSnapshot(): HeadHealth =
        HeadHealth(ok = server != null, running = server != null, port = listenPort, version = GATEWAY_VERSION)

    private fun healthJson(): String = buildJsonObject {
        put("ok", true)
        put("port", listenPort)
        put("version", GATEWAY_VERSION)
        put("head", provider.key)
    }.toString()

    private fun modelsJson(): String {
        // EVERY catalog model gets a discovery row, including the pinned one — Claude Code needs
        // each id present so its display_name supplies the /model picker + status label (the pinned
        // model is otherwise missing from the picker). Which rows actually show is curated by the
        // availableModels allowlist in settings.json, not here.
        val rows = provider.catalog.discoveryRows()
        return buildJsonObject {
            put("object", "list")
            put(
                "data",
                buildJsonArray { rows.forEach { add(json.encodeToJsonElement(DiscoveryRow.serializer(), it)) } },
            )
        }.toString()
    }

    // the ported handleMessages; body-parse failure is a client 400, not a crash (runCatchingCancellable
    // captures the malformed-body failure yet still lets coroutine cancellation propagate).
    private suspend fun handleMessages(call: ApplicationCall) {
        val raw = call.receiveText()
        val parsed = runCatchingCancellable { parseAnthropicBody(raw) }.getOrElse {
            call.respondText(
                errorBodyJson("invalid_request_error", "invalid request body"),
                ContentType.Application.Json,
            )
            return
        }
        val requested = provider.catalog.unwrap(parsed.typed.model)
        if (claudeModelRe.containsMatchIn(requested)) {
            call.respondText(
                errorBodyJson("invalid_request_error", "this head proxies its own models only; got $requested"),
                ContentType.Application.Json,
            )
            return
        }

        val compact = classifyCompact(parsed.typed)
        shadow.record(parsed.typed, compact)
        val sessionId = call.request.headers["x-claude-code-session-id"]
        val built = provider.buildTurn(parsed, compact, sessionId)
        val t0 = clock()
        val slot = gate.acquire()

        try {
            streamTurn(call, built, slot, t0)
        } finally {
            withContext(NonCancellable) { slot.release() }
        }
    }

    /** Open the SSE writer, wire up the per-turn collaborators, and run the single turn. */
    private suspend fun streamTurn(call: ApplicationCall, built: BuiltTurn, slot: InflightGate.Slot, t0: Long) {
        val meta = built.meta
        val upstreamModel = meta.upstreamModel
        call.respondTextWriter(ContentType.Text.EventStream) {
            val emitter = SseEmitter.create(
                write = { frame ->
                    write(frame)
                    flush()
                },
                model = meta.originalModel,
                usagePayload = { usage ->
                    // Anthropic convention (what Claude Code's HUD/autocompact expects): input_tokens
                    // and cache_read_input_tokens are DISJOINT. OpenAI's input_tokens INCLUDES the
                    // cached portion, so subtract it — else input+cache_read double-counts and the
                    // context bar/autocompact fire ~2x early (the "compaction ate my quota" class).
                    val cached = usage?.cachedTokens ?: 0
                    val nonCachedInput = ((usage?.inputTokens ?: 0) - cached).coerceAtLeast(0)
                    buildUsagePayload(
                        TurnUsage(nonCachedInput, usage?.outputTokens ?: 0, 0, cached),
                        provider.catalog.contextWindowFor(upstreamModel),
                    )
                },
            )
            val watchdog = TurnWatchdog(provider.watchdog, clock)
            val pipeline = TurnPipeline(
                compactStats,
                log,
                makeOutputClamp(meta.clientMaxTokens, meta.compact, provider.key, log),
            )
            driveOrEmitError(
                TurnDrive(
                    bodyJson = built.requestBody.toString(),
                    meta = meta,
                    emitter = emitter,
                    watchdog = watchdog,
                    slot = slot,
                    pipeline = pipeline,
                    t0 = t0,
                    upstreamModel = upstreamModel,
                ),
            )
        }
    }

    // The 200 + SSE headers are already committed once respondTextWriter opens, so any exception
    // upstream.post throws (retries exhausted, missing creds, a mid-request socket reset/timeout)
    // must become an honest `event: error` frame — NOT escape and leave the client an empty/truncated
    // 200 or a connection reset (the "empty or malformed response (HTTP 200)" / ECONNRESET class).
    // emitError is a no-op if the terminal already fired, so a partial stream still ends cleanly.
    private suspend fun driveOrEmitError(drive: TurnDrive) {
        try {
            driveOneTurn(drive)
        } catch (e: UpstreamAuthMissing) {
            log(errTurn("auth-missing", drive, ": ${e.message}"))
            drive.emitter.emitError(ErrorType.AUTHENTICATION, "claudex: no upstream credentials — run: claudex login")
        } catch (e: UpstreamFailed) {
            val failure = UpstreamFailureClassifier.classify(FailureSource.HTTP, e.body)
            val detail = "type=${failure.type.wireName} msg=${failure.message.take(ERR_SNIPPET)}"
            log(errTurn("upstream-failed", drive, detail))
            drive.emitter.emitError(failure.type, failure.message)
        } catch (e: IOException) {
            log(errTurn("conn-reset", drive, ": ${e.message}"))
            drive.emitter.emitError(ErrorType.OVERLOADED, "claudex: upstream connection failed (${e.message}) — retry")
        }
    }

    private fun errTurn(kind: String, drive: TurnDrive, detail: String): String =
        "[${provider.key}] turn ERROR $kind compact=${drive.meta.compact} latency=${clock() - drive.t0}ms $detail\n"

    // Per-turn telemetry: outcome + latency (+ tokens/type). The compaction-stall and API-error
    // signals live here — a compact turn that FAILUREs or runs many seconds is now visible.
    private fun turnLine(key: String, meta: TurnMeta, model: String, outcome: TurnOutcome, latencyMs: Long): String {
        val base = "[$key] turn compact=${meta.compact} model=$model latency=${latencyMs}ms"
        return base + when (outcome) {
            is TurnOutcome.Success ->
                " ok out=${outcome.usage.outputTokens} tool=${outcome.hasToolUse} incomplete=${outcome.incomplete}\n"
            is TurnOutcome.Failure ->
                " FAILURE type=${outcome.type.wireName} msg=${outcome.message.take(ERR_SNIPPET)}\n"
            TurnOutcome.ClientAbandoned -> " client-abandoned\n"
        }
    }

    private suspend fun driveOneTurn(drive: TurnDrive) = withContext(Job()) {
        val self = this
        val turnJob: Job = self.coroutineContext[Job]!!
        upstream.post(
            url = provider.upstreamUrl,
            bodyJson = drive.bodyJson,
            auth = provider.auth,
            extraHeaders = { creds -> provider.extraHeaders(creds) },
            onRetry = { log("[${provider.key}] $it\n") },
        ) { resp ->
            drive.slot.touch()
            val poller = drive.watchdog.launchIn(self, drive.slot, turnJob)
            val events = sseJsonEvents(resp.bodyChannel()) {
                drive.slot.touch()
                drive.watchdog.markByte()
            }
            val outcome = provider.streamTranslator(drive.meta) { drive.watchdog.fired }
                .driveTurn(events, drive.emitter)
            poller.cancel()
            (outcome as? TurnOutcome.Success)?.let { s ->
                usageStore.appendOutputTokens(s.usage.outputTokens)
                val usageObj = buildJsonObject {
                    put("input_tokens", s.usage.inputTokens)
                    put("output_tokens", s.usage.outputTokens)
                    put("input_tokens_details", buildJsonObject { put("cached_tokens", s.usage.cachedTokens) })
                }
                log(cacheLogLine(provider.key, drive.upstreamModel, usageObj, drive.meta.compact))
            }
            val latencyMs = clock() - drive.t0
            log(turnLine(provider.key, drive.meta, drive.upstreamModel, outcome, latencyMs))
            drive.pipeline.finishStream(drive.emitter, outcome, drive.meta, latencyMs)
        }
    }

    private suspend fun handleCountTokens(call: ApplicationCall) {
        val raw = call.receiveText()
        val estimate = (raw.length / CHARS_PER_TOKEN).toLong()
        log("[${provider.key}] count_tokens estimate=$estimate (local; no upstream turn)\n")
        call.respondText(buildJsonObject { put("input_tokens", estimate) }.toString(), ContentType.Application.Json)
    }

    private fun errorBodyJson(type: String, message: String): String = buildJsonObject {
        put("type", "error")
        put(
            "error",
            buildJsonObject {
                put("type", type)
                put("message", message)
            },
        )
    }.toString()

    private companion object {
        val claudeModelRe = Regex("^(claude|opus|sonnet|haiku|anthropic)", RegexOption.IGNORE_CASE)
        const val STOP_GRACE_MS = 100L
        const val STOP_TIMEOUT_MS = 500L
        const val CHARS_PER_TOKEN = 4
        const val ERR_SNIPPET = 200
    }
}
