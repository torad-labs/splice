package splice.head.turn

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readLine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.usage.QuotaSnapshot
import splice.core.usage.QuotaWindow
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.MockChatGptUpstream
import splice.head.awaitListening
import splice.head.headDeps
import splice.head.headStores
import splice.head.perf.PerfStats
import splice.head.quotaFor
import splice.head.usage.QuotaTracker
import splice.provider.codex.CodexProvider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.AccountResetText
import splice.upstream.credentials.PoolAccount
import splice.upstream.credentials.Selection
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.transport.UpstreamClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

class AccountTurnSelectionTest(@param:TempDir private val root: Path) {
    @Test
    fun `the next turn switches credentials and quota while the session routing stays intact`() = runTest {
        val rig = AccountTurnRig(root)
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
            assertEquals("quota usage reading full", rig.poolView().lastSwitch?.reason)
        } finally {
            rig.close()
        }
    }

    // Section 8: a login its provider refuses again after its renewal moves the turn to a free login before any byte.
    // Here every login is refused, so the turn ends once each was renewed once, and each leaves the pool: the next turn
    // finds no free login and is answered as an exhausted pool is.
    @Test
    fun `a 401 its renewal cannot cure moves the turn to the backup before any byte`() = runTest {
        val rig = AccountTurnRig(root)
        try {
            rig.start()
            rig.messages(scenario = "authfail").also { it.bodyAsText() }
            val next = rig.messages(scenario = "basic").also { it.bodyAsText() }

            assertEquals(
                listOf("Bearer primary-token", "Bearer primary-token", "Bearer backup-token", "Bearer backup-token"),
                rig.authHeaders(),
            )
            assertEquals(listOf("primary-id", "primary-id", "backup-id", "backup-id"), rig.accountHeaders())
            assertEquals(
                HttpStatusCode.Forbidden,
                next.status,
                "the held login is unavailable; the client must not retry",
            )
        } finally {
            rig.close()
        }
    }

    // Section 8: a renewal the token endpoint refuses moves the turn to a free login at once, before any byte.
    @Test
    fun `a failed renewal moves the turn to the backup before any byte`() = runTest {
        val rig = AccountTurnRig(root)
        try {
            rig.start()
            rig.failPrimaryRefresh()
            rig.messages(scenario = "authfail").also { it.bodyAsText() }
            val next = rig.messages().also { it.bodyAsText() }

            assertEquals(
                listOf("Bearer primary-token", "Bearer backup-token", "Bearer backup-token"),
                rig.authHeaders(),
            )
            assertEquals(listOf("primary-id", "backup-id", "backup-id"), rig.accountHeaders())
            assertEquals(HttpStatusCode.Forbidden, next.status, "the backup was refused too: no credential is usable")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a nonclean normal return releases the probe without clearing its failure count`() = runTest {
        val rig = AccountTurnRig(root)
        try {
            rig.start()
            rig.holdPrimaryUntilProbe()
            rig.messages(sessionId = "nonclean", scenario = "failed").also { it.bodyAsText() }
            rig.failPrimaryRefresh()
            rig.messages(sessionId = "terminal", scenario = "authfail").also { it.bodyAsText() }

            val primary = rig.poolView().accounts.single { it.primary }
            assertEquals("terminal_401", primary.credential.exclusionReason)
            assertEquals(600_000L, checkNotNull(primary.credential.excludedUntilEpochMillis) - rig.accountNowMs())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `deleted selected credential fails its turn and the next turn uses the backup`() = runTest {
        val rig = AccountTurnRig(root)
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
        val rig = AccountTurnRig(root)
        try {
            rig.start()
            val response = rig.messages(sessionId = "").also { it.bodyAsText() }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf("Bearer primary-token"), rig.authHeaders())
        } finally {
            rig.close()
        }
    }

    // Slice 6: a fully held pool selects its earliest eligible reset and asks that provider. The account that actually
    // serves the turn owns its usage headers, regardless of which login served the session before the limit.
    @Test
    fun `when every quota window is held the turn asks the earliest reset login and carries its windows`() = runTest {
        val rig = AccountTurnRig(root)
        try {
            rig.start()
            rig.messages().also { it.bodyAsText() } // session selects primary
            rig.exhaustPrimary()
            rig.messages().also { it.bodyAsText() } // ...and is now sticky to backup
            assertEquals("backup", rig.poolView().selectedLabel)

            val nowSeconds = System.currentTimeMillis() / MS_PER_SECOND
            rig.exhaustAllWithDistinctWindows(nowSeconds + 3_600L, nowSeconds + 86_400L)
            val wrong = rig.primaryFiveHourReset()
            val served = rig.messages().also { it.bodyAsText() }

            assertEquals(HttpStatusCode.OK, served.status, "the provider still decides whether the turn can run")
            val reset = served.headers["anthropic-ratelimit-unified-5h-reset"]
            assertEquals(
                wrong.toString(),
                reset,
                "the earliest-reset primary served this turn, so its window rides the answer",
            )
            assertEquals("Bearer primary-token", rig.authHeaders().last())
            assertEquals("primary", rig.poolView().selectedLabel)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `held quota readings never invent an admission refusal before the provider can answer`() = runTest {
        // The machine's zone is Tokyo when the head is built, which is when its voice reads the zone.
        val rig = inZone(TOKYO) { AccountTurnRig(root) }
        try {
            rig.start()
            val resetEpochSeconds = 2_077_951_777L
            rig.exhaustAll(resetEpochSeconds)
            val response = inZone(TOKYO) { rig.messages() }.also { it.bodyAsText() }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(0L, rig.localErrors())
            assertEquals(0L, rig.providerErrors())
            assertEquals(listOf("Bearer primary-token"), rig.authHeaders())
            assertFalse("all-accounts-exhausted" in rig.perfText())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `an oversized held quota reset cannot prevent the provider from serving the turn`() = runTest {
        val rig = inZone(TOKYO) { AccountTurnRig(root) }
        try {
            rig.start()
            val oversizedReset = Long.MAX_VALUE - 10_000_000_000_000_000L
            rig.exhaustAll(oversizedReset)
            val response = inZone(TOKYO) { rig.messages() }
            response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf("Bearer primary-token"), rig.authHeaders())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `reset text clamps below the four digit wire date range`() {
        assertEquals("0000-01-01T00:00:00Z", AccountResetText.format(Long.MIN_VALUE))
    }

    @Test
    fun `a pool with no usable credentials names a login failure without a rate limit deadline`() = runTest {
        val rig = AccountTurnRig(root, credentialPresent = false)
        try {
            rig.start()
            val response = rig.messages().also { it.bodyAsText() }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals(null, response.headers["Retry-After"])
            assertEquals("false", response.headers["x-should-retry"])
            assertEquals(1L, rig.localErrors())
            assertEquals(0L, rig.providerErrors())
            assertTrue(rig.authHeaders().isEmpty())
            assertFalse("earliest_reset_epoch_seconds" in rig.perfText())
        } finally {
            rig.close()
        }
    }

    @Test
    fun `head restart clears the cooldown authority used by pooled turns`() = runTest {
        val rig = AccountTurnRig(root)
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

internal class AccountTurnRig(root: Path, private val credentialPresent: Boolean = true) {
    private val tmp = Files.createDirectory(root.resolve(UUID.randomUUID().toString()))
    private val mock = MockChatGptUpstream()

    // A getter: the head binds port 0, and restart() rebinds a fresh one the rig must follow.
    private val port: Int get() = head.port
    private val primaryQuota = QuotaTracker(tmp.resolve("primary-quota.json"))
    private val backupQuota = QuotaTracker(tmp.resolve("backup-quota.json"))
    private val perfFile = tmp.resolve("perf.jsonl")
    private val perfStats = PerfStats(perfFile)
    private val logs = CopyOnWriteArrayList<String>()
    private val primaryAuth = AccountAuth("primary-token", "primary-id")
    private val backupAuth = AccountAuth("backup-token", "backup-id")
    private val providerHeld = AtomicBoolean()
    private val primaryCooldown = RateLimitCooldown(ProcessElapsedNow())
    private val backupCooldown = RateLimitCooldown(ProcessElapsedNow())
    private val accountNow = AtomicLong(System.currentTimeMillis())
    private val pool = AccountPool(
        listOf(
            account("primary", primary = true, primaryAuth, primaryQuota, primaryCooldown),
            account("backup", primary = false, backupAuth, backupQuota, backupCooldown),
        ),
        WallClock(accountNow::get),
    )
    private val head = HeadServer(
        provider = provider(),
        listenPort = 0,
        // V4-99: the primary tracker is LOAD-BEARING and it is primaryQuota, never null. Without it
        // HeadDeps.turnQuota gets primary = null, so `label ?: primary` and `primary ?: label` are
        // the same expression and the two precedence tests below could not fail for the reason their
        // own KDoc claims. Measured: with it absent, inverting TurnQuota.forSession survived the
        // whole :daemon-head suite. The primary must be a DIFFERENT tracker from the selected account's
        // or the swap stays invisible.
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000L),
            log = logs::add,
        ).copy(
            policy = HeadDeps.HeadPolicy(maxRequestBytes = { 2_048 }),
            quotaBundle = quotaFor(primaryQuota, pool, mapOf("primary" to primaryQuota, "backup" to backupQuota)),
        ).copy(stores = headStores(tmp).copy(perfStats = perfStats)),
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
        client.post("http://127.0.0.1:$port/v1/messages", turnRequest(sessionId, scenario))

    /** One turn read line by line, each line handed to [onLine] as it arrives, so a test can act while the turn is
     *  still in flight. Returns the whole stream. */
    suspend fun streamed(sessionId: String = SESSION, scenario: String = "basic", onLine: (String) -> Unit): String {
        val text = StringBuilder()
        client.preparePost("http://127.0.0.1:$port/v1/messages", turnRequest(sessionId, scenario)).execute { reply ->
            val channel = reply.bodyAsChannel()
            while (true) {
                val line = channel.readLine() ?: break
                text.append(line).append('\n')
                onLine(line)
            }
        }
        return text.toString()
    }

    private fun turnRequest(sessionId: String, scenario: String): HttpRequestBuilder.() -> Unit = {
        header("Content-Type", "application/json")
        header("x-claude-code-session-id", sessionId)
        setBody(
            """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                "system":"You are a test. SCENARIO:$scenario",
                "messages":[{"role":"user","content":"go"}]}""",
        )
    }

    /** The upstream's "hold" scenario blocks after its first delta until [releaseHold]. */
    fun holdUpstream() = mock.resetHold()

    fun releaseHold() = mock.releaseHold()

    fun exhaustPrimary() {
        primaryQuota.record(quota(100.0))
    }

    fun recoverPrimary() {
        primaryQuota.record(quota(10.0))
    }

    fun exhaustAll(resetEpochSeconds: Long): Long {
        val exhausted = quota(100.0, resetEpochSeconds)
        providerHeld.set(true)
        primaryQuota.record(exhausted)
        backupQuota.record(exhausted)
        return checkNotNull(checkNotNull(exhausted.fiveHour).resetsAt)
    }

    /** V4-84 (4): exhaust BOTH accounts so selection fails, while keeping their windows
     *  DISTINGUISHABLE. [exhaustAll] records one snapshot into both trackers, so primary and backup
     *  look identical at refusal time and no assertion could say which tracker answered. An explicit
     *  provider hold blocks both; different RESETS distinguish their reporting. */
    fun exhaustAllWithDistinctWindows(primaryReset: Long, backupReset: Long) {
        providerHeld.set(true)
        primaryQuota.record(quota(100.0, primaryReset))
        backupQuota.record(quota(100.0, backupReset))
    }

    /** The reset the PRIMARY's tracker reports, so a test can name the wrong answer explicitly. */
    fun primaryFiveHourReset(): Long =
        checkNotNull(primaryQuota.snapshot()?.fiveHour?.resetsAt)

    fun markPrimaryUnavailable() {
        primaryCooldown.markUnavailable(60_000L)
    }

    fun holdPrimaryUntilProbe() {
        (pool.select("hold-seed") as Selection.Chosen).account.markCredentialUnavailable()
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

    fun poolView(sessionId: String = SESSION) = pool.view(sessionId)

    /** The session and thread ids each upstream request carried, in arrival order. */
    fun routingHeaders(): List<Pair<String?, String?>> = mock.upstreamRouting.toList()

    fun logs(): List<String> = logs.toList()

    fun localErrors(): Long = head.healthSnapshot().localOriginErrors

    fun providerErrors(): Long = head.healthSnapshot().providerErrors

    /** Every perf row written so far, one JSON object per turn. */
    fun perfRows(): List<JsonObject> =
        perfText().lineSequence().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }.toList()

    fun perfText(): String {
        assertTrue(AsyncFileIo.drain())
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
        quota = object : AccountQuotaSource {
            override fun snapshot(): QuotaSnapshot? = tracker.snapshot()
            override val held: Boolean get() = providerHeld.get()
        },
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
        // V4-327: only a current reading rides a client response, so this one was just read.
        updatedAt = System.currentTimeMillis(),
    )

    private fun provider(): CodexProvider {
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
            defaultContextWindow = 272_000,
        )
        return CodexProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "codex", label = "claudex"),
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = primaryAuth,
                locations = ProviderLocations(baseUrl = mock.baseUrl),
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
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

private const val MS_PER_SECOND = 1_000L

private const val TOKYO = "Asia/Tokyo"

/** V4-433: runs [block] with the machine's zone set to [zone], and puts the old one back. Inline, so a request the
 *  block sends can suspend. */
private inline fun <T> inZone(zone: String, block: () -> T): T {
    val saved = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone(zone))
    try {
        return block()
    } finally {
        TimeZone.setDefault(saved)
    }
}
