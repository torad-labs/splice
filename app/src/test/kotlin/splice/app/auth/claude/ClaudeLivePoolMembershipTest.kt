// actual sign-in landing and removal must change a booted Claude head's next credential choice.
package splice.app.auth.claude

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.accounts.claude.ClaudeAccountIdentity
import splice.accounts.pool.HeadAccountPinSource
import splice.accounts.signin.AccountMutation
import splice.accounts.signin.ConsoleAccounts
import splice.accounts.signin.HeadRestart
import splice.accounts.signin.LoginStart
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.app.TokenUrlRefreshCall
import splice.app.auth.SignInPlanner
import splice.app.control.ManagedHead
import splice.app.head.HeadServerFactory
import splice.app.head.HeadServing
import splice.app.head.LaunchSpecFactory
import splice.app.head.ManagedHeadFactory
import splice.app.head.QuotaPollSeams
import splice.app.head.StartQuotaPoller
import splice.app.probe.PlaygroundProviders
import splice.app.probe.UpstreamPlaygroundProbe
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderAssembly
import splice.app.provider.ProviderBuild
import splice.client.wrap.ClaudeToRun
import splice.client.wrap.WrapStateRead
import splice.core.auth.CredentialKey
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.turn.WatchdogBudget
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.diagnostics.playground.PlaygroundHead
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.usage.quota.QuotaCadence
import splice.usage.quota.QuotaClocks
import splice.usage.quota.QuotaPoller
import splice.usage.quota.QuotaProbe
import splice.usage.quota.QuotaSnapshotSink
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val HEAD = "claude-splice"
private const val TIMEOUT_MS = 5_000L

class ClaudeLivePoolMembershipTest {
    @TempDir
    lateinit var root: Path

    private val paths by lazy { StatePaths(baseOverride = root.resolve("state")) }
    private val changes = ClaudePoolChanges()
    private val folders by lazy { ClaudeAccountFolders(paths.stateDir, changes = changes) }

    private fun write(folder: Path, label: String) {
        Files.createDirectories(folder)
        val token = "synthetic-$label"
        Files.writeString(
            folder.resolve(".credentials.json"),
            """{"claudeAiOauth":{"accessToken":"$token","refreshToken":"synthetic","expiresAt":4102444800000}}""",
        )
        Files.writeString(folder.resolve(".claude.json"), """{"oauthAccount":{"accountUuid":"copied-stale"}}""")
        val key = requireNotNull(CredentialKey.fromHeaders(mapOf("Authorization" to "Bearer $token")))
        ClaudeCredentialProfiles(paths.stateDir, {}).observed(key, ClaudeAccountIdentity("account-$label", null))
    }

    private fun added(label: String) {
        val pending = folders.pending(HEAD, label)
        write(pending.directory, label)
        assertTrue(folders.land(pending) is ClaudeAccountLanding.Added)
    }

    private fun fixture(
        scope: CoroutineScope,
        registry: PlaygroundProviders,
        polling: StartQuotaPoller? = null,
    ): ManagedHead {
        val config = ConfigService(paths, headOverrides = mapOf("quotaPoll" to if (polling == null) "off" else "on"))
        val key = MgmtKey(paths)
        val planner = SignInPlanner()
        val assembly = ProviderAssembly(
            paths,
            scope,
            {},
            TokenUrlRefreshCall { _, _ -> error("no rotation") },
            claudeChanges = changes,
        )
        val factory = ManagedHeadFactory(
            statePaths = paths,
            providerAssembly = assembly,
            serving = HeadServing(HeadServerFactory(config, key, {}), registry),
            launchSpecFactory = LaunchSpecFactory(Topology(), planner, key, HeadBuildInputs(config, planner)),
            log = {},
            quotaSeams = QuotaPollSeams(
                scope,
                {},
                startQuotaPoller = polling ?: StartQuotaPoller { _, _, _, _ -> null },
            ),
        )
        val model = ModelEntry("synthetic-model", contextWindow = 4_000)
        val build = ProviderBuild(
            key = HEAD,
            head = HeadConfig("synthetic", 0, "synthetic--", model.id),
            providerCfg = ProviderConfig(
                dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                baseUrl = "https://synthetic.example",
                auth = AuthConfig("client"),
            ),
            catalog = ModelCatalog("synthetic--", listOf(model), defaultContextWindow = model.contextWindow),
            watchdog = WatchdogBudget(60.seconds, 60.seconds, 600.seconds),
            cfg = config.getConfig(HEAD),
            loginCommand = "synthetic login",
        )
        return factory.assembleHead(build, 0)
    }

    private fun signIn(scope: CoroutineScope, child: NativeLoginTestProcess): ClaudeAccountSignIn =
        ClaudeAccountSignIn(
            folders,
            NativeClaudeAuth(
                WrapStateRead { ClaudeToRun.Wrapped("fixture-native") },
                emptyMap(),
                ProcessDispatchers().io(),
                NativeAuthStart { builder ->
                    write(Path.of(requireNotNull(builder.environment()["CLAUDE_CONFIG_DIR"])), "office")
                    child
                },
            ),
            scope,
        )

    @Test
    fun `a published login joins quota polling and removal stops only its borrowed poller`() = runBlocking {
        added("work")
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val jobs = CopyOnWriteArrayList<Job>()
        val tick = CompletableDeferred<Boolean>()
        val polling = StartQuotaPoller { head, _, tracker, _ ->
            QuotaPoller(
                scope,
                head,
                QuotaProbe { null },
                QuotaSnapshotSink(tracker::record),
                {},
                cadence = QuotaCadence(ticker = Ticker { tick.await() }),
                clocks = QuotaClocks(wall = WallClock { 1_000_000L }, elapsed = ElapsedClock { 0L }),
            ).also { jobs.add(it.start()) }
        }
        val head = fixture(scope, PlaygroundProviders(), polling)
        try {
            assertEquals(2, jobs.size)
            land(scope, head)
            assertEquals(3, jobs.size, "the landed member must get its own poller")
            assertTrue(jobs.all { it.isActive })
            assertEquals(ClaudeAccountRemoval.Removed, folders.remove(HEAD, "office"))
            assertTrue(jobs.last().isCancelled, "removal must stop the removed member's poller")
            assertTrue(jobs.dropLast(1).all { it.isActive }, "surviving members must keep their poller owners")
        } finally {
            head.head.stop()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private suspend fun sent(registry: PlaygroundProviders, head: ManagedHead): String? {
        var authorization: String? = null
        HttpClient(
            MockEngine {
                authorization = it.headers["Authorization"]
                respond("{}", HttpStatusCode.OK)
            },
        ).use { client ->
            UpstreamPlaygroundProbe(registry, client).run(PlaygroundHead(HEAD, head.auth), "synthetic", null)
        }
        return authorization
    }

    @Test
    fun `a login landed after boot is selectable by the very next product send without a daemon restart`() = runBlocking {
        added("work")
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val registry = PlaygroundProviders()
        val head = fixture(scope, registry)
        val child = NativeLoginTestProcess("https://claude.ai/oauth/authorize?synthetic=allowed\n")
        try {
            head.head.start()
            val pool = requireNotNull(head.accountPool) as HeadAccountPinSource
            assertTrue(pool.pin("work"))
            assertEquals("Bearer synthetic-work", sent(registry, head))
            val owner = signIn(scope, child)
            val status = owner.start(HEAD, "office", HeadRestart { head.head.restart() })
            withTimeout(TIMEOUT_MS) { while (owner.poll(status.id)?.state == LoginState.STARTING) yield() }
            child.finish(0)
            withTimeout(TIMEOUT_MS) { while (owner.poll(status.id)?.state == LoginState.WAITING) yield() }
            assertEquals(LoginState.SIGNED_IN, owner.poll(status.id)?.state)
            assertTrue(pool.pin("office"), "the landed credential must enter the running selector")
            assertEquals("Bearer synthetic-office", sent(registry, head))
        } finally {
            head.head.stop()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `selector refresh cannot restore a login while its folder is being removed`() = runBlocking {
        added("work")
        added("office")
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val head = fixture(scope, PlaygroundProviders())
        try {
            val pool = requireNotNull(head.accountPool)
            val pin = pool as HeadAccountPinSource
            assertTrue(pin.pin("work"))
            changes.withdraw(HEAD, "work")
            assertFalse(pool.view(null).accounts.any { it.label == "work" })
            assertFalse(pin.pin("work"), "refresh must not reopen the still-existing folder during removal")
            assertEquals(ClaudeAccountRemoval.Removed, folders.remove(HEAD, "office"))
            assertFalse(pool.view(null).accounts.any { it.label == "work" }, "another removal cannot complete this one")
            changes.publish(HEAD)
            assertFalse(
                pool.view(null).accounts.any { it.label == "work" },
                "another publication is not removal completion",
            )
            assertFalse(pin.pin("work"))
            assertEquals(ClaudeAccountRemoval.Removed, folders.remove(HEAD, "work"))
            assertFalse(pool.view(null).accounts.any { it.label == "work" })
        } finally {
            head.head.stop()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `a removed login's delayed refusal never holds the replacement at the same label`() = runBlocking {
        added("work")
        added("office")
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val registry = PlaygroundProviders()
        val head = fixture(scope, registry)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            head.head.start()
            assertTrue((requireNotNull(head.accountPool) as HeadAccountPinSource).pin("office"))
            HttpClient(
                MockEngine {
                    entered.complete(Unit)
                    release.await()
                    respond(
                        "{}",
                        HttpStatusCode.TooManyRequests,
                        headersOf(
                            "anthropic-ratelimit-unified-status" to listOf("rejected"),
                            "anthropic-ratelimit-unified-representative-claim" to listOf("five_hour"),
                            "anthropic-ratelimit-unified-reset" to listOf("4102444800"),
                        ),
                    )
                },
            ).use { client ->
                val pending = scope.async {
                    UpstreamPlaygroundProbe(registry, client).run(PlaygroundHead(HEAD, head.auth), "held reply", null)
                }
                withTimeout(TIMEOUT_MS) { entered.await() }
                assertEquals(ClaudeAccountRemoval.Removed, folders.remove(HEAD, "office"))
                val replacement = folders.pending(HEAD, "office")
                write(replacement.directory, "replacement-office")
                assertTrue(folders.land(replacement) is ClaudeAccountLanding.Added)
                release.complete(Unit)
                withTimeout(TIMEOUT_MS) { pending.await() }
                val current = requireNotNull(head.accountPool).view(null).accounts.single { it.label == "office" }
                assertTrue(current.available, "the old credential's refusal cannot hold a newly bound login")
            }
        } finally {
            release.complete(Unit)
            head.head.stop()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private suspend fun land(scope: CoroutineScope, head: ManagedHead) {
        val child = NativeLoginTestProcess("https://claude.ai/oauth/authorize?synthetic=allowed\n")
        val owner = signIn(scope, child)
        val status = owner.start(HEAD, "office", HeadRestart { head.head.restart() })
        withTimeout(TIMEOUT_MS) { while (owner.poll(status.id)?.state == LoginState.STARTING) yield() }
        child.finish(0)
        withTimeout(TIMEOUT_MS) { while (owner.poll(status.id)?.state == LoginState.WAITING) yield() }
        assertEquals(LoginState.SIGNED_IN, owner.poll(status.id)?.state)
    }

    @Test
    fun `the first stored login activates a booted caller and removing it restores the unpooled path`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val registry = PlaygroundProviders()
        val head = fixture(scope, registry)
        try {
            head.head.start()
            val source = requireNotNull(head.accountPool)
            assertFalse(source.active)
            assertEquals(null, registry.target(HEAD)?.login)
            land(scope, head)
            assertTrue(source.active)
            val pool = source as HeadAccountPinSource
            assertTrue(pool.pin("office"))
            assertEquals("Bearer synthetic-office", sent(registry, head))
            assertEquals(ClaudeAccountRemoval.Removed, folders.remove(HEAD, "office"))
            assertFalse(source.active)
            assertEquals(null, registry.target(HEAD)?.login)
            assertFalse(pool.pin("office"))
        } finally {
            head.head.stop()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `a removed in-flight login keeps its captured credential while the next send uses a surviving member`() =
        runBlocking {
            added("work")
            added("office")
            val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
            val registry = PlaygroundProviders()
            val head = fixture(scope, registry)
            val entered = CompletableDeferred<String?>()
            val release = CompletableDeferred<Unit>()
            try {
                head.head.start()
                val pool = requireNotNull(head.accountPool) as HeadAccountPinSource
                assertTrue(pool.pin("office"))
                HttpClient(
                    MockEngine {
                        entered.complete(it.headers["Authorization"])
                        release.await()
                        respond("{}", HttpStatusCode.OK)
                    },
                ).use { client ->
                    val pending = scope.async {
                        UpstreamPlaygroundProbe(registry, client).run(
                            PlaygroundHead(HEAD, head.auth),
                            "synthetic held send",
                            null,
                        )
                    }
                    assertEquals("Bearer synthetic-office", withTimeout(TIMEOUT_MS) { entered.await() })
                    assertEquals(ClaudeAccountRemoval.Removed, folders.remove(HEAD, "office"))
                    assertFalse(pool.pin("office"))
                    assertTrue(pool.pin("work"))
                    assertEquals("Bearer synthetic-work", sent(registry, head))
                    release.complete(Unit)
                    withTimeout(TIMEOUT_MS) { pending.await() }
                    assertFalse(Files.exists(paths.stateDir.resolve("claude-accounts").resolve(HEAD).resolve("office")))
                }
            } finally {
                release.complete(Unit)
                head.head.stop()
                scope.coroutineContext[Job]?.cancelAndJoin()
            }
        }

    @Test
    fun `removing an account withdraws it from the booted pool before another product send`() = runBlocking {
        added("work")
        added("office")
        val scope = CoroutineScope(SupervisorJob() + ProcessDispatchers().io())
        val registry = PlaygroundProviders()
        val head = fixture(scope, registry)
        try {
            head.head.start()
            val pool = requireNotNull(head.accountPool) as HeadAccountPinSource
            assertTrue(pool.pin("office"))
            assertEquals("Bearer synthetic-office", sent(registry, head))
            val arm = ClaudeAccountsArm(
                object : ConsoleAccounts {
                    override suspend fun startLogin(
                        headKey: String,
                        label: String?,
                        restart: HeadRestart,
                    ): LoginStart = LoginStart.UnsupportedAuthKind("client")
                    override fun pollLogin(id: String): LoginStatus? = null
                    override suspend fun removeAccount(headKey: String, label: String): AccountMutation =
                        AccountMutation.UnsupportedAuthKind("client")
                    override suspend fun relabelAccount(
                        headKey: String,
                        label: String,
                        newLabel: String,
                    ): AccountMutation = AccountMutation.UnsupportedAuthKind("client")
                },
                ClaudeAccountsSource { ClaudeAccountsPort(signIn(scope, NativeLoginTestProcess()), folders) },
            )
            assertEquals(AccountMutation.Ok, arm.removeAccount(HEAD, "office"))
            assertFalse(pool.pin("office"), "a removed credential cannot remain an addressable pool member")
            assertTrue(pool.pin("work"))
            assertEquals("Bearer synthetic-work", sent(registry, head))
        } finally {
            head.head.stop()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
