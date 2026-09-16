import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.spi.ElapsedNow
import splice.spi.PostContext
import splice.spi.RateLimitCooldown
import splice.spi.RateLimitTurn
import splice.spi.RemainingTurnWait
import splice.spi.RetryDecision
import splice.spi.RetryNotice
import splice.spi.UpstreamClient
import splice.spi.UpstreamFailed
import splice.spi.UpstreamTurnWaitExhausted
import splice.spi.Waiter
import java.util.concurrent.atomic.AtomicInteger

class RateLimitCooldownTest {
    @Test
    fun `pooled 429 at the interactive ceiling terminates the observed request wave`() {
        var elapsed = 1_000L
        val notices = mutableListOf<String>()
        val cooldown = RateLimitCooldown(ElapsedNow { elapsed })

        val plan = cooldown.rateLimitedPlan(
            pushbackMs = 15_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = true),
            canRetry = true,
            onRetry = RetryNotice(notices::add),
            nextRefreshed = false,
        )

        assertEquals(RetryDecision.GIVE_UP, plan.decision)
        assertEquals(15_000L, cooldown.remainingMs())
        assertEquals(0L, cooldown.unavailableForMs())
        assertEquals(
            listOf(
                "429 rate limit: Retry-After header 15000ms, arming 15000ms follower protection",
                "429 observed with retry budget remaining; giving up to avoid a synchronized retry wave",
            ),
            notices,
        )
        elapsed += 15_000L
        assertEquals(0L, cooldown.remainingMs())
    }

    @Test
    fun `a dying turn gives up a short 429 without evicting the account`() {
        val cooldown = RateLimitCooldown(ElapsedNow { 0L })
        val notices = mutableListOf<String>()

        val plan = cooldown.rateLimitedPlan(
            pushbackMs = 1_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = true),
            canRetry = true,
            onRetry = RetryNotice(notices::add),
            nextRefreshed = false,
        )

        assertEquals(RetryDecision.GIVE_UP, plan.decision)
        assertEquals(0L, cooldown.unavailableForMs())
        assertEquals(0L, cooldown.providerUnavailableForMs())
        assertEquals(1_000L, cooldown.remainingMs())
        assertTrue(notices.none { it.contains("account unavailable") })
    }

    @Test
    fun `pooled 429 above the interactive ceiling removes only this account`() {
        var elapsed = 10L
        val cooldown = RateLimitCooldown(ElapsedNow { elapsed })

        val plan = cooldown.rateLimitedPlan(
            pushbackMs = 15_001L,
            turn = RateLimitTurn(cooldown, pooledAccount = true),
            canRetry = true,
            onRetry = RetryNotice {},
            nextRefreshed = false,
        )

        assertEquals(RetryDecision.GIVE_UP, plan.decision)
        assertEquals(15_001L, cooldown.unavailableForMs())
        assertEquals(15_001L, cooldown.remainingMs())
        elapsed += 15_001L
        assertEquals(0L, cooldown.unavailableForMs())
    }

    @Test
    fun `hostile reset horizon is clamped instead of poisoning the account`() {
        var elapsed = 42L
        val cooldown = RateLimitCooldown(ElapsedNow { elapsed })

        cooldown.markUnavailable(Long.MAX_VALUE)

        assertEquals(120_000L, cooldown.unavailableForMs())
        assertEquals(604_800_000L, cooldown.providerUnavailableForMs())
        elapsed += 120_000L
        assertEquals(0L, cooldown.unavailableForMs())
        elapsed += 604_800_000L
        assertEquals(0L, cooldown.providerUnavailableForMs())
    }

    @Test
    fun `a capped provider delay still saturates near the elapsed clock limit`() {
        var elapsed = Long.MAX_VALUE - 1_000_000L
        val cooldown = RateLimitCooldown(ElapsedNow { elapsed })

        cooldown.markUnavailable(Long.MAX_VALUE)

        assertEquals(120_000L, cooldown.unavailableForMs())
        assertEquals(1_000_000L, cooldown.providerUnavailableForMs())
        elapsed = Long.MAX_VALUE
        assertEquals(0L, cooldown.unavailableForMs())
        assertEquals(0L, cooldown.providerUnavailableForMs())
    }

    @Test
    fun `multi-day 429 names the bounded follower-protection horizon`() {
        val cooldown = RateLimitCooldown(ElapsedNow { 0L })
        val notices = mutableListOf<String>()

        cooldown.rateLimitedPlan(
            pushbackMs = 86_400_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = true),
            canRetry = true,
            onRetry = RetryNotice(notices::add),
            nextRefreshed = false,
        )

        assertTrue(notices.any { it.contains("arming 120000ms follower protection") })
        assertEquals(120_000L, cooldown.remainingMs())
        assertEquals(120_000L, cooldown.unavailableForMs())
        assertEquals(86_400_000L, cooldown.providerUnavailableForMs())
    }

    @Test
    fun `bare 429 does not invent an account-unavailable reset`() {
        val cooldown = RateLimitCooldown(ElapsedNow { 0L })

        cooldown.rateLimitedPlan(
            pushbackMs = null,
            turn = RateLimitTurn(cooldown, pooledAccount = true),
            canRetry = false,
            onRetry = RetryNotice {},
            nextRefreshed = false,
        )

        assertEquals(0L, cooldown.unavailableForMs())
        assertEquals(20_000L, cooldown.remainingMs())
    }

    // V4-46: the INSTRUMENT. It fires on every 429, so a short armed cooldown can be attributed to a
    // short Retry-After or to the 20s default — the distinction the clamp-only line lost, and the one
    // that decides whether the fix honours a short Retry-After or stops arming by default.
    @Test
    fun `the arming line names the Retry-After header and distinguishes absent from present`() {
        val notices = mutableListOf<String>()
        val cooldown = RateLimitCooldown(ElapsedNow { 0L })

        cooldown.rateLimitedPlan(
            pushbackMs = null,
            turn = RateLimitTurn(cooldown, pooledAccount = false),
            canRetry = false,
            onRetry = RetryNotice(notices::add),
            nextRefreshed = false,
        )
        cooldown.rateLimitedPlan(
            pushbackMs = 19_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = false),
            canRetry = false,
            onRetry = RetryNotice(notices::add),
            nextRefreshed = false,
        )

        assertTrue(
            notices.any { it == "429 rate limit: Retry-After header ABSENT, arming 20000ms follower protection" },
            notices.toString(),
        )
        assertTrue(
            notices.any { it == "429 rate limit: Retry-After header 19000ms, arming 19000ms follower protection" },
            notices.toString(),
        )
    }

    // V4-46: the fail-fast body names the GATEWAY interval and claims nothing about the provider. A
    // fail-fast turn never reached upstream, so it cannot know the provider reset; the old wording
    // read as an instruction to retry in Ns, inviting a retry that cannot succeed while crowding out
    // the real cause (a weekly quota wall resets in days, not seconds).
    @Test
    fun `a fail-fast 429 attributes the wait to the gateway and stays silent about the provider`() {
        var elapsed = 1_000L
        val cooldown = RateLimitCooldown(ElapsedNow { elapsed })
        cooldown.arm(19_000L)
        elapsed += 1_000L

        val failure = assertThrows<UpstreamFailed> { cooldown.failFastIfArmed(RetryNotice { }) }

        assertEquals(429, failure.status)
        assertTrue(failure.body.contains("this gateway is holding retries for 18s"), failure.body)
        assertFalse(failure.body.contains("provider"), "no provider reset is knowable here: ${failure.body}")
        assertFalse(failure.body.contains("retry in"), "the old wording read as an instruction: ${failure.body}")
    }

    // V4-46: THE FIXTURE IS THE LIVE EPISODE, not a constructed one. claude-muse, 2026-09-16:
    // 07:46:30 a real 429 whose body said the usage window resets five hours later, Retry-After
    // 488000ms clamped to 120000ms follower protection; then 07:47:02, 07:47:36 and 07:48:15, three
    // turns that never reached upstream and were told to retry in 88s, 54s and 15s. The operator
    // tried three times across two minutes, saw a SMALLER number each time, and read the countdown as
    // a retry schedule while the head was dead for five more hours. The message invited exactly the
    // retry it could not satisfy, and the stepping gaps below reproduce his 88/54/15 exactly.
    @Test
    fun `the live muse episode shrinking gateway countdown must not read as a retry schedule`() {
        var elapsed = 0L
        val cooldown = RateLimitCooldown(ElapsedNow { elapsed })

        cooldown.rateLimitedPlan(
            pushbackMs = 488_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = false),
            canRetry = false,
            onRetry = RetryNotice {},
            nextRefreshed = false,
        )
        assertEquals(120_000L, cooldown.remainingMs(), "the armed horizon clamps; the provider reset does not")

        val bodies = mutableListOf<String>()
        listOf(32_000L, 34_000L, 39_000L).forEach { gap ->
            elapsed += gap
            bodies += assertThrows<UpstreamFailed> { cooldown.failFastIfArmed(RetryNotice { }) }.body
        }

        // The countdown shrinks across the episode, which is what made it read as a schedule.
        assertTrue(bodies[0].contains("for 88s"), bodies[0])
        assertTrue(bodies[1].contains("for 54s"), bodies[1])
        assertTrue(bodies[2].contains("for 15s"), bodies[2])
        // ...and no turn may present that number as when the PROVIDER will accept a retry.
        bodies.forEach { body ->
            assertTrue(body.contains("this gateway is holding retries"), body)
            assertFalse(body.contains("provider"), "the provider reset is hours away and unknowable here: $body")
            assertFalse(body.contains("retry in"), "an invitation to retry is what he acted on: $body")
        }
    }
}

class RateLimitCooldownBudgetTest {
    private val fakeAuth = object : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.ApiKey("k", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "fake", emptyMap())
    }

    private class RecordingWaiter : Waiter {
        val waits = mutableListOf<Long>()

        override suspend fun wait(ms: Long) {
            waits.add(ms)
        }
    }

    @Test
    fun `a spent outer turn budget refuses attempt one with typed local expiry`() = runTest {
        val calls = AtomicInteger()
        val notices = mutableListOf<String>()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("fine", HttpStatusCode.OK, headersOf())
        }
        val client = UpstreamClient(
            firstByteTimeoutMs = 5_000L,
            totalTimeoutMs = 60_000L,
            maxRetries = 3,
            client = HttpClient(engine),
            clock = ElapsedNow { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            remainingTurnWait = RemainingTurnWait { 0L },
            onRetry = RetryNotice(notices::add),
        )

        val failure = assertThrows<UpstreamTurnWaitExhausted> { client.post(context, "{}") { "unreachable" } }

        assertEquals(0, calls.get())
        assertEquals("upstream turn wait budget exhausted", failure.message)
        assertEquals(listOf("upstream turn wait budget exhausted before attempt 1/3"), notices)
    }

    @Test
    fun `a backoff refused by the remaining budget still counts the retry decision`() = runTest {
        val calls = AtomicInteger()
        val notices = mutableListOf<String>()
        val waiter = RecordingWaiter()
        val perf = TurnPerf { 0L }
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("busy", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        val client = UpstreamClient(
            firstByteTimeoutMs = 5_000L,
            totalTimeoutMs = 60_000L,
            maxRetries = 3,
            client = HttpClient(engine),
            waiter = waiter,
            clock = ElapsedNow { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            perf = perf,
            onRetry = RetryNotice(notices::add),
            remainingTurnWait = RemainingTurnWait { 100L },
        )

        assertThrows<UpstreamFailed> { client.post(context, "{}") { "unreachable" } }

        assertEquals(1, calls.get())
        assertTrue(waiter.waits.isEmpty())
        assertEquals(1L, perf.snapshot().counters[PerfKeys.RETRIES])
        assertTrue(notices.contains("upstream backoff up to 220ms does not fit the remaining 100ms budget"))
    }

    @Test
    fun `a short pooled 429 never enters retry backoff`() = runTest {
        val calls = AtomicInteger()
        val waiter = RecordingWaiter()
        val cooldown = RateLimitCooldown(ElapsedNow { 0L })
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1"))
        }
        val client = UpstreamClient(
            firstByteTimeoutMs = 5_000L,
            totalTimeoutMs = 60_000L,
            maxRetries = 3,
            client = HttpClient(engine),
            waiter = waiter,
            clock = ElapsedNow { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            rateLimitCooldown = cooldown,
            remainingTurnWait = RemainingTurnWait { 20_000L },
        )

        assertThrows<UpstreamFailed> { client.post(context, "{}") { "unreachable" } }

        assertEquals(1, calls.get())
        assertTrue(waiter.waits.isEmpty(), "the observed 429 must not schedule a synchronized retry")
        assertEquals(1_000L, cooldown.remainingMs())
        assertEquals(0L, cooldown.unavailableForMs())
    }

    @Test
    fun `UP-001 - a retryable 503 with a long retry-after DOES arm the shared cooldown`() = runTest {
        for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.RequestTimeout)) {
            val calls = AtomicInteger()
            val cooldown = RateLimitCooldown(ElapsedNow { 0L })
            val engine = MockEngine {
                calls.incrementAndGet()
                respond("busy", status, headersOf("Retry-After", "30"))
            }
            val client = UpstreamClient(
                firstByteTimeoutMs = 5_000L,
                totalTimeoutMs = 60_000L,
                maxRetries = 3,
                client = HttpClient(engine),
                clock = ElapsedNow { 0L },
            )
            val context = PostContext(
                url = "https://api.example.test/v1",
                auth = fakeAuth,
                extraHeaders = { emptyMap() },
                rateLimitCooldown = cooldown,
                remainingTurnWait = RemainingTurnWait { 5_000L },
            )

            assertThrows<UpstreamFailed> { client.post(context, "{}") { "unreachable" } }
            assertEquals(1, calls.get())
            assertEquals(30_000L, cooldown.remainingMs(), "$status must protect followers")
            assertEquals(0L, cooldown.unavailableForMs(), "$status must not remove the account from selection")
            assertThrows<UpstreamFailed> { client.post(context, "{}") { "unreachable" } }
            assertEquals(1, calls.get(), "the next $status post must fail fast without reaching upstream")
        }
    }

    @Test
    fun `pooled zero or past retry-after does not outwait the remaining turn budget`() = runTest {
        for (retryAfter in listOf("0", "Wed, 21 Oct 2020 07:28:00 GMT")) {
            val calls = AtomicInteger()
            val waiter = RecordingWaiter()
            val engine = MockEngine {
                calls.incrementAndGet()
                respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", retryAfter))
            }
            val cooldown = RateLimitCooldown(ElapsedNow { 0L })
            val client = UpstreamClient(
                firstByteTimeoutMs = 5_000L,
                totalTimeoutMs = 60_000L,
                maxRetries = 3,
                client = HttpClient(engine),
                waiter = waiter,
                clock = ElapsedNow { 0L },
            )
            val context = PostContext(
                url = "https://api.example.test/v1",
                auth = fakeAuth,
                extraHeaders = { emptyMap() },
                rateLimitCooldown = cooldown,
                remainingTurnWait = RemainingTurnWait { 100L },
            )

            assertThrows<UpstreamFailed> { client.post(context, "{}") { "unreachable" } }
            assertEquals(1, calls.get(), "Retry-After $retryAfter must not outlive the turn budget")
            assertTrue(waiter.waits.isEmpty(), "Retry-After $retryAfter must not start the shipped backoff")
            assertEquals(0L, cooldown.unavailableForMs())
        }
    }
}

class RateLimitCooldownOuterTurnTest {
    private val auth = object : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.Bearer("test")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
    }

    @Test
    fun `exhaustion after backoff preserves the real upstream failure`() = runTest {
        var remaining = 5_000L
        val calls = AtomicInteger()
        val notices = mutableListOf<String>()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("provider-specific failure", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        val client = UpstreamClient(
            firstByteTimeoutMs = 5_000L,
            totalTimeoutMs = 60_000L,
            maxRetries = 3,
            client = HttpClient(engine),
            backoff = { _, _ -> remaining = 0L },
            clock = ElapsedNow { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = auth,
            extraHeaders = { emptyMap() },
            remainingTurnWait = RemainingTurnWait { remaining },
            onRetry = RetryNotice(notices::add),
        )

        val failure = assertThrows<UpstreamFailed> { client.post(context, "{}") { "unreachable" } }

        assertEquals(503, failure.status)
        assertEquals("provider-specific failure", failure.body)
        assertEquals(1, calls.get())
        assertTrue(notices.contains("upstream turn wait budget exhausted before attempt 2/3"))
    }

    @Test
    fun `a second non-pooled round cannot restart the spent outer turn wait budget`() = runTest {
        for (leftAfterFirstRound in listOf(0L, 100L)) {
            var elapsed = 0L
            val calls = AtomicInteger()
            val engine = MockEngine {
                if (calls.incrementAndGet() == 1) {
                    elapsed = 5_000L - leftAfterFirstRound
                    respond("first round", HttpStatusCode.OK, headersOf())
                } else {
                    respond("round two unavailable", HttpStatusCode.ServiceUnavailable, headersOf())
                }
            }
            val client = UpstreamClient(
                firstByteTimeoutMs = 5_000L,
                totalTimeoutMs = 5_000L,
                maxRetries = 3,
                client = HttpClient(engine),
                clock = ElapsedNow { elapsed },
            )
            val context = PostContext(
                url = "https://api.example.test/v1",
                auth = auth,
                extraHeaders = { emptyMap() },
                remainingTurnWait = RemainingTurnWait { 5_000L - elapsed },
            )

            assertEquals("ok", client.post(context, "{}") { "ok" })

            if (leftAfterFirstRound == 0L) {
                assertThrows<UpstreamTurnWaitExhausted> { client.post(context, "{}") { "unreachable" } }
                assertEquals(1, calls.get(), "round two must make zero calls after the turn cap is spent")
            } else {
                val failure = assertThrows<UpstreamFailed> { client.post(context, "{}") { "unreachable" } }
                assertEquals(2, calls.get(), "round two may attempt once but cannot spend a new retry budget")
                assertEquals(503, failure.status)
                assertEquals("round two unavailable", failure.body)
            }
        }
    }
}
