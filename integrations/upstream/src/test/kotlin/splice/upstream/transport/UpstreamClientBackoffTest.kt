// NEW: V4-63 split of UpstreamClientRetryPolicyTest.kt, the backoff family. Pure move: every
// test below is byte-identical to its original, same name, no assertion changes. Helpers shared with
// the sibling classes live in UpstreamClientRetryFixture.kt.
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
import splice.upstream.Waiter
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.atomic.AtomicInteger

class UpstreamClientBackoffTest {

    @Test
    fun `an HTTP-date outside epoch milliseconds falls back to the production retry curve`() = runTest {
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
        assertEquals(listOf(0L), capture.minDelays, "unrepresentable date must fall back to the curve")
    }

    @Test
    fun `garbage retry-after falls back to the curve`() = runTest {
        // NF-04 REWRITE: the old fixture used an HTTP-date as its "malformed" header — that form
        // is now PARSED (RFC 7231), so genuine garbage carries the null-means-curve contract.
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
        assertEquals(listOf(0L), capture.minDelays) // no parseable floor — curve alone decides
    }

    @Test
    fun `the shipped backoff curve doubles from 200ms inside its jitter band`() = runTest {
        val waiter = RecordingWaiter()
        val engine = MockEngine { respond("busy", HttpStatusCode.ServiceUnavailable, headersOf()) }
        assertThrows<UpstreamFailed> { postOnce(realCurveClientOver(engine, waiter)) }
        assertTrue(waiter.waits.size >= 2, "expected the retry loop to back off at least twice: ${waiter.waits}")
        waiter.waits.forEachIndexed { attempt, waited ->
            val base = minOf(200L shl attempt, 10_000L)
            assertTrue(
                waited >= (base * 0.9).toLong() && waited <= (base * 1.1).toLong(),
                "attempt $attempt waited ${waited}ms, outside the +/-10% band around ${base}ms: ${waiter.waits}",
            )
        }
    }

    @Test
    fun `a parseable Retry-After is a FLOOR the curve cannot undercut`() = runTest {
        // G3: minDelayMs rides in as a floor. 3s dwarfs attempt 0's ~200ms, so the floor must win
        // exactly — the shipped lambda's `maxOf(jittered, minDelayMs)`, observed rather than restated.
        val calls = AtomicInteger()
        val waiter = RecordingWaiter()
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) {
                respond("busy", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "3"))
            } else {
                respond("fine", HttpStatusCode.OK, headersOf())
            }
        }
        assertEquals("ok", postOnce(realCurveClientOver(engine, waiter)))
        assertEquals(listOf(3_000L), waiter.waits)
    }

    @Test
    fun `the shipped dns curve walks 1s-2s-4s inside its jitter band`() = runTest {
        // G14: DNS-class transport failures run dnsBackoff, not the generic curve.
        val waiter = RecordingWaiter()
        val engine = MockEngine { throw UnresolvedAddressException() }
        assertThrows<UnresolvedAddressException> { postOnce(realCurveClientOver(engine, waiter)) }
        assertTrue(waiter.waits.size >= 2, "expected DNS retries to back off: ${waiter.waits}")
        waiter.waits.forEachIndexed { attempt, waited ->
            val base = minOf(1_000L shl attempt, 4_000L)
            assertTrue(
                waited >= (base * 0.9).toLong() && waited <= (base * 1.1).toLong(),
                "dns attempt $attempt waited ${waited}ms, outside the +/-10% band around ${base}ms: ${waiter.waits}",
            )
        }
    }

    @Test
    fun `transport waits must fit both budgets including the worst case jitter`() = runTest {
        for (kind in FailureKind.entries) {
            for (remaining in listOf(100L, kind.ceilingMs)) {
                for (postLimited in listOf(false, true)) {
                    val fixture = Fixture(kind, totalTimeoutMs = if (postLimited) remaining else 5_000L)
                    if (!postLimited) fixture.elapsed = 5_000L - remaining

                    val failure = assertThrows<Exception> { fixture.post() }

                    fixture.assertFailure(failure)
                    assertEquals(1, fixture.calls.get())
                    assertTrue(fixture.waits.isEmpty())
                    assertEquals(1L, fixture.perf.snapshot().counters[PerfKeys.RETRIES])
                    assertEquals(
                        if (kind == FailureKind.POST_SEND) 1L else null,
                        fixture.perf.snapshot().counters[PerfKeys.POST_SEND_RETRIES],
                    )
                    assertTrue(fixture.notices.first().startsWith(kind.noticeLabel))
                    assertEquals(
                        "upstream backoff up to ${kind.ceilingMs}ms does not fit the remaining ${remaining}ms budget",
                        fixture.notices.last(),
                    )
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
                    // V4-114 PIN: a spent turn-wait budget THROWS NOTHING now — it answers
                    // UpstreamPost.TurnWaitExhausted. Against the old shape this arm's
                    // assertEquals does not compile (post returned String and threw), which is
                    // what makes it a pin and not a restatement.
                    assertEquals(UpstreamPost.TurnWaitExhausted, fixture.postRaw())
                    assertTrue(fixture.waits.isEmpty())
                    assertEquals(1, fixture.calls.get(), "the second round must not reach upstream")
                    assertNull(fixture.perf.snapshot().counters[PerfKeys.RETRIES])
                    assertEquals("upstream turn wait budget exhausted before attempt 1/3", fixture.notices.single())
                } else {
                    val failure = assertThrows<Exception> { fixture.post() }
                    assertTrue(fixture.waits.isEmpty())
                    fixture.assertFailure(failure)
                    assertEquals(2, fixture.calls.get(), "the second round may try once but must not retry")
                    assertEquals(1L, fixture.perf.snapshot().counters[PerfKeys.RETRIES])
                    assertEquals(
                        "upstream backoff up to ${kind.ceilingMs}ms does not fit the remaining 100ms budget",
                        fixture.notices.last(),
                    )
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
                assertEquals(1L, fixture.perf.snapshot().counters[PerfKeys.RETRIES])
                assertEquals(
                    "upstream backoff up to ${kind.ceilingMs}ms does not fit the remaining 0ms budget",
                    fixture.notices.last(),
                )
            }
        }
    }

    @Test
    fun `transport retries still succeed when the entire jitter band fits`() = runTest {
        for (kind in FailureKind.entries) {
            val fixture = Fixture(kind)
            fixture.elapsed = 5_000L - kind.ceilingMs - 1L

            assertEquals("ok", fixture.post())

            assertEquals(2, fixture.calls.get())
            assertTrue(fixture.waits.single() in 1L..kind.ceilingMs)
            assertEquals(1L, fixture.perf.snapshot().counters[PerfKeys.RETRIES])
            assertTrue(fixture.notices.single().startsWith(kind.noticeLabel))
        }
    }

    @Test
    fun `stream reissues refuse an unfitting wait and preserve the original failure`() = runTest {
        for (kind in FailureKind.entries) {
            val fixture = Fixture(kind)
            fixture.elapsed = 4_900L
            fixture.failuresLeft = 0

            val failure = assertThrows<Exception> { fixture.postWithTornStream() }

            fixture.assertFailure(failure)
            assertEquals(1, fixture.calls.get())
            assertTrue(fixture.waits.isEmpty())
            assertEquals(1L, fixture.perf.snapshot().counters[PerfKeys.RETRIES])
            assertNull(fixture.perf.snapshot().counters[PerfKeys.POST_SEND_RETRIES])
            assertTrue(fixture.notices.first().startsWith("stream torn before first client frame, reissue 1/2:"))
            assertEquals(
                "upstream backoff up to ${kind.ceilingMs}ms does not fit the remaining 100ms budget",
                fixture.notices.last(),
            )
        }
    }

    @Test
    fun `stream reissues still succeed when the wait fits`() = runTest {
        for (kind in FailureKind.entries) {
            val fixture = Fixture(kind)
            fixture.elapsed = 5_000L - kind.ceilingMs - 1L
            fixture.failuresLeft = 0

            assertEquals("ok", fixture.postWithTornStream())

            assertEquals(2, fixture.calls.get())
            assertTrue(fixture.waits.single() in 1L..kind.ceilingMs)
            assertEquals(1L, fixture.perf.snapshot().counters[PerfKeys.RETRIES])
            assertTrue(fixture.notices.single().startsWith("stream torn before first client frame, reissue 1/2:"))
        }
    }

    private fun realCurveClientOver(engine: MockEngine, waiter: RecordingWaiter) = UpstreamClient(
        firstByteTimeoutMs = 5_000,
        totalTimeoutMs = 60_000,
        maxRetries = 3,
        client = HttpClient(engine),
        waiter = waiter,
    )

    private enum class FailureKind(val ceilingMs: Long, val noticeLabel: String = "transport ") {
        DNS(1_100L),
        WRAPPED_DNS(1_100L),
        CONNECT(220L),
        POST_SEND(220L, "transport-possible-duplicate "),
        ;

        fun error(): Exception = when (this) {
            DNS -> UnresolvedAddressException()
            WRAPPED_DNS -> IOException("resolver failed", UnknownHostException("unavailable"))
            CONNECT -> ConnectException("refused")
            POST_SEND -> SocketException("reset")
        }
    }

    private class Fixture(kind: FailureKind, totalTimeoutMs: Long = 5_000L) {
        var elapsed = 0L
        var consumeOnAttemptMs = 0L
        var failuresLeft = 1
        val failure = kind.error()
        val calls = AtomicInteger()
        val notices = mutableListOf<String>()
        val waits = mutableListOf<Long>()
        val perf = TurnPerf { 0L }
        private val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("test")
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
            firstByteTimeoutMs = 5_000L,
            totalTimeoutMs = totalTimeoutMs,
            maxRetries = 3,
            client = HttpClient(engine),
            waiter = Waiter { ms ->
                waits.add(ms)
                elapsed += ms
            },
            clock = ElapsedClock { elapsed },
        )
        private val context = PostContext(
            url = "https://api.example.test/v1",
            auth = auth,
            extraHeaders = { emptyMap() },
            onRetry = { notices.add(it) },
            perf = perf,
            remainingTurnWait = RemainingTurnWait { 5_000L - elapsed },
        )

        fun assertFailure(actual: Exception) {
            assertEquals(failure::class, actual::class)
            assertEquals(failure.message, actual.message)
            // Coroutine stack recovery may wrap the throwable, but must retain the original cause.
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
        }

        suspend fun post(): String = client.posted(context, "{}") { "ok" }

        /** The un-narrowed answer, for the one test whose subject IS the refusal (V4-114). */
        suspend fun postRaw(): UpstreamPost<String> = client.post(context, "{}") { "ok" }

        suspend fun postWithTornStream(): String {
            var torn = true
            return client.posted(context.copy(clientFrameEmitted = { false }), "{}") {
                if (torn) {
                    torn = false
                    throw failure
                }
                "ok"
            }
        }
    }
}
