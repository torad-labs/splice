// NEW: gateway-no-heads. One persisted identity has one set of books, however many routes serve it: a legacy head and
// the gateway's runtime for the same provider share the stores, the account pool and the quota pollers, and two
// identities never do, whatever their names. A second store over one file would serve each route a stale copy of the
// other's hours.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.accounts.order.HeadAccountOrderSource
import splice.accounts.pool.HeadAccountAskSource
import splice.accounts.pool.HeadAccountPinSource
import splice.accounts.pool.HeadAccountPoolSource
import splice.app.TokenUrlRefreshCall
import splice.app.auth.SignInPlanner
import splice.app.provider.ClaudeAccountWiring
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.provider.UpstreamFaultPlan
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.failure.AccountBlock
import splice.core.failure.OwnerStep
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
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.usage.SEVEN_DAY_CLAIM
import splice.core.util.LogSink
import splice.models.roster.DeclaredHead
import splice.models.roster.DeclaredHeads
import splice.oauth.OAuthAccountFiles
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.Selection
import java.nio.file.Files
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

    @Test
    fun `a legacy head and the gateway runtime over the same accounts share pool, quotas, holds and pollers`(
        @TempDir tmp: Path,
    ) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("shared-codex"))
        val authFile = statePaths.stateDir.resolve("auth.json")
        Files.createDirectories(statePaths.stateDir)
        Files.writeString(authFile, "{}")
        OAuthAccountFiles().writeLabeled(AuthKind.ChatgptOAuth, authFile, "backup", buildJsonObject {})

        val rig = rig(statePaths, backgroundScope)
        val headCtx = build(statePaths, key = "claudex", provider = "codex", quotaPoll = "auto")
        val routeCtx = build(statePaths, key = "codex", provider = "codex", quotaPoll = "auto")

        val managed = rig.factory.assembleHead(headCtx, controlPort = 3098)
        val route = rig.factory.assembleRoute(routeCtx, layer = "providers.codex", registers = true)
        val headBooks = rig.factory.booksOf(headCtx, rig.assembly.buildProvider(headCtx))

        route.books.stores.usageStore.appendOutputTokens(123L)
        assertEquals(123L, route.books.stores.usageStore.readState().outputTokens5h)
        assertEquals(0L, headBooks.stores.usageStore.readState().outputTokens5h, "usage stores stay per key")

        val headPoolSource = requireNotNull(managed.authSurface.accountPool)
        val routePool = requireNotNull(route.books.stores.accounts.pool)
        val nowMs = System.currentTimeMillis()
        route.books.stores.accounts.quotas.getValue("primary").record(
            QuotaSnapshot(null, QuotaWindow(73.0, nowMs / 1_000L + 3_600L, 604_800L), "plus", nowMs),
        )
        val primaryView = headPoolSource.view(null).accounts.single { it.label == "primary" }
        assertEquals(73.0, primaryView.quota.sevenDay.usedPercent, "quota recorded on gateway shows on head view")

        val orderSource = headPoolSource as HeadAccountOrderSource
        assertTrue(orderSource.setOrder(listOf("backup", "primary")))
        assertEquals(listOf("backup", "primary"), routePool.order)

        assertSharedPoolSync(headPoolSource, routePool, label = "backup", where = "chatgpt.com")
    }

    @Test
    fun `a claude head and the gateway anthropic runtime share the added-account pool without colliding holds`(
        @TempDir tmp: Path,
    ) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("shared-claude"))
        seedClaudeAccount(statePaths, head = "claude", label = "acct-a")
        seedClaudeAccount(statePaths, head = "claude", label = "acct-b")

        val declared = DeclaredHeads { mapOf("claude" to DeclaredHead("anthropic", emptyList())) }
        val rig = rig(statePaths, backgroundScope, declared = declared)
        val headCtx = claudeBuild(statePaths, key = "claude")
        val routeCtx = claudeBuild(statePaths, key = "anthropic")

        val managed = rig.factory.assembleHead(headCtx, controlPort = 3098)
        val route = rig.factory.assembleRoute(routeCtx, layer = "providers.anthropic", registers = true)
        val headBooks = rig.factory.booksOf(headCtx, rig.assembly.buildProvider(headCtx))

        route.books.stores.usageStore.appendOutputTokens(456L)
        assertEquals(456L, route.books.stores.usageStore.readState().outputTokens5h)
        assertEquals(0L, headBooks.stores.usageStore.readState().outputTokens5h, "usage stores stay per key")

        val headPoolSource = requireNotNull(managed.authSurface.accountPool)
        val routePool = requireNotNull(route.books.stores.accounts.pool)
        val nowMs = System.currentTimeMillis()
        route.books.stores.accounts.quotas.getValue("acct-a").record(
            QuotaSnapshot(null, QuotaWindow(64.0, nowMs / 1_000L + 3_600L, 604_800L), "pro", nowMs),
        )
        val acctAView = headPoolSource.view(null).accounts.single { it.label == "acct-a" }
        assertEquals(64.0, acctAView.quota.sevenDay.usedPercent, "quota recorded on gateway shows on head view")

        assertSharedPoolSync(
            headPoolSource,
            routePool,
            label = "acct-b",
            where = "claude.ai",
            excludedForRoute = setOf("claude-code"),
        )
        assertFalse(
            Files.exists(statePaths.stateDir.resolve("anthropic-acct-b-provider-hold.json")),
            "second route must not create duplicate provider hold files under its own key",
        )
    }

    private fun assertSharedPoolSync(
        headPoolSource: HeadAccountPoolSource,
        routePool: AccountPool,
        label: String,
        where: String,
        excludedForRoute: Set<String> = emptySet(),
    ) {
        val pinSource = headPoolSource as HeadAccountPinSource
        assertTrue(pinSource.pin(label, null))
        assertEquals(label, routePool.pinned(null))
        assertEquals(label, routePool.nextTargetLabel())

        val chosen = routePool.select("sess-door", excluded = excludedForRoute) as Selection.Chosen
        assertEquals(label, chosen.account.account.label)
        val block = AccountBlock(OwnerStep.ALLOW_SUBSCRIPTION_ACCESS, where)
        chosen.account.markCredentialUnavailable(block)

        val blockedView = headPoolSource.view(null).accounts.single { it.label == label }
        assertFalse(blockedView.available, "blocked account must read unavailable on /api/accounts")
        assertEquals(block, blockedView.credential.ownerBlock)
        assertNotNull(blockedView.credential.excludedUntilEpochMillis)

        val resetEpochSeconds = System.currentTimeMillis() / 1_000L + 3_600L
        chosen.account.account.cooldown.planHold.hold(PlanLimit(SEVEN_DAY_CLAIM, resetEpochSeconds)) {}
        val hold = headPoolSource.view(null).accounts.single { it.label == label }.quota.holds.wholeLogin
        assertEquals(resetEpochSeconds, hold?.resetEpochSeconds)

        val askSource = headPoolSource as HeadAccountAskSource
        assertTrue(askSource.askAgain(label))
        val afterAsk = routePool.view(null).accounts.single { it.label == label }
        assertEquals(null, afterAsk.credential.excludedUntilEpochMillis)
    }

    @Test
    fun `two routes pointing at different credential files do not share a pool`(@TempDir tmp: Path) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("distinct-creds"))
        val authA = statePaths.stateDir.resolve("auth-a.json")
        val authB = statePaths.stateDir.resolve("auth-b.json")
        Files.createDirectories(statePaths.stateDir)
        Files.writeString(authA, "{}")
        Files.writeString(authB, "{}")
        OAuthAccountFiles().writeLabeled(AuthKind.ChatgptOAuth, authA, "backup", buildJsonObject {})
        OAuthAccountFiles().writeLabeled(AuthKind.ChatgptOAuth, authB, "backup", buildJsonObject {})

        val rig = rig(statePaths, backgroundScope)
        val first = build(statePaths, key = "claudex-a", provider = "codex", authFile = authA)
        val second = build(statePaths, key = "claudex-b", provider = "codex", authFile = authB)

        val headA = rig.factory.assembleHead(first, controlPort = 3098)
        val headB = rig.factory.assembleHead(second, controlPort = 3100)
        val a = rig.factory.booksOf(first, rig.assembly.buildProvider(first))
        val b = rig.factory.booksOf(second, rig.assembly.buildProvider(second))

        val pinA = requireNotNull(headA.authSurface.accountPool) as HeadAccountPinSource
        assertTrue(pinA.pin("backup", null))
        assertEquals(null, b.stores.accounts.pool?.pinned(null), "pin on first file does not affect second")
        val nowMs = System.currentTimeMillis()
        a.stores.accounts.quotas.getValue("primary").record(
            QuotaSnapshot(null, QuotaWindow(81.0, nowMs / 1_000L + 3_600L, 604_800L), "plus", nowMs),
        )
        val bPrimary =
            requireNotNull(headB.authSurface.accountPool).view(null).accounts.single { it.label == "primary" }
        assertEquals(null, bPrimary.quota.sevenDay.usedPercent, "quota on first file does not leak to second")
    }

    private fun seedClaudeAccount(statePaths: StatePaths, head: String, label: String) {
        val dir = statePaths.stateDir.resolve("claude-accounts").resolve(head).resolve(label)
        Files.createDirectories(dir)
        Files.writeString(
            dir.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"synthetic-$label","refreshToken":"synthetic-r-$label",""" +
                """"expiresAt":4102444800000}}""",
        )
        Files.writeString(
            dir.resolve(".claude.json"),
            """{"oauthAccount":{"accountUuid":"uuid-$label","emailAddress":"$label@example.invalid"}}""",
        )
    }

    private fun rig(
        statePaths: StatePaths,
        scope: CoroutineScope,
        declared: DeclaredHeads = DeclaredHeads { emptyMap() },
    ): Rig {
        val log = LogSink { }
        val config = ConfigService(statePaths)
        val mgmtKey = MgmtKey(statePaths)
        val signInPlanner = SignInPlanner()
        val assembly = ProviderAssembly(
            statePaths,
            scope,
            log,
            TokenUrlRefreshCall { _, _ -> error("refresh must not run during assembly") },
            claudeAccounts = ClaudeAccountWiring(statePaths, log, declared = declared),
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

    private fun build(
        statePaths: StatePaths,
        key: String,
        provider: String,
        quotaPoll: String = "off",
        authFile: Path = statePaths.stateDir.resolve("auth.json"),
    ): ProviderBuild {
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
                auth = AuthConfig(kind = "chatgpt-oauth", file = authFile.toString()),
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
            cfg = ConfigService(statePaths, headOverrides = mapOf("quotaPoll" to quotaPoll)).getConfig(),
        )
    }

    private fun claudeBuild(statePaths: StatePaths, key: String): ProviderBuild {
        val model = ModelEntry(id = "claude-test-model", contextWindow = 200_000)
        return ProviderBuild(
            key = key,
            head = HeadConfig(
                provider = "anthropic",
                port = 3099,
                discoveryPrefix = "claude-$key--",
                pinnedModel = model.id,
                claude = ClaudeWrapperConfig(command = key, configDir = statePaths.stateDir.toString()),
            ),
            providerCfg = ProviderConfig(
                dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                baseUrl = "https://api.anthropic.com",
                auth = AuthConfig(kind = "client"),
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
