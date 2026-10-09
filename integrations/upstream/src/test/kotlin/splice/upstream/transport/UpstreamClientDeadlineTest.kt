// NEW (G4d): cross-attempt wall-clock deadline pins. The retry loop reuses totalTimeoutMs as the
// route-timeout analog (HttpTimeout caps each try's call with the same value) — a
// deadline check runs before every new attempt AND before every backoff sleep, on both the
// HTTP-status BACKOFF path and the transport-error retry path, so a pathological run of
// repeated-slow-failing-attempts cannot spin past the budget even with maxRetries left on the
// clock. MockEngine — no network; a fake stepping clock stands in for real sleeps.
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
        maxRetries: Int,
        clock: () -> Long,
    ) = UpstreamClient(
        totalTimeoutMs = totalTimeoutMs,
        maxRetries = maxRetries,
        client = HttpClient(engine),
        pacing = RetryPacing(backoff = { _, _ -> }),
        clock = clock,
    )

    private suspend fun postOnce(client: UpstreamClient): String = client.posted(
        PostContext(url = "https://api.example.test/v1", auth = fakeAuth, extraHeaders = { emptyMap() }),
        "{}",
    ) { "ok" }

    @Test
    fun `an HTTP backoff rejected by the remaining budget records no retry`() = runTest {
        val calls = AtomicInteger()
        val waits = mutableListOf<Long>()
        val perf = TurnPerf { 0L }
        var now = 0L
        val engine = MockEngine {
            calls.incrementAndGet()
            now = 900L
            respond("busy", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        val client = budgetBackoffClient(engine, ElapsedClock { now }, Waiter { waits.add(it) })

        val failure = assertEnds<UpstreamFailed> {
            client.posted(
                PostContext("https://api.example.test/v1", fakeAuth, { emptyMap() }, perf = perf),
                "{}",
            ) { "ok" }
        }

        assertEquals(503, failure.status)
        assertEquals(1, calls.get())
        assertTrue(waits.isEmpty(), "800 ms cannot fit the remaining 100 ms")
        assertEquals(1L, perf.snapshot().counters[PerfKeys.ATTEMPTS])
        assertNull(perf.snapshot().counters[PerfKeys.RETRIES])
    }

    @Test
    fun `a retry whose deadline expires during backoff records no resend`() = runTest {
        val calls = AtomicInteger()
        val waits = mutableListOf<Long>()
        val perf = TurnPerf { 0L }
        var now = 0L
        val engine = MockEngine {
            calls.incrementAndGet()
            now = 100L
            respond("busy", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        val client = budgetBackoffClient(
            engine,
            ElapsedClock { now },
            Waiter { ms ->
                waits.add(ms)
                now += ms + 100L
            },
        )

        assertEnds<UpstreamFailed> {
            client.posted(
                PostContext("https://api.example.test/v1", fakeAuth, { emptyMap() }, perf = perf),
                "{}",
            ) { "ok" }
        }

        assertEquals(listOf(800L), waits)
        assertEquals(1, calls.get())
        assertEquals(1L, perf.snapshot().counters[PerfKeys.ATTEMPTS])
        assertNull(perf.snapshot().counters[PerfKeys.RETRIES])
    }

    private fun budgetBackoffClient(engine: MockEngine, clock: ElapsedClock, waiter: Waiter): UpstreamClient =
        UpstreamClient(
            totalTimeoutMs = 1_000,
            maxRetries = 3,
            client = HttpClient(engine),
            clock = clock,
            pacing = RetryPacing(curve = BackoffCurve(baseMs = 800, capMs = 800, jitterPct = 0), waiter = waiter),
        )

    @Test
    fun `deadline exceeded gives up before exhausting maxRetries on repeated 5xx failures`() = runTest {
        val calls = AtomicInteger()
        var now = 0L
        val engine = MockEngine {
            calls.incrementAndGet()
            now += 3_000
            respond("boom", HttpStatusCode.ServiceUnavailable, headersOf())
        }
        val client = clientOver(engine, totalTimeoutMs = 5_000, maxRetries = 10) { now }
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertTrue(calls.get() < 10, "deadline should cut the loop short, not exhaust attempts (${calls.get()})")
    }

    @Test
    fun `deadline exceeded gives up before exhausting maxRetries on repeated transport failures`() = runTest {
        val calls = AtomicInteger()
        var now = 0L
        val engine = MockEngine {
            calls.incrementAndGet()
            now += 3_000
            throw ConnectException("refused")
        }
        val client = clientOver(engine, totalTimeoutMs = 5_000, maxRetries = 10) { now }
        assertThrows<ConnectException> { postOnce(client) }
        assertTrue(calls.get() < 10, "deadline should cut the loop short, not exhaust attempts (${calls.get()})")
    }

    @Test
    fun `a renewed outer progress budget is not capped again by post elapsed time`() = runTest {
        val calls = AtomicInteger()
        var now = 0L
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("fine", HttpStatusCode.OK, headersOf())
        }
        val client = clientOver(engine, totalTimeoutMs = 1_000, maxRetries = 3) { now }
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            remainingTurnWait = RemainingTurnWait { 5_000 },
            clientFrameEmitted = { false },
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
        val client = clientOver(engine, totalTimeoutMs = 60_000, maxRetries = 3, clock = System::currentTimeMillis)
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
        val client = clientOver(engine, totalTimeoutMs = 0, maxRetries = 3) { 0L }
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(0, calls.get())
    }
}
