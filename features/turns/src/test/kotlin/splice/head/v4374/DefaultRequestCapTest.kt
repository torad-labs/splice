package splice.head.v4374

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.config.Knob
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.WindowRule
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.head.HeadServer
import splice.head.MockChatGptUpstream
import splice.head.TestResponsesProvider
import splice.head.awaitListening
import splice.head.headDeps
import splice.upstream.ProviderTuning
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

// why: Anthropic documents 32 MB for Messages and Token Counting (platform.claude.com/docs/en/api/errors,
// "Request size limits"), the limit Claude Code prints itself, so splice is never the tighter hop
private const val MESSAGES_API_LIMIT = 32 * 1024 * 1024L

// why: V4-360 walker p20 died with the request at 8,350,828 bytes; one more 1440x900 screenshot crossed 8 MiB
private const val PAST_THE_OLD_CAP = 9 * 1024 * 1024

private class FakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

/** V4-374: a head on its DEFAULT policy admits what Claude Code itself admits. The 8 MiB default cut a
 *  screenshot-heavy session at a quarter of the Messages API's 32 MB, and Claude Code printed its own
 *  "Request too large (max 32MB)", so the user read a limit splice does not have. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefaultRequestCapTest {
    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        defaultRequest { bearerAuth("test-inference-token") }
    }
    private lateinit var head: HeadServer
    private lateinit var tmp: Path
    private val port: Int get() = head.port

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) = runBlocking {
        tmp = tempDir
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
            windowRules = listOf(WindowRule("gpt-5.6", 272_000)),
            defaultContextWindow = 272_000,
        )
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = FakeAuth(),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        )
        // No policy argument: the default IS the subject.
        head = HeadServer(provider = provider, listenPort = 0, deps = headDeps(tmp = tmp))
        head.start()
        awaitListening(port)
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        client.close()
        mock.stop()
    }

    private suspend fun post(path: String, body: String): HttpResponse =
        client.post("http://127.0.0.1:$port$path") {
            header("Content-Type", "application/json")
            setBody(body)
        }

    @Test
    fun `the default cap is the Messages API's own 32 MiB`() {
        assertEquals(MESSAGES_API_LIMIT, Knob.MAX_REQUEST_BYTES.count())
    }

    @Test
    fun `a 9 MiB Messages body of screenshots is a turn, not a 413`() = runBlocking<Unit> {
        val response = post("/v1/messages", ScreenshotBody(PAST_THE_OLD_CAP).text)

        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, text.take(300))
        assertTrue(text.contains("message_stop"), text.take(300))
    }

    @Test
    fun `a 9 MiB count_tokens body is estimated, not a 413`() = runBlocking<Unit> {
        val response = post("/v1/messages/count_tokens", ScreenshotBody(PAST_THE_OLD_CAP).text)

        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, text.take(300))
        assertTrue(text.contains("input_tokens"), text.take(300))
    }

    @Test
    fun `a body one byte past 32 MiB is a 413 that names the limit`() = runBlocking<Unit> {
        val response = post("/v1/messages", "x".repeat((MESSAGES_API_LIMIT + 1).toInt()))

        assertEquals(413, response.status.value)
        assertTrue(response.bodyAsText().contains("request body exceeds $MESSAGES_API_LIMIT bytes"))
    }
}
