// NEW (2026-09-05): Claude Code's activity side query is answered by the head itself — through the
// REAL production path (HeadServer over HTTP, both response shapes) — and the upstream never sees it.
package head

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
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
import splice.provider.codex.CodexProvider
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private class LocalAnswerAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-la", "acct-la")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerLocalAnswerTest {

    private val mock = MockChatGptUpstream()
    private val port = freshPort()
    private val lines = CopyOnWriteArrayList<String>()
    private lateinit var head: HeadServer
    private val client = HttpClient(CIO) {
        defaultRequest { bearerAuth("test-inference-token") }
    }

    @BeforeAll
    fun setUp() = runBlocking {
        val tmp = Files.createTempDirectory("head-local-answer")
        head = HeadServer(
            provider = CodexProvider(
                tuning = ProviderTuning(
                    key = "codex",
                    label = "claudex",
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = LocalAnswerAuth(),
                    baseUrl = mock.baseUrl,
                    watchdog = WatchdogBudget(20.seconds, 20.seconds, 30.seconds),
                ),
                showReasoning = ReasoningDisplay.TEXT,
                replayReasoning = false,
                configEffort = "high",
                configSummary = "detailed",
            ),
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(firstByteTimeoutMs = 20_000, totalTimeoutMs = 30_000, maxRetries = 1),
                inferenceToken = "test-inference-token",
                gate = InflightGate({ 0 }),
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("compact.jsonl")),
                usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
                perfStats = PerfStats(tmp.resolve("perf.jsonl")),
                log = { lines += it },
            ),
        )
        head.start()
        awaitListening(port)
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        mock.stop()
        client.close()
    }

    // The transcript's last tool call is a Read; the last user message is the side query verbatim.
    private fun body(stream: Boolean) =
        """{"model":"claude-codex--gpt-5.6-sol","stream":$stream,"max_tokens":64,""" +
            """"system":"You are a test.","messages":[{"role":"user","content":"fix it"},""" +
            """{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"Read",""" +
            """"input":{"file_path":"/repo/src/runAgent.ts"}}]},""" +
            """{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]},""" +
            """{"role":"user","content":"Describe your most recent action in 3-5 words using present tense """ +
            """(-ing). Name the file or function, not the branch. Do not use tools."}]}"""

    private suspend fun post(stream: Boolean): String =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "sess-label")
            setBody(body(stream))
        }.bodyAsText()

    @Test
    fun `the streamed side query gets a one-block end_turn answer and upstream sees nothing`() = runBlocking {
        val before = mock.upstreamBodies.size
        val sse = post(stream = true)
        assertTrue(sse.contains("event: message_start"), sse)
        assertTrue(sse.contains("Reading runAgent.ts"), sse)
        assertTrue(sse.contains("\"stop_reason\":\"end_turn\""), sse)
        assertTrue(sse.contains("event: message_stop"), sse)
        // Wire-exact beyond the text (review of PR 137): the usage block the HUD reads and an id in
        // the shape the client parses (MessageIds), pinned as presence and shape, not values.
        assertTrue(sse.contains("\"usage\":"), "the synthesized answer must carry a usage block: $sse")
        assertTrue(MESSAGE_ID.containsMatchIn(sse), "the message id must match the shape the client parses: $sse")
        assertEquals(before, mock.upstreamBodies.size, "no upstream turn may happen for the side query")
        assertTrue(
            lines.any { it.contains("activity label answered locally: \"Reading runAgent.ts\"") },
            lines.joinToString(),
        )
    }

    @Test
    fun `the non-stream side query gets the same answer as one JSON body`() = runBlocking {
        val before = mock.upstreamBodies.size
        val json = post(stream = false)
        assertTrue(json.contains("\"text\":\"Reading runAgent.ts\""), json)
        assertTrue(json.contains("\"stop_reason\":\"end_turn\""), json)
        assertTrue(json.contains("\"usage\":"), "the synthesized answer must carry a usage block: $json")
        assertTrue(MESSAGE_ID.containsMatchIn(json), "the message id must match the shape the client parses: $json")
        assertEquals(before, mock.upstreamBodies.size, "no upstream turn may happen for the side query")
    }
}

private val MESSAGE_ID = Regex("\"id\":\"msg_[0-9]+_[0-9]+\"")
