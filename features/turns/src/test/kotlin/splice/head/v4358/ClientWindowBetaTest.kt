// NEW: V4-358's head half. Claude Code strips the 1M hint from the body's `model` and says it in an
// `anthropic-beta` instead (measured on 2.1.283, Sep 28), so a row the launch spelled 1M reaches the head as
// its bare id. The beta is then the only witness of the window the client divides by on that request: with
// it the counts are scaled to a 1e6 window, without it the launch env's, exactly as before.
package splice.head.v4358

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadServer
import splice.head.MockChatGptUpstream
import splice.head.TestResponsesProvider
import splice.head.awaitListening
import splice.head.headDeps
import splice.upstream.ProviderTuning
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val ROW_WINDOW = 500_000L
private const val ONE_MILLION_BETA = "claude-code-20250219,context-1m-2025-08-07,interleaved-thinking-2025-05-14"
private const val OTHER_BETAS = "claude-code-20250219,interleaved-thinking-2025-05-14"

// the multipart scenario's upstream usage: input_tokens 10
private const val UPSTREAM_INPUT_TOKENS = 10L

private class FakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientWindowBetaTest {
    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        defaultRequest { bearerAuth("test-inference-token") }
    }
    private lateinit var head: HeadServer
    private val windows = ClientWindows()

    @BeforeAll
    fun setUp(@TempDir tmp: Path) = runBlocking {
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-6-sol", "Sol", contextWindow = ROW_WINDOW)),
            defaultContextWindow = ROW_WINDOW,
            pinnedModel = "gpt-6-sol",
        )
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-6-sol",
                auth = FakeAuth(),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        )
        val deps = headDeps(tmp = tmp).let { it.copy(stores = it.stores.copy(clientWindows = windows)) }
        head = HeadServer(provider = provider, listenPort = 0, deps = deps)
        head.start()
        awaitListening(head.port)
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        client.close()
        mock.stop()
    }

    /** The `input_tokens` the client is told for one non-streaming turn on the bare id, given [betas]. */
    private fun reportedInput(betas: String, session: String? = null): Long = runBlocking {
        val reply = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("anthropic-beta", betas)
            session?.let { header("x-claude-code-session-id", it) }
            setBody(
                """{"model":"gpt-6-sol","stream":false,"max_tokens":1000,"system":"SCENARIO:multipart",""" +
                    """"messages":[{"role":"user","content":"go"}]}""",
            )
        }
        Json.parseToJsonElement(reply.bodyAsText()).jsonObject.getValue("usage").jsonObject
            .getValue("input_tokens").jsonPrimitive.content.toLong()
    }

    @Test
    fun `a request carrying the 1M-context beta is counted against the client's 1M window`() {
        // 1e6 / 500k = 2.0: the client divides by 1e6 and the row really has 500k
        assertEquals(UPSTREAM_INPUT_TOKENS * 2, reportedInput(ONE_MILLION_BETA))
    }

    @Test
    fun `a request without it is counted against the launch env's window, raw on the pinned row`() {
        assertEquals(UPSTREAM_INPUT_TOKENS, reportedInput(OTHER_BETAS))
    }

    @Test
    fun `the request's own window outranks the one a status-line post taught for the session`() {
        windows.record("s1", 300_000L)

        // 1e6 / 500k = 2.0 from the beta; the post alone would say 300k / 500k = 0.6
        assertEquals(UPSTREAM_INPUT_TOKENS * 2, reportedInput(ONE_MILLION_BETA, session = "s1"))
        assertEquals(UPSTREAM_INPUT_TOKENS * 6 / 10, reportedInput(OTHER_BETAS, session = "s1"), "no witness: the post")
    }
}
