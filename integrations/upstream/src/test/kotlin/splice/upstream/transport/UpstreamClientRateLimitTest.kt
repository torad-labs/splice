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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.auth.LoginAnswer
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.ElapsedClock
import splice.upstream.StreamStart
import splice.upstream.Waiter
import splice.upstream.failure.OpenAiReplies
import splice.upstream.failure.Replies
import splice.upstream.retry.BURST_WAITS
import splice.upstream.retry.RateLimitCooldown
import java.util.concurrent.atomic.AtomicInteger

class NativeRateLimitHeadersTest {

    @Test
    fun `a posted request cannot clear a later refusal even at the same clock tick`() = runTest {
        for (advance in listOf(0L, 1L)) {
            var elapsed = 0L
            var requests = 0
            val posted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cooldown = RateLimitCooldown(ElapsedClock { elapsed })
            val engine = MockEngine {
                if (requests++ == 0) {
                    posted.complete(Unit)
                    release.await()
                    respond("synthetic success", HttpStatusCode.OK)
                } else {
                    respond(NATIVE_REFUSAL, HttpStatusCode.TooManyRequests, headersOf("Retry-After", "60"))
                }
            }
            val http = HttpClient(engine)
            val client = UpstreamClient(
                totalTimeoutMs = 30_000L,
                client = http,
                clock = ElapsedClock { elapsed },
            )
            fun context() = PostContext(
                url = "https://api.example.test/v1",
                auth = fakeAuth,
                extraHeaders = { emptyMap() },
                replies = Replies(OpenAiReplies),
                limits = PostLimits(rateLimitCooldown = cooldown),
            )
            try {
                val older = async { client.posted(context(), "{}") { "ok" } }
                posted.await()
                elapsed += advance
                assertEnds<UpstreamFailed> { client.posted(context(), "{}") { "unreachable" } }
                val horizon = cooldown.remainingMs()
                val selection = cooldown.unavailableForMs()
                val reset = cooldown.providerUnavailableForMs()
                assertTrue(horizon > 0L && selection > 0L && reset > 0L, "the newer refusal holds all horizons")
                release.complete(Unit)
                assertEquals("ok", older.await())

                assertEquals(horizon, cooldown.remainingMs(), "late headers cannot erase the newer refusal")
                assertEquals(selection, cooldown.unavailableForMs())
                assertEquals(reset, cooldown.providerUnavailableForMs())
                elapsed += horizon
                assertEquals(0L, cooldown.remainingMs(), "the original horizon expires without being extended")
            } finally {
                release.complete(Unit)
                http.close()
            }
        }
    }

    @Test
    fun `a native refusal past the floor ends at once and relays its rate-limit headers verbatim`() = runTest {
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
        val waiter = RecordingWaiter()
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
            client = HttpClient(engine),
            pacing = RetryPacing(waiter = waiter),
            clock = ElapsedClock { 0L },
        )
        fun context() = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
        ).also { it.relayRateLimitReplies = true }
        val failure = assertEnds<UpstreamFailed> { client.posted(context(), "{}") { "unreachable" } }
        assertEquals(429, failure.evidence.status)
        assertEquals(native, failure.evidence.body)
        assertEquals(retained, failure.native?.headers)
        // 60 s is past the 15 s floor: the turn is held to the stated reset, which ends it at once until slice 5 holds it.
        assertEquals(emptyList<Long>(), waiter.waits, "a stated reset past the floor is not waited out on the turn")
        assertEquals(1, attempts, "one send")
    }
}

private const val NATIVE_REFUSAL = """{"type":"error","error":{"type":"rate_limit_error",""" +
    """"message":"synthetic refusal","resets_in_seconds":3600}}"""

class UpstreamClientRateLimitTest {

    @Test
    fun `an access refusal reports its original HTTP status without a quota header`() = runTest {
        val answers = mutableListOf<Pair<Int, Long>>()
        val engine = MockEngine {
            respond(
                """{"error":{"code":"permission_denied","message":"synthetic access denied"}}""",
                HttpStatusCode.Forbidden,
            )
        }
        val client = UpstreamClient(totalTimeoutMs = 30_000L, client = HttpClient(engine))
        val context = PostContext(
            "https://api.example.test/v1",
            fakeAuth,
            { emptyMap() },
            replies = Replies(OpenAiReplies),
        ).also {
            it.providerAnswerObserver = ProviderAnswerObserver { status, at -> answers += status to at }
        }
        val failure = assertEnds<UpstreamFailed> { client.posted(context, "{}") { "unreachable" } }
        assertEquals(403, failure.evidence.status)
        assertTrue(answers.isNotEmpty())
        assertTrue(answers.all { it.first == 403 && it.second > 0L })
    }

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
            client = HttpClient(engine),
            // A bare 429 is waited out on the burst schedule (section 8); the waits are taken at once here.
            pacing = RetryPacing(waiter = Waiter { }),
            clock = ElapsedClock { 0L },
        )
        fun context() = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
        ).also { it.relayRateLimitReplies = true }
        val answers = mutableListOf<Pair<Int, Long>>()
        fun observedContext() = context().also {
            it.providerAnswerObserver = ProviderAnswerObserver { status, at -> answers += status to at }
        }
        val older = async {
            client.posted(observedContext(), "{}") {
                accepted.complete(Unit)
                release.await()
                "ok"
            }
        }
        accepted.await()
        assertEquals(listOf(200), answers.map { it.first }, "header acceptance is visible while its body is held")
        assertEnds<UpstreamFailed> { client.posted(observedContext(), "{}") { "unreachable" } }
        release.complete(Unit)
        assertEquals("ok", older.await())
        assertEquals(
            listOf(200) + List(1 + BURST_WAITS) { 429 },
            answers.map { it.first },
            "every send of the refused post is an answer, and completion is not one",
        )
        assertTrue(answers.all { it.second > 0L })
        assertTrue(answers[1].second >= answers[0].second)
        val kept = client.credentialCooldown(mapOf("x-api-key" to "k"))?.rateLimitReply
        assertEquals(native, kept?.body, "completion is not a newer provider acceptance")
        assertEquals(listOf("true"), kept?.headers?.get("x-should-retry"))
        assertTrue(client.rateLimitedForMs > 0L, "the login still reads limited after the older body finishes")
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
            client = HttpClient(engine),
            pacing = RetryPacing(waiter = Waiter { }),
            clock = ElapsedClock { 0L },
        )
        fun context(key: String) = PostContext(
            url = "https://api.example.test/v1",
            auth = object : RefreshableAuthProvider by fakeAuth {
                override suspend fun credentials(): Credentials = Credentials.ApiKey(key, "X-Synthetic-Key", "")

                // Delegation would forward the login lookup to fakeAuth, which answers with its own key.
                override suspend fun answer(): LoginAnswer = LoginAnswer.Granted(credentials())
            },
            extraHeaders = {
                snapshots++
                emptyMap()
            },
            replies = Replies(OpenAiReplies),
        ).also { context -> context.upstreamAccepted = StreamStart { accepted += key } }
        assertEnds<UpstreamFailed> { client.posted(context("synthetic-refused"), "{}") { "unreachable" } }
        assertEquals("ok", client.posted(context("synthetic-healthy"), "{}") { "ok" })
        assertEquals("ok", client.posted(context("synthetic-new"), "{}") { "ok" })
        val again = assertEnds<UpstreamFailed> {
            client.posted(context("synthetic-refused"), "{}") { "unreachable" }
        }
        assertEquals(429, again.evidence.status)
        assertEquals(
            "synthetic refusal",
            again.evidence.body,
            "the refused credential's next turn hears the provider itself",
        )
        // Each refused post is sent on the burst schedule (section 8) before it ends.
        val refused = List(1 + BURST_WAITS) { "synthetic-refused" }
        assertEquals(refused + listOf("synthetic-healthy", "synthetic-new") + refused, sent)
        assertEquals(
            listOf("synthetic-healthy", "synthetic-new"),
            accepted,
            "refusals never permit early stream progress",
        )
        assertEquals(sent.size, snapshots, "each attempt resolves headers once, and sends that snapshot")
    }

    @Test
    fun `a non-429 with a wait past the floor ends at once, keeps its status and marks its login limited`() = runTest {
        var elapsed = 0L
        val waits = mutableListOf<Long>()
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("server busy", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "60"))
        }
        val client = UpstreamClient(
            totalTimeoutMs = 90_000L,
            client = HttpClient(engine),
            pacing = RetryPacing(
                waiter = Waiter { ms ->
                    waits += ms
                    elapsed += ms
                },
            ),
            clock = ElapsedClock { elapsed },
        )
        val observer = assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(503, observer.evidence.status)
        assertEquals("server busy", observer.evidence.body)
        assertEquals(1, calls.get(), "60 s is past the 15 s floor: held to it, which ends the turn at once")
        assertEquals(emptyList<Long>(), waits)
        assertEquals(60_000L, client.rateLimitedForMs)
    }

    @Test
    fun `a non-pooled bare 429 is waited out on the burst schedule, then ends and arms`() = runTest {
        val calls = AtomicInteger()
        val capture = Capture()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf())
        }
        val client = UpstreamClient(
            totalTimeoutMs = 900_000L,
            client = HttpClient(engine),
            pacing = RetryPacing(waiter = Waiter { ms -> capture.waits.add(ms) }),
            clock = ElapsedClock { 0L },
        )
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(5, calls.get(), "one send, then one after each burst wait")
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L), capture.waits, "the burst schedule: 2, 4, 8 and 15 s")
        assertTrue(client.rateLimitedForMs > 0L, "exhaustion arms the horizon")
    }

    @Test
    fun `a waitable 429 is waited out and retried before the turn gives up`() = runTest {
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
            client = HttpClient(engine),
            pacing = RetryPacing(waiter = waiter),
            clock = ElapsedClock { 0L },
        )
        fun context() = PostContext(
            url = "https://api.example.test/v1",
            auth = fakeAuth,
            extraHeaders = { emptyMap() },
            replies = Replies(OpenAiReplies),
            observers = PostObservers(onRetry = { notices.add(it) }),
            limits = PostLimits(
                rateLimitCooldown = cooldown,
                remainingTurnWait = RemainingTurnWait { 5_000L },
            ),
        )

        val observer = assertEnds<UpstreamFailed> { client.posted(context(), "{}") { "unreachable" } }
        assertEquals(429, observer.evidence.status)
        assertEquals("slow down", observer.evidence.body)
        // V4-48: a pushback at or under the 15s ceiling is WAITED OUT and retried, not surrendered.
        assertEquals(3, calls.get(), "a short 429 is retried, not surrendered")
        assertTrue(waiter.waits.isNotEmpty(), "the pushback is waited out rather than skipped")
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
        assertEnds<UpstreamFailed> { postOnce(clientOver(engine)) }
        assertEquals(1, calls.get())
    }

    @Test
    fun `overflowing retry-after saturates instead of wrapping negative`() = runTest {
        // DR-47: seconds*1000 past Long.MAX wrapped NEGATIVE, which read as "tiny pushback" — the
        // give-up branch never fired, the curve retried on a negative floor, and the cooldown armed
        // an already-expired horizon. Saturation turns it into the absurd-pushback case above: one
        // attempt, with the login's horizon clamped at the NF-01 ceiling.
        var elapsed = 0L
        val calls = AtomicInteger()
        val capture = Capture()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("busy", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "9223372036854775808"))
        }
        val client = clientOver(engine, capture, clock = { elapsed })
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get(), "saturated pushback must give up, not retry on a wrapped-negative floor")
        assertTrue(capture.waits.isEmpty())
        assertEquals(120_000L, client.rateLimitedForMs)
        elapsed += 121_000L
        assertEquals(0L, client.rateLimitedForMs, "the saturated horizon is bounded and expires")
    }

    @Test
    fun `http-date retry-after is honoured - arms the cooldown like its seconds twin`() = runTest {
        // NF-04: a date ~30s out behaves exactly like "Retry-After: 30" — the login's horizon is
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
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get()) // 30 s is past the 15 s floor: held to it, which ends the turn at once, armed
        now += 20_000 // past the 20s no-header default but inside the served ~30s
        assertTrue(client.rateLimitedForMs > 0L, "a date-form pushback must arm its horizon, not the 20s guess")
        now += 20_000 // comfortably past the served horizon (margin for test wall-clock drift)
        assertEquals(0L, client.rateLimitedForMs)
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
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(3, calls.get(), "a zero-length backoff spends the retry budget, not the exit")
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(6, calls.get(), "a past date clamps to 0 — no cooldown, no 20s fallback")
    }

    // A 429 marks its login limited, which names the limit on status and steers new sessions to a free
    // login. It never stops the next post: each turn asks the provider itself and waits only on its own
    // failure (Marcos, Oct 26), which retires the shared fail-fast of the 2026-07-19 storm.

    @Test
    fun `a 429 marks its login limited for 20s and the next post still asks the provider`() = runTest {
        var now = 0L
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("""{"detail":"Rate limit exceeded"}""", HttpStatusCode.TooManyRequests, headersOf())
        }
        val client = clientOver(engine, clock = { now })
        // The burst schedule's 2 s and 4 s fit the 5 s harness budget and its 8 s does not: three sends, then ARMED.
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(3, calls.get())
        assertEquals(20_000L, client.rateLimitedForMs, "no Retry-After: the 20s default")
        val next = assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(6, calls.get(), "the next post reaches the provider, held by nothing another post armed")
        assertEquals(429, next.evidence.status)
        assertEquals(
            """{"detail":"Rate limit exceeded"}""",
            next.evidence.body,
            "the provider's own answer, not splice's",
        )
        now += 21_000
        assertEquals(0L, client.rateLimitedForMs)
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
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get()) // 30 s is past the 15 s floor: held to it, which ends the turn at once, ARMED
        assertEquals(30_000L, client.rateLimitedForMs, "the served 30s, verbatim")
        now += 25_000 // past the 20s default but inside the served 30s
        assertEquals(5_000L, client.rateLimitedForMs)
        now += 6_000 // past the 30s Retry-After
        assertEquals(0L, client.rateLimitedForMs)
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
        val armed = assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        val armedBody = armed.evidence.body.orEmpty()
        assertTrue(armedBody.contains("86400"), "true pushback surfaces in the upstream body: $armedBody")
        assertEquals(120_000L, client.rateLimitedForMs, "the horizon clamps to the ceiling, not 24h")
        now += 121_000
        assertEquals(0L, client.rateLimitedForMs)
    }

    // UP-001: Retry-After on a non-retryable non-429 governs that request only. Retryable 408/5xx
    // still mark their login limited, without writing account-selection unavailability.

    @Test
    fun `a non-retryable 403 with a long retry-after does not arm the shared cooldown`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond("forbidden", HttpStatusCode.Forbidden, headersOf("Retry-After", "30"))
        }
        val client = clientOver(engine, clock = { 0L })
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        assertEquals(0L, client.rateLimitedForMs, "a 403 must never arm the shared rate-limit cooldown")
        // proven not-wedged: the very next call reaches upstream immediately, no fail-fast
        assertEnds<UpstreamFailed> { postOnce(client) }
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
        assertEnds<UpstreamFailed> { postOnce(client) }
        assertEquals(1, calls.get())
        assertTrue(client.rateLimitedForMs > 0L)
        client.clearRateLimitCooldown()
        assertEquals(0L, client.rateLimitedForMs)
        assertEnds<UpstreamFailed> { postOnce(client) } // straight to upstream, no fail-fast
        assertEquals(2, calls.get())
    }

    // HD-19 REWRITE. These three replaced two tests that asserted `minOf(200L shl it, 10_000L)`
    // equals `minOf(200L shl it, 10_000L)` — the arithmetic was re-derived in the assertion because
    // the only way to observe the shipped lambda was to sleep through it, so every other test in
    // this file overrode `backoff` with a no-op and the production curve was covered NOWHERE. With
    // the Waiter seam the shipped lambda runs and the wait it requested is the assertion; the tests
    // are exact instead of tautological, and still cost no wall-clock time.
}
