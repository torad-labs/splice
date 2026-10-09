// NEW: the two ways an upstream turn ENDS that nothing pinned end to end before the exceptions that carried them
// became sealed outcomes: the host's own 529, retried and surfaced as overloaded_error (the type Claude Code retries
// on), and an oversized frame AFTER content reached the client, which can no longer be re-issued and must end the
// stream honestly with the content kept. A SIBLING class rather than more cases on HeadServerIntegrationTest, which
// is at detekt's LargeClass ceiling.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.WindowRule
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class EndingsAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerEndingsTest {
    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        defaultRequest { bearerAuth("test-inference-token") }
    }
    private lateinit var head: HeadServer

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
        windowRules = listOf(WindowRule("gpt-5.6", 272_000)),
        defaultContextWindow = 272_000,
    )

    @BeforeAll
    fun setUp(@TempDir tmp: Path) = runTest {
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = EndingsAuth(),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        )
        head = HeadServer(
            provider = provider,
            listenPort = 0,
            deps = headDeps(tmp = tmp, upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2))
                .copy(stores = headStores(tmp)),
        )
        head.start()
        awaitListening(head.port)
    }

    @AfterAll
    fun tearDown() = runTest {
        head.stop()
        client.close()
        mock.stop()
    }

    private suspend fun messages(scenario: String): String =
        client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:$scenario",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()

    @Test
    fun `a host 529 is retried by the head and reaches the client as overloaded_error, never api_error`() = runTest {
        val sse = messages("overload_529")
        assertTrue(sse.contains("event: error"), sse)
        assertTrue(sse.contains("overloaded_error"), sse)
        assertFalse(sse.contains("api_error"), sse)
        val posts = mock.upstreamBodies.count { it.first == "overload_529" }
        assertTrue(posts >= 2, "the head gave up on the 529 without retrying: $posts POST(s)")
    }

    @Test
    fun `an oversized frame after content reached the client ends the stream honestly, with the content kept`() =
        runTest {
            val sse = messages("oversized_after_content")
            assertTrue(sse.contains("partial answer"), "the content already streamed stays: $sse")
            assertTrue(sse.contains("event: error"), sse)
            assertTrue(sse.contains("oversized streaming event"), sse)
            assertFalse(sse.contains("event: message_stop"), sse)
        }
}
