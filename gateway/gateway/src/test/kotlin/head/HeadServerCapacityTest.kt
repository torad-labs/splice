// NEW (G21): HTTP-level admission test — a real HeadServer wired with a bounded InflightGate
// (maxInflight=1, maxQueued=1). Fills the one inflight slot, fills the one queue spot, then
// proves the THIRD concurrent request is shed with a 529 "gateway at capacity" and the exact
// Anthropic error shape, instead of growing the waiter queue without limit.
package head

import campaign.v4105.headDeps
import campaign.v4105.headStores
import campaign.v4105.quotaFor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mock.MockChatGptUpstream
import mock.RATE_LIMITED_STATUS
import mock.TestResponsesProvider
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.gateway.head.HeadServer
import splice.gateway.usage.QuotaTracker
import splice.spi.AccountPool
import splice.spi.AccountQuotaSource
import splice.spi.InflightGate
import splice.spi.MAX_RATE_LIMIT_COOLDOWN_MS
import splice.spi.PoolAccount
import splice.spi.ProcessElapsedNow
import splice.spi.ProviderTuning
import splice.spi.RateLimitCooldown
import splice.spi.UpstreamClient
import java.net.ServerSocket
import java.nio.file.Files
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

private class CapacityFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-cap", "acct-cap")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerCapacityTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) {
        defaultRequest { bearerAuth("test-inference-token") }
    }

    // Ephemeral port (HeadServerLoadTest convention): a hardcoded port BindExceptions when a
    // prior run's socket is still in TIME_WAIT.
    private val port = ServerSocket(0).use { it.localPort }
    private lateinit var head: HeadServer

    // maxRetries = 1: V4-61 makes a 429 with budget left wait the 15s floor in REAL time before
    // retrying; these tests need the ARM that follows exhaustion, not the schedule (provider-spi
    // pins the schedule), so the budget is a single attempt and the arm is immediate.
    private val upstreamClient = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 1)
    private val gate = InflightGate(maxInflight = { 1 }, maxQueued = { 1 })
    private lateinit var tmp: java.nio.file.Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
        defaultContextWindow = 272_000,
    )

    // Extracted from setUp (V4-77) so the second, pooled head below is built from the IDENTICAL
    // provider rather than a hand-copied one that could drift from it.
    private fun capacityProvider() = TestResponsesProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "claudex",
            catalog = catalog,
            pinnedModel = "gpt-5.6-sol",
            auth = CapacityFakeAuth(),
            baseUrl = mock.baseUrl,
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = "high",
        configSummary = "detailed",
    )

    @BeforeAll
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("head-cap")
        head = HeadServer(
            provider = capacityProvider(),
            listenPort = port,
            deps = headDeps(
                tmp = tmp,
                upstream = upstreamClient,
                gate = gate,
                log = {},
            ),
        )
        // No warm-up: HeadEngine.start calls engine.start(wait = false), and Ktor 3.5.2's Netty
        // engine binds with ServerBootstrap.bind(...).sync() before start returns (V4-139).
        head.start()
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        client.close()
        mock.stop()
    }

    // A deadline poll, the rule's sanctioned shape: gate, mock and file state change server-side
    // after the client's response, and none of them offers a signal to await.
    private suspend fun waitFor(capMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + capMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(POLL_MS)
        }
        return cond()
    }

    private suspend fun heldTurn(): String =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:hold",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()

    private suspend fun rejectedTurn(): HttpResponse =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:hold",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }

    @Test
    fun `third concurrent request is shed with a 529 at capacity`() = runBlocking {
        mock.resetHold()
        val first = async(Dispatchers.IO) { heldTurn() }
        assertTrue(waitFor(5_000) { gate.snapshot().inflight == 1 }, "expected the first turn to hold the one slot")

        val second = async(Dispatchers.IO) { heldTurn() }
        assertTrue(
            waitFor(5_000) { gate.snapshot().queued == 1 },
            "expected the second turn to fill the one queue spot",
        )

        val response = rejectedTurn()
        assertEquals(529, response.status.value)
        val body = response.bodyAsText()
        assertTrue(body.contains("overloaded_error"), "expected overloaded_error in: $body")
        assertTrue(body.contains("gateway at capacity"), "expected 'gateway at capacity' in: $body")
        assertEquals(
            """{"type":"error","error":{"type":"overloaded_error","message":"gateway at capacity"}}""",
            body,
        )

        // release both held requests (they finish cleanly — no retry/re-anchor interaction, the
        // deterministic slot-occupancy mechanism the mock prescribes) before the test returns.
        mock.releaseHold()
        listOf(first, second).forEach { it.await() }
    }

    // THE `<Unit>` IS LOAD-BEARING, NOT STYLE — do not tidy it away. This body ends in
    // held.await(), whose value is a String, so without the explicit type argument the method
    // would RETURN a String and JUnit would never discover it: no failure, no skip, no warning.
    // That is what happened for this test's whole life — the XML read tests=3 against four @Test
    // methods until V4-68 added the wall that compares declarations against results.
    // await() rather than join() on purpose: await rethrows a FAILED held turn and fails this
    // test, while join() waits without rethrowing and would quietly weaken it.
    @Test
    fun `count_tokens answers promptly and calls no upstream while the turn gate is saturated`() = runBlocking<Unit> {
        // count_tokens is a local estimate off the turn gate: even with the one inflight slot held,
        // it must return 200 immediately, never touch upstream, and never occupy the gate.
        mock.resetHold()
        val upstreamAtStart = mock.upstreamBodies.size
        val held = async(Dispatchers.IO) { heldTurn() }
        assertTrue(waitFor(5_000) { gate.snapshot().inflight == 1 }, "expected the held turn to occupy the slot")
        // V4-80: WAIT FOR THE HELD TURN'S OWN BODY BEFORE SAMPLING THE BASELINE. The gate counts a
        // turn at ADMISSION, which is strictly before its upstream body is posted, so a baseline
        // taken on inflight==1 alone can miss that body and then attribute it to count_tokens —
        // 'must not call upstream, expected 0 but was 1', observed 2026-09-17 on a slow lane. The
        // assertion below is unchanged and still fails on a real upstream call from count_tokens.
        assertTrue(
            waitFor(5_000) { mock.upstreamBodies.size > upstreamAtStart },
            "expected the held turn's own upstream body to have landed before the baseline",
        )
        val upstreamBefore = mock.upstreamBodies.size

        val resp = client.post("http://127.0.0.1:$port/v1/messages/count_tokens") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol",
                    "messages":[{"role":"user","content":"estimate this locally"}]}""",
            )
        }
        assertEquals(200, resp.status.value)
        assertTrue(resp.bodyAsText().contains("input_tokens"), "expected a local token estimate")
        assertEquals(upstreamBefore, mock.upstreamBodies.size, "count_tokens must not call upstream")
        assertEquals(1, gate.snapshot().inflight, "count_tokens must not occupy the turn gate")

        mock.releaseHold()
        held.await()
    }

    @Test
    fun `restart clears an armed 429 cooldown`() = runBlocking {
        // NF-01: an upstream 429 arms the head-wide fail-fast horizon on the long-lived
        // UpstreamClient; a control-plane restart must be a real escape hatch, not a no-op.
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:quota429",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.also { response ->
            // Precondition (review #94, F156): pin that the quota429 scenario actually fired, or a
            // mock drift and a broken cooldown read identically at the next assert. The head relays
            // upstream failures as a 200 + SSE error event (never an HTTP error status mid-stream).
            // V4-71: the FIRST pre-content 429 turn now rides the wire as overloaded_error (the one
            // in-band type Claude Code retries) with the rate-limit words and code kept, so the
            // precondition pins the code, which is what says the quota429 scenario fired.
            val body = response.bodyAsText()
            assertTrue(
                body.contains("SPLICE-RATE-LIMIT") && body.contains("overloaded_error"),
                "the quota429 scenario must surface a retryable rate-limit event, got: ${body.take(200)}",
            )
        }
        assertTrue(upstreamClient.rateLimitedForMs > 0L, "the 429 should have armed the cooldown")
        head.restart()
        assertEquals(0L, upstreamClient.rateLimitedForMs, "restart must clear the armed horizon")
    }

    // V4-50: the turn AFTER the horizon is armed is the one the operator kept reporting. The test
    // above pins the FIRST turn, which reaches upstream and is relayed as a 200 + SSE error frame
    // because its response is already committed — that stays true and is asserted there. This one
    // pins the SECOND turn, which never reaches upstream at all: it is refused at admission, where
    // a status line is still ours to write.
    //
    // WHY THE STATUS IS THE ASSERTION AND NOT THE MESSAGE. Claude Code's retry-until-reset fires on
    // an APIError with status 429 and reads the deadline off that response; an error frame inside a
    // 200 is not an APIError, so before this row the client had nothing to retry on no matter how
    // the text was worded. Three operator reports in one day were this, and each was first
    // mistaken for a wording problem.
    //
    // RETRY-AFTER IS THE COOLDOWN LIFT (V4-61): the header names when this gateway next lets a
    // request through — at most 120s — never the provider's window reset, which muse stamps on
    // burst 429s that clear in seconds. The scenario's body still carries resets_in_seconds:60 and
    // V4-47 still captures it; that value now rides in the message and the perf row, not the header.
    @Test
    fun `an armed cooldown refuses the next turn with a real 429 carrying the provider deadline`() = runBlocking {
        upstreamClient.clearRateLimitCooldown()
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:quota429",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }
        assertTrue(upstreamClient.rateLimitedForMs > 0L, "precondition: the first turn must arm the horizon")

        val refused = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "messages":[{"role":"user","content":"again"}]}""",
            )
        }
        assertEquals(
            RATE_LIMITED_STATUS,
            refused.status.value,
            "an armed head must refuse with a real 429, not a 200 carrying an error frame",
        )
        val body = refused.bodyAsText()
        assertTrue(
            body.contains("rate_limit_error"),
            "the refusal keeps the Anthropic error shape, got: ${body.take(200)}",
        )
        assertNotNull(
            refused.headers["Retry-After"],
            "the refusal must carry Retry-After — the cooldown lift, when this gateway next lets a request through",
        )
        // V4-55: the refusal must also LEAVE A TRACE. Review of V4-50 found it wrote no perf row
        // and no journal line, so a refused turn did not exist in splice's own telemetry — the
        // exact blindness that made three operator reports of this failure unfalsifiable in one
        // day, on the path built to answer them. The perf file is the assertable half here
        // (this head discards the journal with log = {}); the append is best-effort on a bounded
        // file lane, so it is polled rather than read once.
        val perfFile = tmp.resolve("perf.jsonl")
        val recorded = waitFor(2_000) {
            Files.exists(perfFile) && Files.readString(perfFile).contains("error:rate-limited")
        }
        assertTrue(recorded, "the refusal must record a perf row; a refused turn with no trace is unfalsifiable")

        upstreamClient.clearRateLimitCooldown()
    }

    // V4-77: THE POOLED TWIN OF THE ARM ABOVE, and the law is the same one. A head whose every
    // OAuth account is blocked refuses at admission with a 429 — that part was already true — but
    // it used to hand the client AllAccountsExhausted.earliestResetEpochSeconds RAW: the quota
    // resetsAt / provider unavailability, bounded only by seven days. Three days on the wire is the
    // turn dying either way (a non-persistent Claude Code aborts past 60s, a persistent one sleeps),
    // which is exactly what V4-61 reversed on the cooldown branch and what this arm now pins here.
    //
    // WHY THE ASSERTION IS A DELTA AND NOT A LITERAL DATE: the deadline is a hold from NOW, so the
    // only stable statement about it is its distance from now — positive (a real deadline, not an
    // already-expired one) and inside the clamp. The provider's own three-day reset is asserted
    // where it does belong, in the refusal MESSAGE, so this arm fails just as loudly if a later
    // change bounds the header by dropping the fact instead of by moving it.
    //
    // Its own head, its own port, its own pool: the shared head above must stay pool-free or every
    // other test in this class would route through account selection.
    @Test
    fun `an exhausted pool refuses with a Retry-After bounded by the cooldown clamp`() = runBlocking {
        val cooldown = RateLimitCooldown(ProcessElapsedNow())
        val pooledPort = freshPort()
        val pooled = pooledHead(pooledPort, cooldown)
        pooled.start()
        try {
            awaitListening(pooledPort)
            // BLOCKED AFTER start(), NEVER BEFORE — HeadServer.start() resets the pool (NF-01's
            // restart escape hatch clears every account's cooldown), so an account blocked before
            // the head came up is selectable again and the turn simply succeeds. It did: this arm
            // read 200 instead of 429 until the order was fixed.
            // Three days out, the muse-shaped case: markUnavailable keeps SELECTION blocked for the
            // bounded 120s and reports the full provider window, which is what earliestReset reads.
            cooldown.markUnavailable(THREE_DAYS_MS)
            val sentAtSeconds = System.currentTimeMillis() / MS_PER_S

            val refused = client.post("http://127.0.0.1:$pooledPort/v1/messages") {
                header("Content-Type", "application/json")
                setBody(
                    """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                        "messages":[{"role":"user","content":"go"}]}""",
                )
            }
            val receivedAtSeconds = System.currentTimeMillis() / MS_PER_S

            assertEquals(RATE_LIMITED_STATUS, refused.status.value)
            assertTrue(
                refused.bodyAsText().contains("all OAuth accounts are exhausted; earliest reset is"),
                "the provider's own reset still rides in the message; only the wire deadline is bounded",
            )
            assertBoundedRejectedRefusal(refused, sentAtSeconds, receivedAtSeconds)
        } finally {
            pooled.stop()
        }
    }

    /** EVERY DEADLINE THE REFUSAL STATES, PINNED TOGETHER — extracted (V4-80) so the arm above stays
     *  inside detekt's LongMethod ceiling while saying more, not less.
     *
     *  V4-77'S HALF: Retry-After is the bounded client hold, never the provider window. The ceiling
     *  is measured from [receivedAtSeconds], AFTER the reply — the server stamps now+clamp at
     *  refusal time, somewhere inside the round trip, so measuring from [sentAtSeconds] adds that
     *  trip to the hold and read 121s on a cold pooled head (observed 2026-09-17). The later
     *  instant is the exact statement and a second cannot rescue the defect it pins: a three-day
     *  provider window is 259_200s against a 120s ceiling. The floor still runs from
     *  [sentAtSeconds], because a deadline must be in the future of the request that earned it.
     *
     *  V4-80'S HALF: THE REFUSAL MUST NOT CONTRADICT ITSELF IN ITS OWN HEADERS. V4-77 bounded the
     *  pooled deadline but left this branch emitting NO quota family, so a pooled head with a
     *  tracker answered `anthropic-ratelimit-unified-status: allowed` on the very response that
     *  refused the turn — the same lie V4-51 fixed for the cooldown branch, alive on the branch
     *  V4-51 did not open. The plain `-reset` is the member Claude Code's withRetry reads off a
     *  429, so what is worth pinning is that the two deadlines in one response name the SAME
     *  instant — the bounded hold; re-deriving it from the provider window would read three days
     *  out and fail by the same margin the ceiling catches. */
    private fun assertBoundedRejectedRefusal(refused: HttpResponse, sentAtSeconds: Long, receivedAtSeconds: Long) {
        val retryAfter = checkNotNull(refused.headers["Retry-After"]) {
            "an exhausted pool must still name a deadline the client can wait on"
        }
        val deadlineEpoch = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
        assertTrue(deadlineEpoch > sentAtSeconds, "a deadline already in the past is not a deadline, got: $retryAfter")
        assertTrue(
            deadlineEpoch - receivedAtSeconds <= CLAMP_SECONDS,
            "the client deadline is the cooldown lift, at most ${CLAMP_SECONDS}s — " +
                "got ${deadlineEpoch - receivedAtSeconds}s from $retryAfter",
        )
        assertEquals(
            "rejected",
            refused.headers["anthropic-ratelimit-unified-status"],
            "a refusal asserting `allowed` in its own quota headers contradicts its own 429",
        )
        assertEquals(
            deadlineEpoch.toString(),
            refused.headers["anthropic-ratelimit-unified-reset"],
            "the plain unified-reset must name the bounded client deadline, the same instant as " +
                "Retry-After ($retryAfter) — never the provider window",
        )
    }

    /** The V4-77 head: one OAuth account, on [cooldown], which the caller blocks after start(). */
    private fun pooledHead(pooledPort: Int, cooldown: RateLimitCooldown): HeadServer {
        val account = PoolAccount(
            label = "only",
            primary = true,
            auth = CapacityFakeAuth(),
            quota = AccountQuotaSource { null },
            cooldown = cooldown,
        )
        return HeadServer(
            provider = capacityProvider(),
            listenPort = pooledPort,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 1),
                gate = InflightGate(maxInflight = { 1 }, maxQueued = { 1 }),
                log = {},
                // V4-80: a tracker, because the defect is only observable through one — without a
                // QuotaTracker the head emits no unified family at all and `allowed` vs `rejected`
                // is not a question the response answers. Empty on purpose: a head that has tracked
                // no window must still STATE the refusal (V4-51's deliberate divergence), so this
                // pins the refusal path rather than a snapshot's window members.
                quota = quotaFor(
                    QuotaTracker(tmp.resolve("pooled-quota.json"), log = LogSink { }),
                    AccountPool(listOf(account), WallClock(System::currentTimeMillis)),
                ),
            ).copy(
                // The second rig writes its OWN store files: two heads in one test sharing a usage
                // file would read each other's rows, and it would compile either way.
                stores = headStores(tmp, suffix = "-pooled"),
            ),
        )
    }
}

private const val MS_PER_S = 1_000L
private const val POLL_MS = 100L

// V4-61's ceiling on the client-facing deadline. V4-100: READS RateLimitCooldown's
// MAX_RATE_LIMIT_COOLDOWN_MS itself, which is now public — the pin used to restate 120 because both
// declarations were private to their files, which is precisely the copy this row retires.
private const val CLAMP_SECONDS: Long = MAX_RATE_LIMIT_COOLDOWN_MS / MS_PER_S

private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1_000
