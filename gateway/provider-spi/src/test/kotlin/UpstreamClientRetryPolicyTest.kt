// NEW (G3+G4a-c): retry-policy pins against the reference-harness survey — ALL 5xx retry except
// 501; 408 retries; every 429 observation terminates after arming shared follower protection.
// MockEngine — no network.
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
import splice.spi.AuthRefreshObserver
import splice.spi.ElapsedNow
import splice.spi.PostContext
import splice.spi.RateLimitCooldown
import splice.spi.RemainingTurnWait
import splice.spi.RetryAfter
import splice.spi.UpstreamClient
import splice.spi.UpstreamFailed
import splice.spi.UpstreamTurnWaitExhausted
import splice.spi.Waiter
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.atomic.AtomicInteger

class UpstreamClientRetryPolicyTest {

    private val fakeAuth = object : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials? = Credentials.ApiKey("k", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "fake", emptyMap())
    }

    private class Capture {
        val minDelays = mutableListOf<Long>()
    }

    /** HD-19: the Waiter seam as a recorder. It captures what the PRODUCTION backoff lambda asked
     *  to wait and returns instantly, which is what lets the tests below run the real curve instead
     *  of replacing it with `{ _, _ -> }` and re-deriving its arithmetic in the assertion. */
    private class RecordingWaiter : Waiter {
        val waits = mutableListOf<Long>()

        override suspend fun wait(ms: Long) {
            waits.add(ms)
        }
    }

    /** A client whose backoff lambdas are the SHIPPED defaults — only the wait is faked. */
    private fun realCurveClientOver(engine: MockEngine, waiter: RecordingWaiter) = UpstreamClient(
        firstByteTimeoutMs = 5_000,
        totalTimeoutMs = 60_000,
        maxRetries = 3,
        client = HttpClient(engine),
        waiter = waiter,
    )

    private fun clientOver(
        engine: MockEngine,
        capture: Capture = Capture(),
        clock: () -> Long = System::currentTimeMillis,
    ) = UpstreamClient(
        firstByteTimeoutMs = 5_000,
        totalTimeoutMs = 5_000,
        maxRetries = 3,
        client = HttpClient(engine),
        backoff = { _, minDelayMs -> capture.minDelays.add(minDelayMs) },
        clock = clock,
    )

    private suspend fun postOnce(client: UpstreamClient): String = client.post(
        PostContext(url = "https://api.example.test/v1", auth = fakeAuth, extraHeaders = { emptyMap() }),
        "{}",
    ) { "ok" }

    @Test
    fun `500 and 504 and 408 retry then succeed`() = runTest {
        val retryable = listOf(
            HttpStatusCode.InternalServerError,
            HttpStatusCode.GatewayTimeout,
            HttpStatusCode.RequestTimeout,
        )
        for (status in retryable) {
            val calls = AtomicInteger()
            val engine = MockEngine {
                if (calls.incrementAndGet() == 1) {
                    respond("boom", status, headersOf())
                } else {
                    respond("fine", HttpStatusCode.OK, headersOf())
                }
            }
            assertEquals("ok", postOnce(clientOver(engine)), "status $status should be retryable")
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun `501 and plain 400 are terminal without retry`() = runTest {
        for (status in listOf(HttpStatusCode.NotImplemented, HttpStatusCode.BadRequest)) {
            val calls = AtomicInteger()
            val engine = MockEngine {
                calls.incrementAndGet()
                respond("nope", status, headersOf())
            }
            assertThrows<UpstreamFailed> { postOnce(clientOver(engine)) }
            assertEquals(1, calls.get(), "status $status must not retry")
        }
    }

    @Test
    fun `a successful reactive refresh reports exactly one positive auth outcome`() = runTest {
        val calls = AtomicInteger()
        val refreshes = AtomicInteger()
        val observed = AtomicInteger()
        val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("token")
            override suspend fun refresh(): Credentials {
                refreshes.incrementAndGet()
                return credentials()
            }

            override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
        }
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) {
                respond("unauthorized", HttpStatusCode.Unauthorized, headersOf())
            } else {
                respond("ok", HttpStatusCode.OK, headersOf())
            }
        }
        val context = PostContext(
            url = "https://api.example.test/v1",
            auth = auth,
            extraHeaders = { emptyMap() },
            authRefreshObserver = AuthRefreshObserver { observed.incrementAndGet() },
        )

        assertEquals("ok", clientOver(engine).post(context, "{}") { "ok" })
        assertEquals(2, calls.get())
        assertEquals(1, refreshes.get())
        assertEquals(1, observed.get())
    }

    @Test
    fun `failed response body is capped before classification`() = runTest {
        val engine = MockEngine {
            respond("x".repeat(100_000), HttpStatusCode.BadRequest, headersOf())
        }
        val failure = assertThrows<UpstreamFailed> { postOnce(clientOver(engine)) }
        assertTrue(failure.body.length < 70_000)
        assertTrue(failure.body.endsWith("[… omitted …]"))
    }

    @Test
    fun `non-pooled bare 429 gives up without sleeping inside a 900 second budget`() = runTest {
        val calls = AtomicInteger()
        val capture = Capture()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf())
        }
        val client = UpstreamClient(
            firstByteTimeoutMs = 5_000L,
            totalTimeoutMs = 900_000L,
            maxRetries = 3,
            client = HttpClient(engine),
            backoff = { _, minDelayMs -> capture.minDelays.add(minDelayMs) },
            clock = ElapsedNow { 0L },
        )
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        assertTrue(capture.minDelays.isEmpty())
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get(), "a follower inside Retry-After must not reach upstream")
    }

    @Test
    fun `waitable 429 gives up while followers fail fast`() = runTest {
        val calls = AtomicInteger()
        val waiter = RecordingWaiter()
        val notices = mutableListOf<String>()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1"))
        }
        val cooldown = RateLimitCooldown(ElapsedNow { 0L })
        val client = UpstreamClient(
            firstByteTimeoutMs = 5_000L,
            totalTimeoutMs = 5_000L,
            maxRetries = 3,
            client = HttpClient(engine),
            waiter = waiter,
            clock = ElapsedNow { 0L },
        )
        fun context() = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            onRetry = { notices.add(it) },
            rateLimitCooldown = cooldown,
            remainingTurnWait = RemainingTurnWait { 5_000L },
        )

        val observer = assertThrows<UpstreamFailed> { client.post(context(), "{}") { "unreachable" } }
        assertEquals(429, observer.status)
        assertEquals("slow down", observer.body)
        assertEquals(1, calls.get(), "the observing turn must not retry after receiving 429")
        assertTrue(waiter.waits.isEmpty(), "the observing turn must not schedule a retry wait")
        assertTrue(notices.any { it.contains("giving up to avoid a synchronized retry wave") })

        val follower = assertThrows<UpstreamFailed> { client.post(context(), "{}") { "unreachable" } }
        assertEquals(1, calls.get(), "a follower must fail fast without reaching upstream")
        assertTrue(follower.body.contains("cooldown"))
        assertTrue(waiter.waits.isEmpty(), "neither the observer nor its follower may wait")
        assertEquals(1_000L, cooldown.remainingMs())
        assertEquals(0L, cooldown.unavailableForMs())
    }

    @Test
    fun `absurd retry-after gives up instead of hammering`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("come back tomorrow", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "86400"))
        }
        assertThrows<UpstreamFailed> { postOnce(clientOver(engine)) }
        assertEquals(1, calls.get())
    }

    @Test
    fun `overflowing retry-after saturates instead of wrapping negative`() = runTest {
        // DR-47: seconds*1000 past Long.MAX wrapped NEGATIVE, which read as "tiny pushback" — the
        // give-up branch never fired, the curve retried on a negative floor, and the cooldown armed
        // an already-expired horizon. Saturation turns it into the absurd-pushback case above: one
        // attempt, with follower protection clamped at the NF-01 ceiling.
        var elapsed = 0L
        val calls = AtomicInteger()
        val capture = Capture()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("busy", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "9223372036854775808"))
        }
        val client = clientOver(engine, capture, clock = { elapsed })
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get(), "saturated pushback must give up, not retry on a wrapped-negative floor")
        assertTrue(capture.minDelays.isEmpty())
        assertEquals(120_000L, client.rateLimitedForMs)
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get(), "a retryable 5xx pushback must protect followers")
        elapsed += 121_000L
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(2, calls.get(), "the bounded follower protection must expire")
    }

    @Test
    fun `retry-after seconds saturate before arbitrary-length decimal narrowing`() {
        val retryAfter = RetryAfter()
        assertEquals(Long.MAX_VALUE / 1000 * 1000, retryAfter.retryAfterMs("${Long.MAX_VALUE / 1000}"))
        assertEquals(Long.MAX_VALUE, retryAfter.retryAfterMs("${Long.MAX_VALUE / 1000 + 1}"))
        assertEquals(Long.MAX_VALUE, retryAfter.retryAfterMs("${Long.MAX_VALUE}"))
        assertEquals(Long.MAX_VALUE, retryAfter.retryAfterMs("9223372036854775808"))
        assertEquals(Long.MAX_VALUE, retryAfter.retryAfterMs("9".repeat(100)))
        assertEquals(1_000L, retryAfter.retryAfterMs("0".repeat(100) + "1"))
    }

    @Test
    fun `an HTTP-date outside epoch milliseconds degrades to null`() {
        assertNull(RetryAfter().retryAfterMs("31 Dec 999999999 23:59:59 GMT"))
    }

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
    fun `http-date retry-after is honoured - arms the cooldown like its seconds twin`() = runTest {
        // NF-04: a date ~30s out behaves exactly like "Retry-After: 30" — give up at once
        // (>15s interactive budget), arm the shared cooldown for the served horizon.
        val httpDate = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(30))
        var now = 0L
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", httpDate))
        }
        val client = clientOver(engine, clock = { now })
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get()) // >15s pushback: no retry
        now += 20_000 // past the 20s no-header default but inside the served ~30s
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get(), "a date-form pushback must arm its horizon, not the 20s guess")
        now += 20_000 // comfortably past the served horizon (margin for test wall-clock drift)
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(2, calls.get())
    }

    @Test
    fun `http-date retry-after in the past clamps to zero`() = runTest {
        // NF-04: a stale date must not arm anything (negative deltas clamp to 0) — and must not
        // be treated as garbage either (no accidental 20s default via the null path).
        var now = 0L
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond(
                "slow down",
                HttpStatusCode.TooManyRequests,
                headersOf("Retry-After", "Wed, 21 Oct 2020 07:28:00 GMT"),
            )
        }
        val client = clientOver(engine, clock = { now })
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get(), "non-pooled 429s preserve the one-attempt exit")
        assertThrows<UpstreamFailed> { postOnce(client) } // zero-length horizon: straight upstream
        assertEquals(2, calls.get(), "a past date clamps to 0 — no cooldown, no 20s fallback")
    }

    // Shared 429 cooldown (2026-07-19 storm): one post's rate-limit discovery teaches the whole
    // client — the observer and all followers terminate without multiplying the retry wave.
    @Test
    fun `429 arms a shared cooldown - followers fail fast with zero upstream calls`() = runTest {
        var now = 0L
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("""{"detail":"Rate limit exceeded"}""", HttpStatusCode.TooManyRequests, headersOf())
        }
        val client = clientOver(engine, clock = { now })
        // the observer arms the cooldown and terminates without retrying
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        // a follower during the cooldown fails fast: 429 body names the cooldown, no upstream call
        val e = assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        assertEquals(429, e.status)
        assertTrue(e.body.contains("cooldown"))
        // default cooldown (no Retry-After) expires after 20s — traffic is attempted again
        now += 21_000
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(2, calls.get())
    }

    @Test
    fun `retry-after below the cooldown ceiling gives up at once and arms its full pushback`() = runTest {
        // NF-01 REWRITE of the old honour-the-full-pushback pin: below MAX_RATE_LIMIT_COOLDOWN_MS
        // the served value still wins verbatim; only horizons past the ceiling clamp (next test).
        var now = 0L
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "30"))
        }
        val client = clientOver(engine, clock = { now })
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get()) // >15s pushback: the probe does not retry
        now += 25_000 // past the 20s default but inside the served 30s
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get()) // still cooling — no upstream call
        now += 6_000 // past the 30s Retry-After
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(2, calls.get()) // attempted again
    }

    @Test
    fun `a multi-day retry-after arms a horizon no longer than the cooldown ceiling`() = runTest {
        // NF-01: one 86400s pushback (ChatGPT quota resets legitimately run to days — 142h
        // observed 2026-07-26) must not poison the head permanently. The armed horizon clamps to
        // MAX_RATE_LIMIT_COOLDOWN_MS (120s); the TRUE pushback still reaches the caller in the
        // surfaced upstream body.
        var now = 0L
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond(
                """{"detail":"Rate limit exceeded","resets_in_seconds":86400}""",
                HttpStatusCode.TooManyRequests,
                headersOf("Retry-After", "86400"),
            )
        }
        val client = clientOver(engine, clock = { now })
        val armed = assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        assertTrue(armed.body.contains("86400"), "true pushback surfaces in the upstream body: ${armed.body}")
        now += 119_000 // inside the 120s ceiling — still failing fast
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        now += 2_000 // 121s: the clamp has expired — traffic is attempted again, not in 24h
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(2, calls.get())
    }

    // UP-001: Retry-After on a non-retryable non-429 governs that request only. Retryable 408/5xx
    // still arm local follower protection, without writing account-selection unavailability.
    @Test
    fun `UP-001 - a non-retryable 403 with a long retry-after does not arm the shared cooldown`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("forbidden", HttpStatusCode.Forbidden, headersOf("Retry-After", "30"))
        }
        val client = clientOver(engine, clock = { 0L })
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        assertEquals(0L, client.rateLimitedForMs, "a 403 must never arm the shared rate-limit cooldown")
        // proven not-wedged: the very next call reaches upstream immediately, no fail-fast
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(2, calls.get())
    }

    @Test
    fun `clearRateLimitCooldown drops an armed horizon immediately`() = runTest {
        // NF-01: the restart escape hatch — HeadServer.startLocked() calls this.
        var now = 0L
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "60"))
        }
        val client = clientOver(engine, clock = { now })
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        assertTrue(client.rateLimitedForMs > 0L)
        client.clearRateLimitCooldown()
        assertEquals(0L, client.rateLimitedForMs)
        assertThrows<UpstreamFailed> { postOnce(client) } // straight to upstream, no fail-fast
        assertEquals(2, calls.get())
    }

    // HD-19 REWRITE. These three replaced two tests that asserted `minOf(200L shl it, 10_000L)`
    // equals `minOf(200L shl it, 10_000L)` — the arithmetic was re-derived in the assertion because
    // the only way to observe the shipped lambda was to sleep through it, so every other test in
    // this file overrode `backoff` with a no-op and the production curve was covered NOWHERE. With
    // the Waiter seam the shipped lambda runs and the wait it requested is the assertion; the tests
    // are exact instead of tautological, and still cost no wall-clock time.
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
}

class UpstreamClientTransportBudgetTest {
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

                val failure = assertThrows<Exception> { fixture.post() }

                assertTrue(fixture.waits.isEmpty())
                if (remaining == 0L) {
                    assertEquals(1, fixture.calls.get(), "the second round must not reach upstream")
                    assertTrue(failure is UpstreamTurnWaitExhausted)
                    assertNull(fixture.perf.snapshot().counters[PerfKeys.RETRIES])
                    assertEquals("upstream turn wait budget exhausted before attempt 1/3", fixture.notices.single())
                } else {
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
            clock = ElapsedNow { elapsed },
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

        suspend fun post(): String = client.post(context, "{}") { "ok" }

        suspend fun postWithTornStream(): String {
            var torn = true
            return client.post(context.copy(clientFrameEmitted = { false }), "{}") {
                if (torn) {
                    torn = false
                    throw failure
                }
                "ok"
            }
        }
    }
}
