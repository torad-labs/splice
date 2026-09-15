// NEW: KimiPassthroughArm owns Moonshot headers, identity, quirks base, and both kimi auth paths.
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.provider.KimiPassthroughArm
import splice.app.provider.ProviderBuild
import splice.core.GATEWAY_VERSION
import splice.core.auth.Credentials
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.WatchdogBudget
import splice.provider.openai.ApiKeyAuthProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class KimiPassthroughArmTest {

    @Test
    fun `oauth and api-key kimi paths share headers identity quirks and messages url`(@TempDir tmp: Path) = runTest {
        val state = StatePaths(baseOverride = tmp.resolve("state"))
        Files.createDirectories(state.stateDir)
        val authFile = tmp.resolve("kimi.json")
        Files.writeString(
            authFile,
            """{"access_token":"kimi-access","refresh_token":"kimi-refresh","expires_at":9999999999,"expires_in":3600}""",
        )
        val arm = KimiPassthroughArm(state, backgroundScope, log = {})
        val oauth = arm.kimiOauthProvider(context(tmp, authFile, "kimi-oauth"), "claude-kimi")
        val apiKey = arm.kimiApiKeyProvider(context(tmp, authFile, "api-key"), "claude-kimi")
        assertTrue(apiKey.auth is ApiKeyAuthProvider)
        for (wired in listOf(oauth, apiKey)) {
            assertEquals("https://api.kimi.com/coding/v1/messages", wired.provider.upstreamUrl)
            val headers = wired.provider.extraHeaders(Credentials.ApiKey("secret"))
            assertEquals("text/event-stream", headers["Accept"])
            assertEquals("2023-06-01", headers["anthropic-version"])
            assertEquals("KimiCLI/1.5", headers["User-Agent"])
            assertEquals("splice", headers["X-Msh-Platform"])
            assertEquals(GATEWAY_VERSION, headers["X-Msh-Version"])
            assertTrue(headers.containsKey("X-Msh-Device-Name"))
            assertTrue(headers.containsKey("X-Msh-Device-Model"))
            assertTrue(headers.containsKey("X-Msh-Os-Version"))
            val built = wired.provider.buildTurn(
                AnthropicParse.parseAnthropicBody(CACHE_BODY),
                compact = false,
                sessionId = null,
            )
            assertFalse(built.requestBody.toString().contains("cache_control"))
            assertEquals("k3", built.requestBody["model"]?.jsonPrimitive?.content)
        }
        val extraHeaders = oauth.accounts.single().extraHeaders
            ?: error("oauth accounts must carry extra headers")
        val accountHeaders = extraHeaders(Credentials.ApiKey("secret"))
        assertEquals("text/event-stream", accountHeaders["Accept"])
        assertEquals("KimiCLI/1.5", accountHeaders["User-Agent"])
        assertEquals("splice", accountHeaders["X-Msh-Platform"])
    }

    private fun context(tmp: Path, authFile: Path, kind: String): ProviderBuild {
        val key = "kimi"
        val state = StatePaths(baseOverride = tmp.resolve("state"))
        val config = ConfigService(state, envReader = { null })
        return ProviderBuild(
            key = key,
            head = HeadConfig(
                provider = "kimi",
                port = 3102,
                discoveryPrefix = "claude-kimi--",
                pinnedModel = "k3[1m]",
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                baseUrl = "https://api.kimi.com/coding",
                auth = AuthConfig(kind = kind, file = authFile.toString()),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-kimi--",
                models = listOf(ModelEntry(id = "k3[1m]", contextWindow = 1_000_000)),
                defaultContextWindow = 1_000_000,
            ),
            watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
            cfg = config.getConfig(key),
            loginCommand = "claude-kimi login",
        )
    }
}

private const val CACHE_BODY =
    """{"model":"k3","messages":[{"role":"user","content":[{"type":"text","text":"hi","cache_control":{"type":"ephemeral"}}]}]}"""
