// V4-63 split of UpstreamClientRetryPolicyTest.kt, the backoff family. Failure-handling slice 4 rewrote it: the waits
// are RetryEngine's (final-design.md section 8), so a 503 with no time stated waits the overload schedule, a path that
// did not answer waits the unreachable schedule, and a stream torn before the client saw a frame is sent fresh at once.
// The jitter ceilings and the "backoff does not fit" notices the old curve pinned went with it. Helpers shared with the
// sibling classes live in UpstreamClientRetryFixture.kt.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import splice.upstream.RoundBody
import splice.upstream.StreamRead
import splice.upstream.Waiter
import splice.upstream.failure.Replies
import splice.upstream.retry.MAX_ATTEMPTS_OUTSIDE_HOLDS
import splice.upstream.retry.OVERLOAD_FIRST_WAIT_MS
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.UNREACHABLE_FIRST_WAIT_MS
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import splice.upstream.testing.OpenAiTestReplies as OpenAiReplies

private val NOW_MS = Instant.parse("2026-10-27T15:00:00Z").toEpochMilli()

class UpstreamClientBackoffTest {

    @Test
    fun `an HTTP-date outside epoch milliseconds states no time, so a 503 waits the overload schedule`() = runTest {
        val calls = AtomicInteger()
        val capture = Capture()
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) {
                respond(
                    "busy",
                    HttpStatusCode.ServiceUnavailable,
                    headersOf("Retry-After", "31 Dec 999999999 23:59:59 GMT"),
                )
            } else {
                respond("fine", HttpStatusCode.OK, headersOf())
            }
        }

        assertEquals("ok", postOnce(clientOver(engine, capture)))
        assertEquals(2, calls.get())
        assertEquals(listOf(OVERLOAD_FIRST_WAIT_MS), capture.waits, "an unrepresentable date is no stated time")
    }

    @Test
    fun `a garbage Retry-After states no time, so a 503 waits the overload schedule`() = runTest {
        val calls = AtomicInteger()
        val capture = Capture()
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) {
                respond("busy", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "soon"))
            } else {
                respond("fine", HttpStatusCode.OK, headersOf())
            }
        }
        assertEquals("ok", postOnce(clientOver(engine, capture)))
        assertEquals(listOf(OVERLOAD_FIRST_WAIT_MS), capture.waits)
    }

    @Test
    fun `a parseable Retry-After is a FLOOR the schedule cannot undercut`() = runTest {
        // 3 s dwarfs the overload schedule's first 2 s, so the provider's own time wins, exactly.
        val calls = AtomicInteger()
        val waiter = RecordingWaiter()
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) {
                respond("busy", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "3"))
            } else {
                respond("fine", HttpStatusCode.OK, headersOf())
            }
        }
        assertEquals("ok", postOnce(realScheduleClientOver(engine, waiter)))
        assertEquals(listOf(3_000L), waiter.waits)
    }

    @Test
    fun `a transport failure whose next wait the remaining budget cannot hold ends with that failure`() = runTest {
        for (kind in FailureKind.entries) {
            for (remaining in listOf(100L, UNREACHABLE_FIRST_WAIT_MS - 1)) {
                for (postLimited in listOf(false, true)) {
                    val fixture = Fixture(
                        kind,
                        totalTimeoutMs = if (postLimited) remaining else 5_000L,
                        withTurnBudget = !postLimited,
                    )
                    if (!postLimited) fixture.elapsed = 5_000L - remaining

                    val failure = assertThrows<Exception> { fixture.post() }

                    fixture.assertFailure(failure)
                    assertEquals(1, fixture.calls.get())
                    assertTrue(fixture.waits.isEmpty())
                    assertNull(fixture.perf.snapshot().counters[PerfKeys.RETRIES])
                    assertNull(fixture.perf.snapshot().counters[PerfKeys.POST_SEND_RETRIES])
                }
            }
        }
    }

    @Test
    fun `a later round cannot restart a spent or nearly spent transport wait budget`() = runTest {
        for (kind in FailureKind.entries) {
            for (remaining in listOf(0L, 100L)) {
                val fixture = Fixture(kind)
                fixture.failuresLeft = 0
                fixture.consumeOnAttemptMs = 5_000L - remaining
                assertEquals("ok", fixture.post())
                fixture.failuresLeft = 1
                fixture.consumeOnAttemptMs = 0L

                if (remaining == 0L) {
                    // V4-114: a spent turn-wait budget throws nothing; it answers UpstreamPost.TurnWaitExhausted.
                    assertEquals(UpstreamPost.TurnWaitExhausted, fixture.postRaw())
                    assertTrue(fixture.waits.isEmpty())
                    assertEquals(1, fixture.calls.get(), "the second round must not reach upstream")
                    assertNull(fixture.perf.snapshot().counters[PerfKeys.RETRIES])
                    assertEquals(
                        "upstream turn wait budget exhausted before attempt 1/$MAX_ATTEMPTS_OUTSIDE_HOLDS",
                        fixture.notices.single(),
                    )
                } else {
                    val failure = assertThrows<Exception> { fixture.post() }
                    assertTrue(fixture.waits.isEmpty())
                    fixture.assertFailure(failure)
                    assertEquals(2, fixture.calls.get(), "the second round may try once but must not retry")
                    assertNull(fixture.perf.snapshot().counters[PerfKeys.RETRIES])
                }
            }
        }
    }

    @Test
    fun `transport and stream failures spending the last outer milliseconds never sleep`() = runTest {
        for (kind in FailureKind.entries) {
            for (stream in listOf(false, true)) {
                val fixture = Fixture(kind)
                fixture.elapsed = 4_900L
                fixture.consumeOnAttemptMs = 100L
                fixture.failuresLeft = if (stream) 0 else 1

                val failure = assertThrows<Exception> {
                    if (stream) fixture.postWithTornStream() else fixture.post()
                }

                fixture.assertFailure(failure)
                assertEquals(1, fixture.calls.get())
                assertTrue(fixture.waits.isEmpty())
                assertNull(fixture.perf.snapshot().counters[PerfKeys.RETRIES])
            }
        }
    }

    @Test
    fun `a transport retry goes out once the unreachable schedule's first wait fits`() = runTest {
        for (kind in FailureKind.entries) {
            val fixture = Fixture(kind)
            fixture.elapsed = 5_000L - UNREACHABLE_FIRST_WAIT_MS - 1L

            assertEquals("ok", fixture.post())

            assertEquals(2, fixture.calls.get())
            assertEquals(listOf(UNREACHABLE_FIRST_WAIT_MS), fixture.waits)
            assertEquals(1L, fixture.perf.snapshot().counters[PerfKeys.RETRIES])
            assertEquals(
                if (kind == FailureKind.POST_SEND) 1L else null,
                fixture.perf.snapshot().counters[PerfKeys.POST_SEND_RETRIES],
            )
            assertTrue(fixture.notices.single().startsWith(kind.noticeLabel))
        }
    }

    @Test
    fun `a stream torn before the client saw a frame is sent fresh at once, with no wait`() = runTest {
        for (kind in FailureKind.entries) {
            val fixture = Fixture(kind)
            fixture.elapsed = 4_900L
            fixture.failuresLeft = 0

            assertEquals("ok", fixture.postWithTornStream())

            assertEquals(2, fixture.calls.get())
            assertTrue(fixture.waits.isEmpty())
            assertEquals(1L, fixture.perf.snapshot().counters[PerfKeys.RETRIES])
            assertNull(fixture.perf.snapshot().counters[PerfKeys.POST_SEND_RETRIES])
            assertTrue(
                fixture.notices.single()
                    .startsWith("stream torn before first client frame, reissue 1/$MAX_ATTEMPTS_OUTSIDE_HOLDS:"),
            )
        }
    }

    @Test
    fun `a transport retry whose budget expires during its wait records no resend`() = runTest {
        for (kind in FailureKind.entries) {
            val fixture = Fixture(kind)
            fixture.afterWait = { fixture.elapsed = 5_000L }

            val failure = assertThrows<Exception> { fixture.post() }

            fixture.assertFailure(failure)
            assertEquals(1, fixture.calls.get())
            assertEquals(1, fixture.waits.size)
            assertEquals(1L, fixture.perf.snapshot().counters[PerfKeys.ATTEMPTS])
            assertNull(fixture.perf.snapshot().counters[PerfKeys.RETRIES])
            assertNull(fixture.perf.snapshot().counters[PerfKeys.POST_SEND_RETRIES])
        }
    }

    @Test
    fun `a planned retry refused by missing credentials records no resend`() = runTest {
        for (kind in FailureKind.entries) {
            val fixture = Fixture(kind)
            fixture.afterWait = { fixture.credentialsAvailable = false }

            assertEnds<UpstreamAuthMissing> { fixture.post() }

            assertEquals(1, fixture.calls.get())
            assertEquals(1, fixture.waits.size)
            assertEquals(1L, fixture.perf.snapshot().counters[PerfKeys.ATTEMPTS])
            assertNull(fixture.perf.snapshot().counters[PerfKeys.RETRIES])
            assertNull(fixture.perf.snapshot().counters[PerfKeys.POST_SEND_RETRIES])
        }
    }

    // A turn waits only on its own failure, never on another turn's (Marcos, Oct 26): a login another turn marks
    // limited while this one waits out its own transport failure does not stop this turn's retry.
    @Test
    fun `a planned retry still goes out when another turn marks the login limited during its wait`() = runTest {
        for (kind in FailureKind.entries) {
            val fixture = Fixture(kind)
            val hold = RateLimitCooldown(ElapsedClock { fixture.elapsed })
            fixture.afterWait = { hold.arm(60_000L) }

            assertEquals("ok", fixture.postWithHold(hold), "$kind: the retry reaches the provider and gets its answer")
        }
    }

    private fun realScheduleClientOver(engine: MockEngine, waiter: RecordingWaiter) = UpstreamClient(
        totalTimeoutMs = 60_000,
        client = HttpClient(engine),
        pacing = RetryPacing(waiter = waiter, wall = WallClock { NOW_MS }),
    )

    private enum class FailureKind(val noticeLabel: String = "transport ") {
        DNS,
        WRAPPED_DNS,
        CONNECT,
        POST_SEND("transport-possible-duplicate "),
        ;

        fun error(): Exception = when (this) {
            DNS -> UnresolvedAddressException()
            WRAPPED_DNS -> IOException("resolver failed", UnknownHostException("unavailable"))
            CONNECT -> ConnectException("refused")
            POST_SEND -> SocketException("reset")
        }
    }

    private class Fixture(
        kind: FailureKind,
        totalTimeoutMs: Long = 5_000L,
        withTurnBudget: Boolean = true,
    ) {
        var elapsed = 0L
        var consumeOnAttemptMs = 0L
        var failuresLeft = 1
        var afterWait: () -> Unit = {}
        var credentialsAvailable = true
        val failure = kind.error()
        val calls = AtomicInteger()
        val notices = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val perf = TurnPerf { 0L }
        private val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials? =
                if (credentialsAvailable) Credentials.Bearer("test") else null
            override suspend fun refresh(): Credentials? = null
            override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
        }
        private val engine = MockEngine {
            calls.incrementAndGet()
            elapsed += consumeOnAttemptMs
            if (failuresLeft > 0) {
                failuresLeft -= 1
                throw failure
            }
            respond("fine", HttpStatusCode.OK, headersOf())
        }
        private val client = UpstreamClient(
            totalTimeoutMs = totalTimeoutMs,
            client = HttpClient(engine),
            pacing = RetryPacing(
                waiter = Waiter { ms ->
                    waits.add(ms)
                    elapsed += ms
                    afterWait()
                },
            ),
            clock = ElapsedClock { elapsed },
        )
        private val context = PostContext(
            url = "https://api.example.test/v1",
            auth = auth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
            observers = PostObservers(perf = perf, onRetry = { notices.add(it) }),
            limits = PostLimits(
                remainingTurnWait = if (withTurnBudget) RemainingTurnWait { 5_000L - elapsed } else null,
            ),
        )

        fun assertFailure(actual: Exception) {
            assertEquals(failure::class, actual::class)
            assertEquals(failure.message, actual.message)
            // Coroutine stack recovery may wrap the throwable, but must retain the original cause.
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
        }

        suspend fun post(): String = client.posted(context, "{}") { "ok" }

        suspend fun postWithHold(hold: RateLimitCooldown): String =
            client.posted(context.copy(limits = context.limits.copy(rateLimitCooldown = hold)), "{}") { "ok" }

        /** The un-narrowed answer, for the one test whose subject IS the refusal (V4-114). */
        suspend fun postRaw(): UpstreamPost<String> =
            client.post(context, RoundBody.Text("{}")) { StreamRead.Read("ok") }

        suspend fun postWithTornStream(): String {
            var torn = true
            return client.posted(context.copy(recovery = PostRecovery(clientFrameEmitted = { false })), "{}") {
                if (torn) {
                    torn = false
                    throw failure
                }
                "ok"
            }
        }
    }
}
