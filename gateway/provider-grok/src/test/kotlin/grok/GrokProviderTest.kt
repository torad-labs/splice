// NEW: grok provider — the multi-provider abstraction proof. A HeadServer wired with GrokProvider
// against the mock upstream serves a real turn (same dialect + machine as codex, only quirks +
// auth differ). Plus grok quirks pinned: session-id cache key, effort clamp, no summary,
// tool_choice; and api-key auth from env + file.
package grok

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mock.MockChatGptUpstream
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.Credentials
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.parseAnthropicBody
import splice.core.turn.WatchdogBudget
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.usage.UsageStore
import splice.provider.grok.GrokAuthProvider
import splice.provider.grok.GrokProvider
import splice.spi.InflightGate
import splice.spi.UpstreamClient
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrokProviderTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO)
    private val port = 39270
    private lateinit var head: HeadServer

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-grok--",
        models = listOf(ModelEntry("grok-4.5", "Grok 4.5", contextWindow = 1_000_000)),
        defaultContextWindow = 1_000_000,
    )

    private fun provider(auth: splice.core.auth.RefreshableAuthProvider) = GrokProvider(
        key = "grok",
        label = "grok",
        catalog = catalog,
        pinnedModel = "grok-4.5",
        auth = auth,
        baseUrl = mock.baseUrl,
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        showReasoning = "text",
        replayReasoning = false,
        configEffort = "high",
    )

    @BeforeAll
    fun setUp() = runBlocking {
        val tmp = Files.createTempDirectory("grok-it")
        val keyFile = tmp.resolve("auth.json")
        Files.writeString(keyFile, """{"api_key":"xai-secret-key-123456"}""")
        val auth = GrokAuthProvider(keyFile = keyFile, envReader = { null })
        head = HeadServer(
            provider = provider(auth),
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(5_000, 30_000, 2),
                gate = InflightGate({ 0 }),
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("c.jsonl")),
                usageStore = UsageStore(tmp.resolve("u.json"), tmp.resolve("r.json")),
                log = {},
            ),
        )
        head.start()
        Thread.sleep(700)
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        client.close()
        mock.stop()
    }

    @Test
    fun `grok serves a real turn through the shared dialect and machine`() = runBlocking {
        val sse = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "sess-xyz")
            setBody(
                """{"model":"claude-grok--grok-4.5","stream":true,
                    "system":"You are a test. SCENARIO:basic","messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()
        assertTrue(sse.contains("ok after auth"))
        assertTrue(sse.contains("event: message_stop"))
        // api key rode as the bearer
        assertTrue(mock.upstreamAuths.any { it.second == "Bearer xai-secret-key-123456" })
        // session-id cache key + tool_choice absence (no tools) verified via the sent body
        val body = mock.upstreamBodies.last().second
        assertTrue(body.contains("claude-grok:sess-xyz"))
    }

    @Test
    fun `grok quirks - effort clamps to high, no summary field, session cache key`() {
        val parsed = parseAnthropicBody(
            """{"model":"grok-4.5","effort":"xhigh","messages":[{"role":"user","content":"first"}]}""",
        )
        val grokProvider = provider(GrokAuthProvider(keyFile = null, envReader = { "xai-env-key" }))
        val built = grokProvider.buildTurn(parsed, compact = false, sessionId = "s1")
        val reasoning = built.requestBody["reasoning"]!!.jsonObject
        assertEquals("high", reasoning["effort"]?.jsonPrimitive?.content) // xhigh clamped to high
        assertNull(reasoning["summary"]) // grok has no summary field
        assertEquals("claude-grok:s1", built.requestBody["prompt_cache_key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `api-key auth - env wins over file, masked describe`() = runBlocking {
        val envAuth = GrokAuthProvider(
            keyFile = null,
            envReader = { if (it == "XAI_API_KEY") "xai-from-env-key" else null },
        )
        assertEquals("xai-from-env-key", (envAuth.credentials() as Credentials.ApiKey).key)
        val desc = envAuth.describe()
        assertTrue(desc.present)
        assertEquals("api-key", desc.kind)
        assertTrue(desc.fields["api_key_masked"]!!.contains("…"))
        assertNull((GrokAuthProvider(keyFile = null, envReader = { null })).credentials())
    }

    @Test
    fun `health carries the grok port`(): Unit = runBlocking {
        val body = client.get("http://127.0.0.1:$port/health").bodyAsText()
        assertTrue(body.contains("\"port\":$port"))
    }
}
