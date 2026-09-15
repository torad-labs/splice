// NEW: CLAUDEX_QUOTA_POLL had parser coverage but no production-effect arm. These tests drive
// ManagedHeadFactory.assembleHead through a subscription head and count the poller-start seam, so
// the off switch and the default-on path are both observable without making a network request.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.SignInPlanner
import splice.app.TokenUrlRefreshCall
import splice.app.auth.OAuthAccountFiles
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.quota.CodexQuotaProbe
import splice.app.quota.MuseMintProbe
import splice.app.quota.QuotaProbe
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.ClaudeWrapperConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.gateway.usage.HeaderLookup
import splice.gateway.usage.QuotaTracker
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

    @Test
    fun `quota poll auto starts one poller per OAuth account`(@TempDir tmp: Path) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("pool"))
        val ctx = build(statePaths, quotaPoll = "auto")
        val primaryFile = Path.of(checkNotNull(ctx.providerCfg.auth.file))
        OAuthAccountFiles().writeLabeled(
            AuthKind.ChatgptOAuth,
            primaryFile,
            "backup",
            buildJsonObject {},
        )
        var starts = 0
        val factory = factory(statePaths, backgroundScope, StartQuotaPoller { _, _, _ -> starts += 1 })

        factory.assembleHead(ctx, controlPort = 3098)

        assertEquals(2, starts)
    }

    @Test
    fun `the factory no-accounts tracker decodes an x-codex round`(@TempDir tmp: Path) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("codex-headers"))
        val captured = mutableListOf<QuotaTracker>()
        val factory = factory(
            statePaths,
            backgroundScope,
            StartQuotaPoller { _, _, tracker -> captured += tracker },
        )
        factory.assembleHead(build(statePaths, quotaPoll = "auto"), controlPort = 3098)
        assertCodexRound(captured.single())
    }

    @Test
    fun `the factory account-pool tracker decodes an x-codex round`(@TempDir tmp: Path) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("codex-pool-headers"))
        val ctx = build(statePaths, quotaPoll = "auto")
        val primaryFile = Path.of(checkNotNull(ctx.providerCfg.auth.file))
        OAuthAccountFiles().writeLabeled(
            AuthKind.ChatgptOAuth,
            primaryFile,
            "backup",
            buildJsonObject {},
        )
        val captured = mutableListOf<QuotaTracker>()
        val factory = factory(
            statePaths,
            backgroundScope,
            StartQuotaPoller { _, _, tracker -> captured += tracker },
        )
        factory.assembleHead(ctx, controlPort = 3098)
        assertEquals(2, captured.size)
        captured.forEach(::assertCodexRound)
    }

    @Test
    fun `chatgpt assembly starts a codex probe and muse assembly starts a mint probe`(
        @TempDir tmp: Path,
    ) = runTest {
        val chatgptPaths = StatePaths(baseOverride = tmp.resolve("chatgpt-probe"))
        val chatgptProbes = mutableListOf<QuotaProbe>()
        factory(
            chatgptPaths,
            backgroundScope,
            StartQuotaPoller { _, probe, _ -> chatgptProbes += probe },
        ).assembleHead(build(chatgptPaths, quotaPoll = "auto"), controlPort = 3098)
        assertTrue(chatgptProbes.single() is CodexQuotaProbe)

        val musePaths = StatePaths(baseOverride = tmp.resolve("muse-probe"))
        val museProbes = mutableListOf<QuotaProbe>()
        factory(
            musePaths,
            backgroundScope,
            StartQuotaPoller { _, probe, _ -> museProbes += probe },
        ).assembleHead(museBuild(musePaths), controlPort = 3106)
        assertTrue(museProbes.single() is MuseMintProbe)
    }

    private fun assertCodexRound(tracker: QuotaTracker) {
        tracker.observe(
            HeaderLookup { name ->
                mapOf(
                    "x-codex-primary-used-percent" to "14",
                    "x-codex-primary-window-minutes" to "300",
                    "x-codex-primary-reset-at" to "1788010000",
                )[name]
            },
        )
        assertNotNull(tracker.snapshot()?.fiveHour)
        assertEquals(14.0, tracker.snapshot()!!.fiveHour!!.usedPercent, 1e-9)
    }

    private fun museBuild(statePaths: StatePaths): ProviderBuild {
        val model = ModelEntry(id = "muse", contextWindow = 200_000)
        return ProviderBuild(
            key = "muse",
            head = HeadConfig(
                provider = "muse",
                port = 3106,
                discoveryPrefix = "claude-muse--",
                pinnedModel = model.id,
                claude = ClaudeWrapperConfig(command = "muse", configDir = statePaths.stateDir.toString()),
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                baseUrl = "https://api.meta.ai",
                auth = AuthConfig(kind = "muse-oauth", file = statePaths.stateDir.resolve("muse.json").toString()),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-muse--",
                models = listOf(model),
                defaultContextWindow = model.contextWindow,
            ),
            watchdog = WatchdogBudget(300.seconds, 300.seconds, 900.seconds),
            cfg = ConfigService(statePaths, headOverrides = mapOf("quotaPoll" to "auto")).getConfig(),
            loginCommand = "muse login",
        )
    }
}
