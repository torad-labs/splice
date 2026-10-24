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

/** A retry must tell the waiting client what failed, as signed splice status, never as model text. */
class RetryReasonDeliveryTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `HTTP retry explains the upstream failure before the recovered answer`() = runTest {
        val wire = messages("overload_once")
        assertNotice(wire, "engine is currently overloaded")
        assertTrue(wire.indexOf("engine is currently overloaded") < wire.indexOf("\"text_delta\""), wire)
        assertTrue(wire.contains("event: message_stop\n"), wire)
        assertFalse(wire.contains("event: error\n"), wire)
    }

    @Test
    fun `credential refresh explains the rejected attempt before the recovered answer`() = runTest {
        val wire = messages("refresh")
        assertNotice(wire, "token expired")
        assertTrue(wire.contains("refreshed", ignoreCase = true), wire)
        assertTrue(wire.indexOf("token expired") < wire.indexOf("\"text_delta\""), wire)
        assertTrue(wire.contains("event: message_stop\n"), wire)
        assertFalse(wire.contains("event: error\n"), wire)
    }

    @Test
    fun `stream recovery explains each failure without hiding the terminal error`() = runTest {
        val wire = messages("failed")
        assertNotice(wire, "boom upstream")
        assertTrue(wire.indexOf("boom upstream") < wire.indexOf("event: error\n"), wire)
        assertTrue(wire.contains("event: error\n"), wire)
        assertFalse(wire.contains("event: message_stop\n"), wire)
    }

    @Test
    fun `empty answer retry explains the missing content and still fails honestly when it stays empty`() = runTest {
        val wire = messages("empty_completed")
        assertNotice(wire, "no answer content")
        assertTrue(wire.contains("event: error\n"), wire)
        assertFalse(wire.contains("event: message_stop\n"), wire)
    }

    @Test
    fun `an unretryable rejection has no retry status and keeps its failure`() = runTest {
        val wire = messages("overflow_http")
        assertTrue(wire.contains("prompt is too long"), wire)
        assertFalse(wire.contains("thinking_delta"), wire)
        assertFalse(wire.contains("message_stop"), wire)
    }

    @Test
    fun `transport retry names the failed connection and still ends honestly when it stays down`() = runTest {
        val closedPort = TestPorts.reserve()
        val wire = messages("basic", baseUrl = "http://127.0.0.1:$closedPort")
        assertNotice(wire, "127.0.0.1:$closedPort")
        assertTrue(wire.contains("event: error\n"), wire)
        assertFalse(wire.contains("event: message_stop\n"), wire)
    }

    @Test
    fun `a torn pre-content stream explains its reissue without turning status into model output`() = runTest {
        val wire = messages("truncated_before_content")
        assertNotice(wire, "truncated")
        assertTrue(wire.contains("event: error\n"), wire)
        assertFalse(wire.contains("event: message_stop\n"), wire)
    }

    @Test
    fun `retry status does not hide a later native context overflow`() = runTest {
        val wire = messages("retry_overflow", expectedStatus = 400)
        assertTrue(wire.contains("prompt is too long"), wire)
        assertFalse(wire.contains("thinking_delta"), wire)
    }

    private fun assertNotice(wire: String, reason: String) {
        val events = wire.split("\n\n").mapNotNull { frame ->
            frame.lineSequence().firstOrNull { it.startsWith("data: ") }
                ?.removePrefix("data: ")?.let { Json.parseToJsonElement(it).jsonObject }
        }
        val notices = events.mapNotNull { it["delta"] as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "thinking_delta" }
            .map { it.getValue("thinking").jsonPrimitive.content }
        assertTrue(notices.any { it.contains("[splice]") && it.contains(reason) }, wire)
        assertTrue(wire.contains("splice-wait-notice-v1"), wire)
        val modelText = events.mapNotNull { it["delta"] as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "text_delta" }
            .joinToString("") { it.getValue("text").jsonPrimitive.content }
        assertFalse(modelText.contains(reason), wire)
        assertFalse(notices.any { it.contains("{\"error\"") }, wire)
    }

    private suspend fun messages(
        scenario: String,
        upstream: UpstreamClient = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2),
        baseUrl: String? = null,
        expectedStatus: Int? = null,
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
                setBody(
                    """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":8000,
                        "system":"SCENARIO:$scenario","messages":[{"role":"user","content":"go"}]}""",
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
