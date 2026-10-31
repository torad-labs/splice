package splice.upstream.retry

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.core.wire.NativeReply
import splice.upstream.RetryNotice
import splice.upstream.RoundBody
import splice.upstream.StreamRead
import splice.upstream.Waiter
import splice.upstream.failure.Replies
import splice.upstream.transport.PostContext
import splice.upstream.transport.PostLimits
import splice.upstream.transport.PostObservers
import splice.upstream.transport.PostRecovery
import splice.upstream.transport.RecordingWaiter
import splice.upstream.transport.RemainingTurnWait
import splice.upstream.transport.RetryPacing
import splice.upstream.transport.UpstreamClient
import splice.upstream.transport.UpstreamFailed
import splice.upstream.transport.UpstreamPost
import splice.upstream.transport.assertEnds
import splice.upstream.transport.fakeAuth
import splice.upstream.transport.posted
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import splice.upstream.testing.OpenAiTestReplies as OpenAiReplies

class RateLimitCooldownTest {
    // Failure-handling slice 4: whether a 429 is waited out, held or handed to another login is RetryEngine's
    // (RetryEngineModelTest, RetryEngineThroughClientTest). What a turn leaves on the login once it leaves it on a
    // limit is this file's: the arm, the eviction of a pooled login, the provider's reset and its log line.

    /** The login a turn left on a limit: [pushbackMs] is what its Retry-After asked for, [lift] what the reader read. */
    private fun left(pushbackMs: Long?, pooled: Boolean, lift: Lift = Lift.Unstated): LimitLeft =
        LimitLeft(lift, pushbackMs, planLimit = null, native = null, pooled = pooled)

    @Test
    fun `a limit with no time stated marks its login limited for the default cooldown`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })

        cooldown.limitLeft(left(pushbackMs = null, pooled = false), RetryNotice {})

        assertEquals(20_000L, cooldown.remainingMs(), "a bare 429 arms the default cooldown")
    }

    @Test
    fun `a pooled login left past the floor is removed from selection for that long`() {
        var elapsed = 10L
        val cooldown = RateLimitCooldown(ElapsedClock { elapsed })

        cooldown.limitLeft(left(pushbackMs = 15_001L, pooled = true), RetryNotice {})

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
    fun `multi-day 429 names the bounded horizon on its login`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })
        val notices = mutableListOf<String>()

        cooldown.limitLeft(left(pushbackMs = 86_400_000L, pooled = true), RetryNotice(notices::add))

        assertTrue(notices.any { it.contains("arming 120000ms on this login") })
        assertEquals(MAX_RATE_LIMIT_COOLDOWN_MS, cooldown.remainingMs())
        assertEquals(MAX_RATE_LIMIT_COOLDOWN_MS, cooldown.unavailableForMs())
        assertEquals(86_400_000L, cooldown.providerUnavailableForMs())
    }

    @Test
    fun `bare 429 does not invent an account-unavailable reset`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })

        cooldown.limitLeft(left(pushbackMs = null, pooled = true), RetryNotice {})

        assertEquals(0L, cooldown.unavailableForMs())
        assertEquals(20_000L, cooldown.remainingMs())
    }

    // V4-46: the INSTRUMENT. It fires on every 429 a turn leaves its login on, so a short armed cooldown can be
    // attributed to a short Retry-After or to the 20s default.
    @Test
    fun `the arming line names the Retry-After header and distinguishes absent from present`() {
        val notices = mutableListOf<String>()
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })

        cooldown.limitLeft(left(pushbackMs = null, pooled = false), RetryNotice(notices::add))
        cooldown.limitLeft(left(pushbackMs = 19_000L, pooled = false), RetryNotice(notices::add))

        assertTrue(
            notices.any { it == "429 rate limit: Retry-After header ABSENT, arming 20000ms on this login" },
            notices.toString(),
        )
        assertTrue(
            notices.any { it == "429 rate limit: Retry-After header 19000ms, arming 19000ms on this login" },
            notices.toString(),
        )
    }

    // V4-47's defect: markUnavailable fires only for POOLED logins past the floor, so on a single-account head the
    // provider reset must be recorded by the capture itself.
    @Test
    fun `a single-account head records the provider reset where markUnavailable never fired`() {
        val wall = Instant.parse("2026-09-16T13:34:32Z").toEpochMilli()
        val cooldown = RateLimitCooldown(ElapsedClock { 0L }, WallClock { wall })
        // "resets at 2026-09-16T20:02:52Z", as the reply's reader read it from the body.
        val lift = Lift.At(Instant.parse("2026-09-16T20:02:52Z"), firm = true, LiftSource.BODY)

        cooldown.limitLeft(left(pushbackMs = 5_301_000L, pooled = false, lift = lift), RetryNotice {})

        assertEquals(0L, cooldown.unavailableForMs(), "a single-account head is never evicted")
        assertEquals(23_300_000L, cooldown.providerUnavailableForMs(), "but its reset IS recorded")
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
            totalTimeoutMs = 60_000L,
            client = HttpClient(engine),
            clock = ElapsedClock { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
            limits = PostLimits(remainingTurnWait = RemainingTurnWait { 0L }),
            observers = PostObservers(onRetry = RetryNotice(notices::add)),
        )

        // V4-114 PIN: the exhausted turn-wait budget is a VALUE on post()'s return type. This line
        // does not compile against the old shape (post returned T and threw
        // UpstreamTurnWaitExhausted), and a Delivered here fails the assertEquals instead of
        // arriving as an exception any broad catch on the turn path would have taken for a bug.
        val round = RoundBody.Text("{}")
        assertEquals(UpstreamPost.TurnWaitExhausted, client.post(context, round) { StreamRead.Read("unreachable") })

        assertEquals(0, calls.get())
        // The operator-visible line is unchanged; the class that used to carry it is gone.
        assertEquals(listOf("upstream turn wait budget exhausted before attempt 1/24"), notices)
    }

    // Failure-handling slice 4: RetryEngine ends a turn whose next wait the turn cannot hold, so no wait starts and no
    // retry is counted.
    @Test
    fun `a wait the remaining turn budget cannot hold ends the post with no retry`() = runTest {
        val calls = AtomicInteger()
        val waiter = RecordingWaiter()
        val perf = TurnPerf { 0L }
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("busy", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        val client = UpstreamClient(
            totalTimeoutMs = 60_000L,
            client = HttpClient(engine),
            pacing = RetryPacing(waiter = waiter),
            clock = ElapsedClock { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
            observers = PostObservers(perf = perf),
            limits = PostLimits(remainingTurnWait = RemainingTurnWait { 100L }),
        )

        assertEnds<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }

        assertEquals(1, calls.get())
        assertTrue(waiter.waits.isEmpty())
        assertEquals(null, perf.snapshot().counters[PerfKeys.RETRIES])
    }

    // V4-61's case under section 8: a reset stated past the 15 s floor is not waited out on a cold turn's login. Until
    // slice 5 holds a turn it ends at once, and the login is marked limited, clamped.
    @Test
    fun `a non-pooled 429 whose reset is past the floor ends at once and marks its login limited`() = runTest {
        val calls = AtomicInteger()
        val waiter = RecordingWaiter()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "5301"))
        }
        val client = UpstreamClient(
            totalTimeoutMs = 60_000L,
            client = HttpClient(engine),
            pacing = RetryPacing(waiter = waiter),
            clock = ElapsedClock { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
            limits = PostLimits(remainingTurnWait = RemainingTurnWait { 60_000L }),
        )

        assertEnds<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }

        assertEquals(1, calls.get(), "a reset past the floor is never waited out on the login")
        assertTrue(waiter.waits.isEmpty(), waiter.waits.toString())
        assertEquals(MAX_RATE_LIMIT_COOLDOWN_MS, client.rateLimitedForMs, "the login is marked limited, clamped")
    }

    // Section 8's burst row: a 429 stated inside the floor is waited out on its login at 2, 4, 8 and 15 s, never under
    // the time it stated, and then a pooled turn moves to another login. Only that last answer arms the login.
    @Test
    fun `a short pooled 429 is waited out on the burst schedule, then handed to another login`() = runTest {
        val calls = AtomicInteger()
        val waiter = RecordingWaiter()
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1"))
        }
        val client = UpstreamClient(
            totalTimeoutMs = 60_000L,
            client = HttpClient(engine),
            pacing = RetryPacing(waiter = waiter),
            clock = ElapsedClock { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
            limits = PostLimits(
                rateLimitCooldown = cooldown,
                remainingTurnWait = RemainingTurnWait { 20_000L },
            ),
            // No byte reached the client: a login switches only before content (section 8).
            recovery = PostRecovery(clientFrameEmitted = { false }),
        )

        val posted = client.post(context, RoundBody.Text("{}")) { StreamRead.Read("unreachable") }

        assertTrue(posted is UpstreamPost.Refused, "the pooled turn is handed to another login: $posted")
        assertEquals(5, calls.get())
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L), waiter.waits)
        assertEquals(1_000L, cooldown.remainingMs(), "the last answer arms; the waits in between do not")
        assertEquals(0L, cooldown.unavailableForMs())
    }

    // A 408 is no row of a reader's table since slice 3: an unknown 4xx, sent once more and never marked
    // (OneEngineThroughHeadsTest).
    @Test
    fun `a 503 with a long retry-after marks its login limited without excluding it`() = runTest {
        for (status in listOf(HttpStatusCode.ServiceUnavailable)) {
            val calls = AtomicInteger()
            val cooldown = RateLimitCooldown(ElapsedClock { 0L })
            val engine = MockEngine {
                calls.incrementAndGet()
                respond("busy", status, headersOf("Retry-After", "30"))
            }
            val client = UpstreamClient(
                totalTimeoutMs = 60_000L,
                client = HttpClient(engine),
                clock = ElapsedClock { 0L },
            )
            val context = PostContext(
                url = "https://api.example.test/v1",
                auth = fakeAuth,
                extraHeaders = { emptyMap() },
                replies = Replies(OpenAiReplies),
                limits = PostLimits(
                    rateLimitCooldown = cooldown,
                    remainingTurnWait = RemainingTurnWait { 5_000L },
                ),
            )

            assertEnds<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }
            assertEquals(1, calls.get())
            assertEquals(30_000L, cooldown.remainingMs(), "$status marks its login limited")
            assertEquals(0L, cooldown.unavailableForMs(), "$status must not remove the account from selection")
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
                totalTimeoutMs = 60_000L,
                client = HttpClient(engine),
                pacing = RetryPacing(waiter = waiter),
                clock = ElapsedClock { 0L },
            )
            val context = PostContext(
                url = "https://api.example.test/v1",
                auth = fakeAuth,
                extraHeaders = { emptyMap() },
                replies = Replies(OpenAiReplies),
                limits = PostLimits(
                    rateLimitCooldown = cooldown,
                    remainingTurnWait = RemainingTurnWait { 100L },
                ),
            )

            assertEnds<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }
            assertEquals(1, calls.get(), "Retry-After $retryAfter must not outlive the turn budget")
            assertTrue(waiter.waits.isEmpty(), "Retry-After $retryAfter must not start the shipped backoff")
            assertEquals(0L, cooldown.unavailableForMs())
        }
    }
}

class RateLimitCooldownOuterTurnTest {
    @Test
    fun `every refusal writer protects its state from an earlier posting receipt`() {
        for (writer in 0..4) {
            val cooldown = RateLimitCooldown(ElapsedClock { 0L }, WallClock { 1_000L })
            val accepted = cooldown.acceptance()
            when (writer) {
                0 -> cooldown.arm(60_000L)
                1 -> cooldown.markUnavailable(60_000L)
                2 -> cooldown.captureProviderReset(Lift.At(Instant.ofEpochMilli(61_000L), true, LiftSource.BODY))
                3 -> cooldown.planHold.hold(PlanLimit("seven_day", 61L), RetryNotice { })
                4 -> cooldown.rateLimitReply = NativeReply("synthetic refusal", emptyMap())
            }
            val horizon = cooldown.remainingMs()
            val selection = cooldown.unavailableForMs()
            val reset = cooldown.providerUnavailableForMs()
            val plan = cooldown.planHold.live()
            val reply = cooldown.rateLimitReply

            accepted()

            assertEquals(horizon, cooldown.remainingMs(), "writer $writer retains its horizon")
            assertEquals(selection, cooldown.unavailableForMs(), "writer $writer retains account exclusion")
            assertEquals(reset, cooldown.providerUnavailableForMs(), "writer $writer retains its reset")
            assertEquals(plan, cooldown.planHold.live(), "writer $writer retains its plan")
            assertEquals(reply, cooldown.rateLimitReply, "writer $writer retains its provider reply")
        }
    }

    @Test
    fun `a posting after all holds clears them even without advancing either clock`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L }, WallClock { 1_000L })
        cooldown.arm(60_000L)
        cooldown.markUnavailable(60_000L)
        cooldown.captureProviderReset(Lift.At(Instant.ofEpochMilli(61_000L), true, LiftSource.BODY))
        cooldown.planHold.hold(PlanLimit("seven_day", 61L), RetryNotice { })
        cooldown.rateLimitReply = NativeReply("synthetic refusal", emptyMap())
        val accepted = cooldown.acceptance()

        accepted()

        assertEquals(0L, cooldown.remainingMs())
        assertEquals(0L, cooldown.unavailableForMs())
        assertEquals(0L, cooldown.providerUnavailableForMs())
        assertEquals(null, cooldown.planHold.live())
        assertEquals(null, cooldown.rateLimitReply)
    }

    @Test
    fun `a repeated refusal with unchanged facts is still newer than a posting`() {
        val cooldown = RateLimitCooldown(ElapsedClock { 0L }, WallClock { 1_000L })
        val plan = PlanLimit("seven_day", 61L)
        cooldown.planHold.hold(plan, RetryNotice { })
        val accepted = cooldown.acceptance()
        cooldown.planHold.hold(plan, RetryNotice { })

        accepted()

        assertEquals(plan, cooldown.planHold.live(), "an equal reset is a new refusal observation")
    }

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
            totalTimeoutMs = 60_000L,
            client = HttpClient(engine),
            pacing = RetryPacing(waiter = { remaining = 0L }),
            clock = ElapsedClock { 0L },
        )
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = auth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
            limits = PostLimits(remainingTurnWait = RemainingTurnWait { remaining }),
            observers = PostObservers(onRetry = RetryNotice(notices::add)),
        )

        val failure = assertEnds<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }

        assertEquals(503, failure.evidence.status)
        assertEquals("provider-specific failure", failure.evidence.message)
        assertEquals(1, calls.get())
        assertTrue(notices.contains("upstream turn wait budget exhausted before attempt 2/24"), notices.toString())
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
                totalTimeoutMs = 5_000L,
                client = HttpClient(engine),
                clock = ElapsedClock { elapsed },
            )
            val context = PostContext(
                url = "https://api.example.test/v1",
                auth = auth,
                extraHeaders = { emptyMap() },
                replies = Replies(OpenAiReplies),
                limits = PostLimits(remainingTurnWait = RemainingTurnWait { 5_000L - elapsed }),
            )

            assertEquals("ok", client.posted(context, "{}") { "ok" })

            if (leftAfterFirstRound == 0L) {
                // V4-114 PIN: same value, on the second round of a spent turn cap.
                val round = RoundBody.Text("{}")
                assertEquals(
                    UpstreamPost.TurnWaitExhausted,
                    client.post(context, round) { StreamRead.Read("unreachable") },
                )
                assertEquals(1, calls.get(), "round two must make zero calls after the turn cap is spent")
            } else {
                val failure = assertEnds<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }
                assertEquals(2, calls.get(), "round two may attempt once but cannot spend a new retry budget")
                assertEquals(503, failure.evidence.status)
                assertEquals("round two unavailable", failure.evidence.message)
            }
        }
    }
}
