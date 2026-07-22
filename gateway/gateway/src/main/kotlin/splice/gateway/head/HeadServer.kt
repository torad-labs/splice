// PORT-OF: server/src/codex-proxy.mjs createServer/handleMessages @ pre-public-port-baseline, GENERIC over the
// Provider SPI (the module law keeps concrete dialects out of :gateway). A Ktor Netty embedded
// server on loopback per head. Routes: POST /v1/messages EXACTLY (count_tokens gets its own
// cheap handler — the named change: Node forwarded it as a real turn and burned quota), GET
// /v1/models (discovery-wrapped), GET /health {ok,port,version}. SPLIT (2026-07-18, the audit's
// god-file finding): THIS file is the server shell + request ADMISSION (parse → validate →
// classify → build → gate slot); everything per-turn lives in TurnDriver (drive + telemetry).
// Slot release is NonCancellable (leak-safe teardown).
package splice.gateway.head

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.netty.channel.socket.SocketChannelConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import splice.core.util.MonoClock
import splice.core.util.runCatchingCancellable
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.compact.classifyCompact
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.spi.BuiltTurn
import splice.spi.GatewayAtCapacityException
import splice.spi.InflightGate
import splice.spi.Provider
import splice.spi.UpstreamClient
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Collaborators the head needs, bundled to keep the constructor lean. */
public data class HeadDeps(
    val upstream: UpstreamClient,
    /** Per-install bearer used by local Claude clients. Never use a source-known sentinel here. */
    val inferenceToken: String,
    val gate: InflightGate,
    val shadow: ShadowClassifier,
    val compactStats: CompactStats,
    val usageStore: UsageStore,
    val perfStats: PerfStats,
    val log: (String) -> Unit,
    val clock: () -> Long = MonoClock::nowMs,
    val requestMaterializationGate: RequestMaterializationGate = RequestMaterializationGate(),
    val maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
    val requestReadTimeoutMs: Long = DEFAULT_REQUEST_READ_TIMEOUT_MS,
    // mirror_reasoning knob, threaded to TurnPipeline (restart-required like the other reasoning knobs)
    val mirrorReasoning: Boolean = true,
) {
    init {
        require(inferenceToken.isNotBlank()) { "inferenceToken must not be blank" }
        require(requestReadTimeoutMs > 0) { "requestReadTimeoutMs must be positive" }
    }

    public companion object {
        public const val DEFAULT_MAX_REQUEST_BYTES: Int = 8 * 1024 * 1024
        public const val DEFAULT_REQUEST_READ_TIMEOUT_MS: Long = 30_000
    }
}

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
    private val lifecycle = Mutex()

    override val key: String get() = provider.key
    override val label: String get() = provider.label
    override val port: Int get() = listenPort

    override suspend fun start(): Unit = lifecycle.withLock { startLocked() }

    override suspend fun stop(): Unit = lifecycle.withLock { stopLocked() }

    override suspend fun restart(): Unit = lifecycle.withLock {
        stopLocked()
        startLocked()
    }

    private fun startLocked() {
        if (server != null) return
        // G20 contract: a control-plane restart promises a fresh diagnostic baseline; the counters
        // live on the long-lived TurnDriver, so reset them here (review 2026-07-19).
        driver.resetHealth()
        // G26: local (not a class field) so a control-plane restart (POST /api/heads/:head/restart)
        // re-arms verification instead of going permanently silent after the first restart.
        val nodelayLogged = AtomicBoolean(false)
        val engine = embeddedServer(
            Netty,
            serverConfig {
                module {
                    install(SSE)
                    routing {
                        get("/health") { call.respondText(healthJson(), ContentType.Application.Json) }
                        get("/v1/models") {
                            if (authorize(call)) {
                                call.respondText(modelsJson(), ContentType.Application.Json)
                            }
                        }
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
            // G26: verification-only (never sets a socket option) — fires on the first ACCEPTED
            // client connection with a real, already-connected socket (NettyChannelInitializer
            // .initChannel), logged once per start() so N connections don't spam the log.
            channelPipelineConfig = { pipeline ->
                if (nodelayLogged.compareAndSet(false, true)) {
                    val noDelay = (pipeline.channel().config() as? SocketChannelConfig)?.isTcpNoDelay
                    log("[${provider.key}] tcp_nodelay(server)=${noDelay ?: "unknown"}\n")
                }
            }
        }
        engine.start(wait = false)
        server = engine
    }

    private suspend fun stopLocked() {
        // Drain in-flight turns so clients get honest terminals (sealCancelledTurn) before Netty
        // tears the engine. Bounded wait — never block restart forever.
        val deadlineNs = System.nanoTime() + STOP_DRAIN_NS
        while (gate.snapshot().inflight > 0 && System.nanoTime() < deadlineNs) {
            delay(STOP_DRAIN_POLL_MS)
        }
        if (gate.snapshot().inflight > 0) {
            log("[${provider.key}] stop: draining timed out with inflight=${gate.snapshot().inflight} — forcing engine stop\n")
        }
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
        deps.usageStore.flushNow()
        driver.resetHealth()
    }

    override fun healthSnapshot(): HeadHealth {
        val counts = driver.healthCounters()
        val gateSnap = gate.snapshot()
        return HeadHealth(
            ok = server != null,
            running = server != null,
            port = listenPort,
            version = GATEWAY_VERSION,
            localOriginErrors = counts.localOrigin,
            providerErrors = counts.providerError,
            gateInflight = gateSnap.inflight,
            gateQueued = gateSnap.queued,
            gateLimit = gateSnap.limit,
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

    private sealed class Preparation {
        data class Ready(val built: BuiltTurn, val stream: Boolean) : Preparation()
        data class Rejected(val message: String) : Preparation()
    }
    private data class ReceivedBody(val text: String, val bytes: Int)
    private class RequestBodyTooLarge(val limit: Int) : RuntimeException()

    // Admission is acquired BEFORE reading the body. Queued calls therefore retain no transcript;
    // only admitted calls may enter the process-shared materialization gate and hold raw/parsed/built
    // representations. Body-parse failure is a client 400, not a crash.
    private suspend fun handleMessages(call: ApplicationCall) {
        if (!authorize(call)) return
        val perf = TurnPerf(clock)
        val t0 = clock()
        val slot = acquireSlotOrRespond(call) ?: return
        perf.mark(PerfKeys.GATE)
        perf.setCount(PerfKeys.INFLIGHT, gate.snapshot().inflight.toLong())

        try {
            val prepared = materializeOrRespond(call) { prepareTurn(call, perf) } ?: return
            when (prepared) {
                is Preparation.Rejected -> call.respondText(
                    errorBodyJson(INVALID_REQUEST_ERROR, prepared.message),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                is Preparation.Ready -> {
                    // stream:true → SSE (the interactive path); stream:false → one buffered JSON body
                    // (Claude Code's internal non-stream calls, served by collecting the same machinery).
                    if (prepared.stream) {
                        driver.stream(call, prepared.built, slot, t0, perf)
                    } else {
                        driver.collect(call, prepared.built, slot, t0, perf)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { slot.release() }
        }
    }

    private suspend fun acquireSlotOrRespond(call: ApplicationCall): InflightGate.Slot? = try {
        gate.acquire()
    } catch (_: GatewayAtCapacityException) {
        log("[${provider.key}] admission rejected: gateway at capacity (queued=${gate.snapshot().queued})\n")
        call.respondText(
            errorBodyJson("overloaded_error", "gateway at capacity"),
            ContentType.Application.Json,
            HttpStatusCode(GATEWAY_CAPACITY_STATUS, "Gateway At Capacity"),
        )
        null
    }

    private suspend fun <T> materializeOrRespond(call: ApplicationCall, block: suspend () -> T): T? = try {
        deps.requestMaterializationGate.withLease(block)
    } catch (tooLarge: RequestBodyTooLarge) {
        call.respondText(
            errorBodyJson(INVALID_REQUEST_ERROR, "request body exceeds ${tooLarge.limit} bytes"),
            ContentType.Application.Json,
            HttpStatusCode(CONTENT_TOO_LARGE_STATUS, "Content Too Large"),
        )
        null
    } catch (_: TimeoutCancellationException) {
        call.respondText(
            errorBodyJson(INVALID_REQUEST_ERROR, "request body read timed out"),
            ContentType.Application.Json,
            HttpStatusCode.RequestTimeout,
        )
        null
    }

    private suspend fun prepareTurn(call: ApplicationCall, perf: TurnPerf): Preparation {
        val body = receiveBodyBounded(call, deps.maxRequestBytes)
        perf.mark(PerfKeys.RECV)
        perf.setCount(PerfKeys.REQ_BYTES, body.bytes.toLong())
        val parsed = runCatchingCancellable { parseAnthropicBody(body.text) }.getOrNull()
            ?: return Preparation.Rejected("invalid request body")
        val unwrappedModel = provider.catalog.unwrap(parsed.typed.model)
        if (!provider.catalog.contains(parsed.typed.model)) {
            return Preparation.Rejected("this head proxies its own models only; got $unwrappedModel")
        }

        // One scan of system + last-user text: classification and shadow instrumentation share it.
        val compactProbe = classifyCompact(parsed.typed)
        shadow.record(parsed.typed, compactProbe)
        perf.mark(PerfKeys.PARSE)
        val built = provider.buildTurn(parsed, compactProbe.compact, call.request.headers["x-claude-code-session-id"])
        perf.mark(PerfKeys.BUILD)
        return Preparation.Ready(built, parsed.typed.stream)
    }

    private suspend fun handleCountTokens(call: ApplicationCall) {
        if (!authorize(call)) return
        // Share the admission gate so a flood of large count_tokens bodies cannot bypass
        // maxInflight while /v1/messages is capacity-protected.
        val slot = acquireSlotOrRespond(call) ?: return
        try {
            val body = materializeOrRespond(call) { receiveBodyBounded(call, deps.maxRequestBytes) } ?: return
            val parsed = runCatchingCancellable { parseAnthropicBody(body.text) }.getOrNull()
            if (parsed == null) {
                call.respondText(
                    errorBodyJson(INVALID_REQUEST_ERROR, "invalid request body"),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
            } else {
                // Conservative and Unicode-safe: UTF-8 bytes / 3 overestimates ordinary English while
                // avoiding the old UTF-16 chars / 4 undercount for CJK and emoji. The complete JSON body
                // intentionally contributes structural/tool overhead.
                val estimate =
                    ((body.bytes + TOKEN_ESTIMATE_BYTES - 1) / TOKEN_ESTIMATE_BYTES)
                        .coerceAtLeast(1)
                        .toLong()
                log("[${provider.key}] count_tokens estimate=$estimate (local; no upstream turn)\n")
                call.respondText(
                    buildJsonObject { put("input_tokens", estimate) }.toString(),
                    ContentType.Application.Json,
                )
            }
        } finally {
            withContext(NonCancellable) { slot.release() }
        }
    }

    private suspend fun receiveBodyBounded(call: ApplicationCall, limit: Int): ReceivedBody {
        return withTimeout(deps.requestReadTimeoutMs) {
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > limit) throw RequestBodyTooLarge(limit)
            val channel = call.receiveChannel()
            val capacity = minOf(declared?.toInt() ?: READ_BUFFER_BYTES, limit).coerceAtLeast(0)
            val output = ByteArrayOutputStream(capacity)
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            var read = readAvailableOrEof(channel, buffer)
            while (read >= 0) {
                total += read
                if (total > limit) throw RequestBodyTooLarge(limit)
                output.write(buffer, 0, read)
                read = readAvailableOrEof(channel, buffer)
            }
            ReceivedBody(output.toString(Charsets.UTF_8), total)
        }
    }

    private suspend fun authorize(call: ApplicationCall): Boolean {
        val bearer = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(call.request.headers[HttpHeaders.Authorization].orEmpty().trim())
            ?.groupValues
            ?.get(1)
            ?.trim()
        val presented = bearer ?: call.request.headers["x-api-key"]
        val expectedBytes = deps.inferenceToken.toByteArray(Charsets.UTF_8)
        val presentedBytes = presented?.toByteArray(Charsets.UTF_8)
        val allowed = presentedBytes != null &&
            presentedBytes.size == expectedBytes.size &&
            MessageDigest.isEqual(presentedBytes, expectedBytes)
        if (allowed) return true
        call.respondText(
            errorBodyJson("authentication_error", "invalid local gateway credentials"),
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized,
        )
        return false
    }

    private suspend fun readAvailableOrEof(
        channel: ByteReadChannel,
        buffer: ByteArray,
    ): Int {
        var read = channel.readAvailable(buffer, 0, buffer.size)
        while (read == 0) {
            if (!channel.awaitContent(1)) return -1
            read = channel.readAvailable(buffer, 0, buffer.size)
        }
        return read
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
        // Grace/timeout for Netty engine.stop after the drain window below.
        const val STOP_GRACE_MS = 500L
        const val STOP_TIMEOUT_MS = 2_000L
        // Wait for in-flight SSE turns to finish (or cancel cleanly) before tearing the engine.
        const val STOP_DRAIN_NS = 5_000_000_000L // 5s
        const val STOP_DRAIN_POLL_MS = 50L
        const val TOKEN_ESTIMATE_BYTES = 3
        const val READ_BUFFER_BYTES = 16 * 1024
        const val CONTENT_TOO_LARGE_STATUS = 413
        const val INVALID_REQUEST_ERROR = "invalid_request_error"

        // Concurrent long-lived SSE turns per head (2x the 1000-stream design target).
        const val RUNNING_LIMIT = 2048

        // Response-write stall cap — at least as long as default streamIdle (180s) so a slow-but-alive
        // client is not killed by Netty while the two-tier watchdog still considers the stream healthy.
        const val WRITE_TIMEOUT_S = 300

        // Same numeric convention as UpstreamFailureClassifier's OVERLOADED_STATUS (kept as its
        // own const here to avoid a cross-module const import and satisfy detekt MagicNumber).
        const val GATEWAY_CAPACITY_STATUS = 529
    }
}
