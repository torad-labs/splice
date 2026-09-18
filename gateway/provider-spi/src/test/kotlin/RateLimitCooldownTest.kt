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
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.spi.MAX_RATE_LIMIT_COOLDOWN_MS
import splice.spi.PostContext
import splice.spi.RateLimitCooldown
import splice.spi.RateLimitTurn
import splice.spi.RemainingTurnWait
import splice.spi.RetryDecision
import splice.spi.RetryNotice
import splice.spi.UpstreamClient
import splice.spi.UpstreamFailed
import splice.spi.UpstreamPost
import splice.spi.Waiter
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class RateLimitCooldownTest {
    @Test
    fun `pooled 429 at the interactive ceiling terminates the observed request wave`() {
        var elapsed = 1_000L
        val notices = mutableListOf<String>()
        val cooldown = RateLimitCooldown(ElapsedClock { elapsed })

        val plan = cooldown.rateLimitedPlan(
            pushbackMs = 15_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = true),
            canRetry = true,
            onRetry = RetryNotice(notices::add),
            nextRefreshed = false,
        )

        // V4-48 REVERSED THIS ROW'S ORIGINAL POLICY, deliberately: at the ceiling, and with budget
        // left, a 429 now takes the same BACKOFF branch a 408 or 5xx pushback has always taken.
        assertEquals(RetryDecision.BACKOFF, plan.decision)
        assertEquals(15_000L, plan.minDelayMs, "the wait is the provider's own pushback, not a curve")
        assertEquals(0L, cooldown.remainingMs(), "waiting it out must NOT arm follower protection")
        assertEquals(0L, cooldown.unavailableForMs())
        assertEquals(
            listOf("429 rate limit: Retry-After header 15000ms; budget remaining, retrying in 15000ms"),
            notices,
        )
    }

    @Test
    fun `a dying turn gives up a short 429 without evicting the account`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })
        val notices = mutableListOf<String>()

        val plan = cooldown.rateLimitedPlan(
            pushbackMs = 1_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = true),
            canRetry = true,
            onRetry = RetryNotice(notices::add),
            nextRefreshed = false,
        )

        assertEquals(RetryDecision.BACKOFF, plan.decision)
        assertEquals(1_000L, plan.minDelayMs)
        assertEquals(0L, cooldown.unavailableForMs())
        assertEquals(0L, cooldown.providerUnavailableForMs())
        assertEquals(0L, cooldown.remainingMs(), "the wait path leaves the head unarmed")
        assertTrue(notices.none { it.contains("account unavailable") })
    }

    // V4-61: THE OPERATOR'S CASE. muse answers a burst 429 with its 5h-window reset as Retry-After
    // (5301000ms live), and his own re-send moments later succeeds — so the long number is not this
    // 429's retry-after, and giving up on it (V4-48 waited out only a header at or under 15s) left
    // every muse rate limit dead on the first attempt. An unpooled head has no backup to rotate to;
    // waiting the 15s floor and retrying HERE is the whole recovery.
    @Test
    fun `a long retry-after on a non-pooled 429 backs off at the 15s floor instead of giving up`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })
        val notices = mutableListOf<String>()

        val plan = cooldown.rateLimitedPlan(
            pushbackMs = 5_301_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = false),
            canRetry = true,
            onRetry = RetryNotice(notices::add),
            nextRefreshed = false,
        )

        assertEquals(RetryDecision.BACKOFF, plan.decision)
        assertEquals(15_000L, plan.minDelayMs, "the floor is the ceiling, never the provider's window")
        assertEquals(0L, cooldown.remainingMs(), "a retrying turn leaves the head unarmed")
        assertEquals(0L, cooldown.unavailableForMs(), "an unpooled head has no account to evict")
        assertEquals(
            listOf("429 rate limit: Retry-After header 5301000ms; budget remaining, retrying in 15000ms"),
            notices,
        )
    }

    // V4-61: a bare 429 — the ChatGPT backend's {"detail":"Rate limit exceeded"} with no header at
    // all — used to fall past V4-48's short-wait branch (which required a header) straight to
    // give-up. The absent header is the COMMON case, not the edge.
    @Test
    fun `an absent retry-after on a 429 with budget left backs off at the 15s floor`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })

        val plan = cooldown.rateLimitedPlan(
            pushbackMs = null,
            turn = RateLimitTurn(cooldown, pooledAccount = false),
            canRetry = true,
            onRetry = RetryNotice {},
            nextRefreshed = false,
        )

        assertEquals(RetryDecision.BACKOFF, plan.decision)
        assertEquals(15_000L, plan.minDelayMs)
        assertEquals(0L, cooldown.remainingMs(), "a retrying turn leaves the head unarmed")
    }

    // V4-61 partner assertion (green before the change too): the horizon is armed ONCE, on
    // exhaustion, never on the retries that precede it.
    @Test
    fun `a 429 with no budget left gives up and arms the follower horizon`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })

        val plan = cooldown.rateLimitedPlan(
            pushbackMs = null,
            turn = RateLimitTurn(cooldown, pooledAccount = false),
            canRetry = false,
            onRetry = RetryNotice {},
            nextRefreshed = false,
        )

        assertEquals(RetryDecision.GIVE_UP, plan.decision)
        assertEquals(20_000L, cooldown.remainingMs(), "a bare 429 arms the default cooldown on exhaustion")
    }

    @Test
    fun `pooled 429 above the interactive ceiling removes only this account`() {
        var elapsed = 10L
        val cooldown = RateLimitCooldown(ElapsedClock { elapsed })

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
        val cooldown = RateLimitCooldown(ElapsedClock { elapsed })

        cooldown.markUnavailable(Long.MAX_VALUE)

        assertEquals(MAX_RATE_LIMIT_COOLDOWN_MS, cooldown.unavailableForMs())
        assertEquals(604_800_000L, cooldown.providerUnavailableForMs())
        elapsed += MAX_RATE_LIMIT_COOLDOWN_MS
        assertEquals(0L, cooldown.unavailableForMs())
        elapsed += 604_800_000L
        assertEquals(0L, cooldown.providerUnavailableForMs())
    }

    @Test
    fun `a capped provider delay still saturates near the elapsed clock limit`() {
        var elapsed = Long.MAX_VALUE - 1_000_000L
        val cooldown = RateLimitCooldown(ElapsedClock { elapsed })

        cooldown.markUnavailable(Long.MAX_VALUE)

        assertEquals(MAX_RATE_LIMIT_COOLDOWN_MS, cooldown.unavailableForMs())
        assertEquals(1_000_000L, cooldown.providerUnavailableForMs())
        elapsed = Long.MAX_VALUE
        assertEquals(0L, cooldown.unavailableForMs())
        assertEquals(0L, cooldown.providerUnavailableForMs())
    }

    @Test
    fun `multi-day 429 names the bounded follower-protection horizon`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })
        val notices = mutableListOf<String>()

        cooldown.rateLimitedPlan(
            pushbackMs = 86_400_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = true),
            canRetry = true,
            onRetry = RetryNotice(notices::add),
            nextRefreshed = false,
        )

        assertTrue(notices.any { it.contains("arming 120000ms follower protection") })
        assertEquals(MAX_RATE_LIMIT_COOLDOWN_MS, cooldown.remainingMs())
        assertEquals(MAX_RATE_LIMIT_COOLDOWN_MS, cooldown.unavailableForMs())
        assertEquals(86_400_000L, cooldown.providerUnavailableForMs())
    }

    @Test
    fun `bare 429 does not invent an account-unavailable reset`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })

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
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })

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
        val cooldown = RateLimitCooldown(ElapsedClock { elapsed })
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
        val cooldown = RateLimitCooldown(ElapsedClock { elapsed })

        cooldown.rateLimitedPlan(
            pushbackMs = 488_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = false),
            canRetry = false,
            onRetry = RetryNotice {},
            nextRefreshed = false,
        )
        assertEquals(
            MAX_RATE_LIMIT_COOLDOWN_MS,
            cooldown.remainingMs(),
            "the armed horizon clamps; the provider reset does not",
        )

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

    // V4-47: THE LIVE EPISODE AGAIN, and this time the provider DOES name its reset. claude-muse,
    // 2026-09-16 13:34:32 — Retry-After 5301000ms clamped to 120s, body naming a window reset 88
    // MINUTES out at 20:02:52Z. The operator was told to retry in 120s and retried into the same 429
    // four times. The body must now name the provider's own horizon and say the wait will not help.
    @Test
    fun `the live muse episode names the provider reset so 120s cannot read as a retry schedule`() {
        var elapsed = 0L
        val wall = Instant.parse("2026-09-16T13:34:32Z").toEpochMilli()
        val cooldown = RateLimitCooldown(ElapsedClock { elapsed }, WallClock { wall })
        val body = """{"error":{"message":"Subscription quota exhausted. Your usage window resets """ +
            """at 2026-09-16T20:02:52Z","type":"rate_limit_error"},"type":"error"}"""

        cooldown.rateLimitedPlan(
            pushbackMs = 5_301_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = false),
            canRetry = false,
            onRetry = RetryNotice {},
            nextRefreshed = false,
            body = body,
        )

        assertEquals(MAX_RATE_LIMIT_COOLDOWN_MS, cooldown.remainingMs(), "the ARMED horizon still clamps at 120s")
        assertEquals(23_300_000L, cooldown.providerUnavailableForMs(), "the body reset is 6h28m20s out")

        // No clock advance before reading the body: the cooldown is armed to 120s, so the
        // remaining is still 120s and the provider instant is exactly the one the body named.
        val failure = assertThrows<UpstreamFailed> { cooldown.failFastIfArmed(RetryNotice { }) }

        assertTrue(failure.body.contains("this gateway is holding retries"), failure.body)
        assertTrue(failure.body.contains("holding retries for 120s"), failure.body)
        assertTrue(
            failure.body.contains("2026-09-16T20:02:52Z"),
            "the provider horizon must be named on the WALL base, not the elapsed one: ${failure.body}",
        )
        // V4-61: the window is reported, never asserted as the deadline (the operator's re-send
        // cleared a "88 minute" 429 in seconds), and the body is the Anthropic error envelope the
        // classifier and the presentation seam both read — never a hand-built detail object.
        assertTrue(failure.body.contains("the upstream reports its quota window resets at"), failure.body)
        assertTrue(failure.body.contains("\"type\":\"rate_limit_error\""), failure.body)
        assertTrue(!failure.body.contains("\"detail\""), "no detail key: ${failure.body}")
    }

    // V4-47's defect: markUnavailable was the ONLY writer of the provider reset and it fires only for
    // POOLED turns over the 15s ceiling — so on a single-account head, which is every head the
    // operator runs, the reset was never recorded at all.
    @Test
    fun `a single-account head records the provider reset where markUnavailable never fired`() {
        val wall = Instant.parse("2026-09-16T13:34:32Z").toEpochMilli()
        val cooldown = RateLimitCooldown(ElapsedClock { 0L }, WallClock { wall })

        cooldown.rateLimitedPlan(
            pushbackMs = 5_301_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = false),
            canRetry = false,
            onRetry = RetryNotice {},
            nextRefreshed = false,
            body = """{"error":{"message":"resets at 2026-09-16T20:02:52Z"}}""",
        )

        assertEquals(0L, cooldown.unavailableForMs(), "a single-account head is never evicted")
        assertEquals(23_300_000L, cooldown.providerUnavailableForMs(), "but its reset IS recorded")
    }

    @Test
    fun `the reset is read from every spelling the vendors use`() {
        val wall = Instant.parse("2026-09-16T13:34:32Z").toEpochMilli()

        fun captured(body: String): Long {
            val cooldown = RateLimitCooldown(ElapsedClock { 0L }, WallClock { wall })
            cooldown.rateLimitedPlan(
                pushbackMs = 5_301_000L,
                turn = RateLimitTurn(cooldown, pooledAccount = false),
                canRetry = false,
                onRetry = RetryNotice {},
                nextRefreshed = false,
                body = body,
            )
            return cooldown.providerUnavailableForMs()
        }

        assertEquals(23_300_000L, captured("""{"message":"usage window resets at 2026-09-16T20:02:52Z"}"""))
        assertEquals(3_600_000L, captured("""{"resets_at":${wall / 1_000 + 3_600}}"""), "epoch seconds")
        assertEquals(3_600_000L, captured("""{"resets_in_seconds":3600}"""), "a duration is already a delay")
        assertEquals(0L, captured("""{"detail":"Rate limit exceeded"}"""), "a bare body names nothing")
    }

    @Test
    fun `a body naming no reset keeps the V4-46 wording and still claims nothing`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L }, WallClock { 0L })
        cooldown.rateLimitedPlan(
            pushbackMs = 5_301_000L,
            turn = RateLimitTurn(cooldown, pooledAccount = false),
            canRetry = false,
            onRetry = RetryNotice {},
            nextRefreshed = false,
            body = """{"detail":"Rate limit exceeded"}""",
        )
        assertEquals(0L, cooldown.providerUnavailableForMs())

        val failure = assertThrows<UpstreamFailed> { cooldown.failFastIfArmed(RetryNotice { }) }

        assertTrue(failure.body.contains("this gateway is holding retries"), failure.body)
        assertFalse(failure.body.contains("provider"), "no provider horizon is known: ${failure.body}")
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
            clock = ElapsedClock { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            remainingTurnWait = RemainingTurnWait { 0L },
            onRetry = RetryNotice(notices::add),
        )

        // V4-114 PIN: the exhausted turn-wait budget is a VALUE on post()'s return type. This line
        // does not compile against the old shape (post returned T and threw
        // UpstreamTurnWaitExhausted), and a Delivered here fails the assertEquals instead of
        // arriving as an exception any broad catch on the turn path would have taken for a bug.
        assertEquals(UpstreamPost.TurnWaitExhausted, client.post(context, "{}") { "unreachable" })

        assertEquals(0, calls.get())
        // The operator-visible line is unchanged; the class that used to carry it is gone.
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
            clock = ElapsedClock { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            perf = perf,
            onRetry = RetryNotice(notices::add),
            remainingTurnWait = RemainingTurnWait { 100L },
        )

        assertThrows<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }

        assertEquals(1, calls.get())
        assertTrue(waiter.waits.isEmpty())
        assertEquals(1L, perf.snapshot().counters[PerfKeys.RETRIES])
        assertTrue(notices.contains("upstream backoff up to 220ms does not fit the remaining 100ms budget"))
    }

    // V4-61 end to end through the SHIPPED backoff: three attempts, two 15s waits, then the honest
    // give-up and the arm — "retry every 15 seconds" measured, not asserted on a plan object. The
    // header says 5301s; the schedule must not.
    @Test
    fun `a non-pooled 429 with a long retry-after is retried on the 15s schedule until the budget is spent`() = runTest {
        val calls = AtomicInteger()
        val waiter = RecordingWaiter()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "5301"))
        }
        val client = UpstreamClient(
            firstByteTimeoutMs = 5_000L,
            totalTimeoutMs = 60_000L,
            maxRetries = 3,
            client = HttpClient(engine),
            waiter = waiter,
            clock = ElapsedClock { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            remainingTurnWait = RemainingTurnWait { 60_000L },
        )

        assertThrows<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }

        assertEquals(3, calls.get(), "every attempt in the budget is spent before the client sees a 429")
        assertEquals(listOf(15_000L, 15_000L), waiter.waits, "the schedule is the 15s floor, not the 5301s header")
        assertEquals(
            MAX_RATE_LIMIT_COOLDOWN_MS,
            client.rateLimitedForMs,
            "exhaustion arms the follower horizon, clamped",
        )
    }

    // V4-48 REVERSED THIS. It used to assert that a short pooled 429 never enters retry backoff —
    // 1 call, no wait, the cooldown armed. A short pushback now takes the same BACKOFF branch a 408
    // or 5xx has always taken, so it WAITS and RETRIES instead of giving up, and it does NOT arm the
    // follower horizon (arming would fail every other turn on the head for the interval this one is
    // waiting out). The name moved with the assertion so it cannot keep claiming the old policy.
    @Test
    fun `a short pooled 429 now waits and retries instead of giving up`() = runTest {
        val calls = AtomicInteger()
        val waiter = RecordingWaiter()
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })
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
            clock = ElapsedClock { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            rateLimitCooldown = cooldown,
            remainingTurnWait = RemainingTurnWait { 20_000L },
        )

        assertThrows<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }

        assertEquals(3, calls.get(), "a short 429 now spends the retry budget it always had")
        // The WAITS do not arm; the FINAL give-up does, exactly as the 5xx branch arms only in its
        // give-up case. So the horizon is the last pushback, not the sum of the waits.
        assertEquals(1_000L, cooldown.remainingMs(), "the final give-up arms; the waits in between do not")
        assertEquals(0L, cooldown.unavailableForMs())
    }

    @Test
    fun `UP-001 - a retryable 503 with a long retry-after DOES arm the shared cooldown`() = runTest {
        for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.RequestTimeout)) {
            val calls = AtomicInteger()
            val cooldown = RateLimitCooldown(ElapsedClock { 0L })
            val engine = MockEngine {
                calls.incrementAndGet()
                respond("busy", status, headersOf("Retry-After", "30"))
            }
            val client = UpstreamClient(
                firstByteTimeoutMs = 5_000L,
                totalTimeoutMs = 60_000L,
                maxRetries = 3,
                client = HttpClient(engine),
                clock = ElapsedClock { 0L },
            )
            val context = PostContext(
                url = "https://api.example.test/v1",
                auth = fakeAuth,
                extraHeaders = { emptyMap() },
                rateLimitCooldown = cooldown,
                remainingTurnWait = RemainingTurnWait { 5_000L },
            )

            assertThrows<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }
            assertEquals(1, calls.get())
            assertEquals(30_000L, cooldown.remainingMs(), "$status must protect followers")
            assertEquals(0L, cooldown.unavailableForMs(), "$status must not remove the account from selection")
            assertThrows<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }
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
            val cooldown = RateLimitCooldown(ElapsedClock { 0L })
            val client = UpstreamClient(
                firstByteTimeoutMs = 5_000L,
                totalTimeoutMs = 60_000L,
                maxRetries = 3,
                client = HttpClient(engine),
                waiter = waiter,
                clock = ElapsedClock { 0L },
            )
            val context = PostContext(
                url = "https://api.example.test/v1",
                auth = fakeAuth,
                extraHeaders = { emptyMap() },
                rateLimitCooldown = cooldown,
                remainingTurnWait = RemainingTurnWait { 100L },
            )

            assertThrows<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }
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
            clock = ElapsedClock { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = auth,
            extraHeaders = { emptyMap() },
            remainingTurnWait = RemainingTurnWait { remaining },
            onRetry = RetryNotice(notices::add),
        )

        val failure = assertThrows<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }

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
                clock = ElapsedClock { elapsed },
            )
            val context = PostContext(
                url = "https://api.example.test/v1",
                auth = auth,
                extraHeaders = { emptyMap() },
                remainingTurnWait = RemainingTurnWait { 5_000L - elapsed },
            )

            assertEquals("ok", client.posted(context, "{}") { "ok" })

            if (leftAfterFirstRound == 0L) {
                // V4-114 PIN: same value, on the second round of a spent turn cap.
                assertEquals(UpstreamPost.TurnWaitExhausted, client.post(context, "{}") { "unreachable" })
                assertEquals(1, calls.get(), "round two must make zero calls after the turn cap is spent")
            } else {
                val failure = assertThrows<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }
                assertEquals(2, calls.get(), "round two may attempt once but cannot spend a new retry budget")
                assertEquals(503, failure.status)
                assertEquals("round two unavailable", failure.body)
            }
        }
    }
}
