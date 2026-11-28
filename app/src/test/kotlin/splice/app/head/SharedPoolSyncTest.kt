// NEW: end-to-end shared-pool lifecycle, 0->1 / 1->0 and multi-head Claude membership propagation, upgrade hold
// migration and restart durability, single-login OAuth cooldown sync, policy preservation, and restart isolation.
package splice.app.head

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.order.HeadAccountOrderSource
import splice.accounts.pool.HeadAccountPinSource
import splice.app.TokenUrlRefreshCall
import splice.app.auth.SignInPlanner
import splice.app.auth.claude.ClaudeCredentialProfiles
import splice.app.provider.ClaudeAccountWiring
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.app.provider.ProviderLogin
import splice.app.provider.ProviderLogins
import splice.app.provider.UpstreamFaultPlan
import splice.core.auth.CredentialKey
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
import splice.core.usage.SEVEN_DAY_CLAIM
import splice.core.util.LogSink
import splice.core.wire.NativeReply
import splice.models.roster.DeclaredHead
import splice.models.roster.DeclaredHeads
import splice.oauth.OAuthAccountFiles
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.Selection
import splice.upstream.retry.ProviderHold
import splice.upstream.retry.ProviderHoldStore
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.SavedLoginHold
import splice.upstream.retry.UpstreamCooldowns
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private val TEST_CREDENTIAL_KEY = "a".repeat(64)

class SharedPoolSyncTest {
    private data class Rig(val factory: ManagedHeadFactory, val assembly: ProviderAssembly)

    @Test
    fun `claude head and gateway route share one pool from zero accounts and propagate live add to Ready`(
        @TempDir tmp: Path,
    ) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("zero-to-one"))
        val declared = DeclaredHeads { mapOf("claude" to DeclaredHead("anthropic", emptyList())) }
        val rig = rig(statePaths, backgroundScope, declared)
        val headCtx = claudeBuild(statePaths, key = "claude")
        val routeCtx = claudeBuild(statePaths, key = "anthropic")

        val managed = rig.factory.assembleHead(headCtx, controlPort = 3098)
        val assembled = rig.factory.assembleRoute(routeCtx, layer = "providers.anthropic", registers = true)
        val served = ServedProviderRoute("anthropic", routeCtx, assembled, ProviderLogins())

        assertTrue(served.login() is ProviderLogin.Missing)

        seedClaudeAccount(statePaths, head = "claude", label = "acct-a")
        assertTrue(rig.assembly.claudePoolChanges.publish("claude"))

        assertTrue(served.login() is ProviderLogin.Ready, "gateway route sees newly added login as Ready")
        val routePool = requireNotNull(assembled.books.stores.accounts.pool)
        val chosen = routePool.select("sess-door", excluded = setOf("claude-code")) as Selection.Chosen
        assertEquals("acct-a", chosen.account.account.label)
        val block = AccountBlock(OwnerStep.ALLOW_SUBSCRIPTION_ACCESS, "claude.ai")
        chosen.account.markCredentialUnavailable(block)

        val headAcct =
            requireNotNull(managed.authSurface.accountPool).view(null).accounts.single { it.label == "acct-a" }
        assertFalse(headAcct.available, "owner block recorded on gateway route is immediately visible on head")
        assertEquals(block, headAcct.credential.ownerBlock)
    }

    @Test
    fun `withdrawing the last added claude login updates both head pool and gateway route login to Missing`(
        @TempDir tmp: Path,
    ) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("one-to-zero"))
        seedClaudeAccount(statePaths, head = "claude", label = "acct-a")
        val declared = DeclaredHeads { mapOf("claude" to DeclaredHead("anthropic", emptyList())) }
        val rig = rig(statePaths, backgroundScope, declared)
        val headCtx = claudeBuild(statePaths, key = "claude")
        val routeCtx = claudeBuild(statePaths, key = "anthropic")

        val managed = rig.factory.assembleHead(headCtx, controlPort = 3098)
        val assembled = rig.factory.assembleRoute(routeCtx, layer = "providers.anthropic", registers = true)
        val served = ServedProviderRoute("anthropic", routeCtx, assembled, ProviderLogins())

        assertTrue(served.login() is ProviderLogin.Ready)

        rig.assembly.claudePoolChanges.withdraw("claude", "acct-a")
        statePaths.stateDir.resolve("claude-accounts/claude/acct-a").toFile().deleteRecursively()
        rig.assembly.claudePoolChanges.completeWithdrawal("claude", "acct-a")

        assertTrue(served.login() is ProviderLogin.Missing, "gateway route drops removed login back to Missing")
        val headAccounts = requireNotNull(managed.authSurface.accountPool).view(null).accounts.map { it.label }
        assertFalse("acct-a" in headAccounts, "head pool drops removed login: $headAccounts")
    }

    @Test
    fun `two declared claude heads route gateway dynamically on add to second head and removal from first`(
        @TempDir tmp: Path,
    ) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("multi-head-claude"))
        val empty = DeclaredHead("anthropic", emptyList())
        val declared = DeclaredHeads { linkedMapOf("claude" to empty, "claude2" to empty) }
        val rig = rig(statePaths, backgroundScope, declared)
        rig.factory.assembleHead(claudeBuild(statePaths, key = "claude"), controlPort = 3098)
        val head2 = rig.factory.assembleHead(claudeBuild(statePaths, key = "claude2"), controlPort = 3100)
        val routeCtx = claudeBuild(statePaths, key = "anthropic")
        val assembled = rig.factory.assembleRoute(routeCtx, layer = "providers.anthropic", registers = true)
        val served = ServedProviderRoute("anthropic", routeCtx, assembled, ProviderLogins())
        assertTrue(served.login() is ProviderLogin.Missing)

        val migratedReset = System.currentTimeMillis() / 1_000L + 5_400L
        ProviderHoldFiles(statePaths, LogSink { }).forAddedAccount("anthropic", "acct-b")
            .save(ProviderHold(migratedReset, PlanLimit(SEVEN_DAY_CLAIM, migratedReset)))

        // Adding the first login to claude2 makes gateway Ready, migrates anthropic holds, and shares live state.
        seedClaudeAccount(statePaths, head = "claude2", label = "acct-b")
        assertTrue(rig.assembly.claudePoolChanges.publish("claude2"))
        assertTrue(served.login() is ProviderLogin.Ready, "gateway follows first account added to second head")
        val routePool = requireNotNull(assembled.books.stores.accounts.pool)
        val head2Pool = requireNotNull(head2.authSurface.accountPool)
        val migratedAcct = head2Pool.view(null).accounts.single { it.label == "acct-b" }
        assertEquals(migratedReset, migratedAcct.quota.holds.wholeLogin?.resetEpochSeconds)

        assertTrue(routePool.select("sess-b-held", excluded = setOf("claude-code")) is Selection.Exhausted)
        routePool.members.single { it.label == "acct-b" }.cooldown.acceptance().invoke()
        val resetEpochSeconds = System.currentTimeMillis() / 1_000L + 7_200L
        val chosenB = routePool.select("sess-b", excluded = setOf("claude-code")) as Selection.Chosen
        chosenB.account.account.cooldown.planHold.hold(PlanLimit(SEVEN_DAY_CLAIM, resetEpochSeconds)) {}
        val block = AccountBlock(OwnerStep.ALLOW_SUBSCRIPTION_ACCESS, "claude.ai")
        chosenB.account.markCredentialUnavailable(block)
        val head2View = head2Pool.view(null).accounts.single { it.label == "acct-b" }
        assertEquals(resetEpochSeconds, head2View.quota.holds.wholeLogin?.resetEpochSeconds, "live hold on claude2")
        assertEquals(block, head2View.credential.ownerBlock, "live owner block on claude2")

        // Restarting claude2 must not wipe the shared live member used by the multi-head gateway.
        restartHead(head2)
        assertEquals(block, routePool.view(null).accounts.single { it.label == "acct-b" }.credential.block)

        // Switching gateway onto claude and back to claude2 preserves claude2's live holds.
        seedClaudeAccount(statePaths, head = "claude", label = "acct-a")
        assertTrue(rig.assembly.claudePoolChanges.publish("claude"))
        rig.assembly.claudePoolChanges.withdraw("claude", "acct-a")
        statePaths.stateDir.resolve("claude-accounts/claude/acct-a").toFile().deleteRecursively()
        rig.assembly.claudePoolChanges.completeWithdrawal("claude", "acct-a")

        assertTrue(served.login() is ProviderLogin.Ready, "gateway stays Ready by falling over to second head")
        val backOnB = routePool.view(null).accounts.single { it.label == "acct-b" }
        assertEquals(resetEpochSeconds, backOnB.quota.holds.wholeLogin?.resetEpochSeconds, "claude2 hold preserved")
    }

    @Test
    fun `legacy 064 claude login blocks without account or identity survive boot and a second restart`(
        @TempDir tmp: Path,
    ) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("legacy-064-blocks"))
        seedClaudeAccount(statePaths, head = "claude", label = "acct-a")
        seedClaudeAccount(statePaths, head = "claude", label = "acct-b")
        val profiles = ClaudeCredentialProfiles(statePaths.stateDir, LogSink { })
        listOf("acct-a", "acct-b").forEach { label ->
            val key = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer synthetic-$label")))
            profiles.observed(key, ClaudeAccountIdentity("uuid-$label", "$label@example.invalid"))
        }

        val holds = ProviderHoldFiles(statePaths, LogSink { })
        val block = AccountBlock(OwnerStep.ALLOW_SUBSCRIPTION_ACCESS, "claude.ai")
        val untilMs = System.currentTimeMillis() + 1_800_000L
        val legacyHold = SavedLoginHold(untilEpochMillis = untilMs, failureCount = 1, block = block)
        holds.forAddedAccount("claude", "acct-a").loginHold().save(legacyHold)
        holds.forAddedAccount("anthropic", "acct-b").loginHold().save(legacyHold)

        val declared = DeclaredHeads { mapOf("claude" to DeclaredHead("anthropic", emptyList())) }
        val headCtx = claudeBuild(statePaths, key = "claude")
        val routeCtx = claudeBuild(statePaths, key = "anthropic")

        val boot1 = rig(statePaths, backgroundScope, declared)
        val head1 = boot1.factory.assembleHead(headCtx, controlPort = 3098)
        boot1.factory.assembleRoute(routeCtx, layer = "providers.anthropic", registers = true)
        val accounts1 = requireNotNull(head1.authSurface.accountPool).view(null).accounts.associateBy { it.label }
        assertEquals(block, accounts1.getValue("acct-a").credential.ownerBlock, "canonical legacy block survives boot")
        assertEquals(block, accounts1.getValue("acct-b").credential.ownerBlock, "migrated legacy block survives boot")

        // Upgraded persisted login-hold files on disk now carry both verified account and credential file identity.
        val upgradedA = requireNotNull(holds.forAddedAccount("claude", "acct-a").loginHold().load())
        val upgradedB = requireNotNull(holds.forAddedAccount("claude", "acct-b").loginHold().load())
        assertEquals("uuid-acct-a", upgradedA.account, "upgraded canonical hold stamps verified account")
        assertNotNull(upgradedA.identity, "upgraded canonical hold stamps credential file identity")
        assertEquals("uuid-acct-b", upgradedB.account, "upgraded migrated hold stamps verified account")
        assertNotNull(upgradedB.identity, "upgraded migrated hold stamps credential file identity")

        // Second fresh restart verifies upgraded identity-stamped files continue surviving restarts.
        val boot2 = rig(statePaths, backgroundScope, declared)
        val head2 = boot2.factory.assembleHead(headCtx, controlPort = 3098)
        val accounts2 = requireNotNull(head2.authSurface.accountPool).view(null).accounts.associateBy { it.label }
        assertEquals(block, accounts2.getValue("acct-a").credential.ownerBlock, "canonical block survives reboot 2")
        assertEquals(block, accounts2.getValue("acct-b").credential.ownerBlock, "migrated block survives reboot 2")
    }

    @Test
    fun `migrated claude provider and login holds survive into the next daemon restart`(@TempDir tmp: Path) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("upgrade-migration"))
        seedClaudeAccount(statePaths, head = "claude", label = "acct-a")
        seedClaudeAccount(statePaths, head = "claude", label = "acct-b")

        val holds = ProviderHoldFiles(statePaths, LogSink { })
        val resetEpochSeconds = System.currentTimeMillis() / 1_000L + 86_400L
        holds.forAddedAccount("anthropic", "acct-a")
            .save(ProviderHold(resetEpochSeconds, PlanLimit(SEVEN_DAY_CLAIM, resetEpochSeconds)))
        val block = AccountBlock(OwnerStep.ALLOW_SUBSCRIPTION_ACCESS, "claude.ai")
        val untilMs = System.currentTimeMillis() + 1_800_000L
        holds.forAddedAccount("anthropic", "acct-b")
            .loginHold()
            .save(SavedLoginHold(untilEpochMillis = untilMs, failureCount = 2, block = block, account = "uuid-acct-b"))

        val declared = DeclaredHeads { mapOf("claude" to DeclaredHead("anthropic", emptyList())) }
        val firstRig = rig(statePaths, backgroundScope, declared)
        val headCtx = claudeBuild(statePaths, key = "claude")
        val routeCtx = claudeBuild(statePaths, key = "anthropic")

        val managed = firstRig.factory.assembleHead(headCtx, controlPort = 3098)
        firstRig.factory.assembleRoute(routeCtx, layer = "providers.anthropic", registers = true)

        val migrated = requireNotNull(managed.authSurface.accountPool).view(null).accounts.associateBy { it.label }
        assertEquals(resetEpochSeconds, migrated.getValue("acct-a").quota.holds.wholeLogin?.resetEpochSeconds)
        assertEquals(block, migrated.getValue("acct-b").credential.ownerBlock)
        assertFalse(Files.exists(statePaths.stateDir.resolve("anthropic-acct-a-provider-hold.json")))
        assertFalse(Files.exists(statePaths.stateDir.resolve("anthropic-acct-b-provider-hold-login.json")))

        // Reopen a fresh daemon factory over the same stateDir: migrated plan hold and owner block must survive reboot.
        val restartedRig = rig(statePaths, backgroundScope, declared)
        val restartedHead = restartedRig.factory.assembleHead(headCtx, controlPort = 3098)
        val afterRestart =
            requireNotNull(restartedHead.authSurface.accountPool).view(null).accounts.associateBy { it.label }
        assertEquals(resetEpochSeconds, afterRestart.getValue("acct-a").quota.holds.wholeLogin?.resetEpochSeconds)
        assertFalse(afterRestart.getValue("acct-a").available, "migrated plan hold survives daemon restart")
        assertFalse(afterRestart.getValue("acct-b").available, "migrated owner block survives daemon restart")
        assertEquals(block, afterRestart.getValue("acct-b").credential.ownerBlock)
        assertNotNull(afterRestart.getValue("acct-b").credential.excludedUntilEpochMillis)
    }

    @Test
    fun `single-login oauth routes share credential-scoped holds and keep unscoped or unpersisted sources intact`(
        @TempDir tmp: Path,
    ) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("single-oauth"))
        val authFile = statePaths.stateDir.resolve("auth.json")
        Files.createDirectories(statePaths.stateDir)
        Files.writeString(authFile, "{}")

        val holds = ProviderHoldFiles(statePaths, LogSink { })
        val resetEpochSeconds = System.currentTimeMillis() / 1_000L + 3_600L
        val hold = ProviderHold(resetEpochSeconds, PlanLimit(SEVEN_DAY_CLAIM, resetEpochSeconds))
        // Pre-seed both an unscoped legacy file (must stay untouched) and a credential-scoped hold (must migrate).
        holds.forHead("codex").save(hold)
        requireNotNull(holds.forHead("codex").forCredential(TEST_CREDENTIAL_KEY)).save(hold)

        val rig = rig(statePaths, backgroundScope)
        val headCtx = codexBuild(statePaths, key = "claudex", authFile = authFile)
        val routeCtx = codexBuild(statePaths, key = "codex", authFile = authFile)

        val managed = rig.factory.assembleHead(headCtx, controlPort = 3098)
        rig.factory.assembleRoute(routeCtx, layer = "providers.codex", registers = true)

        assertTrue(managed.head.providerResetForMs() > 0L, "migrated credential-scoped hold is visible on legacy head")
        assertTrue(Files.exists(statePaths.stateDir.resolve("codex-provider-hold.json")), "unscoped legacy file kept")
        assertFalse(
            Files.exists(statePaths.stateDir.resolve("codex-provider-hold-$TEST_CREDENTIAL_KEY.json")),
            "migrated credential-scoped source file deleted after destination persisted",
        )
        assertTrue(Files.exists(statePaths.stateDir.resolve("claudex-provider-hold-$TEST_CREDENTIAL_KEY.json")))

        // When destination already holds the same reset without rateLimitReply and rejects writes, keep source intact.
        val source = requireNotNull(holds.forHead("failed-src").forCredential(TEST_CREDENTIAL_KEY))
        RateLimitCooldown(ProcessElapsedNow(), store = source).apply {
            adoptHold(hold)
            rateLimitReply = NativeReply("{}", emptyMap())
        }
        val staleDest = object : ProviderHoldStore {
            override fun load(): ProviderHold = hold
            override fun save(hold: ProviderHold) = Unit
            override fun forCredential(key: String): ProviderHoldStore = this
        }
        UpstreamCooldowns(ProcessElapsedNow(), store = staleDest).adoptFrom(holds.forHead("failed-src"))
        assertNotNull(source.load(), "source hold must stay intact when destination did not persist rateLimitReply")
    }

    @Test
    fun `restarting a legacy head preserves shared pool state for both different-key and same-key gateway routes`(
        @TempDir tmp: Path,
    ) = runTest {
        assertRestartPreservesSharedState(backgroundScope, tmp.resolve("diff-key"), "claudex", "codex")
        assertRestartPreservesSharedState(backgroundScope, tmp.resolve("same-key"), "codex", "codex")
    }

    @Test
    fun `second legacy head sharing an oauth pool does not overwrite first head's saved pins or order`(
        @TempDir tmp: Path,
    ) = runTest {
        val statePaths = StatePaths(baseOverride = tmp.resolve("preserve-policy"))
        val authFile = statePaths.stateDir.resolve("auth.json")
        Files.createDirectories(statePaths.stateDir)
        Files.writeString(authFile, "{}")
        OAuthAccountFiles().writeLabeled(AuthKind.ChatgptOAuth, authFile, "backup", buildJsonObject {})

        val rig = rig(statePaths, backgroundScope)
        val firstCtx = codexBuild(statePaths, key = "claudex-a", authFile = authFile, port = 0)
        val secondCtx = codexBuild(statePaths, key = "claudex-b", authFile = authFile, port = 0)

        val firstHead = rig.factory.assembleHead(firstCtx, controlPort = 3098)
        val firstPool = requireNotNull(firstHead.authSurface.accountPool)
        assertTrue((firstPool as HeadAccountOrderSource).setOrder(listOf("backup", "primary")))
        assertTrue((firstPool as HeadAccountPinSource).pin("backup", null))

        val secondHead = rig.factory.assembleHead(secondCtx, controlPort = 3100)
        val secondPool = requireNotNull(secondHead.authSurface.accountPool)

        assertEquals(listOf("backup", "primary"), firstPool.order(), "second head must not wipe first head's order")
        assertEquals("backup", firstPool.view(null).pinnedLabel, "second head must not wipe first head's pin")
        assertEquals(
            listOf("backup", "primary"),
            (secondPool as HeadAccountOrderSource).order(),
            "second head observes owner's live order",
        )
        assertEquals("backup", secondPool.view(null).pinnedLabel, "second head observes owner's live pin")
    }

    private suspend fun assertRestartPreservesSharedState(
        scope: CoroutineScope,
        root: Path,
        headKey: String,
        routeKey: String,
    ) {
        val statePaths = StatePaths(baseOverride = root)
        val authFile = statePaths.stateDir.resolve("auth.json")
        Files.createDirectories(statePaths.stateDir)
        Files.writeString(authFile, "{}")
        OAuthAccountFiles().writeLabeled(AuthKind.ChatgptOAuth, authFile, "backup", buildJsonObject {})

        val rig = rig(statePaths, scope)
        val headCtx = codexBuild(statePaths, key = headKey, authFile = authFile, port = 0)
        val routeCtx = codexBuild(statePaths, key = routeKey, authFile = authFile, port = 0)

        val managed = rig.factory.assembleHead(headCtx, controlPort = 3098)
        val route = rig.factory.assembleRoute(routeCtx, layer = "providers.codex", registers = true)
        val pool = requireNotNull(route.books.stores.accounts.pool)

        val chosen = pool.select("sess-sticky") as Selection.Chosen
        val blockedLabel = chosen.account.account.label
        val block = AccountBlock(OwnerStep.ALLOW_SUBSCRIPTION_ACCESS, "chatgpt.com")
        chosen.account.markCredentialUnavailable(block)
        val resetEpochSeconds = System.currentTimeMillis() / 1_000L + 3_600L
        chosen.account.account.cooldown.planHold.hold(PlanLimit(SEVEN_DAY_CLAIM, resetEpochSeconds)) {}

        managed.head.start()
        try {
            managed.head.restart()

            assertEquals(
                blockedLabel,
                pool.view("sess-sticky").selectedLabel,
                "stickiness survives ($headKey/$routeKey)",
            )
            val blockedView = pool.view(null).accounts.single { it.label == blockedLabel }
            assertEquals(block, blockedView.credential.block, "owner block survives ($headKey/$routeKey)")
            assertNotNull(blockedView.credential.excludedUntilEpochMillis, "auth exclusion survives")
            assertEquals(resetEpochSeconds, blockedView.quota.holds.wholeLogin?.resetEpochSeconds, "plan hold survives")
        } finally {
            managed.head.stop()
        }
    }

    private suspend fun restartHead(managed: splice.app.control.ManagedHead) {
        managed.head.start()
        try {
            managed.head.restart()
        } finally {
            managed.head.stop()
        }
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

    private fun claudeBuild(statePaths: StatePaths, key: String): ProviderBuild {
        val model = ModelEntry(id = "claude-test-model", contextWindow = 200_000)
        return ProviderBuild(
            key = key,
            head = HeadConfig(
                provider = "anthropic",
                port = 0,
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

    private fun codexBuild(statePaths: StatePaths, key: String, authFile: Path, port: Int = 3099): ProviderBuild {
        val model = ModelEntry(id = "codex-test-model", contextWindow = 400_000)
        return ProviderBuild(
            key = key,
            head = HeadConfig(
                provider = "codex",
                port = port,
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
            cfg = ConfigService(statePaths, headOverrides = mapOf("quotaPoll" to "off")).getConfig(),
        )
    }
}
