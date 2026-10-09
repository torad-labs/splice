package splice.app.provider.v4393

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.core.auth.RefreshAttempt
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.turn.WatchdogBudget
import splice.topology.TopologyLoader
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class MuseLegacyDialectTest {
    @Test
    fun `legacy Muse topology boots on Responses and names the stale declaration once`(@TempDir root: Path) = runTest {
        val lines = mutableListOf<String>()
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val provider = ProviderAssembly(
            statePaths = paths,
            probeScope = backgroundScope,
            log = { lines += it },
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic") },
        )
        val wired = provider.buildProvider(context(root, paths, "muse", "anthropic-passthrough"))
        assertEquals("https://api.meta.ai/v1/responses", wired.provider.upstreamUrl)
        assertEquals(1, lines.count { it.contains("stale") && it.contains("openai-responses") }, "$lines")
    }

    @Test
    fun `Muse Responses appends v1 only when its declared base lacks it`(@TempDir root: Path) = runTest {
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val lines = mutableListOf<String>()
        val assembly = ProviderAssembly(
            statePaths = paths,
            probeScope = backgroundScope,
            log = { lines += it },
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic") },
        )
        val oldBase = assembly.buildProvider(context(root, paths, "muse", "openai-responses"))
        assertEquals("https://api.meta.ai/v1/responses", oldBase.provider.upstreamUrl)
        assertEquals(1, lines.count { it.contains("base_url") && it.contains("stale") }, "$lines")
        val currentBase = assembly.buildProvider(
            context(root, paths, "muse", "openai-responses", "https://api.meta.ai/v1"),
        )
        assertEquals("https://api.meta.ai/v1/responses", currentBase.provider.upstreamUrl)
    }

    @Test
    fun `Muse OAuth on a different provider or unsupported dialect still refuses`(@TempDir root: Path) = runTest {
        val paths = StatePaths(baseOverride = root.resolve("state"))
        val assembly = ProviderAssembly(
            statePaths = paths,
            probeScope = backgroundScope,
            log = {},
            refreshCall = TokenUrlRefreshCall { _, _ -> RefreshAttempt.Denied("synthetic") },
        )
        for ((provider, dialect) in listOf("not-muse" to "anthropic-passthrough", "muse" to "openai-chat")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                assembly.buildProvider(context(root, paths, provider, dialect))
            }
            assertTrue(error.message.orEmpty().contains("incompatible auth kind"), error.message)
        }
    }

    private fun context(
        root: Path,
        paths: StatePaths,
        provider: String,
        dialect: String,
        baseUrl: String = "https://api.meta.ai",
    ): ProviderBuild {
        val topology = TopologyLoader.parse(
            """
            [providers.$provider]
            dialect = "$dialect"
            base_url = "$baseUrl"
            auth = { kind = "muse-oauth", file = "${root.resolve("auth.json")}" }
            [[providers.$provider.models]]
            id = "muse-spark-1.3[1m]"
            context_window = 1000000
            [heads.claude-muse]
            provider = "$provider"
            port = 31393
            discovery_prefix = "claude-muse--"
            pinned_model = "muse-spark-1.3[1m]"
            """.trimIndent(),
        )
        val head = topology.heads.getValue("claude-muse")
        val cfg = topology.providers.getValue(provider)
        return ProviderBuild(
            key = "claude-muse",
            head = head,
            providerCfg = cfg,
            catalog = cfg.catalogFor(head),
            watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
            cfg = ConfigService(paths).getConfig("claude-muse"),
            loginCommand = "claude-muse login",
        )
    }
}
