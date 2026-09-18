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
import splice.spi.MAX_RATE_LIMIT_COOLDOWN_MS
import splice.spi.PoolAccount
import splice.spi.ProcessElapsedNow
import splice.spi.ProviderTuning
import splice.spi.RateLimitCooldown
import splice.spi.Selection
import splice.spi.UpstreamClient
import java.nio.file.Files
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

class AccountTurnSelectionTest {
    private val imfFixdate = Regex("""[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT""")

    /** V4-77: a client deadline is a HOLD FROM NOW — positive, and inside V4-61's clamp — never the
     *  provider's window. Shared by the two exhaustion arms so the law is stated once. */
    /** V4-84 (7): the CEILING is measured from [receivedAtSeconds], the FLOOR from [sentAtSeconds].
     *
     *  The server stamps its deadline at refusal time, somewhere inside the round trip, so measuring
     *  the ceiling from the moment the request LEFT adds that trip to the hold and reads 121s against
     *  a 120s clamp whenever the second boundary falls inside the reply — the flake this pins. The
     *  later instant is the exact statement, and a second cannot rescue the defect: a three-day
     *  provider window is 259_200s against a 120s ceiling. The floor still runs from [sentAtSeconds],
     *  because a deadline must be in the future of the request that earned it. Same split, same
     *  reasoning, as HeadServerCapacityTest.assertBoundedRejectedRefusal. */
    private fun assertBoundedHold(retryAfter: String, sentAtSeconds: Long, receivedAtSeconds: Long) {
        val deadlineSeconds = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
        assertTrue(
            deadlineSeconds - sentAtSeconds > 0L,
            "a deadline already in the past is not a deadline, got: $retryAfter",
        )
        val holdSeconds = deadlineSeconds - receivedAtSeconds
        assertTrue(
            holdSeconds <= CLAMP_SECONDS,
            "the client deadline is the cooldown lift, at most ${CLAMP_SECONDS}s — got ${holdSeconds}s",
        )
    }

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

    // V4-77 REWRITE of `all exhausted records a local refusal with literal IMF-fixdate`, whose
    // Retry-After assertion was the literal 2035 reset. It was RED against this row and had to be:
    // it pinned the client deadline to the provider's window, which is what V4-61 reversed on the
    // cooldown branch and what V4-77 reverses here (a non-persistent Claude Code ABORTS on a
    // Retry-After past 60s; a persistent one sleeps through it). What this arm is ABOUT is
    // unchanged and still asserted: the refusal is local, it is an IMF-fixdate — the format is
    // still pinned, by shape — and it names the provider's real reset. What moved is WHICH instant
    // the header carries: a bounded hold from now instead of the window.
    /** V4-84 (4): the refusal's quota family comes from the SELECTED account's tracker, not the
     *  primary's. deps.quota is ONE tracker per label — the primary's — so a pooled head whose
     *  session is sticky to backup shipped primary's bars on the 429, and the client's utilization
     *  jumped to the other account's number. The window is identified by its RESET here rather than
     *  its utilization, because both accounts must read 100% to be unselectable and only the reset
     *  can then tell the two trackers apart. Mutation: restoring deps.quota returns the primary's
     *  reset and this fails. */
    @Test
    fun `an exhausted refusal carries the SELECTED account's quota windows, not the primary's`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            rig.messages().also { it.bodyAsText() } // session selects primary
            rig.exhaustPrimary()
            rig.messages().also { it.bodyAsText() } // ...and is now sticky to backup
            assertEquals("backup", rig.poolView().selectedLabel)

            val nowSeconds = System.currentTimeMillis() / MS_PER_SECOND
            rig.exhaustAllWithDistinctWindows(nowSeconds + 3_600L, nowSeconds + 86_400L)
            val wrong = rig.primaryFiveHourReset()
            val refused = rig.messages().also { it.bodyAsText() }

            assertEquals(HttpStatusCode.TooManyRequests, refused.status)
            val reset = refused.headers["anthropic-ratelimit-unified-5h-reset"]
            assertTrue(
                reset != wrong.toString(),
                "the refusal shipped the PRIMARY's window ($wrong) on a session routed to backup",
            )
            assertEquals(
                (nowSeconds + 86_400L).toString(),
                reset,
                "the refusal must carry the SELECTED account's window; the primary's is $wrong",
            )
        } finally {
            rig.close()
        }
    }

    @Test
    fun `all exhausted refuses with an IMF-fixdate Retry-After bounded by the clamp`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            val resetEpochSeconds = 2_077_951_777L
            val reset = Instant.ofEpochSecond(rig.exhaustAll(resetEpochSeconds)).toString()
            val sentAtSeconds = System.currentTimeMillis() / MS_PER_SECOND

            val response = rig.messages().also { body ->
                assertTrue(body.bodyAsText().contains("all OAuth accounts are exhausted; earliest reset is $reset"))
            }
            val receivedAtSeconds = System.currentTimeMillis() / MS_PER_SECOND

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            val retryAfter = checkNotNull(response.headers["Retry-After"])
            assertTrue(imfFixdate.matches(retryAfter), "Retry-After must stay IMF-fixdate, got: $retryAfter")
            assertBoundedHold(retryAfter, sentAtSeconds, receivedAtSeconds)
            assertEquals(1L, rig.localErrors())
            assertEquals(0L, rig.providerErrors())
            assertTrue(rig.authHeaders().isEmpty(), "an exhausted pool must not contact upstream")
            assertTrue(rig.logs().any { it.contains("all-accounts-exhausted") && it.contains("earliest_reset=$reset") })
            assertTrue(rig.perfText().contains("\"outcome\":\"error:all-accounts-exhausted\""))
        } finally {
            rig.close()
        }
    }

    // V4-77 REWRITE of `... with a formatter-safe Retry-After`, RED for the same reason as the arm
    // above. Its SUBJECT survives intact and gains reach: an absurd upstream reset must not break
    // the refusal. It used to prove that through the formatter alone; now it also proves the
    // clamp arithmetic does not overflow on the same hostile number before bounding it, and the
    // formatter-safe 9999 instant is still asserted where it now lives, in the message.
    @Test
    fun `oversized exhausted reset stays rate limited with a bounded Retry-After`() = runTest {
        val rig = AccountTurnRig()
        try {
            rig.start()
            val oversizedReset = Long.MAX_VALUE - 10_000_000_000_000_000L
            rig.exhaustAll(oversizedReset)
            val sentAtSeconds = System.currentTimeMillis() / MS_PER_SECOND

            val response = rig.messages()
            val receivedAtSeconds = System.currentTimeMillis() / MS_PER_SECOND

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertBoundedHold(checkNotNull(response.headers["Retry-After"]), sentAtSeconds, receivedAtSeconds)
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
            // V4-99: the primary tracker, and it is LOAD-BEARING. Without it HeadDeps.turnQuota
            // gets primary = null, so `label ?: primary` and `primary ?: label` are the same
            // expression and the two precedence tests below could not fail for the reason their
            // own KDoc claims ("Mutation: restoring deps.quota returns the primary's reset and this
            // fails"). Measured: with this line absent, inverting TurnQuota.forSession survived the
            // whole :gateway suite. The primary must be a DIFFERENT tracker from the selected
            // account's or the swap stays invisible, which is why it is primaryQuota and not null.
            quota = primaryQuota,
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

    /** V4-84 (4): exhaust BOTH accounts so selection fails, while keeping their windows
     *  DISTINGUISHABLE. [exhaustAll] records one snapshot into both trackers, so primary and backup
     *  look identical at refusal time and no assertion could say which tracker answered. Same
     *  utilization — both must still block — but different RESETS is what separates them. */
    fun exhaustAllWithDistinctWindows(primaryReset: Long, backupReset: Long) {
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

private const val MS_PER_SECOND = 1_000L

// V4-61's ceiling on the CLIENT-FACING deadline. V4-100: READS the one declaration
// (splice.spi.MAX_RATE_LIMIT_COOLDOWN_MS, public as of this row) instead of restating 120 here. The
// point of the row is that the production ceiling and the two pins asserting against it are ONE
// number; the comment this replaces ("both are private to their files, so the pin states the number
// the law states") was the copy admitting it could not see its own source.
private const val CLAMP_SECONDS: Long = MAX_RATE_LIMIT_COOLDOWN_MS / MS_PER_SECOND
