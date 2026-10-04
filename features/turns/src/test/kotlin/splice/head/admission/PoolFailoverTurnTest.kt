// NEW: failover within one provider, driven through a real head (operator ruling, Oct 3, 3:44 PM CT: "when one hits its
// limit the same command moves to the next in the order Accounts sets"). A passthrough head with three synthetic logins
// in a pool, over a local upstream that refuses a login with a native 429 naming its spent plan window. The turn that
// meets the 429 moves the same request to the next free login before the client hears any refusal. Later turns stay
// on the serving login. With every login held, admission replays the nearest-reset login's native refusal locally.
// No login is retried within a request, and splice adds no retry words.
package splice.head.admission

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.ClientAuthProvider
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaHeaderRead
import splice.core.util.AsyncFileIo
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.headDeps
import splice.head.headStores
import splice.head.perf.PerfStats
import splice.head.quotaFor
import splice.upstream.ProviderTuning
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.FileProviderHoldStore
import splice.upstream.retry.InflightGate
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val HOUR_S = 3_600L
private const val SESSION = "one-command"

class PoolFailoverTurnTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `the same streamed request moves from the plan-limited login to the next free login`() = runBlocking {
        val rig = FailoverRig(directory, limited = mapOf("one" to 2 * HOUR_S))
        rig.start()
        try {
            val first = rig.turn()
            assertEquals(HttpStatusCode.OK, first.first, "no native refusal reaches the client while a login is free")
            assertEquals(listOf("one", "two"), rig.requests, "one attempt on each login within the same request")
            assertEquals(rig.bodies[0], rig.bodies[1], "handoff sends the exact prepared request bytes")
            assertEquals("5-hour plan limit reached", rig.switchReason())
            assertEquals(HttpStatusCode.OK, rig.turn().first)
            assertEquals(listOf("one", "two", "two"), rig.requests)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `the same buffered request moves to the next free login without a rate limit answer`() = runBlocking {
        val rig = FailoverRig(directory, limited = mapOf("one" to 2 * HOUR_S))
        rig.start()
        try {
            assertEquals(HttpStatusCode.OK, rig.turn(stream = false).first)
            assertEquals(listOf("one", "two"), rig.requests)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a forwarded single login records its stable label and never retries its native refusal`() = runBlocking {
        val rig = FailoverRig(directory, limited = mapOf("one" to 2 * HOUR_S), pooled = false)
        rig.start()
        try {
            assertEquals(HttpStatusCode.TooManyRequests, rig.turn().first)
            assertEquals(listOf("one"), rig.requests)
            assertEquals("claude-code", rig.lastAccount())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `the usage row names the login that served the same request`() = runBlocking {
        val rig = FailoverRig(directory, limited = mapOf("one" to 2 * HOUR_S))
        rig.start()
        try {
            rig.turn()
            assertEquals("two", rig.lastAccount(), "usage is attributed to the serving login, not the refused one")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `with every login held the client gets the native refusal of the login whose reset is nearest`() =
        runBlocking {
            val resets = mapOf("one" to 3 * HOUR_S, "two" to HOUR_S, "three" to 2 * HOUR_S)
            val rig = FailoverRig(directory, limited = resets)
            rig.start()
            try {
                assertEquals(HttpStatusCode.TooManyRequests, rig.turn().first)
                assertEquals(listOf("one", "two", "three"), rig.requests, "each login meets its own limit once")

                val held = rig.turn()

                assertEquals(HttpStatusCode.TooManyRequests, held.first)
                assertEquals(refusal("two"), held.second, "two resets first")
                assertEquals(listOf("one", "two", "three"), rig.requests, "replayed at admission, no upstream request")
            } finally {
                rig.close()
            }
        }
}

private fun refusal(login: String): String =
    """{"type":"error","error":{"type":"rate_limit_error","message":"five_hour window rejected for $login"}}"""

/** A synthetic subscription login: its own bearer, answered in Anthropic's unified plan family, which it reads the way
 *  the forwarded login does. */
private class SyntheticLogin(private val token: String) : RefreshableAuthProvider {
    private val family = ClientAuthProvider(token)
    override suspend fun credentials(): Credentials = Credentials.Bearer(token)
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
    override fun planLimit(header: QuotaHeaderRead, nowEpochSeconds: Long): PlanLimit? =
        family.planLimit(header, nowEpochSeconds)
}

/** [limited] names each login the upstream refuses and how far out its five-hour reset is. */
private class FailoverRig(directory: Path, private val limited: Map<String, Long>, pooled: Boolean = true) {
    val requests = CopyOnWriteArrayList<String>()
    val bodies = CopyOnWriteArrayList<String>()
    private val labels = listOf("one", "two", "three")
    private val logins = labels.associateWith(::SyntheticLogin)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { request ->
            bodies += request.requestBody.use { it.readBytes().decodeToString() }
            val login = request.requestHeaders.getFirst("Authorization").orEmpty().removePrefix("Bearer ")
            requests += login
            val resetIn = limited[login]
            if (resetIn != null) {
                val reset = (System.currentTimeMillis() / 1_000 + resetIn).toString()
                mapOf(
                    "anthropic-ratelimit-unified-status" to "rejected",
                    "anthropic-ratelimit-unified-representative-claim" to "five_hour",
                    "anthropic-ratelimit-unified-reset" to reset,
                    "anthropic-ratelimit-unified-5h-status" to "rejected",
                    "anthropic-ratelimit-unified-5h-utilization" to "1.0",
                    "anthropic-ratelimit-unified-5h-reset" to reset,
                ).forEach { (name, value) -> request.responseHeaders.add(name, value) }
            }
            val body = if (resetIn != null) refusal(login) else SUCCESS_WIRE
            val type = if (resetIn != null) "application/json" else "text/event-stream"
            request.responseHeaders.add("Content-Type", type)
            val bytes = body.toByteArray(Charsets.UTF_8)
            request.sendResponseHeaders(if (resetIn != null) 429 else 200, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val pool = AccountPool(
        labels.map { label ->
            PoolAccount(
                label = label,
                primary = label == "one",
                auth = logins.getValue(label),
                quota = AccountQuotaSource { null },
                cooldown = RateLimitCooldown(
                    ElapsedClock { 0L },
                    store = FileProviderHoldStore(directory.resolve("$label-provider-hold.json"), LogSink {}),
                ),
            )
        },
        WallClock { System.currentTimeMillis() },
    ).also { it.order = labels }
    private val providerClient = HttpClient(CIO)
    private val perfFile = directory.resolve("perf.jsonl")
    private val head = HeadServer(
        PassthroughProvider(
            ProviderTuning(
                key = "synthetic",
                label = "synthetic",
                catalog = ModelCatalog(
                    discoveryPrefix = "synthetic--",
                    models = listOf(ModelEntry("model", "Synthetic", contextWindow = 200_000)),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = "model",
                auth = if (pooled) logins.getValue("one") else ClientAuthProvider("synthetic"),
                baseUrl = "http://127.0.0.1:${server.address.port}",
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            PassthroughQuirks(providerTag = "synthetic"),
        ),
        listenPort = 0,
        deps = headDeps(
            tmp = directory,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 4, client = providerClient),
            gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
            quota = quotaFor(null, if (pooled) pool else null),
            policy = HeadDeps.HeadPolicy(forwardClientAuth = true),
        ).copy(stores = headStores(directory).copy(perfStats = PerfStats(perfFile))),
    )
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("one") } }

    suspend fun start() = head.start()

    suspend fun turn(stream: Boolean = true): Pair<HttpStatusCode, String> {
        val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", SESSION)
            setBody(
                """{"model":"synthetic--model","stream":$stream,"max_tokens":16,"messages":[{"role":"user","content":"go"}]}""",
            )
        }
        return response.status to response.bodyAsText()
    }

    fun switchReason(): String? = pool.view(SESSION).lastSwitch?.reason

    fun lastAccount(): String? {
        check(AsyncFileIo.drain())
        val row = Files.readAllLines(perfFile).last()
        return Json.parseToJsonElement(row).jsonObject["account"]?.jsonPrimitive?.content
    }

    suspend fun close() {
        head.stop()
        client.close()
        providerClient.close()
        server.stop(0)
    }
}

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
