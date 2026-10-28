// NEW: the real head's error journal and perf row must name the same canonical outcome.
package splice.head

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
import splice.core.client.CLIENT_WALL_HEADER
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.usage.PlanLimit
import splice.core.util.AsyncFileIo
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.failure.ResponsesReplies
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.Waiter
import splice.upstream.failure.FailedReply
import splice.upstream.failure.ProviderDeviation
import splice.upstream.failure.Replies
import splice.upstream.transport.RetryPacing
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val PLAN_BODY = """{"error":{"type":"usage_limit_reached","message":"synthetic plan spent"}}"""
private const val BURST_BODY = """{"error":{"type":"rate_limit_exceeded","message":"synthetic burst"}}"""
private const val FAILURE_BODY = """{"error":{"type":"server_error","message":"synthetic upstream failure"}}"""

/** A client timeout too short to hold any limit, so a limit is handed to the client rather than held. */
private const val SHORT_WALL_MS = 10_000L

private class SyntheticAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic-token")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "synthetic")
}

/** The head's reader names the plan body's spent window, as a body-reading provider's does (CodexReplies), and reads
 *  the reply as the limit of that window, lifting at the reset it named. */
private object PlanBodyReader : ProviderDeviation {
    override fun planLimit(reply: FailedReply): PlanLimit? =
        if (reply.body == PLAN_BODY) PlanLimit("seven_day", reply.answeredAt.epochSecond + 86_400) else null

    override fun http(reply: FailedReply): FailureCause.Provider? = planLimit(reply)?.let { plan ->
        val lift = Lift.At(Instant.ofEpochSecond(plan.resetEpochSeconds), true, LiftSource.BODY)
        FailureCause.Provider.Limited(lift, LimitWindow.WEEKLY)
    }
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
                name = ProviderName(key = "synthetic", label = "Synthetic"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-synthetic--",
                    models = listOf(ModelEntry("synthetic-model", "Synthetic", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "synthetic-model",
                auth = SyntheticAuth(),
                locations = ProviderLocations(baseUrl = "http://127.0.0.1:${upstream.address.port}"),
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
            replies = Replies(ResponsesReplies, PlanBodyReader),
        ),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            // The engine's waits are taken at once: this case is about the ending a spent schedule records.
            upstream = UpstreamClient(totalTimeoutMs = 30_000, pacing = RetryPacing(waiter = Waiter { })),
            log = { logs.add(it) },
        ),
    )

    suspend fun assertOutcome(expected: String) {
        try {
            head.start()
            val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
                bearerAuth("test-inference-token")
                header("Content-Type", "application/json")
                // A session whose client timeout cannot hold a limit (slice 5): the limit reaches its client at once.
                header(CLIENT_WALL_HEADER, SHORT_WALL_MS.toString())
                setBody(
                    """{"model":"claude-synthetic--synthetic-model","stream":true,"max_tokens":64,
                        "messages":[{"role":"user","content":"synthetic request"}]}""",
                )
            }.bodyAsText()
            // Its client reads one [splice] line: in band, or as a 429 the session re-asks at the reset (family L).
            assertTrue("[splice]" in response, response)
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
    fun `a burst 429 logs the recorded rate-limited outcome`(@TempDir tmp: Path) = runBlocking {
        FailedHead(tmp, 429, BURST_BODY).assertOutcome("error:rate-limited")
    }

    @Test
    fun `an ordinary upstream failure retains the recorded upstream-failed outcome`(@TempDir tmp: Path) = runBlocking {
        FailedHead(tmp, 500, FAILURE_BODY).assertOutcome("error:upstream-failed")
    }
}
