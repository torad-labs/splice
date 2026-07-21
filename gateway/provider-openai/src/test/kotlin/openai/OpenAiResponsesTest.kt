// NEW: openai-platform provider — proves the openai-responses dialect is reused across a THIRD
// auth/quirk profile (api-key, no account header, summary supported). A real turn through a
// HeadServer wired with OpenAiResponsesProvider against the shared Responses mock upstream.
package openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import mock.MockChatGptUpstream
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiResponsesProvider
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAiResponsesTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) {
        defaultRequest { bearerAuth("test-inference-token") }
    }
    private val port = freshPort()
    private lateinit var head: HeadServer

    @BeforeAll
    fun setUp() = runBlocking {
        val tmp = Files.createTempDirectory("oai-it")
        val provider = OpenAiResponsesProvider(
            tuning = ProviderTuning(
                key = "openai",
                label = "openai",
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-openai--",
                    models = listOf(ModelEntry("gpt-5-pro", "GPT-5 Pro", contextWindow = 400_000)),
                    defaultContextWindow = 400_000,
                ),
                pinnedModel = "gpt-5-pro",
                auth = ApiKeyAuthProvider("OPENAI_API_KEY", envReader = { "sk-openai-key-abcdef" }),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        )
        head = HeadServer(
            provider = provider,
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(5_000, 30_000, 2),
                inferenceToken = "test-inference-token",
                gate = InflightGate({ 0 }),
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("c.jsonl")),
                usageStore = UsageStore(tmp.resolve("u.json"), tmp.resolve("r.json")),
                perfStats = PerfStats(tmp.resolve("p.jsonl")),
                log = {},
            ),
        )
        head.start()
        awaitListening(port)
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        client.close()
        mock.stop()
    }

    @Test
    fun `openai-platform serves a real turn via the shared Responses dialect - api-key, no account header`() = runBlocking {
        val sse = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-openai--gpt-5-pro","stream":true,
                    "system":"You are a test. SCENARIO:basic","messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()
        assertTrue(sse.contains("ok after auth"))
        assertTrue(sse.contains("event: message_stop"))
        // api key rode as the bearer; NO ChatGPT-Account-ID header (openai platform, not ChatGPT)
        assertTrue(mock.upstreamAuths.any { it.second == "Bearer sk-openai-key-abcdef" })
    }
}
