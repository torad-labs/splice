// NEW: failover within one provider, driven through a real head (operator ruling, Oct 3, 3:44 PM CT: "when one hits its
// limit the same command moves to the next in the order Accounts sets"). A passthrough head with three synthetic logins
// in a pool, over a local upstream that refuses a login with a native 429 naming its spent plan window. The turn that
// meets the 429 hands the client that native reply; the same session's next turn reaches the next login in the order,
// with no restart and nothing done by the client. With every login held, the client gets the native refusal of the
// login whose reset is nearest, replayed at admission with no upstream request: splice adds no retry of its own.
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
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.HeadServer
import splice.head.headDeps
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
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val HOUR_S = 3_600L
private const val SESSION = "one-command"

class PoolFailoverTurnTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `the login that hits its limit hands the client its refusal, and the same command's next turn uses the next`() =
        runBlocking {
            val rig = FailoverRig(directory, limited = mapOf("one" to 2 * HOUR_S))
            rig.start()
            try {
                val first = rig.turn()
                assertEquals(HttpStatusCode.TooManyRequests, first.first)
                assertEquals(refusal("one"), first.second, "the native reply, untouched")
                val second = rig.turn()
                assertEquals(HttpStatusCode.OK, second.first)
                assertEquals(HttpStatusCode.OK, rig.turn().first)
                assertEquals(listOf("one", "two", "two"), rig.requests)
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
                repeat(3) { assertEquals(HttpStatusCode.TooManyRequests, rig.turn().first) }
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
private class FailoverRig(directory: Path, private val limited: Map<String, Long>) {
    val requests = CopyOnWriteArrayList<String>()
    private val labels = listOf("one", "two", "three")
    private val logins = labels.associateWith(::SyntheticLogin)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { request ->
            request.requestBody.use { it.readBytes() }
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
                auth = logins.getValue("one"),
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
            quota = quotaFor(null, pool),
        ),
    )
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }

    suspend fun start() = head.start()

    suspend fun turn(): Pair<HttpStatusCode, String> {
        val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", SESSION)
            setBody(
                """{"model":"synthetic--model","stream":true,"max_tokens":16,"messages":[{"role":"user","content":"go"}]}""",
            )
        }
        return response.status to response.bodyAsText()
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
