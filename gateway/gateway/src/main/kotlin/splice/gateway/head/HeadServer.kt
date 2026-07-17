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
import splice.core.turn.TurnMeta
import splice.core.turn.TurnOutcome
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
import splice.spi.InflightGate
import splice.spi.Provider
import splice.spi.TurnWatchdog
import splice.spi.UpstreamClient
import splice.spi.sseJsonEvents

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

    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "ReturnCount",
        "TooGenericExceptionCaught",
        "InstanceOfCheckForException",
    ) // the ported handleMessages; body-parse failure is a client 400, not a crash
    private suspend fun handleMessages(call: ApplicationCall) {
        val raw = call.receiveText()
        val parsed = try {
            parseAnthropicBody(raw)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
        val meta = built.meta
        val upstreamModel = meta.upstreamModel
        val t0 = clock()
        val slot = gate.acquire()

        try {
            call.respondTextWriter(ContentType.Text.EventStream) {
                val emitter = SseEmitter.create(
                    write = { frame ->
                        write(frame)
                        flush()
                    },
                    model = meta.originalModel,
                    usagePayload = { usage ->
                        buildUsagePayload(
                            TurnUsage(usage?.inputTokens ?: 0, usage?.outputTokens ?: 0, 0, 0),
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
                driveOneTurn(built.requestBody.toString(), meta, emitter, watchdog, slot, pipeline, t0, upstreamModel)
            }
        } finally {
            withContext(NonCancellable) { slot.release() }
        }
    }

    @Suppress("LongParameterList", "LongMethod")
    private suspend fun driveOneTurn(
        bodyJson: String,
        meta: TurnMeta,
        emitter: SseEmitter,
        watchdog: TurnWatchdog,
        slot: InflightGate.Slot,
        pipeline: TurnPipeline,
        t0: Long,
        upstreamModel: String,
    ) = withContext(Job()) {
        val self = this
        val turnJob: Job = self.coroutineContext[Job]!!
        upstream.post(
            url = provider.upstreamUrl,
            bodyJson = bodyJson,
            auth = provider.auth,
            extraHeaders = { creds -> provider.extraHeaders(creds) },
            onRetry = { log("[${provider.key}] $it\n") },
        ) { resp ->
            slot.touch()
            val poller = watchdog.launchIn(self, slot, turnJob)
            val events = sseJsonEvents(resp.bodyChannel()) {
                slot.touch()
                watchdog.markByte()
            }
            val outcome = provider.streamTranslator(meta) { watchdog.fired }.driveTurn(events, emitter)
            poller.cancel()
            (outcome as? TurnOutcome.Success)?.let { s ->
                usageStore.appendOutputTokens(s.usage.outputTokens)
                val usageObj = buildJsonObject { put("input_tokens", s.usage.inputTokens) }
                log(cacheLogLine(provider.key, upstreamModel, usageObj, meta.compact))
            }
            pipeline.finishStream(emitter, outcome, meta, clock() - t0)
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
    }
}
