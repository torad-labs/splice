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
import splice.spi.InflightGate
import splice.spi.PoolAccount
import splice.spi.ProcessElapsedNow
import splice.spi.ProviderTuning
import splice.spi.RateLimitCooldown
import splice.spi.UpstreamClient
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
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
    fun `all exhausted records and logs the refusal before responding`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            val reset = Instant.ofEpochSecond(rig.exhaustAll()).toString()

            val response = rig.messages().also { body ->
                assertTrue(body.bodyAsText().contains("all OAuth accounts are exhausted; earliest reset is $reset"))
            }

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertTrue(rig.authHeaders().isEmpty(), "an exhausted pool must not contact upstream")
            assertTrue(rig.logs().any { it.contains("all-accounts-exhausted") && it.contains("earliest_reset=$reset") })
            assertTrue(rig.perfText().contains("\"outcome\":\"error:all-accounts-exhausted\""))
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

private class AccountTurnRig {
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
    private val pool = AccountPool(
        listOf(
            account("primary", primary = true, primaryAuth, primaryQuota, primaryCooldown),
            account("backup", primary = false, backupAuth, backupQuota, backupCooldown),
        ),
        AccountNow(System::currentTimeMillis),
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

    suspend fun messages(sessionId: String = SESSION): HttpResponse =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", sessionId)
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:basic",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }

    fun exhaustPrimary() {
        primaryQuota.record(quota(100.0))
    }

    fun exhaustAll(): Long {
        val exhausted = quota(100.0)
        primaryQuota.record(exhausted)
        backupQuota.record(exhausted)
        return checkNotNull(checkNotNull(exhausted.fiveHour).resetsAt)
    }

    fun markPrimaryUnavailable() {
        primaryCooldown.markUnavailable(60_000L)
    }

    suspend fun restart() {
        head.restart()
        awaitListening(port)
    }

    fun authHeaders(): List<String?> = mock.upstreamAuths.map { it.second }

    fun accountHeaders(): List<String?> = mock.upstreamAccountIds.map { it.second }

    fun poolView() = pool.view(SESSION)

    fun logs(): List<String> = logs.toList()

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
    )

    private fun quota(used: Double): QuotaSnapshot = QuotaSnapshot(
        fiveHour = QuotaWindow(used, System.currentTimeMillis() / 1_000L + 3_600L, 18_000L),
        sevenDay = QuotaWindow(20.0, System.currentTimeMillis() / 1_000L + 86_400L, 604_800L),
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
    override suspend fun credentials(): Credentials = Credentials.Bearer(token, accountId)
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
}

private const val SESSION = "session-1"
