package splice.head.hold

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.client.CLIENT_WALL_HEADER
import splice.core.client.HOLD_BUDGET_MS
import splice.core.client.NO_HOLD_HEADER
import splice.core.config.LAUNCH_PROOF_HEADER
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.headDeps
import splice.head.quotaFor
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.InflightGate
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

private const val HOUR_S = 3_600L
private const val LONG_WALL_MS = HOLD_BUDGET_MS + 960_000L
private const val SESSION = "no-hold-session"
private const val POLL_ATTEMPTS = 100
private const val POLL_DELAY_MS = 25L

class NoHoldTurnTest(@param:TempDir private val dir: Path) {
    @Test
    fun `x-splice-no-hold fails fast with 400 invalid_request_error and zero hold for stream and non-stream`() =
        runBlocking {
            val rig = NoHoldRig(dir, limited = mapOf("one" to 2 * HOUR_S), pooled = false)
            rig.start()
            try {
                for (stream in listOf(true, false)) {
                    val response = rig.send(stream = stream, noHold = "1")
                    val body = response.bodyAsText()
                    assertEquals(HttpStatusCode.BadRequest, response.status, "stream=$stream: $body")
                    assertEquals("false", response.headers["x-should-retry"], "stream=$stream: $body")
                    assertTrue("invalid_request_error" in body, "stream=$stream: $body")
                    assertTrue("Waiting for limits is disabled for this request." in body, "stream=$stream: $body")
                    assertFalse("event: ping" in body, "no hold pings for stream=$stream: $body")
                    assertFalse("thinking_delta" in body, "no synthetic thinking notice for stream=$stream: $body")
                }
                assertEquals(listOf("one", "one"), rig.requests)
                assertEquals(0, rig.heldCount())
            } finally {
                rig.close()
            }
        }

    @Test
    fun `without x-splice-no-hold a 5h limit keeps default hold behavior and resumes when cleared`() = runBlocking {
        val rig = NoHoldRig(dir, limited = mapOf("one" to 2 * HOUR_S), pooled = false)
        rig.start()
        try {
            val pending = async { rig.send(stream = true, noHold = null) }
            var held = false
            repeat(POLL_ATTEMPTS) {
                if (rig.heldCount() > 0) {
                    held = true
                    return@repeat
                }
                delay(POLL_DELAY_MS)
            }
            assertTrue(held, "turn without x-splice-no-hold should enter hold")
            rig.clearLimits()
            rig.tryHeldNow()
            val response = pending.await()
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            assertTrue("This turn waits" in body, "hold notice emitted: $body")
            assertTrue("synthetic success" in body, "resumed and completed: $body")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `pooled no-hold switches to a free login and skips already-held earlier-reset bounces`() = runBlocking {
        val freeRig = NoHoldRig(dir.resolve("free"), limited = mapOf("one" to 2 * HOUR_S), pooled = true)
        freeRig.start()
        try {
            val ok = freeRig.send(stream = true, noHold = "1")
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            assertEquals(listOf("one", "two"), freeRig.requests, "switches from limited login to free login")
        } finally {
            freeRig.close()
        }

        val allHeldRig = NoHoldRig(
            dir.resolve("all-held"),
            limited = mapOf("one" to 2 * HOUR_S, "two" to 5 * HOUR_S),
            pooled = true,
        )
        allHeldRig.start()
        try {
            val failed = allHeldRig.send(stream = true, noHold = "1")
            val body = failed.bodyAsText()
            assertEquals(HttpStatusCode.BadRequest, failed.status, body)
            assertEquals("false", failed.headers["x-should-retry"], body)
            assertTrue("invalid_request_error" in body, body)
            assertTrue("Waiting for limits is disabled for this request." in body, body)
            assertEquals(
                listOf("one", "two"),
                allHeldRig.requests,
                "tries each untried login once and does not bounce back to already-held earlier-reset login one",
            )
            assertEquals(0, allHeldRig.heldCount())
        } finally {
            allHeldRig.close()
        }
    }
}

private class NoHoldRig(
    dir: Path,
    limited: Map<String, Long>,
    pooled: Boolean,
) {
    private val limits = AtomicReference(limited)
    val requests = CopyOnWriteArrayList<String>()

    private val labels = if (pooled) listOf("one", "two") else listOf("one")
    private val logins = labels.associateWith { label ->
        object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("token-$label")
            override suspend fun refresh(): Credentials? = null
            override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
        }
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { exchange ->
            val login = exchange.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer token-")
            requests += login
            exchange.requestBody.use { it.readBytes() }
            val resetIn = limits.get()[login]
            if (resetIn != null) {
                val reset = (System.currentTimeMillis() / 1_000L + resetIn).toString()
                exchange.responseHeaders.add("anthropic-ratelimit-unified-status", "rejected")
                exchange.responseHeaders.add("anthropic-ratelimit-unified-representative-claim", "five_hour")
                exchange.responseHeaders.add("anthropic-ratelimit-unified-reset", reset)
                exchange.responseHeaders.add("anthropic-ratelimit-unified-5h-status", "rejected")
                exchange.responseHeaders.add("anthropic-ratelimit-unified-5h-utilization", "1.0")
                exchange.responseHeaders.add("anthropic-ratelimit-unified-5h-reset", reset)
                exchange.responseHeaders.add("Content-Type", "application/json")
                val bytes = """{"type":"error","error":{"type":"rate_limit_error","message":"limit"}}"""
                    .toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(429, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                val bytes = SUCCESS_WIRE.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        start()
    }
    private val held = labels.map { label ->
        PoolAccount(
            label = label,
            primary = label == "one",
            auth = logins.getValue(label),
            quota = AccountQuotaSource { null },
            cooldown = RateLimitCooldown(
                ElapsedClock { 0L },
                store = FileProviderHoldStore(dir.resolve("$label-hold.json"), LogSink {}),
            ),
        )
    }
    private val pool = AccountPool(held, WallClock { System.currentTimeMillis() }).also { it.order = labels }
    private val providerClient = HttpClient(CIO)
    private val deps = headDeps(
        tmp = dir,
        upstream = UpstreamClient(totalTimeoutMs = 30_000, client = providerClient),
        gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
    ).copy(
        quotaBundle = quotaFor(null, if (pooled) pool else null),
        policy = HeadDeps.HeadPolicy(forwardClientAuth = false),
        tokens = HeadDeps.HeadTokens({ "test-token" }, { "test-op" }),
    )
    private val head = HeadServer(
        PassthroughProvider(
            ProviderTuning(
                name = ProviderName(key = "synthetic", label = "synthetic"),
                catalog = ModelCatalog(
                    discoveryPrefix = "synthetic--",
                    models = listOf(ModelEntry("model", "Synthetic", contextWindow = 200_000)),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = "model",
                auth = logins.getValue("one"),
                locations = ProviderLocations(baseUrl = "http://127.0.0.1:${server.address.port}"),
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            PassthroughQuirks(providerTag = "synthetic"),
        ),
        listenPort = 0,
        deps = deps,
    )
    private val client = HttpClient(CIO) {
        defaultRequest {
            bearerAuth("test-token")
            header(LAUNCH_PROOF_HEADER, "test-token")
        }
    }

    suspend fun start() = head.start()

    fun clearLimits() {
        limits.set(emptyMap())
    }

    fun heldCount(): Int = deps.traffic.liveTurns.sayings().size

    fun tryHeldNow(): Int = deps.traffic.liveTurns.holds.tryNow()

    suspend fun send(stream: Boolean, noHold: String?): HttpResponse =
        client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", SESSION)
            header(CLIENT_WALL_HEADER, LONG_WALL_MS.toString())
            if (noHold != null) header(NO_HOLD_HEADER, noHold)
            setBody(
                """{"model":"synthetic--model","stream":$stream,"max_tokens":16,""" +
                    """"messages":[{"role":"user","content":"go"}]}""",
            )
        }

    suspend fun close() {
        head.stop()
        client.close()
        providerClient.close()
        server.stop(0)
    }
}

private val SUCCESS_WIRE = listOf(
    """{"type":"message_start","message":{"id":"msg_synthetic","type":"message",""" +
        """"role":"assistant","model":"model","usage":{"input_tokens":1,"output_tokens":0}}}""",
    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"synthetic success"}}""",
    """{"type":"content_block_stop","index":0}""",
    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
    """{"type":"message_stop"}""",
).joinToString("\n\n", postfix = "\n\n") { event ->
    val kind = event.substringAfter("\"type\":\"").substringBefore('"')
    "event: $kind\ndata: $event"
}
