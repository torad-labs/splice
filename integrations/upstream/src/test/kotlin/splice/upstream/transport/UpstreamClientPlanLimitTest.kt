// NEW: V4-233 and V4-234 at the transport. A 429 whose unified headers name a spent PLAN window is
// not re-sent before its reset, holds the head until that reset, and hands the client our sentence
// naming it, so the reset and not the upstream's words decides whether the client waits. The same
// bytes from a provider that reads no plan family, or with no reset named, keep V4-61's schedule and
// the upstream's own words exactly.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.auth.ClientAuthProvider
import splice.core.auth.RefreshableAuthProvider
import splice.core.util.ElapsedClock
import splice.upstream.retry.MAX_RATE_LIMIT_COOLDOWN_MS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class UpstreamClientPlanLimitTest {

    private val forwarded = ClientAuthProvider("claude-splice")

    private fun ctx(auth: RefreshableAuthProvider, notices: MutableList<String>) = PostContext(
        url = "https://api.example.test/v1/messages",
        auth = auth,
        extraHeaders = { emptyMap() },
        onRetry = { notices.add(it) },
    )

    private fun client(engine: MockEngine, clock: ElapsedClock = ElapsedClock { 0L }) = UpstreamClient(
        totalTimeoutMs = 900_000L,
        maxRetries = 3,
        client = HttpClient(engine),
        backoff = { _, _ -> },
        clock = clock,
    )

    private fun resetInAnHour(): Long = System.currentTimeMillis() / MS + HOUR_S

    private fun assertOurSentence(body: String) {
        assertTrue(body.contains("5-hour window is used up until"), body)
        assertFalse(body.contains('—'), "no em dash in text the client shows: $body")
        CLIENT_STOP_PHRASES.forEach { phrase ->
            assertFalse(body.lowercase().contains(phrase), "'$phrase' would end a persistent client's wait: $body")
        }
    }

    @Test
    fun `a 429 naming a spent plan window gives up at once, holds until the reset and says so`() = runTest {
        val calls = AtomicInteger()
        val reset = resetInAnHour()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond(PLAN_BODY, HttpStatusCode.TooManyRequests, planHeaders(reset))
        }
        val notices = mutableListOf<String>()
        val client = client(engine)

        val failure = assertThrows<UpstreamFailed> { client.posted(ctx(forwarded, notices), "{}") { "unreachable" } }

        assertEquals(1, calls.get(), "a spent plan window is not re-sent before the reset it named")
        assertEquals(429, failure.status)
        assertOurSentence(failure.body)
        assertEquals("five_hour", client.planHoldClaim)
        assertTrue(
            client.planHoldForMs in (HOUR_S - 10) * MS..HOUR_S * MS,
            "held until the upstream's reset: ${client.planHoldForMs}ms",
        )
        assertTrue(client.rateLimitedForMs in 1L..MAX_RATE_LIMIT_COOLDOWN_MS, "the horizon keeps NF-01's clamp")
        assertTrue(notices.any { it.startsWith("429 plan limit:") }, notices.toString())
        assertTrue(
            notices.any { it.contains("Extra usage is required") },
            "the upstream's own words go to the log: $notices",
        )
    }

    @Test
    fun `the same 429 on a provider that reads no plan family keeps the 15 second schedule and its words`() =
        runTest {
            val calls = AtomicInteger()
            val engine = MockEngine {
                calls.incrementAndGet()
                respond(PLAN_BODY, HttpStatusCode.TooManyRequests, planHeaders(resetInAnHour()))
            }
            val client = client(engine)

            val failure = assertThrows<UpstreamFailed> {
                client.posted(ctx(fakeAuth, mutableListOf()), "{}") { "unreachable" }
            }

            assertEquals(3, calls.get(), "V4-61: every attempt in the budget is spent")
            assertEquals(PLAN_BODY, failure.body, "the upstream's words pass through unchanged")
            assertEquals(0L, client.planHoldForMs)
            assertNull(client.planHoldClaim)
        }

    @Test
    fun `with no reset named the upstream's words reach the client, because waiting cannot fix a spend limit`() =
        runTest {
            val calls = AtomicInteger()
            val engine = MockEngine {
                calls.incrementAndGet()
                respond(
                    PLAN_BODY,
                    HttpStatusCode.TooManyRequests,
                    headersOf(
                        "anthropic-ratelimit-unified-status" to listOf("rejected"),
                        "anthropic-ratelimit-unified-representative-claim" to listOf("five_hour"),
                    ),
                )
            }
            val client = client(engine)

            val failure = assertThrows<UpstreamFailed> {
                client.posted(ctx(forwarded, mutableListOf()), "{}") { "unreachable" }
            }

            assertEquals(3, calls.get(), "no named reset, no plan hold: V4-61's schedule")
            assertEquals(PLAN_BODY, failure.body)
            assertEquals(0L, client.planHoldForMs)
        }

    @Test
    fun `followers fail fast naming the plan, the lift is a named probe, and an answer ends the hold`() = runTest {
        val now = AtomicLong(0L)
        val calls = AtomicInteger()
        val limited = AtomicBoolean(true)
        val reset = resetInAnHour()
        val engine = MockEngine {
            calls.incrementAndGet()
            if (limited.get()) {
                respond(PLAN_BODY, HttpStatusCode.TooManyRequests, planHeaders(reset))
            } else {
                respond("ok", HttpStatusCode.OK, headersOf())
            }
        }
        val notices = mutableListOf<String>()
        val client = client(engine, ElapsedClock { now.get() })
        assertThrows<UpstreamFailed> { client.posted(ctx(forwarded, notices), "{}") { "unreachable" } }

        val follower = assertThrows<UpstreamFailed> {
            client.posted(ctx(forwarded, notices), "{}") { "unreachable" }
        }
        assertEquals(1, calls.get(), "a follower inside the horizon never reaches upstream")
        assertOurSentence(follower.body)

        now.set(MAX_RATE_LIMIT_COOLDOWN_MS + 1)
        limited.set(false)
        assertEquals("ok", client.posted(ctx(forwarded, notices), "{}") { "ok" })

        assertEquals(2, calls.get(), "the turn after the lift is the probe, and it went out")
        assertTrue(notices.any { it.startsWith("plan hold: probing upstream") }, notices.toString())
        assertEquals(0L, client.planHoldForMs, "an answered turn ends the hold")
        assertNull(client.planHoldClaim)
    }

    @Test
    fun `a restart clears the plan hold with the horizon`() = runTest {
        val engine = MockEngine { respond(PLAN_BODY, HttpStatusCode.TooManyRequests, planHeaders(resetInAnHour())) }
        val client = client(engine)
        assertThrows<UpstreamFailed> { client.posted(ctx(forwarded, mutableListOf()), "{}") { "unreachable" } }
        assertTrue(client.planHoldForMs > 0L, "precondition: the hold is live")

        client.clearRateLimitCooldown()

        assertEquals(0L, client.planHoldForMs)
        assertEquals(0L, client.rateLimitedForMs)
    }

    private fun planHeaders(reset: Long) = headersOf(
        "anthropic-ratelimit-unified-status" to listOf("rejected"),
        "anthropic-ratelimit-unified-representative-claim" to listOf("five_hour"),
        "anthropic-ratelimit-unified-reset" to listOf("$reset"),
    )
}

// The words #290's red arm planted, which end a persistent client's wait when they reach it.
private const val PLAN_BODY =
    """{"type":"error","error":{"type":"rate_limit_error","message":"Extra usage is required to keep going."}}"""

// Claude Code 2.1.257's stop phrases, the list RateLimitRefusalClientContractTest sweeps.
private val CLIENT_STOP_PHRASES = listOf(
    "service_spend_limit_reached",
    "exceeded_limit",
    "credits_required",
    "usage credits are required",
    "extra usage is required",
    "out_of_credits",
)

private const val HOUR_S = 3_600L
private const val MS = 1_000L
