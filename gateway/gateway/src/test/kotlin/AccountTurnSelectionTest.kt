import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import mock.MockChatGptUpstream
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.perf.PerfStats
import splice.gateway.usage.QuotaTracker
import splice.gateway.usage.UsageStore
import splice.provider.codex.CodexProvider
import splice.spi.AccountNow
import splice.spi.AccountPool
import splice.spi.AccountQuotaSource
import splice.spi.AccountResetText
import splice.spi.InflightGate
import splice.spi.PoolAccount
import splice.spi.ProcessElapsedNow
import splice.spi.ProviderTuning
import splice.spi.RateLimitCooldown
import splice.spi.UpstreamClient
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

class AccountTurnSelectionTest {
    @Test
    fun `the next turn switches credentials and quota while the session routing stays intact`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            val first = rig.messages().also { it.bodyAsText() }
            rig.exhaustPrimary()
            val second = rig.messages().also { it.bodyAsText() }

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals(listOf("Bearer primary-token", "Bearer backup-token"), rig.authHeaders())
            assertEquals(listOf("primary-id", "backup-id"), rig.accountHeaders())
            assertEquals("0.1000", first.headers["anthropic-ratelimit-unified-5h-utilization"])
            assertEquals("0.3700", second.headers["anthropic-ratelimit-unified-5h-utilization"])
            assertEquals("backup", rig.poolView().selectedLabel)
            assertEquals("5-hour quota exhausted", rig.poolView().lastSwitch?.reason)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `terminal 401 keeps the current turn on one account and evicts it from the next turn`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            rig.messages(scenario = "authfail").also { it.bodyAsText() }
            val next = rig.messages(scenario = "basic").also { it.bodyAsText() }

            assertEquals(HttpStatusCode.OK, next.status)
            assertEquals(
                listOf("Bearer primary-token", "Bearer primary-token", "Bearer backup-token"),
                rig.authHeaders(),
            )
            assertEquals(listOf("primary-id", "primary-id", "backup-id"), rig.accountHeaders())
            assertEquals("backup", rig.poolView().selectedLabel)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `failed refresh evicts the selected account only after its turn fails`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            rig.failPrimaryRefresh()
            rig.messages(scenario = "authfail").also { it.bodyAsText() }
            val next = rig.messages().also { it.bodyAsText() }

            assertEquals(HttpStatusCode.OK, next.status)
            assertEquals(listOf("Bearer primary-token", "Bearer backup-token"), rig.authHeaders())
            assertEquals(listOf("primary-id", "backup-id"), rig.accountHeaders())
            assertEquals("backup", rig.poolView().selectedLabel)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a nonclean normal return releases the probe without clearing its failure count`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            rig.holdPrimaryUntilProbe()
            rig.messages(sessionId = "nonclean", scenario = "failed").also { it.bodyAsText() }
            rig.failPrimaryRefresh()
            rig.messages(sessionId = "terminal", scenario = "authfail").also { it.bodyAsText() }

            val primary = rig.poolView().accounts.single { it.primary }
            assertEquals("terminal_401", primary.authExclusionReason)
            assertEquals(600_000L, checkNotNull(primary.authExcludedUntilEpochMillis) - rig.accountNowMs())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `deleted selected credential fails its turn and the next turn uses the backup`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            rig.deletePrimaryCredential()
            rig.messages().also { it.bodyAsText() }
            val next = rig.messages().also { it.bodyAsText() }

            assertEquals(HttpStatusCode.OK, next.status)
            assertEquals(listOf("Bearer backup-token"), rig.authHeaders())
            assertEquals(listOf("backup-id"), rig.accountHeaders())
            assertEquals("backup", rig.poolView().selectedLabel)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `an empty session header is treated as no session`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            val response = rig.messages(sessionId = "").also { it.bodyAsText() }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf("Bearer primary-token"), rig.authHeaders())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `all exhausted records a local refusal with literal IMF-fixdate`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            val resetEpochSeconds = 2_077_951_777L
            val reset = Instant.ofEpochSecond(rig.exhaustAll(resetEpochSeconds)).toString()

            val response = rig.messages().also { body ->
                assertTrue(body.bodyAsText().contains("all OAuth accounts are exhausted; earliest reset is $reset"))
            }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("Tue, 06 Nov 2035 08:49:37 GMT", response.headers["Retry-After"])
            assertEquals(1L, rig.localErrors())
            assertEquals(0L, rig.providerErrors())
            assertTrue(rig.authHeaders().isEmpty(), "an exhausted pool must not contact upstream")
            assertTrue(rig.logs().any { it.contains("all-accounts-exhausted") && it.contains("earliest_reset=$reset") })
            assertTrue(rig.perfText().contains("\"outcome\":\"error:all-accounts-exhausted\""))
        } finally {
            rig.close()
        }
    }

    @Test
    fun `oversized exhausted reset stays rate limited with a formatter-safe Retry-After`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            val oversizedReset = Long.MAX_VALUE - 10_000_000_000_000_000L
            rig.exhaustAll(oversizedReset)

            val response = rig.messages()

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("Fri, 31 Dec 9999 23:59:59 GMT", response.headers["Retry-After"])
            assertTrue(response.bodyAsText().contains("earliest reset is 9999-12-31T23:59:59Z"))
            assertTrue(rig.authHeaders().isEmpty(), "an exhausted pool must not contact upstream")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `reset text clamps below the four digit wire date range`() {
        assertEquals("0000-01-01T00:00:00Z", AccountResetText.format(Long.MIN_VALUE))
    }

    @Test
    fun `all exhausted without reset evidence does not invent Retry-After`() = runTest {
        val rig = AccountTurnRig(credentialPresent = false)
        try {
            rig.start()

            val response = rig.messages().also { it.bodyAsText() }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertNull(response.headers["Retry-After"])
            assertEquals(1L, rig.localErrors())
            assertEquals(0L, rig.providerErrors())
            assertTrue(rig.authHeaders().isEmpty())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `head restart clears the cooldown authority used by pooled turns`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            rig.markPrimaryUnavailable()
            assertEquals(HttpStatusCode.OK, rig.messages().also { it.bodyAsText() }.status)
            rig.restart()
            assertEquals(HttpStatusCode.OK, rig.messages().also { it.bodyAsText() }.status)

            assertEquals(listOf("Bearer backup-token", "Bearer primary-token"), rig.authHeaders())
        } finally {
            rig.close()
        }
    }
}

private class AccountTurnRig(private val credentialPresent: Boolean = true) {
    private val tmp = Files.createTempDirectory("account-turn")
    private val mock = MockChatGptUpstream()
    private val port = freshPort()
    private val primaryQuota = QuotaTracker(tmp.resolve("primary-quota.json"))
    private val backupQuota = QuotaTracker(tmp.resolve("backup-quota.json"))
    private val perfFile = tmp.resolve("perf.jsonl")
    private val perfStats = PerfStats(perfFile)
    private val logs = CopyOnWriteArrayList<String>()
    private val primaryAuth = AccountAuth("primary-token", "primary-id")
    private val backupAuth = AccountAuth("backup-token", "backup-id")
    private val primaryCooldown = RateLimitCooldown(ProcessElapsedNow())
    private val backupCooldown = RateLimitCooldown(ProcessElapsedNow())
    private val accountNow = AtomicLong(System.currentTimeMillis())
    private val pool = AccountPool(
        listOf(
            account("primary", primary = true, primaryAuth, primaryQuota, primaryCooldown),
            account("backup", primary = false, backupAuth, backupQuota, backupCooldown),
        ),
        AccountNow(accountNow::get),
    )
    private val head = HeadServer(
        provider = provider(),
        listenPort = port,
        deps = HeadDeps(
            upstream = UpstreamClient(firstByteTimeoutMs = 5_000L, totalTimeoutMs = 30_000L, maxRetries = 2),
            inferenceToken = "test-inference-token",
            gate = InflightGate({ 0 }),
            shadow = ShadowClassifier(log = {}),
            compactStats = CompactStats(tmp.resolve("compact.jsonl")),
            usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
            perfStats = perfStats,
            log = logs::add,
            maxRequestBytes = 2_048,
            accountPool = pool,
            accountQuotas = mapOf("primary" to primaryQuota, "backup" to backupQuota),
        ),
    )
    private val client = HttpClient(CIO) {
        defaultRequest { bearerAuth("test-inference-token") }
    }

    init {
        primaryQuota.record(quota(10.0))
        backupQuota.record(quota(37.0))
    }

    suspend fun start() {
        head.start()
        awaitListening(port)
    }

    suspend fun messages(sessionId: String = SESSION, scenario: String = "basic"): HttpResponse =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", sessionId)
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:$scenario",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }

    fun exhaustPrimary() {
        primaryQuota.record(quota(100.0))
    }

    fun exhaustAll(resetEpochSeconds: Long): Long {
        val exhausted = quota(100.0, resetEpochSeconds)
        primaryQuota.record(exhausted)
        backupQuota.record(exhausted)
        return checkNotNull(checkNotNull(exhausted.fiveHour).resetsAt)
    }

    fun markPrimaryUnavailable() {
        primaryCooldown.markUnavailable(60_000L)
    }

    fun holdPrimaryUntilProbe() {
        pool.select("hold-seed").markCredentialUnavailable()
        accountNow.addAndGet(300_000L)
    }

    fun accountNowMs(): Long = accountNow.get()

    fun failPrimaryRefresh() {
        primaryAuth.refreshSucceeds = false
    }

    fun deletePrimaryCredential() {
        primaryAuth.present = false
    }

    suspend fun restart() {
        head.restart()
        awaitListening(port)
    }

    fun authHeaders(): List<String?> = mock.upstreamAuths.map { it.second }

    fun accountHeaders(): List<String?> = mock.upstreamAccountIds.map { it.second }

    fun poolView() = pool.view(SESSION)

    fun logs(): List<String> = logs.toList()

    fun localErrors(): Long = head.healthSnapshot().localOriginErrors

    fun providerErrors(): Long = head.healthSnapshot().providerErrors

    fun perfText(): String {
        perfStats.tailNumeric()
        return Files.readString(perfFile)
    }

    suspend fun close() {
        head.stop()
        client.close()
        mock.stop()
    }

    private fun account(
        label: String,
        primary: Boolean,
        auth: RefreshableAuthProvider,
        tracker: QuotaTracker,
        cooldown: RateLimitCooldown,
    ): PoolAccount = PoolAccount(
        label = label,
        primary = primary,
        auth = auth,
        quota = AccountQuotaSource(tracker::snapshot),
        cooldown = cooldown,
        credentialPresent = credentialPresent,
    )

    private fun quota(
        used: Double,
        resetEpochSeconds: Long = System.currentTimeMillis() / 1_000L + 3_600L,
    ): QuotaSnapshot = QuotaSnapshot(
        fiveHour = QuotaWindow(used, resetEpochSeconds, 18_000L),
        sevenDay = QuotaWindow(20.0, resetEpochSeconds + 82_800L, 604_800L),
        plan = "plus",
    )

    private fun provider(): CodexProvider {
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
            defaultContextWindow = 272_000,
        )
        return CodexProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = primaryAuth,
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        )
    }
}

private class AccountAuth(private val token: String, private val accountId: String) : RefreshableAuthProvider {
    @Volatile
    var present: Boolean = true

    @Volatile
    var refreshSucceeds: Boolean = true

    override suspend fun credentials(): Credentials? = if (present) Credentials.Bearer(token, accountId) else null
    override suspend fun refresh(): Credentials? = if (refreshSucceeds) credentials() else null
    override suspend fun describe(): AuthDescription = AuthDescription(present, "test")
}

private const val SESSION = "session-1"
