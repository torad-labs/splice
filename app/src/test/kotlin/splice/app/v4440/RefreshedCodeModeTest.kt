// NEW: V4-440 — real roster refresh moves a retained Codex arm's backend tool-mode facts.
package splice.app.v4440

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.app.provider.CodexResponsesArm
import splice.app.provider.HeadModelsSource
import splice.app.provider.ModelRosters
import splice.app.provider.ProviderBuild
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.model.ModelEntry
import splice.core.parse.AnthropicParse
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.WatchdogBudget
import splice.core.util.SecureFile
import splice.models.discovery.Discovery
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val MODE_MODEL = "gpt-6-synthetic"
private const val MODE_BODY = """{"model":"gpt-6-synthetic","max_tokens":8,"stream":true,
"tools":[{"name":"synthetic_tool","input_schema":{"type":"object"}}],
"messages":[{"role":"user","content":"synthetic request"}]}"""

class RefreshedCodeModeTest {
    @Test
    fun `roster refresh changes tool mode on the next build of the existing provider`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val paths = StatePaths(baseOverride = tmp)
        val authFile = tmp.resolve("synthetic-auth.json")
        SecureFile.writeAtomic0600(authFile, """{"tokens":{"access_token":"synthetic-token"}}""")
        val provider = ProviderConfig(
            Dialect.OPENAI_RESPONSES,
            "https://synthetic.example.test/backend-api/codex",
            AuthConfig(AuthKind.ChatgptOAuth.wire, file = authFile.toString()),
            models = listOf(ModelEntry(MODE_MODEL, contextWindow = 128_000)),
        )
        var listed = listOf(DiscoveredModel(MODE_MODEL))
        val rosters = ModelRosters(
            paths,
            {},
            HeadModelsSource { _, _ -> Discovery.Found("${provider.baseUrl}/models", listed) },
        )
        rosters.resolve(mapOf("synthetic" to provider))
        val wired = CodexResponsesArm(
            paths,
            this,
            {},
            TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic-denied") },
        ).codexOAuthProvider(context(paths, provider, rosters), "Synthetic")
        val body = AnthropicParse.parseAnthropicBody(MODE_BODY)
        try {
            val direct = wired.provider.buildTurn(body, false, "synthetic-session")
            assertFalse(direct.requestBody.toString().contains("\"name\":\"exec\""))
            listed = listOf(DiscoveredModel(MODE_MODEL, toolMode = "code_mode_only"))
            rosters.resolve(mapOf("synthetic" to provider))
            val marked = wired.provider.buildTurn(body, false, "synthetic-session")
            assertTrue(marked.requestBody.toString().contains("\"name\":\"exec\""), marked.requestBody.toString())
            listed = listOf(DiscoveredModel(MODE_MODEL, toolMode = "function"))
            rosters.resolve(mapOf("synthetic" to provider))
            val unmarked = wired.provider.buildTurn(body, false, "synthetic-session")
            assertFalse(unmarked.requestBody.toString().contains("\"name\":\"exec\""))
        } finally {
            wired.provider.onHeadStop()
        }
    }

    private fun context(
        paths: StatePaths,
        provider: ProviderConfig,
        rosters: ModelRosters,
    ): ProviderBuild {
        val head = HeadConfig("synthetic", 0, "claude-synthetic--", MODE_MODEL)
        return ProviderBuild(
            key = "synthetic",
            head = head,
            providerCfg = provider,
            catalog = provider.catalogFor(head),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            cfg = ConfigService(paths, envReader = { null }).getConfig("synthetic"),
            loginCommand = "",
            discovered = rosters,
        )
    }
}
