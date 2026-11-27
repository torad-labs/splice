// NEW: Claude Code strips the 1M hint from the model and says it in an `anthropic-beta`, which then decides
// the window the counts are scaled to on that request.
package splice.head

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
import splice.core.model.CLIENT_WINDOW_HEADER
import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val ROW_WINDOW = 500_000L
private const val ONE_MILLION_BETA = "claude-code-20250219,context-1m-2025-08-07,interleaved-thinking-2025-05-14"
private const val OTHER_BETAS = "claude-code-20250219,interleaved-thinking-2025-05-14"

// the multipart scenario's upstream usage: input_tokens 10
private const val UPSTREAM_INPUT_TOKENS = 10L

private class WindowBetaAuth : RefreshableAuthProvider {
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
                name = ProviderName(key = "codex", label = "claudex"),
                catalog = catalog,
                pinnedModel = "gpt-6-sol",
                auth = WindowBetaAuth(),
                locations = ProviderLocations(baseUrl = mock.baseUrl),
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

    /** The `input_tokens` the client is told for one non-streaming turn on the bare id, given [betas], from a process
     *  whose launch named [launchWindow]. */
    private fun reportedInput(betas: String, session: String? = null, launchWindow: Long? = null): Long = runBlocking {
        val reply = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            header("anthropic-beta", betas)
            session?.let { header("x-claude-code-session-id", it) }
            launchWindow?.let { header(CLIENT_WINDOW_HEADER, it.toString()) }
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
    fun `a status-line post cannot discount a turn that names no window`() {
        windows.record("s1", 300_000L)

        assertEquals(UPSTREAM_INPUT_TOKENS * 2, reportedInput(ONE_MILLION_BETA, session = "s1"))
        assertEquals(UPSTREAM_INPUT_TOKENS, reportedInput(OTHER_BETAS, session = "s1"))
        assertEquals(6L, reportedInput(OTHER_BETAS, session = "s1", launchWindow = 300_000))
    }

    @Test
    fun `a larger status-line window can still raise a turn's reported input`() {
        windows.record("larger", 750_000L)
        assertEquals(15L, reportedInput(OTHER_BETAS, session = "larger"))
    }

    // #706: a session first launched when the row declared 229,376 kept that window under its id. Resumed by a
    // process launched on today's 500k, its main thread on `[1m]` never posted again, and a subagent without the 1M
    // beta was shown 0.46x its real count, so it never compacted and the backend refused it at its slot.
    @Test
    fun `a resumed session's subagent is counted against the window its own process was launched with`() {
        windows.record("resumed", 229_376L)

        assertEquals(
            UPSTREAM_INPUT_TOKENS,
            reportedInput(OTHER_BETAS, session = "resumed", launchWindow = ROW_WINDOW),
            "500k / 500k: the real count, not the earlier launch's 229,376 / 500k",
        )
        // the main thread on `[1m]` in the same process is still counted against the client's 1M
        assertEquals(
            UPSTREAM_INPUT_TOKENS * 2,
            reportedInput(ONE_MILLION_BETA, session = "resumed", launchWindow = ROW_WINDOW),
        )
    }
}
