// The head factory's quota path as an operator sees it: opening usage probes every account through its
// retained poller without a model turn, and each account's tracker (pool, or the empty-accounts fallback)
// decodes the x-codex rate-limit headers of a served round.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.app.auth.SignInPlanner
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
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
import splice.core.usage.QuotaHeaderRead
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.ElapsedClock
import splice.core.util.LogSink
import splice.head.usage.QuotaTracker
import splice.oauth.OAuthAccountFiles
import splice.usage.quota.QuotaCadence
import splice.usage.quota.QuotaClocks
import splice.usage.quota.QuotaPoller
import splice.usage.quota.QuotaProbe
import splice.usage.quota.QuotaSnapshotSink
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class ManagedHeadFactoryQuotaPollTest {

    private fun factory(
        statePaths: StatePaths,
        scope: CoroutineScope,
        startQuotaPoller: StartQuotaPoller,
        onPrimaryQuota: OnPrimaryQuota = OnPrimaryQuota { _ -> },
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
            serving = HeadServing(HeadServerFactory(config, mgmtKey, log)),
            launchSpecFactory = LaunchSpecFactory(
                topology = Topology(),
                signInPlanner = signInPlanner,
                mgmtKey = mgmtKey,
                buildInputs = HeadBuildInputs(config, signInPlanner),
            ),
            log = log,
            quotaSeams = QuotaPollSeams(
                scope,
                log,
                startQuotaPoller = startQuotaPoller,
                onPrimaryQuota = onPrimaryQuota,
            ),
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
    fun `opening usage probes every account through its retained poller`(@TempDir tmp: Path) = runTest {
        val paths = StatePaths(baseOverride = tmp.resolve("probe-now"))
        val ctx = build(paths, quotaPoll = "auto")
        OAuthAccountFiles().writeLabeled(
            AuthKind.ChatgptOAuth,
            Path.of(checkNotNull(ctx.providerCfg.auth.file)),
            "backup",
            buildJsonObject {},
        )
        var calls = 0
        val trackers = mutableListOf<QuotaTracker>()
        val managed = factory(
            paths,
            backgroundScope,
            StartQuotaPoller { head, _, tracker, intervalMs ->
                trackers += tracker
                QuotaPoller(
                    backgroundScope,
                    head,
                    QuotaProbe {
                        calls++
                        QuotaSnapshot(
                            fiveHour = QuotaWindow(25.0, null, 18_000),
                            updatedAt = 1_788_000_000_000L,
                        )
                    },
                    QuotaSnapshotSink(tracker::record),
                    { },
                    cadence = QuotaCadence(intervalMs = intervalMs),
                    clocks = QuotaClocks(elapsed = ElapsedClock { 0L }),
                )
            },
        ).assembleHead(ctx, controlPort = 3098)
        managed.usage.probeNow()
        assertEquals(2, calls, "both primary and added account must refresh without a model turn")
        managed.usage.probeNow()
        assertEquals(2, calls, "every returned poller keeps its own admission floor")
        assertTrue(trackers.all { it.snapshot()?.updatedAt == 1_788_000_000_000L })
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
            StartQuotaPoller { _, _, tracker, _ ->
                captured += tracker
                null
            },
        )
        factory.assembleHead(ctx, controlPort = 3098)
        assertEquals(2, captured.size)
        captured.forEach(::assertCodexRound)
    }

    @Test
    fun `the empty-accounts fallback tracker decodes an x-codex round`(@TempDir tmp: Path) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("api-key-headers"))
        val ctx = apiKeyBuild(statePaths)
        val wired = ProviderAssembly(
            statePaths,
            backgroundScope,
            LogSink { },
            TokenUrlRefreshCall { _, _ -> error("refresh must not run during assembly") },
        ).buildProvider(ctx)
        assertTrue(
            wired.accounts.isEmpty(),
            "this arm covers the empty-accounts fallback; if an api-key head grows accounts the test must move",
        )
        var starts = 0
        val captured = mutableListOf<QuotaTracker>()
        factory(
            statePaths,
            backgroundScope,
            StartQuotaPoller { _, _, _, _ ->
                starts += 1
                null
            },
            OnPrimaryQuota { captured += it },
        ).assembleHead(ctx, controlPort = 3100)
        assertEquals(0, starts)
        assertCodexRound(captured.single())
    }

    private fun assertCodexRound(tracker: QuotaTracker) {
        tracker.observe(
            QuotaHeaderRead { name ->
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

    private fun apiKeyBuild(statePaths: StatePaths): ProviderBuild {
        val model = ModelEntry(id = "gpt-4.1", contextWindow = 200_000)
        return ProviderBuild(
            key = "openai",
            head = HeadConfig(
                provider = "openai",
                port = 3100,
                discoveryPrefix = "claude-openai--",
                pinnedModel = model.id,
                claude = ClaudeWrapperConfig(command = "openai", configDir = statePaths.stateDir.toString()),
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://api.openai.com/v1",
                auth = AuthConfig(kind = "api-key"),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-openai--",
                models = listOf(model),
                defaultContextWindow = model.contextWindow,
            ),
            watchdog = WatchdogBudget(300.seconds, 300.seconds, 900.seconds),
            cfg = ConfigService(statePaths, headOverrides = mapOf("quotaPoll" to "auto")).getConfig(),
            loginCommand = "openai login",
        )
    }
}
