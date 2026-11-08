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
import splice.upstream.RetryNotice
import splice.upstream.RoundBody
import splice.upstream.StreamRead
import splice.upstream.Waiter
import splice.upstream.failure.Replies
import splice.upstream.retry.LimitLeft
import splice.upstream.retry.RateLimitCooldown
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

    private fun context(cooldown: RateLimitCooldown, free: Boolean) = PostContext(
        url = "https://api.example.test/v1",
        auth = fakeAuth,
        extraHeaders = { emptyMap() },
        replies = Replies(OpenAiTestReplies),
        recovery = PostRecovery(clientFrameEmitted = { false }),
        limits = PostLimits(rateLimitCooldown = cooldown, otherLoginFree = { free }),
    )
}
