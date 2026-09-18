// NEW: V4-63 split of UpstreamClientRetryPolicyTest.kt, the ratelimit family. Pure move: every
// test below is byte-identical to its original, same name, no assertion changes. Helpers shared with
// the sibling classes live in UpstreamClientRetryFixture.kt.
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
import splice.core.util.ElapsedClock
import splice.spi.PostContext
import splice.spi.RateLimitCooldown
import splice.spi.RemainingTurnWait
import splice.spi.RetryAfter
import splice.spi.UpstreamClient
import splice.spi.UpstreamFailed
import java.util.concurrent.atomic.AtomicInteger

class UpstreamClientRateLimitTest {

    @Test
    fun `non-pooled bare 429 retries every 15 seconds inside a 900 second budget, then arms`() = runTest {
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
            clock = ElapsedClock { 0L },
        )
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(3, calls.get(), "every attempt in the budget is spent before the turn fails")
        assertEquals(listOf(15_000L, 15_000L), capture.minDelays, "the floor is 15s on every retry of a bare 429")
        assertTrue(client.rateLimitedForMs > 0L, "exhaustion arms the horizon")
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(3, calls.get(), "a follower inside the armed horizon must not reach upstream")
    }

    @Test
    fun `a waitable 429 is waited out, then followers fail fast`() = runTest {
        val calls = AtomicInteger()
        val waiter = RecordingWaiter()
        val notices = mutableListOf<String>()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1"))
        }
        val cooldown = RateLimitCooldown(ElapsedClock { 0L })
        val client = UpstreamClient(
            firstByteTimeoutMs = 5_000L,
            totalTimeoutMs = 5_000L,
            maxRetries = 3,
            client = HttpClient(engine),
            waiter = waiter,
            clock = ElapsedClock { 0L },
        )
        fun context() = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            onRetry = { notices.add(it) },
            rateLimitCooldown = cooldown,
            remainingTurnWait = RemainingTurnWait { 5_000L },
        )

        val observer = assertThrows<UpstreamFailed> { client.posted(context(), "{}") { "unreachable" } }
        assertEquals(429, observer.status)
        assertEquals("slow down", observer.body)
        // V4-48: a pushback at or under the 15s ceiling is WAITED OUT and retried, not surrendered.
        assertEquals(3, calls.get(), "a short 429 is retried, not surrendered")
        assertTrue(waiter.waits.isNotEmpty(), "the pushback is waited out rather than skipped")

        val waitsAfterObserver = waiter.waits.size
        val follower = assertThrows<UpstreamFailed> { client.posted(context(), "{}") { "unreachable" } }
        assertEquals(3, calls.get(), "a follower must fail fast without reaching upstream")
        // V4-46: STRICTER than the word it replaced. The follower body must identify the GATEWAY as
        // the holder of the interval — that is the property the row guarantees — where the old
        // contains("cooldown") merely pinned a vocabulary word.
        assertTrue(follower.body.contains("this gateway is holding retries"), follower.body)
        assertEquals(waitsAfterObserver, waiter.waits.size, "a follower fails fast; only the observer waited")
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
    fun `http-date retry-after is honoured - arms the cooldown like its seconds twin`() = runTest {
        // NF-04: a date ~30s out behaves exactly like "Retry-After: 30" — the shared cooldown is
        // armed for the SERVED horizon, whichever exit the turn leaves by.
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
        assertEquals(1, calls.get()) // V4-61: 15s retry does not fit the 5s harness budget; gives up armed
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
        // V4-48: a zero-length pushback is still a pushback, so the turn spends its retry budget on
        // it — but a past date must still ARM NOTHING, which is NF-04's actual claim and is what the
        // second turn proves: nothing carried over, so it reaches upstream again.
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(3, calls.get(), "a zero-length backoff spends the retry budget, not the exit")
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(6, calls.get(), "a past date clamps to 0 — no cooldown, no 20s fallback")
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
        // V4-61: a 15s retry cannot fit the 5s harness budget, so the observer exits at once, ARMED
        assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        // a follower during the cooldown fails fast: 429 body names the GATEWAY as the holder of the
        // interval, no upstream call
        val e = assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        assertEquals(429, e.status)
        assertTrue(e.body.contains("this gateway is holding retries"), e.body)
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
        assertEquals(1, calls.get()) // V4-61: 15s retry does not fit the 5s harness budget; gives up ARMED
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
}
