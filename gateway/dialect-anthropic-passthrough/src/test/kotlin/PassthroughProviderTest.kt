// NEW (CH-3, campaign claude-head): the generic passthrough provider carries NO vendor facts —
// every difference between heads on this dialect is declared data. These tests pin both ends of
// that claim: plugged with Kimi's quirks + identity it reproduces the old KimiProvider exactly,
// and given nothing it emits nothing vendor-specific (the shape the claude head needs).
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.passthrough.PassthroughProvider
import splice.dialect.passthrough.PassthroughQuirks
import splice.dialect.passthrough.PassthroughQuirksDefaults
import splice.spi.ProviderTuning
import kotlin.time.Duration.Companion.seconds

private val CATALOG = ModelCatalog(
    discoveryPrefix = "claude-kimi--",
    models = listOf(ModelEntry("k3[1m]", "Kimi K3", contextWindow = 1_048_576)),
    defaultContextWindow = 262_144,
)

private object NoAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("k", header = "x-api-key", prefix = "")
    override suspend fun describe(): AuthDescription = AuthDescription(present = true, kind = "test")
    override suspend fun refresh(): Credentials = credentials()
}

private fun provider(
    quirks: PassthroughQuirks,
    staticHeaders: Map<String, String> = emptyMap(),
    identityHeaders: () -> Map<String, String> = { emptyMap() },
) = PassthroughProvider(
    tuning = ProviderTuning(
        key = "kimi",
        label = "kimi",
        catalog = CATALOG,
        pinnedModel = "k3[1m]",
        auth = NoAuth,
        baseUrl = "https://api.kimi.com/coding",
        watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
    ),
    quirks = quirks,
    staticHeaders = staticHeaders,
    identityHeaders = identityHeaders,
)

/** The first content block of the first message in a built request. */
private fun firstBlock(req: JsonObject): JsonObject =
    req["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject

class PassthroughProviderTest {

    private val creds = Credentials.ApiKey("k", header = "x-api-key", prefix = "")

    @Test
    fun `upstream url is the Anthropic Messages path on the configured base`() {
        assertEquals(
            "https://api.kimi.com/coding/v1/messages",
            provider(PassthroughQuirksDefaults().kimi("kimi")).upstreamUrl,
        )
    }

    // The old KimiProvider hardcoded these; they are TOML now, and the wire must not notice.
    @Test
    fun `kimi's header set is reproduced from declared data alone`() {
        val headers = provider(
            quirks = PassthroughQuirksDefaults().kimi("kimi"),
            staticHeaders = mapOf("anthropic-version" to "2023-06-01", "User-Agent" to "KimiCLI/1.5"),
            identityHeaders = { mapOf("X-Msh-Device-Id" to "dev-1", "X-Msh-Platform" to "linux") },
        ).extraHeaders(creds)

        assertEquals("text/event-stream", headers["Accept"])
        assertEquals("2023-06-01", headers["anthropic-version"])
        assertEquals("KimiCLI/1.5", headers["User-Agent"])
        assertEquals("dev-1", headers["X-Msh-Device-Id"])
        assertEquals("linux", headers["X-Msh-Platform"])
        // Auth is UpstreamClient's job — this dialect never sets Authorization itself, which is
        // what lets kimi ride x-api-key and a client-auth head forward the caller's own credential.
        assertNull(headers["Authorization"])
    }

    // The claude head's shape: no vendor identity, no vendor UA — only what it declared.
    @Test
    fun `a head that declares nothing emits nothing vendor-specific`() {
        val headers = provider(PassthroughQuirks(providerTag = "claude-splice")).extraHeaders(creds)
        assertEquals(mapOf("Accept" to "text/event-stream"), headers)
        assertFalse(headers.keys.any { it.startsWith("X-Msh-") })
        assertFalse(headers.values.any { it.contains("KimiCLI") })
    }

    @Test
    fun `static headers ride without an identity supplier`() {
        val headers = provider(
            quirks = PassthroughQuirks(providerTag = "claude-splice"),
            staticHeaders = mapOf("anthropic-version" to "2023-06-01"),
        ).extraHeaders(creds)
        assertEquals(setOf("Accept", "anthropic-version"), headers.keys)
    }

    @Test
    fun `the wrapped picker id is stripped to the upstream model`() {
        val built = provider(PassthroughQuirksDefaults().kimi("kimi")).buildTurn(
            AnthropicParse.parseAnthropicBody(
                """{"model":"claude-kimi--k3[1m]","messages":[{"role":"user","content":"hi"}]}""",
            ),
            compact = false,
            sessionId = null,
        )
        assertEquals("k3", built.requestBody["model"]?.jsonPrimitive?.content)
        assertEquals("k3", built.meta.upstreamModel)
        assertEquals("claude-kimi--k3[1m]", built.meta.originalModel)
    }

    @Test
    fun `quirks reach the builder — kimi deforms where a neutral head does not`() {
        val body = """{"model":"m","messages":[{"role":"user","content":[
            {"type":"text","text":"hi","cache_control":{"type":"ephemeral"}}]}]}"""
        val kimiReq = provider(PassthroughQuirksDefaults().kimi("kimi"))
            .buildTurn(AnthropicParse.parseAnthropicBody(body), compact = false, sessionId = null).requestBody
        val neutralReq = provider(PassthroughQuirks(providerTag = "claude-splice"))
            .buildTurn(AnthropicParse.parseAnthropicBody(body), compact = false, sessionId = null).requestBody

        assertNull(firstBlock(kimiReq)["cache_control"], "kimi strips cache_control")
        assertTrue(firstBlock(neutralReq)["cache_control"] != null, "a neutral head preserves it")
    }

    @Test
    fun `reasoning display is off so the text mirror never double-renders thinking`() = runTest {
        val p = provider(PassthroughQuirksDefaults().kimi("kimi"))
        assertEquals(ReasoningDisplay.OFF, p.showReasoning)
        assertFalse(p.replayReasoning)
    }
}
