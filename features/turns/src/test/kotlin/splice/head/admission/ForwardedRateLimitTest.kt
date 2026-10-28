// NEW: one forwarded login's refusal cannot hold another login on the same listener.
package splice.head.admission

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.ClientAuthProvider
import splice.core.auth.CredentialKey
import splice.core.client.CLIENT_WALL_HEADER
import splice.core.client.SESSION_HEADER
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.headDeps
import splice.head.noQuota
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.Waiter
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.InflightGate
import splice.upstream.transport.RetryPacing
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ForwardedRateLimitTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `account rows name the effective caller login without guessing an unknown identity`() = runBlocking {
        for (stream in listOf(false, true)) {
            val known = mapOf(
                key("synthetic-healthy") to "one@example.invalid",
                key("synthetic-new-login") to "two@example.invalid",
            )
            val rig = LimitRig(
                directory.resolve(stream.toString()),
                names = HeadDeps.CredentialAccountNames(known::get),
            )
            rig.head.start()
            try {
                for (token in listOf("synthetic-healthy", "synthetic-new-login", "synthetic-unproved")) {
                    assertEquals(HttpStatusCode.OK, rig.turn(token, stream).first)
                }
                assertEquals(listOf("one@example.invalid", "two@example.invalid", "claude-code"), rig.accounts(3))
            } finally {
                rig.close()
            }
        }
    }

    // Each turn asks the provider itself (Marcos, Oct 26): a refused credential's next turn is sent, not replayed.
    @Test
    fun `every refused turn asks the provider itself and keeps the proved account`() = runBlocking {
        val names = HeadDeps.CredentialAccountNames { digest ->
            "held@example.invalid".takeIf { digest == key("synthetic-refused") }
        }
        val rig = LimitRig(directory, names = names)
        rig.head.start()
        try {
            repeat(2) { assertEquals(HttpStatusCode.TooManyRequests, rig.turn("synthetic-refused").first) }
            assertEquals(listOf("synthetic-refused", "synthetic-refused"), rig.requests)
            assertEquals(listOf("held@example.invalid", "held@example.invalid"), rig.accounts(2))
        } finally {
            rig.close()
        }
    }

    @Test
    fun `account row wait includes a third append after the initial read`() = runBlocking {
        val file = directory.resolve("synthetic-perf.jsonl")
        Files.writeString(file, "{\"account\":\"one\"}\n{\"account\":\"two\"}\n")
        val waiting = async { awaitAccounts(file, 3) }
        yield()
        assertFalse(waiting.isCompleted, "the first read must not certify a missing third row")
        Files.writeString(file, "{\"account\":\"three\"}\n", StandardOpenOption.APPEND)
        assertEquals(listOf("one", "two", "three"), waiting.await())
    }

    @Test
    fun `account row wait fails within its bound when the third turn produces no row`() {
        val file = directory.resolve("synthetic-perf.jsonl")
        Files.writeString(file, "{\"account\":\"one\"}\n{\"account\":\"two\"}\n")
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking { awaitAccounts(file, 3, 100.milliseconds) }
        }
    }

    private fun key(token: String): String =
        requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))

    /** The console names the login a head's requests carry from these reports (2026-10-04: claude-splice's usage
     *  read its command's folder while its requests carried the ~/.claude login). Each attempt reports the digest of
     *  the credential it actually sent and the session the request named, so a second login on the same head, and a
     *  second session on it, are each heard as themselves. */
    @Test
    fun `every forwarded attempt reports the digest of the credential it sent, never the credential`() = runBlocking {
        val sent = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String?>>()
        val names = object : HeadDeps.CredentialAccountNames {
            override fun forCredential(key: String): String? = null
            override fun sent(key: String, session: String?) {
                sent += key to session
            }
        }
        val rig = LimitRig(directory, limits = false, names = names)
        rig.head.start()
        try {
            assertEquals(HttpStatusCode.OK, rig.turn("synthetic-first-login", session = "synthetic-session").first)
            assertEquals(HttpStatusCode.OK, rig.turn("synthetic-second-login").first)
            assertEquals(
                listOf(key("synthetic-first-login") to "synthetic-session", key("synthetic-second-login") to null),
                sent.toList(),
            )
            assertTrue(
                sent.none { (digest, _) -> digest.contains("synthetic") },
                "a report carries the digest, never the token: $sent",
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a refused credential never holds an existing or newly seen login`() = runBlocking {
        val rig = LimitRig(directory)
        rig.head.start()
        try {
            assertEquals(HttpStatusCode.OK, rig.turn("synthetic-healthy").first)
            rig.turn("synthetic-refused")
            assertTrue(rig.upstream.rateLimitedForMs > 0L, "the refused login must actually arm a hold")
            assertEquals(HttpStatusCode.OK, rig.turn("synthetic-healthy").first)
            assertEquals(HttpStatusCode.OK, rig.turn("synthetic-new-login").first)
            assertEquals(4, rig.requests.size, "each healthy credential reaches the provider")
            assertEquals(listOf("synthetic-refused"), rig.refused)
        } finally {
            rig.close()
        }
    }

    // Slice 5: a limit a day away does not fit this session's 960 s, so it is handed to Claude Code (family L) as a
    // 429 naming the true reset, since no other login could serve it; the provider's own reply is no longer relayed.
    @Test
    fun `a refused credential's weekly limit reaches its client as a 429 naming the true reset`() = runBlocking {
        for (stream in listOf(false, true)) {
            val rig = LimitRig(directory.resolve(stream.toString()))
            rig.head.start()
            try {
                repeat(2) {
                    val (status, body, headers) = rig.turn("synthetic-refused", stream)
                    assertEquals(HttpStatusCode.TooManyRequests, status)
                    rig.assertHandedOver(body, headers)
                }
                assertEquals(2, rig.requests.size, "the same credential's next turn asks the provider itself")
            } finally {
                rig.close()
            }
        }
    }

    // A short-wall buffered request hands the unstated limit to its client as family L. A streamed request with
    // enough wall for the burst commits its retry notice and ends in band. Neither blocks a different credential.
    @Test
    fun `headerless 429 exhausts its burst and ends honestly without holding another login`() = runBlocking {
        for (stream in listOf(false, true)) {
            val rig = LimitRig(directory.resolve(stream.toString()), limits = false)
            rig.head.start()
            try {
                val sentAtSeconds = System.currentTimeMillis() / 1_000
                val wallMs = if (stream) 60_000L else 20_000L
                val (status, body, headers) = rig.turn("synthetic-refused", stream, wallMs = wallMs)
                val receivedAtSeconds = System.currentTimeMillis() / 1_000
                if (stream) {
                    assertEquals(HttpStatusCode.OK, status)
                    val events = body.lineSequence().filter { it.startsWith("data: ") }
                        .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.toList()
                    val error = events.single { it["type"]?.jsonPrimitive?.content == "error" }
                        .getValue("error").jsonObject
                    assertEquals("overloaded_error", error.getValue("type").jsonPrimitive.content)
                    assertTrue("without saying when it resets" in error.getValue("message").jsonPrimitive.content)
                    assertTrue("tries again in 15 seconds" in body, "the retry notice committed the stream")
                    assertTrue(events.none { it["type"]?.jsonPrimitive?.content == "message_stop" })
                    assertEquals(null, headers["retry-after"])
                    assertEquals(null, headers["anthropic-ratelimit-unified-status"])
                    assertEquals(BURST_SENDS, rig.refused.size, "the stream exhausts its burst before the handoff")
                } else {
                    assertEquals(HttpStatusCode.TooManyRequests, status)
                    val error = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject
                    assertEquals("rate_limit_error", error.getValue("type").jsonPrimitive.content)
                    val line = error.getValue("message").jsonPrimitive.content
                    assertTrue(line.startsWith("[splice] ") && "without saying when it resets" in line, line)
                    assertFalse(headers["x-should-retry"] == "false", "the client can retry family L")
                    assertEquals("30", headers["retry-after"])
                    assertEquals("rejected", headers["anthropic-ratelimit-unified-status"])
                    val reset = checkNotNull(headers["anthropic-ratelimit-unified-reset"]).toLong()
                    assertTrue(reset in (sentAtSeconds + 30)..(receivedAtSeconds + 31), "a thirty-second recheck")
                    assertEquals(null, headers["anthropic-ratelimit-unified-5h-reset"])
                    assertEquals(null, headers["anthropic-ratelimit-unified-7d-reset"])
                    assertEquals(1, rig.refused.size, "the short client wall cannot fit the burst's first wait")
                }
                assertEquals(HttpStatusCode.OK, rig.turn("synthetic-healthy", stream).first)
            } finally {
                rig.close()
            }
        }
    }

    @Test
    fun `restart retains the scoped weekly reset, and each later turn asks the provider`() =
        runBlocking {
            val first = LimitRig(directory)
            first.head.start()
            try {
                first.turn("synthetic-refused")
            } finally {
                first.close()
            }
            // The restarted head asks the same provider, whose weekly window has not moved. A rig built a second
            // later must not name a later reset, or the provider's own reset could not be told from a rewritten one.
            val restarted = LimitRig(directory, reset = first.reset)
            restarted.head.start()
            try {
                assertEquals("seven_day", restarted.upstream.planHold?.claim)
                assertTrue(restarted.upstream.providerResetForMs > 0L)
                val (status, body, headers) = restarted.turn("synthetic-refused")
                assertEquals(HttpStatusCode.TooManyRequests, status)
                first.assertHandedOver(body, headers)
                assertEquals(listOf("synthetic-refused"), restarted.requests, "the refused credential's turn is sent")
                assertEquals(HttpStatusCode.OK, restarted.turn("synthetic-new-login").first)
                assertEquals(listOf("synthetic-refused", "synthetic-new-login"), restarted.requests)
            } finally {
                restarted.close()
            }
        }

    @Test
    fun `the persisted provider reset belongs to the refused credential not the head`() = runBlocking {
        val rig = LimitRig(directory)
        rig.head.start()
        try {
            rig.turn("synthetic-refused")
            val files = Files.list(directory).use { listed ->
                listed.filter { it.fileName.toString().contains("provider-hold") }.toList()
            }
            assertEquals(1, files.size, "one refused credential, one stored reset")
            assertFalse(files.single().fileName.toString() == "provider-hold.json", "an unscoped reset is unsafe")
            val persisted = Files.readString(files.single())
            assertTrue(persisted.contains("seven_day"))
            assertFalse(persisted.contains("synthetic-refused"), "credentials are never persisted")
            val restored = FileProviderHoldStore(directory.resolve("provider-hold.json"), LogSink {})
            val refused = requireNotNull(
                CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-refused")),
            )
            val healthy = requireNotNull(
                CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-healthy")),
            )
            assertEquals(
                rig.limitHeaders["anthropic-ratelimit-unified-7d-reset"]?.toLong(),
                restored.forCredential(refused).load()?.plan?.resetEpochSeconds,
            )
            assertEquals(null, restored.forCredential(healthy).load())
        } finally {
            rig.close()
        }
    }
}

private class LimitRig(
    private val directory: Path,
    private val limits: Boolean = true,
    clock: ElapsedClock = ElapsedClock { 0L },
    names: HeadDeps.CredentialAccountNames = HeadDeps.CredentialAccountNames { null },
    /** When the provider's weekly window resets: one instant, named alike in every refusal until it lifts. */
    val reset: Long = System.currentTimeMillis() / 1_000 + 86_400,
) {
    val requests = mutableListOf<String>()
    val refused = mutableListOf<String>()
    val limitHeaders = linkedMapOf(
        "anthropic-ratelimit-unified-status" to "rejected",
        "anthropic-ratelimit-unified-representative-claim" to "seven_day",
        "anthropic-ratelimit-unified-reset" to reset.toString(),
        "anthropic-ratelimit-unified-7d-status" to "rejected",
        "anthropic-ratelimit-unified-7d-utilization" to "1.0",
        "anthropic-ratelimit-unified-7d-reset" to reset.toString(),
        "anthropic-ratelimit-unified-5h-status" to "allowed",
        "anthropic-ratelimit-unified-5h-utilization" to "0.125",
        "anthropic-ratelimit-unified-5h-reset" to (reset - 43_200).toString(),
        "anthropic-ratelimit-unified-overage-disabled-reason" to "synthetic-budget",
        // Every refusal names the same instant, including Retry-After, rather than another day from each send.
        "retry-after" to DateTimeFormatter.RFC_1123_DATE_TIME
            .format(Instant.ofEpochSecond(reset).atOffset(ZoneOffset.UTC)),
    )

    /** The weekly limit as a session that cannot hold it gets it (family L, one login): splice's line naming the
     *  weekly limit, and the deadlines Claude Code's retry sleeps on naming the true reset, so the session re-asks
     *  then. Claude Code is not told to stop retrying. */
    fun assertHandedOver(body: String, headers: Headers) {
        val error = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject
        assertEquals("rate_limit_error", error.getValue("type").jsonPrimitive.content, body)
        val line = error.getValue("message").jsonPrimitive.content
        assertTrue(line.startsWith("[splice] ") && "weekly limit" in line, line)
        assertEquals("rejected", headers["anthropic-ratelimit-unified-status"])
        assertEquals(reset.toString(), headers["anthropic-ratelimit-unified-reset"])
        assertEquals(reset.toString(), headers["anthropic-ratelimit-unified-7d-reset"])
        val retryAfter = checkNotNull(headers["retry-after"]).toLong()
        val untilReset = reset - System.currentTimeMillis() / 1_000
        assertTrue(retryAfter in (untilReset - 5)..(untilReset + 1), "retry-after names the reset: $retryAfter")
        assertFalse(headers["x-should-retry"] == "false", "Claude Code is not told to stop: $headers")
    }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { request ->
            request.requestBody.use { it.readBytes() }
            val credential = request.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ")
            requests += credential
            val limited = credential == "synthetic-refused"
            val refusal = if (limits) LIMIT_BODY else HEADERLESS_LIMIT_BODY
            val body = if (limited) refusal else SUCCESS_WIRE
            if (limited) {
                refused += credential
                request.responseHeaders.add("x-should-retry", "true")
                if (limits) {
                    limitHeaders.forEach { (name, value) -> request.responseHeaders.add(name, value) }
                    request.responseHeaders.add("anthropic-ratelimit-unified-extra", "opaque-first")
                    request.responseHeaders.add("anthropic-ratelimit-unified-extra", "opaque-second")
                }
            }
            request.responseHeaders.add("Content-Type", if (limited) "application/json" else "text/event-stream")
            val bytes = body.toByteArray(Charsets.UTF_8)
            request.sendResponseHeaders(if (limited) 429 else 200, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val providerClient = HttpClient(CIO)
    val upstream = UpstreamClient(
        totalTimeoutMs = 30_000,
        // These cases pin what the client receives once the schedule is spent. A burst 429 is waited out at 2, 4, 8
        // and 15 s first (section 8), which this real-socket rig takes at once.
        client = providerClient,
        pacing = RetryPacing(waiter = Waiter { }),
        clock = clock,
        holdStore = FileProviderHoldStore(directory.resolve("provider-hold.json"), LogSink {}),
    )
    private val client = HttpClient(CIO)
    private val provider = PassthroughProvider(
        ProviderTuning(
            name = ProviderName(key = "synthetic", label = "synthetic"),
            catalog = ModelCatalog(
                discoveryPrefix = "synthetic--",
                models = listOf(ModelEntry("model", "Synthetic", contextWindow = 200_000)),
                defaultContextWindow = 200_000,
            ),
            pinnedModel = "model",
            auth = ClientAuthProvider("synthetic"),
            locations = ProviderLocations(baseUrl = "http://127.0.0.1:${server.address.port}"),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
        ),
        PassthroughQuirks(providerTag = "synthetic"),
    )
    val head = HeadServer(
        provider,
        listenPort = 0,
        deps = headDeps(
            tmp = directory,
            upstream = upstream,
            gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
            log = {},
        ).copy(
            policy = HeadDeps.HeadPolicy(forwardClientAuth = true),
            quotaBundle = noQuota().copy(credentialAccountNames = names),
        ),
    )

    suspend fun turn(
        credential: String,
        stream: Boolean = true,
        session: String? = null,
        /** The session's client timeout, as a launched session names it; none reads as the old 960 s. */
        wallMs: Long? = null,
    ): Triple<HttpStatusCode, String, Headers> {
        val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Authorization", "Bearer $credential")
            if (wallMs != null) header(CLIENT_WALL_HEADER, wallMs.toString())
            if (session != null) header(SESSION_HEADER, session)
            header("Content-Type", "application/json")
            setBody(
                """{"model":"synthetic--model","stream":$stream,"max_tokens":16,""" +
                    """"messages":[{"role":"user","content":"synthetic"}]}""",
            )
        }
        return Triple(response.status, response.bodyAsText(), response.headers)
    }

    suspend fun accounts(expectedRows: Int): List<String?> =
        awaitAccounts(directory.resolve("perf.jsonl"), expectedRows)

    suspend fun close() {
        head.stop()
        client.close()
        providerClient.close()
        server.stop(0)
    }
}

/** Await the rows these turns owe, including an append submitted after the HTTP reply returned. */
private suspend fun awaitAccounts(
    file: Path,
    expectedRows: Int,
    timeout: Duration = 5.seconds,
): List<String?> = withTimeout(timeout) {
    var accounts = emptyList<String?>()
    while (accounts.size < expectedRows) {
        // A concurrent append may be partial: only newline-terminated rows are durable JSONL records.
        val complete = if (Files.exists(file)) Files.readString(file).substringBeforeLast('\n', "") else ""
        accounts = complete.lineSequence().filter { it.isNotEmpty() }.map { line ->
            Json.parseToJsonElement(line).jsonObject["account"]?.jsonPrimitive?.content
        }.toList()
        if (accounts.size < expectedRows) yield()
    }
    accounts
}

private const val LIMIT_BODY =
    """{"type":"error","error":{"type":"rate_limit_error","message":"synthetic weekly window rejected"}}"""
private const val HEADERLESS_LIMIT_BODY = """{"type":"error","error":{"type":"rate_limit_error","message":"Error"}}"""
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

/** A burst 429 is sent once and again after each of the 2, 4, 8 and 15 s waits (final-design.md section 8). */
private const val BURST_SENDS = 5
