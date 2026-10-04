// NEW: V4-63 split of UpstreamClientRetryPolicyTest.kt, the ratelimit family. Pure move: every
// test below is byte-identical to its original, same name, no assertion changes. Helpers shared with
// the sibling classes live in UpstreamClientRetryFixture.kt.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.ElapsedClock
import splice.upstream.StreamStart
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.retry.RetryAfter
import java.util.concurrent.atomic.AtomicInteger

class NativeRateLimitHeadersTest {

    @Test
    fun `native refusals relay the whole rate-limit header family verbatim without retrying`() = runTest {
        val native = """{"type":"error","error":{"type":"rate_limit_error","message":"synthetic refusal"}}"""
        val retained = linkedMapOf(
            "Anthropic-Ratelimit-Requests-Limit" to listOf("50"),
            "anthropic-ratelimit-requests-remaining" to listOf("0"),
            "anthropic-ratelimit-requests-reset" to listOf("2030-01-01T00:00:00Z"),
            "anthropic-ratelimit-tokens-limit" to listOf("1000"),
            "anthropic-ratelimit-tokens-remaining" to listOf("0"),
            "anthropic-ratelimit-tokens-reset" to listOf("2030-01-01T00:01:00Z"),
            "anthropic-ratelimit-unified-status" to listOf("rejected"),
            "anthropic-ratelimit-future-window" to listOf("first", "second"),
            "Retry-After" to listOf("60"),
            "X-Should-Retry" to listOf("true", "false"),
        )
        var attempts = 0
        val engine = MockEngine {
            attempts++
            respond(
                native,
                HttpStatusCode.TooManyRequests,
                headersOf(*(retained + ("set-cookie" to listOf("synthetic-private"))).toList().toTypedArray()),
            )
        }
        val client = UpstreamClient(
            totalTimeoutMs = 30_000L,
            maxRetries = 4,
            client = HttpClient(engine),
            backoff = { _, _ -> error("native retry belongs to the client") },
            clock = ElapsedClock { 0L },
        )
        fun context() = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
        ).also { it.relayRateLimitReplies = true }
        val observer = assertThrows<UpstreamFailed> { client.posted(context(), "{}") { "unreachable" } }
        val follower = assertThrows<UpstreamFailed> { client.posted(context(), "{}") { "unreachable" } }
        for (failure in listOf(observer, follower)) {
            assertEquals(429, failure.status)
            assertEquals(native, failure.body)
            assertEquals(retained, failure.rateLimitReply?.headers)
        }
        assertTrue(follower.localHold)
        assertEquals(1, attempts, "neither proxy retries nor the held follower reach upstream")
    }
}

class UpstreamClientRateLimitTest {

    @Test
    fun `an older accepted stream cannot erase a later native refusal when its body finishes`() = runTest {
        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val native = """{"type":"error","error":{"type":"rate_limit_error","message":"synthetic refusal"}}"""
        val engine = MockEngine {
            if (requests++ == 0) {
                respond("synthetic success", HttpStatusCode.OK, headersOf())
            } else {
                respond(native, HttpStatusCode.TooManyRequests, headersOf("x-should-retry", "true"))
            }
        }
        val client = UpstreamClient(
            totalTimeoutMs = 30_000L,
            maxRetries = 4,
            client = HttpClient(engine),
            clock = ElapsedClock { 0L },
        )
        fun context() = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
        ).also { it.relayRateLimitReplies = true }
        val older = async {
            client.posted(context(), "{}") {
                accepted.complete(Unit)
                release.await()
                "ok"
            }
        }
        accepted.await()
        assertThrows<UpstreamFailed> { client.posted(context(), "{}") { "unreachable" } }
        release.complete(Unit)
        assertEquals("ok", older.await())
        val follower = assertThrows<UpstreamFailed> { client.posted(context(), "{}") { "unreachable" } }
        assertEquals(native, follower.body, "completion is not a newer provider acceptance")
        assertEquals(listOf("true"), follower.rateLimitReply?.headers?.get("x-should-retry"))
        assertEquals(2, requests, "the follower uses the credential's latest native refusal")
    }

    @Test
    fun `custom carriers isolate the exact header snapshot sent on the wire`() = runTest {
        val sent = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        var snapshots = 0
        val engine = MockEngine { request ->
            val key = requireNotNull(request.headers["X-Synthetic-Key"])
            sent += key
            if (key == "synthetic-refused") {
                respond("synthetic refusal", HttpStatusCode.TooManyRequests, headersOf())
            } else {
                respond("synthetic success", HttpStatusCode.OK, headersOf())
            }
        }
        val client = UpstreamClient(
            totalTimeoutMs = 30_000L,
            maxRetries = 1,
            client = HttpClient(engine),
            clock = ElapsedClock { 0L },
        )
        fun context(key: String) = PostContext(
            url = "https://api.example.test/v1",
            auth = object : RefreshableAuthProvider by fakeAuth {
                override suspend fun credentials(): Credentials = Credentials.ApiKey(key, "X-Synthetic-Key", "")
            },
            extraHeaders = {
                snapshots++
                emptyMap()
            },
        ).also { context -> context.upstreamAccepted = StreamStart { accepted += key } }
        assertThrows<UpstreamFailed> { client.posted(context("synthetic-refused"), "{}") { "unreachable" } }
        assertEquals("ok", client.posted(context("synthetic-healthy"), "{}") { "ok" })
        assertEquals("ok", client.posted(context("synthetic-new"), "{}") { "ok" })
        val follower = assertThrows<UpstreamFailed> {
            client.posted(context("synthetic-refused"), "{}") { "unreachable" }
        }
        assertTrue(follower.localHold)
        assertEquals(listOf("synthetic-refused", "synthetic-healthy", "synthetic-new"), sent)
        assertEquals(
            listOf("synthetic-healthy", "synthetic-new"),
            accepted,
            "refusals never permit early stream progress",
        )
        assertEquals(4, snapshots, "each attempt resolves headers once, including the local identity check")
    }

    @Test
    fun `a long non-429 pushback does not block the observer's own retry`() = runTest {
        var elapsed = 0L
        val calls = AtomicInteger()
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) {
                respond("server busy", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "60"))
            } else {
                respond("recovered", HttpStatusCode.OK, headersOf())
            }
        }
        val client = UpstreamClient(
            totalTimeoutMs = 90_000L,
            maxRetries = 2,
            client = HttpClient(engine),
            backoff = { _, delay -> elapsed += delay },
            clock = ElapsedClock { elapsed },
        )
        assertEquals("ok", postOnce(client))
        assertEquals(2, calls.get(), "the observer retries upstream, not a synthetic local 429")
        assertEquals(15_000L, elapsed)
        assertEquals(0L, client.rateLimitedForMs, "a recovered request leaves no follower cooldown")
    }

    @Test
    fun `an exhausted non-429 pushback preserves its status and protects followers`() = runTest {
        var elapsed = 0L
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("server busy", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "60"))
        }
        val client = UpstreamClient(
            totalTimeoutMs = 90_000L,
            maxRetries = 2,
            client = HttpClient(engine),
            backoff = { _, delay -> elapsed += delay },
            clock = ElapsedClock { elapsed },
        )
        val observer = assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(503, observer.status)
        assertEquals("server busy", observer.body)
        assertEquals(2, calls.get())
        assertEquals(60_000L, client.rateLimitedForMs)
        val follower = assertThrows<UpstreamFailed> { postOnce(client) }
        assertEquals(429, follower.status)
        assertEquals(2, calls.get(), "the follower is held without an upstream request")
    }

    @Test
    fun `non-pooled bare 429 retries every 15 seconds inside a 900 second budget, then arms`() = runTest {
        val calls = AtomicInteger()
        val capture = Capture()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf())
        }
        val client = UpstreamClient(
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
