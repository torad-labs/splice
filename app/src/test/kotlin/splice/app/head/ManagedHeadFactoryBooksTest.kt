// NEW: gateway-no-heads. One persisted identity has one set of books, however many routes serve it: a legacy head and
// the gateway's runtime for the same provider share the stores, the account pool and the quota pollers, and two
// identities never do, whatever their names. A second store over one file would serve each route a stale copy of the
// other's hours.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.app.TokenUrlRefreshCall
import splice.app.auth.SignInPlanner
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.provider.UpstreamFaultPlan
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

class ManagedHeadFactoryBooksTest {
    private data class Rig(val factory: ManagedHeadFactory, val assembly: ProviderAssembly)

    @Test
    fun `a head and a runtime for the same provider are answered with the same books`(@TempDir tmp: Path) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("same"))
        val rig = rig(statePaths, backgroundScope)
        val ctx = build(statePaths, key = "codex", provider = "codex")
        rig.factory.assembleHead(ctx, controlPort = 3098)

        val head = rig.factory.booksOf(ctx, rig.assembly.buildProvider(ctx))
        val runtime = rig.factory.booksOf(ctx, rig.assembly.buildProvider(ctx))

        assertSame(head, runtime, "one identity, one set of books")
        assertSame(head.stores.usageStore, runtime.stores.usageStore)
        assertSame(head.quota.primary, runtime.quota.primary)
    }

    @Test
    fun `a key asked for under another provider is refused, never answered with the first provider's books`(
        @TempDir tmp: Path,
    ) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("other"))
        val rig = rig(statePaths, backgroundScope)
        val ctx = build(statePaths, key = "codex", provider = "codex")
        rig.factory.assembleHead(ctx, controlPort = 3098)
        val unrelated = ctx.copy(head = ctx.head.copy(provider = "openrouter"))

        val refused = assertThrows<IllegalStateException> {
            rig.factory.booksOf(unrelated, rig.assembly.buildProvider(unrelated))
        }

        assertTrue("codex" in refused.message.orEmpty() && "openrouter" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `two keys keep two sets of books`(@TempDir tmp: Path) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("two"))
        val rig = rig(statePaths, backgroundScope)
        val first = build(statePaths, key = "codex", provider = "codex")
        val second = build(statePaths, key = "claudex", provider = "codex")
        rig.factory.assembleHead(first, controlPort = 3098)
        rig.factory.assembleHead(second, controlPort = 3100)

        val a = rig.factory.booksOf(first, rig.assembly.buildProvider(first))
        val b = rig.factory.booksOf(second, rig.assembly.buildProvider(second))

        assertNotSame(a.stores.usageStore, b.stores.usageStore, "another head on one provider keeps its own books")
        assertEquals("codex", a.provider)
        assertEquals("codex", b.provider)
    }

    private fun rig(statePaths: StatePaths, scope: CoroutineScope): Rig {
        val log = LogSink { }
        val config = ConfigService(statePaths)
        val mgmtKey = MgmtKey(statePaths)
        val signInPlanner = SignInPlanner()
        val assembly = ProviderAssembly(
            statePaths,
            scope,
            log,
            TokenUrlRefreshCall { _, _ -> error("refresh must not run during assembly") },
        )
        val factory = ManagedHeadFactory(
            statePaths = statePaths,
            providerAssembly = assembly,
            serving = HeadServing(HeadServerFactory(config, mgmtKey, log)),
            launchSpecFactory = LaunchSpecFactory(
                topology = Topology(),
                signInPlanner = signInPlanner,
                mgmtKey = mgmtKey,
                buildInputs = HeadBuildInputs(config, signInPlanner),
            ),
            log = log,
            quotaSeams = QuotaPollSeams(scope, log, startQuotaPoller = StartQuotaPoller { _, _, _, _ -> null }),
        )
        return Rig(factory, assembly)
    }

    private fun build(statePaths: StatePaths, key: String, provider: String): ProviderBuild {
        val model = ModelEntry(id = "gpt-5.6-sol", contextWindow = 400_000)
        return ProviderBuild(
            key = key,
            head = HeadConfig(
                provider = provider,
                port = 3099,
                discoveryPrefix = "claude-$key--",
                pinnedModel = model.id,
                claude = ClaudeWrapperConfig(command = key, configDir = statePaths.stateDir.toString()),
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://chatgpt.com/backend-api/codex",
                auth = AuthConfig(kind = "chatgpt-oauth", file = statePaths.stateDir.resolve("auth.json").toString()),
            ),
            catalog = ModelCatalog(
                discoveryPrefix = "claude-$key--",
                models = listOf(model),
                defaultContextWindow = model.contextWindow,
            ),
            faultPlan = UpstreamFaultPlan(
                watchdog = WatchdogBudget(300.seconds, 300.seconds, 900.seconds),
                loginCommand = "$key login",
            ),
            cfg = ConfigService(statePaths, headOverrides = mapOf("quotaPoll" to "off")).getConfig(),
        )
    }
}
