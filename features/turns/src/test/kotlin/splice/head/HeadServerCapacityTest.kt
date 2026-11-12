// NEW (G21): HTTP-level admission test — a real HeadServer wired with a bounded InflightGate
// (maxInflight=1, maxQueued=1). Fills the one inflight slot, fills the one queue spot, then
// proves the THIRD concurrent request is shed with a 529 "gateway at capacity" and the exact
// Anthropic error shape, instead of growing the waiter queue without limit.
package splice.head

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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.client.CLIENT_WALL_HEADER
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.dialect.responses.ReasoningSettings
import splice.head.usage.QuotaTracker
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.codemode.ProcessElapsedNow
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.InflightGate
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
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

    // The head binds port 0 and this reads back what it got (HeadServerLoadTest convention): a fixed
    // port BindExceptions on TIME_WAIT, and a port leased then released can be taken before the bind.
    // A getter, so restart() — which rebinds a fresh OS-assigned port — is followed automatically.
    private val port: Int get() = head.port
    private lateinit var head: HeadServer

    // maxRetries = 1: V4-61 makes a 429 with budget left wait the 15s floor in REAL time before
    // retrying; these tests need the ARM that follows exhaustion, not the schedule (:upstream
    // pins the schedule), so the budget is a single attempt and the arm is immediate.
    private val upstreamClient = UpstreamClient(totalTimeoutMs = 30_000)
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
            name = ProviderName(key = "codex", label = "claudex"),
            catalog = catalog,
            pinnedModel = "gpt-5.6-sol",
            auth = CapacityFakeAuth(),
            locations = ProviderLocations(baseUrl = mock.baseUrl),
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            loginCommand = "claudex login",
        ),
        reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
    )

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) = runBlocking {
        tmp = tempDir
        head = HeadServer(
            provider = capacityProvider(),
            listenPort = 0,
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
        val line = "[splice] claudex is already running 1 turn, its limit. Claude Code sends this turn again."
        assertEquals("""{"type":"error","error":{"type":"overloaded_error","message":"$line"}}""", body)

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
        // NF-01: an upstream 429 marks the login limited on the long-lived UpstreamClient, which
        // status and account steering read; a control-plane restart must clear it, not keep it.
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            // A session whose client timeout cannot hold a limit (slice 5): the limit reaches its client at once.
            header(CLIENT_WALL_HEADER, "10000")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:quota429",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.also { response ->
            // Precondition (review #94, F156): pin that the quota429 scenario actually fired, or a
            // mock drift and a broken cooldown read identically at the next assert. The client's
            // short wall cannot fit the hold, so slice 5 hands the limit back as a 429 before commit.
            assertEquals(429, response.status.value)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("[splice] claudex is at its usage limit") && body.contains("rate_limit_error"),
                "the quota429 scenario must hand the usage limit to the client, got: ${body.take(200)}",
            )
        }
        assertTrue(upstreamClient.rateLimitedForMs > 0L, "the 429 should have armed the cooldown")
        head.restart()
        assertEquals(0L, upstreamClient.rateLimitedForMs, "restart must clear the armed horizon")
    }

    // A locally held login remains eligible when no login is free. The provider's actual answer decides the turn.
    @Test
    fun `a pool with every eligible login held still asks the provider`() = runBlocking {
        val cooldown = RateLimitCooldown(ProcessElapsedNow())
        val pooled = pooledHead(cooldown)
        pooled.start()
        val pooledPort = pooled.port
        try {
            awaitListening(pooledPort)
            // Start clears cooldowns, so install the provider hold after the head is listening.
            cooldown.markUnavailable(THREE_DAYS_MS)
            assertTrue(cooldown.providerUnavailableForMs() > 0L, "the login is held before admission")
            val upstreamBefore = mock.upstreamBodies.size

            val response = client.post("http://127.0.0.1:$pooledPort/v1/messages") {
                header("Content-Type", "application/json")
                setBody(
                    """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                        "messages":[{"role":"user","content":"go"}]}""",
                )
            }

            assertEquals(200, response.status.value)
            val body = response.bodyAsText()
            assertTrue("message_stop" in body, "the provider serves the held login's request: $body")
            assertEquals(upstreamBefore + 1, mock.upstreamBodies.size, "admission must ask the provider")
        } finally {
            pooled.stop()
        }
    }

    /** One pooled OAuth account whose local provider hold the caller sets after start(). */
    private fun pooledHead(cooldown: RateLimitCooldown): HeadServer {
        val account = PoolAccount(
            label = "only",
            primary = true,
            auth = CapacityFakeAuth(),
            quota = AccountQuotaSource { null },
            cooldown = cooldown,
        )
        return HeadServer(
            provider = capacityProvider(),
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 30_000),
                gate = InflightGate(maxInflight = { 1 }, maxQueued = { 1 }),
                log = {},
            ).copy(
                // V4-80: a tracker, because the defect is only observable through one — without a
                // QuotaTracker the head emits no unified family at all and `allowed` vs `rejected`
                // is not a question the response answers. Empty on purpose: a head that has tracked
                // no window must still STATE the refusal (V4-51's deliberate divergence), so this
                // pins the refusal path rather than a snapshot's window members.
                quotaBundle = quotaFor(
                    QuotaTracker(tmp.resolve("pooled-quota.json"), log = LogSink { }),
                    AccountPool(listOf(account), WallClock(System::currentTimeMillis)),
                ),
                // The second rig writes its OWN store files: two heads in one test sharing a usage
                // file would read each other's rows, and it would compile either way.
                stores = headStores(tmp, suffix = "-pooled"),
            ),
        )
    }
}

private const val POLL_MS = 100L

private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1_000
