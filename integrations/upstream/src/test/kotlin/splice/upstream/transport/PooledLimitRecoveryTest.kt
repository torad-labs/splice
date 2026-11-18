package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LimitWindow
import splice.core.util.ElapsedClock
import splice.core.util.WallClock
import splice.upstream.RetryNotice
import splice.upstream.RoundBody
import splice.upstream.StreamRead
import splice.upstream.TurnRetry
import splice.upstream.Waiter
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.credentials.PoolAccount
import splice.upstream.failure.Replies
import splice.upstream.retry.LimitLeft
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.RetryLedger
import splice.upstream.testing.OpenAiTestReplies

class PooledLimitRecoveryTest {
    @Test
    fun `a bare 429 hands the turn to a free sibling before waiting`() = runTest {
        val engine = MockEngine { respond("Rate limit exceeded", HttpStatusCode.TooManyRequests) }
        HttpClient(engine).use { http ->
            val cooldown = RateLimitCooldown(ElapsedClock { 0L })
            val client =
                UpstreamClient(
                    totalTimeoutMs = 60_000L,
                    client = http,
                    pacing = RetryPacing(
                        waiter = Waiter {
                            error("a free login can answer now")
                        },
                    ),
                )

            val result = client.post(context(cooldown, free = true), RoundBody.Text("{}")) { StreamRead.Read("answer") }

            assertInstanceOf(UpstreamPost.Refused::class.java, result)
            assertNull(cooldown.terminalLimit, "an absent reset is not proof of a spent plan")
        }
    }

    @Test
    fun `a cached exhausted login can hand off without extending its refusal`() = runTest {
        var now = 0L
        val cooldown = RateLimitCooldown(ElapsedClock { now })
        val cause = FailureCause.Provider.Limited(Lift.Unstated, LimitWindow.BILLING_CYCLE)
        cooldown.limitLeft(LimitLeft(cause.lift, null, null, null, true, cause), RetryNotice {})
        now = 19_000L
        HttpClient(MockEngine { error("a known exhausted credential must not be posted again") }).use { http ->
            val client = UpstreamClient(totalTimeoutMs = 60_000L, client = http, clock = ElapsedClock { now })

            val result = client.post(context(cooldown, free = true), RoundBody.Text("{}")) { StreamRead.Read("answer") }

            assertInstanceOf(UpstreamPost.Refused::class.java, result)
            assertEquals(1_000L, cooldown.remainingMs(), "handoff does not renew an old refusal")
        }
    }

    @Test
    fun `without a sibling a short unstated throttle recovers within the same turn`() = runTest {
        var now = 0L
        HttpClient(
            MockEngine {
                if (now == 0L) {
                    respond("Rate limit exceeded", HttpStatusCode.TooManyRequests)
                } else {
                    respond("answer", HttpStatusCode.OK)
                }
            },
        ).use { http ->
            val client = UpstreamClient(
                totalTimeoutMs = 180_000L,
                client = http,
                clock = ElapsedClock { now },
                pacing = RetryPacing(waiter = Waiter { now += it }),
            )

            val result = client.post(
                context(RateLimitCooldown(ElapsedClock { now }), free = false),
                RoundBody.Text("{}"),
            ) {
                StreamRead.Read("answer")
            }

            assertEquals(UpstreamPost.Delivered("answer"), result)
            assertTrue(now in 1L..30_000L, "recovery is a short bounded pause")
        }
    }

    @Test
    fun `a persistent bare throttle ends retryably instead of holding without a reset`() = runTest {
        var now = 0L
        HttpClient(MockEngine { respond("Rate limit exceeded", HttpStatusCode.TooManyRequests) }).use { http ->
            val client = UpstreamClient(
                client = http,
                totalTimeoutMs = 180_000L,
                clock = ElapsedClock { now },
                pacing = RetryPacing(waiter = Waiter { now += it }),
            )
            val result = client.post(
                context(RateLimitCooldown(ElapsedClock { now }), free = false),
                RoundBody.Text("{}"),
            ) {
                StreamRead.Read("answer")
            }

            val ending = assertInstanceOf(UpstreamPost.Ended::class.java, result)
            val failure = assertInstanceOf(UpstreamFailed::class.java, ending.ending)
            assertInstanceOf(FailureCause.Provider.Limited::class.java, failure.cause)
            assertTrue(
                now in 60_000L..120_000L,
                "a shared throttle gets bounded recovery time, never an indefinite hold",
            )
            assertNull(failure.planLimit)
        }
    }

    /** Nov 17, 2026: one small request Anthropic refused with a bare 429 on every login was asked of every login of a
     *  pool, marked each limited and let go of the Use now pin. The same answer on a second login is no login's. */
    @Test
    fun `a bare 429 two logins answer alike is not asked of a third, marks neither and keeps the pin`() = runTest {
        var now = 0L
        val first = account("first", ElapsedClock { now })
        val second = account("second", ElapsedClock { now })
        val pool = AccountPool(listOf(first, second, account("third", ElapsedClock { now })), WallClock { 1_000_000L })
        pool.pin("first")
        HttpClient(MockEngine { respond("Rate limit exceeded", HttpStatusCode.TooManyRequests) }).use { http ->
            val client = UpstreamClient(client = http, totalTimeoutMs = 180_000L, clock = ElapsedClock { now })
            val turn = TurnRetry(RetryLedger()) { afterMs, _, _ -> now += afterMs }

            val left = client.post(context(first.cooldown, free = true, turn), RoundBody.Text("{}")) {
                StreamRead.Read("answer")
            }
            val stayed = client.post(context(second.cooldown, free = true, turn), RoundBody.Text("{}")) {
                StreamRead.Read("answer")
            }

            assertInstanceOf(UpstreamPost.Refused::class.java, left, "one other login is asked")
            val ending = assertInstanceOf(UpstreamPost.Ended::class.java, stayed, "the pool is not walked")
            assertInstanceOf(FailureCause.Provider.Limited::class.java, (ending.ending as UpstreamFailed).cause)
            assertEquals(0L, first.cooldown.remainingMs(), "the first login is not shown limited")
            assertEquals(0L, second.cooldown.remainingMs(), "nor the second")
            assertEquals("first", pool.pinned(), "a refusal that is no login's lets no pin go")
        }
    }

    @Test
    fun `a bare 429 the next login does not repeat marks the login it came from`() = runTest {
        var now = 0L
        val first = account("first", ElapsedClock { now })
        val second = account("second", ElapsedClock { now })
        val pool = AccountPool(listOf(first, second), WallClock { 1_000_000L })
        pool.pin("first")
        var sends = 0
        val engine = MockEngine {
            sends += 1
            if (sends == 1) respond("Rate limit exceeded", HttpStatusCode.TooManyRequests) else respond("answer")
        }
        HttpClient(engine).use { http ->
            val client = UpstreamClient(client = http, totalTimeoutMs = 180_000L, clock = ElapsedClock { now })
            val turn = TurnRetry(RetryLedger()) { afterMs, _, _ -> now += afterMs }

            val left = client.post(context(first.cooldown, free = true, turn), RoundBody.Text("{}")) {
                StreamRead.Read("answer")
            }
            assertEquals("first", pool.pinned(), "whose the limit is stays open until another login answers")
            val served = client.post(context(second.cooldown, free = true, turn), RoundBody.Text("{}")) {
                StreamRead.Read("answer")
            }

            assertInstanceOf(UpstreamPost.Refused::class.java, left)
            assertEquals(UpstreamPost.Delivered("answer"), served)
            assertTrue(first.cooldown.remainingMs() > 0L, "the login that alone refused is shown limited")
            assertNull(pool.pinned(), "and its Use now pin lets go")
        }
    }

    private fun account(label: String, clock: ElapsedClock) = PoolAccount(
        label = label,
        primary = label == "first",
        auth = fakeAuth,
        quota = AccountQuotaSource { null },
        cooldown = RateLimitCooldown(clock),
    )

    private fun context(cooldown: RateLimitCooldown, free: Boolean, turn: TurnRetry) =
        context(cooldown, free).let { it.copy(limits = it.limits.copy(retry = turn)) }

    private fun context(cooldown: RateLimitCooldown, free: Boolean) = PostContext(
        url = "https://api.example.test/v1",
        auth = fakeAuth,
        extraHeaders = { emptyMap() },
        replies = Replies(OpenAiTestReplies),
        recovery = PostRecovery(clientFrameEmitted = { false }),
        limits = PostLimits(rateLimitCooldown = cooldown, otherLoginFree = { free }),
    )
}
