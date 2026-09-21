// NEW: v0.4.0 FEATURES.md §11 — NEVER-BELOW-STATUS-QUO for the account pool: a head holding one
// account assembles exactly as before 0.4.0 (no pool, no account projection), and the primary's
// quota snapshot keeps living in the per-head state file an upgraded install already has.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.SignInPlanner
import splice.app.TokenUrlRefreshCall
import splice.app.auth.OAuthAccountFiles
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
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.LogSink
import splice.head.usage.QuotaTracker
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val HEAD = "claudex"
private const val USED_PCT = 42

class ManagedHeadFactoryAccountsTest {

    @Test
    fun `a head holding only its primary account gets no pool and no account projection`(@TempDir tmp: Path) =
        runTest {
            val statePaths = StatePaths(baseOverride = tmp.resolve("single"))
            val managed = factory(statePaths, backgroundScope).assembleHead(build(statePaths), controlPort = 3098)
            assertNull(managed.accountPool, "one account is no choice: the pre-0.4.0 path end to end")
            assertNull(managed.accountAuth)
        }

    @Test
    fun `a second labeled account makes a pool`(@TempDir tmp: Path) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("pool"))
        val ctx = build(statePaths)
        OAuthAccountFiles().writeLabeled(AuthKind.ChatgptOAuth, primaryFile(ctx), "backup", buildJsonObject {})
        val managed = factory(statePaths, backgroundScope).assembleHead(ctx, controlPort = 3098)
        val labels = checkNotNull(managed.accountPool).view(null).accounts.map { it.label }
        assertEquals(listOf("primary", "backup"), labels)
    }

    @Test
    fun `the primary's quota stays in the per-head state file an upgraded install already has`(
        @TempDir tmp: Path,
    ) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("upgrade"))
        val ctx = build(statePaths)
        OAuthAccountFiles().writeLabeled(AuthKind.ChatgptOAuth, primaryFile(ctx), "backup", buildJsonObject {})
        val resetsAt = System.currentTimeMillis() / 1_000L + 3_600L
        QuotaTracker(statePaths.quotaFile(HEAD)).record(
            QuotaSnapshot(fiveHour = QuotaWindow(USED_PCT.toDouble(), resetsAt, 18_000L)),
        )

        val managed = factory(statePaths, backgroundScope).assembleHead(ctx, controlPort = 3098)

        assertEquals(USED_PCT, managed.usage.snapshot().quota?.fiveHour?.usedPct, "the head's tracked quota")
        val primary = checkNotNull(managed.accountPool).view(null).accounts.first { it.primary }
        assertEquals(USED_PCT.toDouble(), primary.fiveHourUsedPercent, "the pool's primary reads the same file")
    }

    private fun primaryFile(ctx: ProviderBuild): Path = Path.of(checkNotNull(ctx.providerCfg.auth.file))

    private fun factory(statePaths: StatePaths, scope: CoroutineScope): ManagedHeadFactory {
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
            startQuotaPoller = { _, _, _, _ -> },
        )
    }

    private fun build(statePaths: StatePaths): ProviderBuild {
        val model = ModelEntry(id = "gpt-5.6-sol", contextWindow = 400_000)
        return ProviderBuild(
            key = HEAD,
            head = HeadConfig(
                provider = "codex",
                port = 3099,
                discoveryPrefix = "claude-codex--",
                pinnedModel = model.id,
                claude = ClaudeWrapperConfig(command = HEAD, configDir = statePaths.stateDir.toString()),
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
            cfg = ConfigService(statePaths, headOverrides = mapOf("quotaPoll" to "off")).getConfig(),
            loginCommand = "claudex login",
        )
    }
}
