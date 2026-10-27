// REWRITTEN: failure-handling slice 2 (2026-10-26). This file pinned the Oct 24 per-attempt notices, each quoting the
// provider's own words, a host and port, "refreshed" or "truncated". Slice 2 narrows them to one plain line per
// recovery episode, in the turn's voice (final-design.md section 8, rule 3): a turn that recovers quickly shows its
// answer and nothing of the failure, and a turn that keeps failing shows one line for the episode, however many times
// it sends the request again, then ends with its own line. Section 13 did not list this file; its old assertions are
// the per-attempt behavior the slice replaces.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.testing.TestPorts
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** What the turn does next, as the line that opens a recovery episode ends: it sends the request again as it was, or
 *  it continues the answer from where it stopped. */
private val NEXT_SENTENCES = listOf("This turn asks again.", "This turn continues from where it stopped.")

/** An exchange the conversation already holds, which makes the turn after it a later one rather than the first. */
private const val PRIOR_EXCHANGE = """{"role":"user","content":"start"},{"role":"assistant","content":"Started."},"""

/** Words no line a person reads may hold: splice's old codes and retry wording, and the turn's own host. */
private val INTERNAL_WORDS = listOf("[SPLICE-", "Reason:", "attempt", "upstream", "127.0.0.1")

/** A retry tells the waiting person at most one plain [splice] line per recovery episode, as signed splice status and
 *  never as model text, and never the provider's words, a host, a count of tries or splice's codes. */
class RetryReasonDeliveryTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `a retry that recovers at once shows the answer and nothing of the failure`() = runTest {
        val wire = messages("overload_once")
        assertNothingInternal(wire, "engine is currently overloaded")
        assertEquals(emptyList<String>(), notices(wire), "a wait of a second is not news: $wire")
        assertTrue(wire.contains("\"text_delta\""), wire)
        assertTrue(wire.contains("event: message_stop\n"), wire)
        assertFalse(wire.contains("event: error\n"), wire)
    }

    @Test
    fun `a refreshed login is not news to the person, and the answer arrives`() = runTest {
        val wire = messages("refresh")
        assertNothingInternal(wire, "token expired")
        assertFalse(wire.contains("refreshed", ignoreCase = true), wire)
        assertEquals(emptyList<String>(), notices(wire), wire)
        assertTrue(wire.contains("event: message_stop\n"), wire)
        assertFalse(wire.contains("event: error\n"), wire)
    }

    @Test
    fun `a failure the turn keeps re-sending shows one line for the episode, then ends with its own`() = runTest {
        val wire = messages("failed")
        assertNothingInternal(wire, "boom upstream")
        assertOneEpisodeThenError(wire)
    }

    @Test
    fun `an empty answer asked again shows one line and still fails honestly when it stays empty`() = runTest {
        val wire = messages("empty_completed")
        assertNothingInternal(wire, "no answer content")
        val line = assertOneEpisodeThenError(wire)
        assertTrue(line.endsWith("This turn asks again."), line)
    }

    @Test
    fun `an unretryable rejection has no recovery line and keeps its 400, whose line says the prompt is too long`() =
        runTest {
            val wire = messages("overflow_http", expectedStatus = 400, laterTurn = true)
            val line = statusLine(wire)
            assertTrue(line.startsWith("[splice] ") && line.contains("prompt is too long"), line)
            assertFalse(wire.contains("thinking_delta"), wire)
            assertFalse(wire.contains("message_stop"), wire)
        }

    @Test
    fun `a first message that overflows keeps its 400 and leaves out the words a client compacts on`() = runTest {
        val wire = messages("overflow_http", expectedStatus = 400)
        val line = statusLine(wire)
        assertTrue(line.startsWith("[splice] This first message is too long for claudex"), line)
        assertFalse(line.contains("prompt is too long", ignoreCase = true), line)
    }

    @Test
    fun `a provider no connection reaches is never named by its address`() = runTest {
        val closedPort = TestPorts.reserve()
        val wire = messages("basic", baseUrl = "http://127.0.0.1:$closedPort")
        assertNothingInternal(wire, ":$closedPort")
        assertTrue(wire.contains("event: error\n"), wire)
        assertFalse(wire.contains("event: message_stop\n"), wire)
    }

    @Test
    fun `a stream torn before content shows one line for its re-sends, never the word truncated`() = runTest {
        val wire = messages("truncated_before_content")
        assertNothingInternal(wire, "truncated")
        assertOneEpisodeThenError(wire)
    }

    @Test
    fun `a request re-sent before it overflows still answers 400 with the overflow's line`() = runTest {
        val wire = messages("retry_overflow", expectedStatus = 400, laterTurn = true)
        assertTrue(statusLine(wire).contains("prompt is too long"), wire)
        assertFalse(wire.contains("thinking_delta"), wire)
    }

    /** Every [splice] line the client was shown in splice's notice blocks, in order, one per line of text. */
    private fun notices(wire: String): List<String> = deltas(wire, "thinking_delta", "thinking")
        .flatMap { it.split('\n') }
        .filter { it.startsWith("[splice] ") }

    /** The model's own text, which never carries a line of splice's. */
    private fun modelText(wire: String): String = deltas(wire, "text_delta", "text").joinToString("")

    private fun deltas(wire: String, type: String, field: String): List<String> = events(wire)
        .mapNotNull { it["delta"] as? JsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == type }
        .map { it.getValue(field).jsonPrimitive.content }

    /** The message of the turn's error event, the line it ended with; null for a turn that ended clean. */
    private fun errorLine(wire: String): String? = wire.split("\n\n")
        .firstOrNull { it.startsWith("event: error\n") }
        ?.let { frame -> frame.lineSequence().first { it.startsWith("data: ") }.removePrefix("data: ") }
        ?.let { Json.parseToJsonElement(it).jsonObject.getValue("error").jsonObject.getValue("message") }
        ?.jsonPrimitive?.content

    /** The line of an answer that was a status rather than a stream: its JSON error body's message. */
    private fun statusLine(body: String): String =
        Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject.getValue("message").jsonPrimitive.content

    private fun events(wire: String): List<JsonObject> = wire.split("\n\n").mapNotNull { frame ->
        frame.lineSequence().firstOrNull { it.startsWith("data: ") }
            ?.removePrefix("data: ")?.let { Json.parseToJsonElement(it) as? JsonObject }
    }

    /** Every line the person read is one of splice's plain lines, holding none of [providerWords] or [INTERNAL_WORDS],
     *  and none of them reached the model's text. */
    private fun assertNothingInternal(wire: String, providerWords: String) {
        (notices(wire) + listOfNotNull(errorLine(wire))).forEach { line ->
            assertTrue(line.startsWith("[splice] "), "not one of splice's lines: $line")
            (INTERNAL_WORDS + providerWords).forEach { word ->
                assertFalse(line.contains(word, ignoreCase = true), "'$word' reached the person: $line")
            }
        }
        assertFalse(modelText(wire).contains("[splice]"), wire)
    }

    /** The episode's one line, which says what the turn does next, signed as splice's own block, before the error event
     *  that ends the turn with its own line. Returns that one line. */
    private fun assertOneEpisodeThenError(wire: String): String {
        val shown = notices(wire)
        assertEquals(1, shown.size, "one line for one episode, however many re-sends: $shown")
        val line = shown.single()
        assertTrue(NEXT_SENTENCES.any { line.endsWith(it) }, "the line says what the turn does next: $line")
        assertTrue(wire.contains("splice-wait-notice-v1"), wire)
        assertTrue(wire.indexOf("event: error\n") > wire.indexOf("thinking_delta"), wire)
        assertTrue(errorLine(wire)?.startsWith("[splice] ") == true, wire)
        assertFalse(wire.contains("event: message_stop\n"), wire)
        return line
    }

    private suspend fun messages(
        scenario: String,
        upstream: UpstreamClient = UpstreamClient(totalTimeoutMs = 30_000),
        baseUrl: String? = null,
        expectedStatus: Int? = null,
        laterTurn: Boolean = false,
    ): String {
        val mock = MockChatGptUpstream()
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "codex", label = "claudex"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = RefreshingAuth(),
                locations = ProviderLocations(baseUrl = baseUrl ?: mock.baseUrl),
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        )
        val head = HeadServer(
            provider = provider,
            listenPort = 0,
            deps = headDeps(tmp, upstream = upstream),
        )
        val client = HttpClient(CIO) { engine { requestTimeout = 0 } }
        try {
            head.start()
            awaitListening(head.port)
            val response = client.post("http://127.0.0.1:${head.port}/v1/messages") {
                bearerAuth("test-inference-token")
                header("Content-Type", "application/json")
                // A later turn holds an answer already, so it is not the conversation's first exchange.
                val earlier = if (laterTurn) PRIOR_EXCHANGE else ""
                setBody(
                    """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                        "system":"SCENARIO:$scenario","messages":[$earlier{"role":"user","content":"go"}]}""",
                )
            }
            if (expectedStatus != null) assertEquals(expectedStatus, response.status.value)
            return response.bodyAsText()
        } finally {
            head.stop()
            client.close()
            mock.stop()
        }
    }

    private class RefreshingAuth : RefreshableAuthProvider {
        private var refreshed = false
        override suspend fun credentials(): Credentials =
            Credentials.Bearer(if (refreshed) "tok-test" else "tok-old", "acct-test")
        override suspend fun refresh(): Credentials {
            refreshed = true
            return credentials()
        }
        override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
    }
}
