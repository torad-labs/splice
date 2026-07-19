// PORT-OF: server/src/codex-proxy.mjs createServer/handleMessages @ 4ca99f7, GENERIC over the
// Provider SPI (the module law keeps concrete dialects out of :gateway). A Ktor Netty embedded
// server on loopback per head. Routes: POST /v1/messages EXACTLY (count_tokens gets its own
// cheap handler — the named change: Node forwarded it as a real turn and burned quota), GET
// /v1/models (discovery-wrapped), GET /health {ok,port,version}. SPLIT (2026-07-18, the audit's
// god-file finding): THIS file is the server shell + request ADMISSION (parse → validate →
// classify → build → gate slot); everything per-turn lives in TurnDriver (drive + telemetry).
// Slot release is NonCancellable (leak-safe teardown).
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
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
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.util.runCatchingCancellable
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.compact.classifyCompact
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.spi.GatewayAtCapacityException
import splice.spi.InflightGate
import splice.spi.Provider
import splice.spi.UpstreamClient

/** Collaborators the head needs, bundled to keep the constructor lean. */
public data class HeadDeps(
    val upstream: UpstreamClient,
    val gate: InflightGate,
    val shadow: ShadowClassifier,
    val compactStats: CompactStats,
    val usageStore: UsageStore,
    val perfStats: PerfStats,
    val log: (String) -> Unit,
    val clock: () -> Long = System::currentTimeMillis,
)

public class HeadServer(
    private val provider: Provider,
    private val listenPort: Int,
    private val deps: HeadDeps,
) : Head {

    private val gate get() = deps.gate
    private val shadow get() = deps.shadow
    private val log get() = deps.log
    private val clock get() = deps.clock

    private val json = Json { ignoreUnknownKeys = true }
    private val driver = TurnDriver(provider, deps)

    @Volatile
    private var server: EmbeddedServer<NettyApplicationEngine, *>? = null

    override val key: String get() = provider.key
    override val label: String get() = provider.label
    override val port: Int get() = listenPort

    override suspend fun start() {
        if (server != null) return
        val engine = embeddedServer(
            Netty,
            serverConfig {
                module {
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
            },
        ) {
            connector {
                host = "127.0.0.1"
                port = listenPort
            }
            // Every call here is a LONG-LIVED SSE turn that occupies a running slot for its
            // whole life (seconds..minutes). Netty's default runningLimit (32) silently queues
            // the 33rd concurrent agent turn behind in-flight ones — a fleet of subagents feels
            // like the gateway "can barely hold a few". HeadServerLoadTest pins the ceiling at
            // >= 1000 concurrently-held streams.
            runningLimit = RUNNING_LIMIT
            // Default 10s killed stream TAILS during 1000-way completion bursts (load test:
            // 52/1000 truncated) — a write that waits on a busy client/kernel buffer is not a
            // dead stream. The watchdog owns real staleness; keep this as a last-resort cap.
            responseWriteTimeoutSeconds = WRITE_TIMEOUT_S
        }
        engine.start(wait = false)
        server = engine
    }

    override suspend fun stop() {
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
    }

    override fun healthSnapshot(): HeadHealth {
        val counts = driver.healthCounters()
        return HeadHealth(
            ok = server != null,
            running = server != null,
            port = listenPort,
            version = GATEWAY_VERSION,
            localOriginErrors = counts.localOrigin,
            providerErrors = counts.providerError,
        )
    }

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

    // Admission: parse → validate model/stream → classify compact → build → gate slot, then hand
    // the turn to the driver. Body-parse failure is a client 400, not a crash
    // (runCatchingCancellable captures it yet still lets coroutine cancellation propagate).
    private suspend fun handleMessages(call: ApplicationCall) {
        val perf = TurnPerf(clock)
        val raw = call.receiveText()
        perf.mark(PerfKeys.RECV)
        perf.setCount(PerfKeys.REQ_BYTES, raw.length.toLong())
        val parsed = runCatchingCancellable { parseAnthropicBody(raw) }.getOrNull()
        val rejection = when {
            parsed == null -> "invalid request body"
            claudeModelRe.containsMatchIn(provider.catalog.unwrap(parsed.typed.model)) ->
                "this head proxies its own models only; got ${provider.catalog.unwrap(parsed.typed.model)}"
            // Non-stream responses are DELIBERATELY unserved rather than wrongly shaped: Claude
            // Code always streams, and answering stream:false with SSE bytes corrupts SDK callers.
            !parsed.typed.stream -> "this gateway serves streaming clients only — set \"stream\": true"
            else -> null
        }
        if (rejection != null || parsed == null) {
            call.respondText(
                errorBodyJson("invalid_request_error", rejection ?: "invalid request body"),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }

        // One scan of system + last-user text: classification and the shadow instrument share it
        // so a long system prompt is not lowercased/scanned twice per request.
        val compactProbe = classifyCompact(parsed.typed)
        shadow.record(parsed.typed, compactProbe)
        perf.mark(PerfKeys.PARSE)
        val built = provider.buildTurn(parsed, compactProbe.compact, call.request.headers["x-claude-code-session-id"])
        perf.mark(PerfKeys.BUILD)
        val t0 = clock()
        val slot = try {
            gate.acquire()
        } catch (_: GatewayAtCapacityException) {
            log("[${provider.key}] admission rejected: gateway at capacity (queued=${gate.snapshot().queued})\n")
            call.respondText(
                errorBodyJson("overloaded_error", "gateway at capacity"),
                ContentType.Application.Json,
                HttpStatusCode(GATEWAY_CAPACITY_STATUS, "Gateway At Capacity"),
            )
            return
        }
        perf.mark(PerfKeys.GATE)
        perf.setCount(PerfKeys.INFLIGHT, gate.snapshot().inflight.toLong())

        try {
            driver.stream(call, built, slot, t0, perf)
        } finally {
            withContext(NonCancellable) { slot.release() }
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

        // Concurrent long-lived SSE turns per head (2x the 1000-stream design target).
        const val RUNNING_LIMIT = 2048

        // Response-write stall cap — generous: the two-tier watchdog owns liveness enforcement.
        const val WRITE_TIMEOUT_S = 60

        // Same numeric convention as UpstreamFailureClassifier's OVERLOADED_STATUS (kept as its
        // own const here to avoid a cross-module const import and satisfy detekt MagicNumber).
        const val GATEWAY_CAPACITY_STATUS = 529
    }
}
