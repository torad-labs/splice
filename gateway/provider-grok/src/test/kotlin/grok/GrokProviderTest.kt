// NEW: grok provider — the multi-provider abstraction proof, now on OAuth (SuperGrok/X-Premium+).
// A HeadServer wired with GrokProvider + GrokAuthProvider (reading ~/.grok/auth.json) serves a real
// turn (same dialect + machine as codex, only quirks + oauth-vs-chatgpt-oauth differ). Plus grok
// quirks pinned (session-id cache key, effort clamp, no summary) and the OAuth Bearer + refresh.
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
import splice.provider.grok.GrokRefreshedTokens
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GrokProviderTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO)
    private val port = 39270
    private lateinit var head: HeadServer
    private lateinit var tmp: Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-grok--",
        models = listOf(ModelEntry("grok-4.5", "Grok 4.5", contextWindow = 1_000_000)),
        defaultContextWindow = 1_000_000,
    )

    private fun provider(auth: splice.core.auth.RefreshableAuthProvider) = GrokProvider(
        tuning = ProviderTuning(
            key = "grok",
            label = "grok",
            catalog = catalog,
            pinnedModel = "grok-4.5",
            auth = auth,
            baseUrl = mock.baseUrl,
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        showReasoning = "text",
        replayReasoning = false,
        configEffort = "high",
    )

    /** ~/.grok/auth.json with an OAuth access token. */
    private fun oauthAuth(
        dir: Path,
        access: String = "xai-access-token-abc",
        refresh: String = "xai-refresh",
    ): GrokAuthProvider {
        val authFile = dir.resolve(".grok").resolve("auth.json")
        Files.createDirectories(authFile.parent)
        Files.writeString(authFile, """{"tokens":{"access_token":"$access","refresh_token":"$refresh"}}""")
        return GrokAuthProvider(
            authPath = authFile,
            refreshCall = { GrokRefreshedTokens("refreshed-access", "refreshed-refresh") },
        )
    }

    @BeforeAll
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("grok-it")
        head = HeadServer(
            provider = provider(oauthAuth(tmp)),
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
    fun `grok serves a real turn through the shared dialect and machine on oauth`() = runBlocking {
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
        // the OAuth access token rode as the bearer (no ChatGPT-Account-ID header — grok has none)
        assertTrue(mock.upstreamAuths.any { it.second == "Bearer xai-access-token-abc" })
        // session-id cache key in the body
        assertTrue(mock.upstreamBodies.last().second.contains("claude-grok:sess-xyz"))
    }

    @Test
    fun `grok quirks - effort clamps to high, no summary field, session cache key`() = runBlocking {
        val parsed = parseAnthropicBody(
            """{"model":"grok-4.5","effort":"xhigh","messages":[{"role":"user","content":"first"}]}""",
        )
        val grokProvider = provider(oauthAuth(Files.createTempDirectory("q")))
        val built = grokProvider.buildTurn(parsed, compact = false, sessionId = "s1")
        val reasoning = built.requestBody["reasoning"]!!.jsonObject
        assertEquals("high", reasoning["effort"]?.jsonPrimitive?.content) // xhigh clamped to high
        assertNull(reasoning["summary"]) // grok has no summary field
        assertEquals("claude-grok:s1", built.requestBody["prompt_cache_key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `oauth auth - reads access token, refresh rotates it, masked describe`() = runBlocking {
        val dir = Files.createTempDirectory("oauth")
        val auth = oauthAuth(dir, access = "first-access", refresh = "first-refresh")
        assertEquals("first-access", (auth.credentials() as Credentials.Bearer).token)
        assertNull((auth.credentials() as Credentials.Bearer).accountId) // grok carries no account id
        // refresh writes the rotated token back to auth.json and returns it
        assertEquals("refreshed-access", (auth.refresh() as Credentials.Bearer).token)
        val onDisk = Files.readString(dir.resolve(".grok").resolve("auth.json"))
        assertTrue(onDisk.contains("refreshed-access") && onDisk.contains("refreshed-refresh"))
        val desc = auth.describe()
        assertTrue(desc.present)
        assertEquals("grok-oauth", desc.kind)
        // no token material in the introspection
        assertTrue(desc.fields.values.none { it.contains("access") })
        assertNull((GrokAuthProvider(authPath = dir.resolve("missing.json"), refreshCall = { null })).credentials())
    }

    @Test
    fun `health carries the grok port`(): Unit = runBlocking {
        assertTrue(client.get("http://127.0.0.1:$port/health").bodyAsText().contains("\"port\":$port"))
    }
}
