package splice.app.provider

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.turn.TurnScope
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.ResponsesProvider
import splice.provider.codex.CodexProvider
import splice.provider.codex.CodexQuirks
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiQuirks
import splice.provider.openai.OpenAiResponsesProvider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

// Goal: a public Responses WebSocket uses public auth, while the Codex route keeps its own negotiation header.
class ResponsesWebSocketHeaderBoundaryTest {
    private val reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, null, null)

    @Test
    fun `ordinary OpenAI WebSockets do not inherit Codex negotiation or routing headers`() = runBlocking {
        val headers = handshake(Credentials.ApiKey("synthetic-key")) { url ->
            OpenAiResponsesProvider(
                tuning = tuning("openai", url),
                reasoning = reasoning,
                quirks = OpenAiQuirks().defaultQuirks().withWebSocketToml(true),
            )
        }
        assertEquals("Bearer synthetic-key", headers["authorization"])
        assertNull(headers["openai-beta"])
        assertNull(headers["chatgpt-account-id"])
        assertFalse(headers.keys.any { it.startsWith("x-codex") }, "$headers")
    }

    @Test
    fun `Codex WebSockets retain their own negotiation header and account identity`() = runBlocking {
        val headers = handshake(Credentials.Bearer("synthetic-token", "synthetic-account")) { url ->
            CodexProvider(
                tuning = tuning("codex", url),
                reasoning = reasoning,
                quirks = CodexQuirks().defaultQuirks().withWebSocketToml(true),
                log = {},
            )
        }
        assertEquals("Bearer synthetic-token", headers["authorization"])
        assertEquals("synthetic-account", headers["chatgpt-account-id"])
        assertEquals("responses_websockets=2026-02-06", headers["openai-beta"])
    }

    private suspend fun handshake(
        credentials: Credentials,
        providerAt: (String) -> ResponsesProvider,
    ): Map<String, String> = HeaderPeer().use { peer ->
        val provider = providerAt(peer.url)
        try {
            val runner = checkNotNull(provider.wsRunner)
            runner.attempt(
                """{"model":"test-model","input":[{"role":"user","content":"hello"}]}""",
                TurnMeta(
                    compact = false,
                    reasoning = TurnReasoning(ReasoningDisplay.TEXT, "high", "detailed", null),
                    route = TurnRoute(true, "test-model", "test-model", null),
                    scope = TurnScope(conversationKey = "header-probe", sessionId = "header-session"),
                ),
                emptyMap(),
                credentials,
            )
            peer.headers.get(10, TimeUnit.SECONDS)
        } finally {
            provider.onHeadStop()
        }
    }

    private fun tuning(name: String, url: String): ProviderTuning = ProviderTuning(
        name = ProviderName(key = name, label = name),
        catalog = ModelCatalog(
            discoveryPrefix = "claude-probe--",
            models = listOf(ModelEntry(id = "test-model", contextWindow = 200_000)),
            defaultContextWindow = 200_000,
        ),
        pinnedModel = "test-model",
        auth = ApiKeyAuthProvider("UNUSED_TEST_KEY", envReader = { "synthetic-key" }),
        locations = ProviderLocations(baseUrl = url),
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    )
}

/** Records the real HTTP upgrade and refuses it before any generation or model traffic can occur. */
private class HeaderPeer : AutoCloseable {
    private val server = ServerSocket(0)
    private val executor = Executors.newSingleThreadExecutor()
    val headers = CompletableFuture<Map<String, String>>()
    val url: String = "http://127.0.0.1:${server.localPort}"

    init {
        executor.submit {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    check(reader.readLine().startsWith("GET "))
                    val received = buildMap {
                        var line = reader.readLine()
                        while (!line.isNullOrEmpty()) {
                            val separator = line.indexOf(':')
                            if (separator > 0) {
                                val name = line.substring(0, separator).lowercase(Locale.ROOT)
                                put(name, line.substring(separator + 1).trim())
                            }
                            line = reader.readLine()
                        }
                    }
                    headers.complete(received)
                    socket.getOutputStream().apply {
                        val response = "HTTP/1.1 400 Test handshake captured\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        write(response.toByteArray())
                        flush()
                    }
                }
            } catch (failure: Exception) {
                headers.completeExceptionally(failure)
            }
        }
    }

    override fun close() {
        server.close()
        executor.shutdownNow()
    }
}
