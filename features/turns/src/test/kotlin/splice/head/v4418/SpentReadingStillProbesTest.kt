// NEW: V4-418 — reporting only. A head whose current reading names a window fully used still lets its first turn
// reach the upstream (V4-47's gate is unchanged): only a refused turn arms a hold, so the reading can never turn a week
// that has just reset, or a plan the provider has since topped up, into a refusal splice invented. V4-452: the reading
// is HeadServer.quotaFull, and it is not a refusal, so providerResetForMs stays 0 beside it.
package splice.head.v4418

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
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.head.HeadServer
import splice.head.MockChatGptUpstream
import splice.head.TestResponsesProvider
import splice.head.awaitListening
import splice.head.headDeps
import splice.head.quotaFor
import splice.head.usage.QuotaTracker
import splice.upstream.ProviderTuning
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val SIX_DAYS_S = 6L * 24 * 3_600
private const val SEVEN_DAY_S = 7L * 24 * 3_600
private const val MESSAGES_BODY =
    """{"model":"gpt-5.6-sol","max_tokens":64,"stream":true,"messages":[{"role":"user","content":"hello"}]}"""

private class ProbeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpentReadingStillProbesTest {
    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        defaultRequest { bearerAuth("test-inference-token") }
    }
    private lateinit var head: HeadServer
    private lateinit var tmp: Path

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) = runBlocking {
        tmp = tempDir
        val now = System.currentTimeMillis()
        val week = QuotaWindow(100.0, now / 1_000L + SIX_DAYS_S, SEVEN_DAY_S)
        val quota = QuotaTracker(tmp.resolve("quota.json"))
        quota.record(QuotaSnapshot(sevenDay = week, plan = "plus", updatedAt = now))
        head = HeadServer(
            provider = TestResponsesProvider(
                tuning = ProviderTuning(
                    key = "codex",
                    label = "claudex",
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = ProbeAuth(),
                    baseUrl = mock.baseUrl,
                    watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                ),
                showReasoning = ReasoningDisplay.TEXT,
                replayReasoning = false,
                configEffort = "high",
                configSummary = "detailed",
            ),
            listenPort = 0,
            deps = headDeps(tmp = tmp, quota = quotaFor(quota, null)),
        )
        head.start()
        awaitListening(head.port)
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        client.close()
        mock.stop()
    }

    @Test
    fun `a head whose reading is full still sends its first turn upstream`() = runBlocking<Unit> {
        assertTrue(head.quotaFull() != null, "the reading is held as the head's full reading")
        assertEquals(0L, head.providerResetForMs(), "a full reading is not a refusal")

        val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            setBody(MESSAGES_BODY)
        }

        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, text.take(300))
        assertTrue(text.contains("message_stop"), text.take(300))
        assertEquals(1, mock.upstreamBodies.size, "the turn reached the upstream")
    }
}
