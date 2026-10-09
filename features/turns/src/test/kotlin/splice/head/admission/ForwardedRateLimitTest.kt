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
import splice.head.turn.SESSION_HEADER
import splice.upstream.ProviderTuning
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.InflightGate
import splice.upstream.retry.MAX_RATE_LIMIT_COOLDOWN_MS
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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

    @Test
    fun `the locally replayed refusal keeps the proved account without an upstream attempt`() = runBlocking {
        val names = HeadDeps.CredentialAccountNames { digest ->
            "held@example.invalid".takeIf { digest == key("synthetic-refused") }
        }
        val rig = LimitRig(directory, names = names)
        rig.head.start()
        try {
            repeat(2) { assertEquals(HttpStatusCode.TooManyRequests, rig.turn("synthetic-refused").first) }
            assertEquals(1, rig.requests.size)
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

    @Test
    fun `provider and local refusals preserve native status body and every limit header`() = runBlocking {
        for (stream in listOf(false, true)) {
            val rig = LimitRig(directory.resolve(stream.toString()))
            rig.head.start()
            try {
                repeat(2) {
                    val (status, body, headers) = rig.turn("synthetic-refused", stream)
                    assertEquals(HttpStatusCode.TooManyRequests, status)
                    assertEquals(LIMIT_BODY, body)
                    rig.limitHeaders.forEach { (name, value) -> assertEquals(value, headers[name], name) }
                    assertEquals(
                        listOf("opaque-first", "opaque-second"),
                        headers.getAll("anthropic-ratelimit-unified-extra"),
                    )
                }
                assertEquals(1, rig.requests.size, "the same credential's follower uses its stored refusal")
            } finally {
                rig.close()
            }
        }
    }

    @Test
    fun `headerless provider 429 reaches the client immediately without holding another login`() = runBlocking {
        val rig = LimitRig(directory, limits = false)
        rig.head.start()
        try {
            repeat(2) {
                val (status, body, headers) = rig.turn("synthetic-refused")
                assertEquals(HttpStatusCode.TooManyRequests, status)
                assertEquals(HEADERLESS_LIMIT_BODY, body)
                assertEquals("true", headers["x-should-retry"])
                assertEquals(null, headers["retry-after"])
                assertEquals(null, headers["anthropic-ratelimit-unified-status"])
            }
            assertEquals(HttpStatusCode.OK, rig.turn("synthetic-healthy").first)
            assertEquals(HttpStatusCode.OK, rig.turn("synthetic-new-login").first)
            assertEquals(1, rig.refused.size, "native backoff belongs to the client, not four hidden proxy retries")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `restart retains the scoped native refusal without an upstream attempt or holding another login`() = runBlocking {
        val first = LimitRig(directory)
        first.head.start()
        try {
            first.turn("synthetic-refused")
        } finally {
            first.close()
        }
        var elapsed = 0L
        val restarted = LimitRig(directory, clock = ElapsedClock { elapsed })
        restarted.head.start()
        try {
            assertEquals("seven_day", restarted.upstream.planHold?.claim)
            assertTrue(restarted.upstream.providerResetForMs > 0L)
            assertEquals(
                MAX_RATE_LIMIT_COOLDOWN_MS,
                restarted.upstream.rateLimitedForMs,
                "startup retains the native credential's refusal, bounded by the existing re-probe ceiling",
            )
            val (status, body, headers) = restarted.turn("synthetic-refused")
            assertEquals(HttpStatusCode.TooManyRequests, status)
            assertEquals(LIMIT_BODY, body)
            first.limitHeaders.forEach { (name, value) -> assertEquals(value, headers[name], name) }
            assertEquals(0, restarted.requests.size, "the persisted native refusal makes zero upstream attempts")
            assertEquals(HttpStatusCode.OK, restarted.turn("synthetic-new-login").first)
            assertEquals(listOf("synthetic-new-login"), restarted.requests)

            elapsed = MAX_RATE_LIMIT_COOLDOWN_MS + 1
            assertEquals(HttpStatusCode.TooManyRequests, restarted.turn("synthetic-refused").first)
            assertEquals(listOf("synthetic-new-login", "synthetic-refused"), restarted.requests)
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
            assertEquals(LIMIT_BODY, restored.forCredential(refused).load()?.rateLimitReply?.body)
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
) {
    val requests = mutableListOf<String>()
    val refused = mutableListOf<String>()
    private val reset = System.currentTimeMillis() / 1_000 + 86_400
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
        "retry-after" to "86400",
    )
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
        maxRetries = 4,
        client = providerClient,
        clock = clock,
        holdStore = FileProviderHoldStore(directory.resolve("provider-hold.json"), LogSink {}),
    )
    private val client = HttpClient(CIO)
    private val provider = PassthroughProvider(
        ProviderTuning(
            key = "synthetic",
            label = "synthetic",
            catalog = ModelCatalog(
                discoveryPrefix = "synthetic--",
                models = listOf(ModelEntry("model", "Synthetic", contextWindow = 200_000)),
                defaultContextWindow = 200_000,
            ),
            pinnedModel = "model",
            auth = ClientAuthProvider("synthetic"),
            baseUrl = "http://127.0.0.1:${server.address.port}",
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
    ): Triple<HttpStatusCode, String, Headers> {
        val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Authorization", "Bearer $credential")
            if (session != null) header(SESSION_HEADER, session)
            header("Content-Type", "application/json")
            setBody(
                """{"model":"synthetic--model","stream":$stream,"max_tokens":16,"messages":[{"role":"user","content":"synthetic"}]}""",
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

private const val LIMIT_BODY = """{"type":"error","error":{"type":"rate_limit_error","message":"synthetic weekly window rejected"}}"""
private const val HEADERLESS_LIMIT_BODY = """{"type":"error","error":{"type":"rate_limit_error","message":"Error"}}"""
private val SUCCESS_WIRE = listOf(
    """{"type":"message_start","message":{"id":"msg_synthetic","type":"message","role":"assistant","model":"model","usage":{"input_tokens":1,"output_tokens":0}}}""",
    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"synthetic success"}}""",
    """{"type":"content_block_stop","index":0}""",
    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
    """{"type":"message_stop"}""",
).joinToString("\n\n", postfix = "\n\n") { event ->
    val kind = event.substringAfter("\"type\":\"").substringBefore('"')
    "event: $kind\ndata: $event"
}
