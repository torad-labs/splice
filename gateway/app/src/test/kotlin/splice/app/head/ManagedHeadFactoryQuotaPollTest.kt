// NEW: CLAUDEX_QUOTA_POLL had parser coverage but no production-effect arm. These tests drive
// ManagedHeadFactory.assembleHead through a subscription head and count the poller-start seam, so
// the off switch and the default-on path are both observable without making a network request.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.SignInPlanner
import splice.app.TokenUrlRefreshCall
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class ManagedHeadFactoryQuotaPollTest {

    private fun factory(
        statePaths: StatePaths,
        scope: CoroutineScope,
        startQuotaPoller: StartQuotaPoller,
    ): ManagedHeadFactory {
        val log = LogSink { }
        val config = ConfigService(statePaths)
        val mgmtKey = MgmtKey(statePaths)
        val signInPlanner = SignInPlanner()
        return ManagedHeadFactory(
            statePaths = statePaths,
            providerAssembly = ProviderAssembly(
                statePaths,
                scope,
                log,
                TokenUrlRefreshCall { _, _ -> error("refresh must not run during assembly") },
            ),
            headServerFactory = HeadServerFactory(config, mgmtKey, log),
            launchSpecFactory = LaunchSpecFactory(
                topology = Topology(),
                signInPlanner = signInPlanner,
                mgmtKey = mgmtKey,
                buildInputs = HeadBuildInputs(config, signInPlanner),
            ),
            probeScope = scope,
            log = log,
            startQuotaPoller = startQuotaPoller,
        )
    }

    private fun build(statePaths: StatePaths, quotaPoll: String): ProviderBuild {
        val model = ModelEntry(id = "gpt-5.6-sol", contextWindow = 400_000)
        return ProviderBuild(
            key = "claudex",
            head = HeadConfig(
                provider = "codex",
                port = 3099,
                discoveryPrefix = "claude-codex--",
                pinnedModel = model.id,
                claude = ClaudeWrapperConfig(command = "claudex", configDir = statePaths.stateDir.toString()),
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://chatgpt.com/backend-api/codex",
                auth = AuthConfig(kind = "chatgpt-oauth", file = statePaths.stateDir.resolve("auth.json").toString()),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(model),
                defaultContextWindow = model.contextWindow,
            ),
            watchdog = WatchdogBudget(300.seconds, 300.seconds, 900.seconds),
            cfg = ConfigService(statePaths, headOverrides = mapOf("quotaPoll" to quotaPoll)).getConfig(),
            loginCommand = "claudex login",
        )
    }

    @Test
    fun `quota poll off does not start a poller`(@TempDir tmp: Path) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("off"))
        var starts = 0
        val factory = factory(statePaths, backgroundScope, StartQuotaPoller { _, _, _ -> starts += 1 })

        factory.assembleHead(build(statePaths, quotaPoll = "off"), controlPort = 3098)

        assertEquals(0, starts)
    }

    @Test
    fun `quota poll auto starts one poller for a subscription head`(@TempDir tmp: Path) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("auto"))
        var starts = 0
        val factory = factory(statePaths, backgroundScope, StartQuotaPoller { _, _, _ -> starts += 1 })

        factory.assembleHead(build(statePaths, quotaPoll = "auto"), controlPort = 3098)

        assertEquals(1, starts)
    }
}
