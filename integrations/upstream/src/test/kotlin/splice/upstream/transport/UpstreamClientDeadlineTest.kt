// NEW (G4d): cross-attempt wall-clock deadline pins. The retry loop reuses totalTimeoutMs as the
// route-timeout analog (HttpTimeout caps each try's call with the same value). A deadline check runs before every
// new attempt, and RetryEngine ends a post whose next wait the time left cannot hold (failure-handling slice 4), on
// both the HTTP-status path and the transport-error path, so a run of slow failing attempts cannot spin past the
// budget while the engine's schedule still has waits left. MockEngine, no network; a fake stepping clock stands in
// for real sleeps.
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
import splice.upstream.StreamRead
import splice.upstream.Waiter
import splice.upstream.failure.OpenAiReplies
import splice.upstream.failure.Replies
import splice.upstream.retry.OVERLOAD_FIRST_WAIT_MS
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicInteger

class UpstreamClientDeadlineTest {

    private val fakeAuth = object : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials? = Credentials.ApiKey("k", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "fake", emptyMap())
    }

    private fun clientOver(
        engine: MockEngine,
        totalTimeoutMs: Long,
        clock: () -> Long,
    ) = UpstreamClient(
        totalTimeoutMs = totalTimeoutMs,
        client = HttpClient(engine),
        pacing = RetryPacing(waiter = Waiter { }),
        clock = clock,
    )

    private suspend fun postOnce(client: UpstreamClient): String = client.posted(
        PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
        ),
        "{}",
    ) { "ok" }

    @Test
    fun `an HTTP wait the remaining budget cannot hold records no retry`() = runTest {
        val calls = AtomicInteger()
        val waits = mutableListOf<Long>()
        val perf = TurnPerf { 0L }
        var now = 0L
        val engine = MockEngine {
            calls.incrementAndGet()
            now = 900L
            respond("busy", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        val client = budgetClient(engine, 1_000, ElapsedClock { now }, Waiter { waits.add(it) })

        val failure = assertEnds<UpstreamFailed> {
            client.posted(
                PostContext(
                    "https://api.example.test/v1",
                    fakeAuth,
                    { emptyMap() },
                    replies = Replies(OpenAiReplies),
                    observers = PostObservers(perf = perf),
                ),
                "{}",
            ) { "ok" }
        }

        assertEquals(503, failure.evidence.status)
        assertEquals(1, calls.get())
        assertTrue(waits.isEmpty(), "the overload schedule's first 2 s cannot fit the remaining 100 ms")
        assertEquals(1L, perf.snapshot().counters[PerfKeys.ATTEMPTS])
        assertNull(perf.snapshot().counters[PerfKeys.RETRIES])
    }

    @Test
    fun `a retry whose deadline expires during its wait records no resend`() = runTest {
        val calls = AtomicInteger()
        val waits = mutableListOf<Long>()
        val perf = TurnPerf { 0L }
        var now = 0L
        val engine = MockEngine {
            calls.incrementAndGet()
            now = 100L
            respond("busy", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        val client = budgetClient(
            engine,
            3_000,
            ElapsedClock { now },
            Waiter { ms ->
                waits.add(ms)
                // the wait overruns the deadline
                now += ms + 1_000L
            },
        )

        assertEnds<UpstreamFailed> {
            client.posted(
                PostContext(
                    "https://api.example.test/v1",
                    fakeAuth,
                    { emptyMap() },
                    replies = Replies(OpenAiReplies),
                    observers = PostObservers(perf = perf),
                ),
                "{}",
            ) { "ok" }
        }

        assertEquals(listOf(OVERLOAD_FIRST_WAIT_MS), waits)
        assertEquals(1, calls.get())
        assertEquals(1L, perf.snapshot().counters[PerfKeys.ATTEMPTS])
        assertNull(perf.snapshot().counters[PerfKeys.RETRIES])
    }

    private fun budgetClient(engine: MockEngine, totalMs: Long, clock: ElapsedClock, waiter: Waiter): UpstreamClient =
        UpstreamClient(
            totalTimeoutMs = totalMs,
            client = HttpClient(engine),
            clock = clock,
            pacing = RetryPacing(waiter = waiter),
        )

    @Test
    fun `the deadline cuts repeated 5xx failures short of the overload schedule`() = runTest {
        val calls = AtomicInteger()
        var now = 0L
        val engine = MockEngine {
            calls.incrementAndGet()
            now += 3_000
            respond("boom", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        val client = clientOver(engine, totalTimeoutMs = 5_000) { now }
        assertEnds<UpstreamFailed> { postOnce(client) }
        // 3 s spent by the first send leaves 2 s, which holds the first 2 s wait; the second send spends the rest.
        assertEquals(2, calls.get(), "the deadline cuts the loop short, not the schedule")
    }

    @Test
    fun `the deadline cuts repeated transport failures short of the unreachable schedule`() = runTest {
        val calls = AtomicInteger()
        var now = 0L
        val engine = MockEngine {
            calls.incrementAndGet()
            now += 3_000
            throw ConnectException("refused")
        }
        val client = clientOver(engine, totalTimeoutMs = 5_000) { now }
        assertThrows<ConnectException> { postOnce(client) }
        // 3 s spent by the first send leaves 2 s, which holds the first 1 s wait; the second send spends the rest.
        assertEquals(2, calls.get(), "the deadline cuts the loop short, not the schedule")
    }

    @Test
    fun `a renewed outer progress budget is not capped again by post elapsed time`() = runTest {
        val calls = AtomicInteger()
        var now = 0L
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("fine", HttpStatusCode.OK, headersOf())
        }
        val client = clientOver(engine, totalTimeoutMs = 1_000) { now }
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
            limits = PostLimits(remainingTurnWait = RemainingTurnWait { 5_000 }),
            recovery = PostRecovery(clientFrameEmitted = { false }),
        )
        var deliveries = 0
        val result = client.postedRead(context, "{}") {
            if (deliveries++ == 0) {
                now = 2_000
                StreamRead.Torn(java.net.SocketException("synthetic pre-content tear after reasoning"))
            } else {
                StreamRead.Read("ok")
            }
        }
        assertEquals("ok", result, "the still-renewed owner, not a second wall clock, decides the retry budget")
        assertEquals(2, calls.get())
    }

    @Test
    fun `ample deadline still allows the full attempt budget`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) {
                respond("boom", HttpStatusCode.ServiceUnavailable, headersOf())
            } else {
                respond("fine", HttpStatusCode.OK, headersOf())
            }
        }
        val client = clientOver(engine, totalTimeoutMs = 60_000, clock = System::currentTimeMillis)
        assertEquals("ok", postOnce(client))
        assertEquals(2, calls.get())
    }

    @Test
    fun `deadline check fires before the very first attempt if already exceeded at entry`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("fine", HttpStatusCode.OK, headersOf())
        }
        val client = clientOver(engine, totalTimeoutMs = 0) { 0L }
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(0, calls.get())
    }
}
