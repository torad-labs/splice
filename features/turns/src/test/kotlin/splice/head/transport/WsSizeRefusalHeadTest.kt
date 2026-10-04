// NEW: a round the WebSocket peer refused as too large reaches Claude Code as HTTP 400 "prompt is too long", sent once.
package splice.head.transport

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.TurnPerf
import splice.core.perf.WsAttemptTiming
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.WatchdogBudget
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.TestResponsesProvider
import splice.head.admission.RequestMaterializationGate
import splice.head.headDeps
import splice.head.headStores
import splice.upstream.Provider
import splice.upstream.ProviderTuning
import splice.upstream.WsRound
import splice.upstream.WsRoundRunner
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

private const val BAD_REQUEST = 400
private const val RETRIES = 3

/** The Responses runner's contract, and nothing else: an attempt whose peer closed 1009 before any event counts
 *  the refusal on the turn and rides SSE (WsSizeRefusalCountTest pins the real runner to it). [refuses] false is a
 *  socket that failed for any other reason. */
private class RefusingRunner(private val refuses: Boolean) : WsRoundRunner {
    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): WsRound? = null

    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
        perf: TurnPerf?,
    ): WsRound? {
        if (refuses) perf?.let(::WsAttemptTiming)?.refusedAsTooLarge()
        return null
    }

    override fun isFailureTerminal(event: JsonObject): Boolean = false

    override fun roundEnded(meta: TurnMeta, ok: Boolean) = Unit

    override fun roundBypassed(meta: TurnMeta) = Unit
}

private class RefusingWsProvider(private val inner: Provider, private val runner: WsRoundRunner) : Provider by inner {
    override val wsRunner: WsRoundRunner get() = runner
}

private class FixedAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-size", "acct-size")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fixed")
}

/**
 * Oct 4, 6:31 AM CT onward, on the claudex head: the WebSocket peer closed a code-mode round 1009 before any
 * event, the round rode SSE, HTTP answered 400 `{"detail":"Bad Request"}` four times, and Claude Code read an
 * overload and sent the same request again for 36 minutes. Claude Code 2.1.289 compacts on an error whose text
 * includes "prompt is too long" (its K6n) and retries only 429 and 5xx.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WsSizeRefusalHeadTest(@param:TempDir private val tmp: Path) {
    private val posts = AtomicInteger()
    private val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            ex.requestBody.use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
            posts.incrementAndGet()
            val body = """{"detail":"Bad Request"}""".toByteArray()
            ex.sendResponseHeaders(BAD_REQUEST, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        start()
    }
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private var built = 0

    @AfterAll
    fun tearDown() {
        client.close()
        upstream.stop(0)
    }

    private data class Run(val status: Int, val body: String, val posts: Int)

    private fun turn(refuses: Boolean): Run {
        val head = HeadServer(
            provider = RefusingWsProvider(provider(), RefusingRunner(refuses)),
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = RETRIES),
                seams = HeadDeps.HeadSeams(requestMaterializationGate = RequestMaterializationGate()),
            ).copy(stores = headStores(tmp, suffix = "-${++built}")),
        )
        val before = posts.get()
        runBlocking { head.start() }
        try {
            val response = runBlocking {
                client.post("http://127.0.0.1:${head.port}/v1/messages") {
                    setBody(
                        """{"model":"claude-codex--gpt-6.1-sol","stream":true,"max_tokens":100,
                            "messages":[{"role":"user","content":"hi"}]}""",
                    )
                }
            }
            return Run(response.status.value, runBlocking { response.bodyAsText() }, posts.get() - before)
        } finally {
            runBlocking { head.stop() }
        }
    }

    private fun provider(): Provider = TestResponsesProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "claudex",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-6.1-sol", "Sol", contextWindow = 400_000)),
                defaultContextWindow = 400_000,
            ),
            pinnedModel = "gpt-6.1-sol",
            auth = FixedAuth(),
            baseUrl = "http://127.0.0.1:${upstream.address.port}",
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = "high",
        configSummary = "detailed",
    )

    @Test
    fun `a body the WebSocket peer refused is refused over HTTP once and the client is told to compact`() {
        val run = turn(refuses = true)

        assertEquals(1, run.posts, "the same bytes are sent over HTTP once")
        assertEquals(BAD_REQUEST, run.status, "a 400 Claude Code never retries: ${run.body}")
        assertTrue("prompt is too long" in run.body.lowercase(), run.body)
        assertFalse("overloaded" in run.body, run.body)
    }

    @Test
    fun `the same 400 with no WebSocket refusal before it keeps its retries and no compaction line`() {
        val run = turn(refuses = false)

        assertEquals(RETRIES, run.posts, "V4-62 retries a 400 nothing named")
        assertFalse("prompt is too long" in run.body.lowercase(), run.body)
    }
}
