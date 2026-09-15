// NEW: CodexResponsesArm owns ChatGPT OAuth accounts, /responses URL, routing headers, quirks, and
// code-mode default-on. Shared ResponsesArm only dispatches.
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.app.auth.OAuthAccountFiles
import splice.app.provider.CodexResponsesArm
import splice.app.provider.ProviderBuild
import splice.app.provider.WiredAccount
import splice.core.auth.Credentials
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.QuirksConfig
import splice.core.turn.WatchdogBudget
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class CodexResponsesArmTest {

    @Test
    fun `codex oauth path pins responses url routing quirks accounts and code-mode on`(@TempDir tmp: Path) = runTest {
        val state = StatePaths(baseOverride = tmp.resolve("state"))
        Files.createDirectories(state.stateDir)
        val primaryFile = tmp.resolve("auth.json")
        Files.writeString(
            primaryFile,
            """{"tokens":{"access_token":"synthetic-primary","account_id":"primary-id"}}""",
        )
        OAuthAccountFiles().writeLabeled(
            AuthKind.ChatgptOAuth,
            primaryFile,
            "backup",
            Json.parseToJsonElement(
                """{"tokens":{"access_token":"synthetic-backup","account_id":"backup-id"}}""",
            ).jsonObject,
        )
        val ctx = context(tmp, primaryFile)
        assertTrue(ctx.providerCfg.codeModeEnabled)
        val arm = CodexResponsesArm(
            state,
            backgroundScope,
            log = {},
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        val wired = arm.codexOAuthProvider(ctx, "claude-codex")
        assertEquals("https://chatgpt.com/backend-api/codex/responses", wired.provider.upstreamUrl)
        assertEquals(listOf("primary", "backup"), wired.accounts.map(WiredAccount::label))
        assertEquals(wired.accounts.single(WiredAccount::primary).auth, wired.auth)
        val creds = wired.auth.credentials() as Credentials.Bearer
        assertEquals(Credentials.Bearer("synthetic-primary", "primary-id"), creds)
        val headers = wired.provider.extraHeaders(creds)
        assertEquals("text/event-stream", headers["Accept"])
        assertEquals("primary-id", headers["ChatGPT-Account-ID"])
        val built = wired.provider.buildTurn(
            AnthropicParse.parseAnthropicBody(TOOL_BODY),
            compact = false,
            sessionId = "session-1",
        )
        assertEquals("session-1", built.extraHeaders["session-id"])
        assertTrue(built.extraHeaders.containsKey("thread-id"))
        assertEquals("model=gpt-5.6-sol", built.extraHeaders["x-codex-routing-hint"])
        val wire = built.requestBody.toString()
        assertTrue(wire.contains("\"strict\":false"))
        assertFalse(wire.contains("\"strict\":true"))
        assertEquals("", built.requestBody["instructions"]?.jsonPrimitive?.content)
        wired.provider.onHeadStop()
    }

    private fun context(tmp: Path, authFile: Path): ProviderBuild {
        val key = "codex"
        val state = StatePaths(baseOverride = tmp.resolve("state"))
        val config = ConfigService(state, envReader = { null })
        return ProviderBuild(
            key = key,
            head = HeadConfig(
                provider = "codex",
                port = 3100,
                discoveryPrefix = "claude-codex--",
                pinnedModel = "gpt-5.6-sol",
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://chatgpt.com/backend-api/codex",
                auth = AuthConfig(kind = AuthKind.ChatgptOAuth.wire, file = authFile.toString()),
                quirks = QuirksConfig(accountIdHeader = true),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry(id = "gpt-5.6-sol", contextWindow = 1_000_000)),
                defaultContextWindow = 1_000_000,
            ),
            watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
            cfg = config.getConfig(key),
            loginCommand = "claude-codex login",
        )
    }
}

private const val TOOL_BODY =
    """{"model":"gpt-5.6-sol","stream":true,"max_tokens":1024,""" +
        """"tools":[{"name":"Read","input_schema":{"type":"object"}},""" +
        """{"name":"Bash","input_schema":{"type":"object"},"strict":true}],""" +
        """"messages":[{"role":"user","content":"Ping."}]}"""
