// NEW: V4-431 — the real head's error journal and perf row must name the same canonical outcome.
package splice.head.v4431

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.usage.PlanLimit
import splice.core.util.AsyncFileIo
import splice.head.HeadServer
import splice.head.TestResponsesProvider
import splice.head.headDeps
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val PLAN_BODY = """{"error":{"type":"usage_limit_reached","message":"synthetic plan spent"}}"""
private const val BURST_BODY = """{"error":{"type":"rate_limit_exceeded","message":"synthetic burst"}}"""
private const val FAILURE_BODY = """{"error":{"type":"server_error","message":"synthetic upstream failure"}}"""

private class SyntheticAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic-token")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
    override fun planLimitFromBody(body: String, nowEpochSeconds: Long): PlanLimit? =
        if (body == PLAN_BODY) PlanLimit("seven_day", nowEpochSeconds + 86_400) else null
}

private class FailedHead(tmp: Path, status: Int, body: String) {
    private val logs = CopyOnWriteArrayList<String>()
    private val perfFile = tmp.resolve("perf.jsonl")
    private val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val client = HttpClient(CIO)
    private val head = HeadServer(
        provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "synthetic",
                label = "Synthetic",
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-synthetic--",
                    models = listOf(ModelEntry("synthetic-model", "Synthetic", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "synthetic-model",
                auth = SyntheticAuth(),
                baseUrl = "http://127.0.0.1:${upstream.address.port}",
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        ),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 1),
            log = { logs.add(it) },
        ),
    )

    suspend fun assertOutcome(expected: String) {
        try {
            head.start()
            val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
                bearerAuth("test-inference-token")
                header("Content-Type", "application/json")
                setBody(
                    """{"model":"claude-synthetic--synthetic-model","stream":true,"max_tokens":64,
                        "messages":[{"role":"user","content":"synthetic request"}]}""",
                )
            }.bodyAsText()
            assertTrue("event: error" in response, response)
            assertTrue(AsyncFileIo.drain(), "the perf append must finish")
            val perf = Files.readAllLines(perfFile).single()
            val outcome = Json.parseToJsonElement(perf).jsonObject.getValue("outcome").jsonPrimitive.content
            assertEquals(expected, outcome, perf)
            val line = logs.single { "turn ERROR " in it }
            val logged = line.substringAfter("turn ERROR ").substringBefore(' ')
            assertEquals(outcome, logged, "the journal and perf must agree: $line")
        } finally {
            head.stop()
            client.close()
            upstream.stop(0)
        }
    }
}

class ErrorOutcomeLogTest {
    @Test
    fun `a named spent plan window logs the recorded plan-limit outcome`(@TempDir tmp: Path) = runBlocking {
        FailedHead(tmp, 429, PLAN_BODY).assertOutcome("error:plan-limit")
    }

    @Test
    fun `a burst 429 retains the recorded upstream-failed outcome`(@TempDir tmp: Path) = runBlocking {
        FailedHead(tmp, 429, BURST_BODY).assertOutcome("error:upstream-failed")
    }

    @Test
    fun `an ordinary upstream failure retains the recorded upstream-failed outcome`(@TempDir tmp: Path) = runBlocking {
        FailedHead(tmp, 500, FAILURE_BODY).assertOutcome("error:upstream-failed")
    }
}
