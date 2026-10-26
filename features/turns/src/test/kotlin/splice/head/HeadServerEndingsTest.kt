// NEW: the two ways an upstream turn ENDS that nothing pinned end to end before the exceptions that carried them
// became sealed outcomes: the host's own 529, retried and surfaced as overloaded_error (the type Claude Code retries
// on), and an oversized frame AFTER content reached the client, which can no longer be re-issued and must end the
// stream honestly with the content kept. A SIBLING class rather than more cases on HeadServerIntegrationTest, which
// is at detekt's LargeClass ceiling. Failure-handling slice 2 adds a third: a prompt too long, whose line depends on
// whether the request is the conversation's first exchange.
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.FailureCause
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.RequestFacts
import splice.core.failure.Tear
import splice.core.model.CatalogWindows
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.WindowRule
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
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
        windows = CatalogWindows(windowRules = listOf(WindowRule("gpt-5.6", 272_000))),
        defaultContextWindow = 272_000,
    )

    @BeforeAll
    fun setUp(@TempDir tmp: Path) = runTest {
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "codex", label = "claudex"),
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = EndingsAuth(),
                locations = ProviderLocations(baseUrl = mock.baseUrl),
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

    /** One turn of [scenario]. With [priorExchange] the conversation already holds an answer: not its first. */
    private suspend fun messages(scenario: String, priorExchange: Boolean = false): String =
        client.post("http://127.0.0.1:${head.port}/v1/messages") {
            header("Content-Type", "application/json")
            val earlier = if (priorExchange) PRIOR_EXCHANGE else ""
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:$scenario",
                    "messages":[$earlier{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()

    /** The error a client read in [body]: the stream's error event, or the JSON body of a status not yet committed. */
    private fun errorOf(body: String): JsonObject? {
        val event = body.lineSequence().dropWhile { it != "event: error" }.drop(1).firstOrNull()?.removePrefix("data: ")
        return runCatching { Json.parseToJsonElement(event ?: body).jsonObject["error"]?.jsonObject }.getOrNull()
    }

    /** The words of the error in [body], or null when it has none. */
    private fun errorWords(body: String): String? = errorOf(body)?.get("message")?.jsonPrimitive?.content

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
            // The line this head's voice says for a cut answer after content, on this first exchange (slice 2).
            val torn = FailureCause.Path.Torn(Tear.OVERSIZED_EVENT, toolOpen = false)
            val line = head.voice.turn(RequestFacts(compaction = false, firstExchange = true))
                .ending(torn, Position(Moment.MID_CONTENT, streamBegan = true))
            assertEquals(line.text, errorWords(sse), sse)
            assertFalse(sse.contains("event: message_stop"), sse)
        }

    // final-design.md, "Rules across families": a too-long prompt's line says "prompt is too long", the words Claude
    // Code compacts on, and a first exchange, which compacting cannot shorten, gets the same status and type with the
    // phrase left out. The head learns which exchange it is from the request (failure-handling slice 2).
    @Test
    fun `a prompt too long tells Claude Code to compact, except on the conversation's first message`() = runTest {
        val first = messages("overflow_http")
        val later = messages("overflow_http", priorExchange = true)
        val firstLine = checkNotNull(errorWords(first)) { "the first message's client read no line: $first" }
        val laterLine = checkNotNull(errorWords(later)) { "the later prompt's client read no line: $later" }

        assertTrue(COMPACT_WORDS in laterLine, "a later prompt asks Claude Code to compact: $laterLine")
        assertFalse(COMPACT_WORDS in firstLine, "a first message cannot be compacted, so none is asked: $firstLine")
        assertTrue("first message is too long" in firstLine, "the line says it is the first message: $firstLine")
        assertEquals(errorOf(later)?.get("type"), errorOf(first)?.get("type"), "both end with the same type")
    }
}

/** The phrase Claude Code compacts the conversation on (V4-446, V4-256). */
private const val COMPACT_WORDS = "prompt is too long"

/** An exchange the conversation already holds, ahead of its last message. */
private const val PRIOR_EXCHANGE =
    """{"role":"user","content":"hi"},{"role":"assistant","content":"hello"},"""
