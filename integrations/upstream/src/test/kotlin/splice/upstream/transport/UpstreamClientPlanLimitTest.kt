// NEW: V4-233 and V4-234 at the transport. A 429 whose unified headers name a spent PLAN window is
// not re-sent by the turn that met it, records that reset for status, and ends on a cause naming the
// reset and the window, so the line a person reads is written from the reset and never from the
// upstream's words, which the evidence and the log keep whole (failure-handling slice 2). The next turn
// still asks the provider itself (Marcos, Oct 26). The same bytes from a provider that reads no plan
// family, or with no reset named, keep V4-61's schedule and the upstream's own words exactly.
//
// Failure-handling slice 3: the head's reader reads the unified headers where the reply arrives (AnthropicReplies,
// whose own test pins the reading; this module cannot depend on the dialect), so a reader that reads them as it does
// stands in for it here, and the login provider reads no plan family any more.
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
import splice.core.auth.ClientAuthProvider
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.FailureCause
import splice.core.failure.HeadVoice
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.usage.PlanLimit
import splice.core.usage.PlanLimits
import splice.core.util.ElapsedClock
import splice.core.util.LocalTimeText
import splice.upstream.failure.FailedReply
import splice.upstream.failure.OpenAiReplies
import splice.upstream.failure.ProviderDeviation
import splice.upstream.failure.Replies
import splice.upstream.retry.MAX_RATE_LIMIT_COOLDOWN_MS
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UpstreamClientPlanLimitTest {

    private val forwarded = ClientAuthProvider("claude-splice")

    /** A 429's unified plan headers read as AnthropicReplies reads them: the window they name, lifting firmly at the
     *  reset they state, and none of the upstream's words, which are a stop phrase. */
    private val unified = Replies(
        OpenAiReplies,
        object : ProviderDeviation {
            override fun http(reply: FailedReply): FailureCause.Provider? = planLimit(reply)?.let {
                val lift = Lift.At(Instant.ofEpochSecond(it.resetEpochSeconds), firm = true, LiftSource.RESET_HEADER)
                FailureCause.Provider.Limited(lift, LimitWindow.FIVE_HOUR.takeIf { _ -> it.claim == "five_hour" })
            }

            override fun planLimit(reply: FailedReply): PlanLimit? = reply.takeIf { it.status == TOO_MANY_REQUESTS }
                ?.let { PlanLimits.of({ name -> it.header(name) }, it.answeredAt.epochSecond) }
        },
    )

    private fun ctx(
        auth: RefreshableAuthProvider,
        notices: MutableList<String>,
        replies: Replies = unified,
    ) = PostContext(
        url = "https://api.example.test/v1/messages",
        auth = auth,
        extraHeaders = { emptyMap() },
        replies = replies,
        observers = PostObservers(onRetry = { notices.add(it) }),
    )

    private fun client(engine: MockEngine, clock: ElapsedClock = ElapsedClock { 0L }) = UpstreamClient(
        totalTimeoutMs = 900_000L,
        maxRetries = 3,
        client = HttpClient(engine),
        pacing = RetryPacing(backoff = { _, _ -> }),
        clock = clock,
    )

    private fun resetInAnHour(): Long = System.currentTimeMillis() / MS + HOUR_S

    /** The ending [failure] names the spent five-hour window lifting at [reset], and the line a person reads of it
     *  says that reset in the machine's zone, quotes none of the upstream's words, and is the trace's sentence. */
    private fun assertTheLine(failure: UpstreamFailed, reset: Long) {
        val cause = FailureCause.Provider.Limited(
            Lift.At(Instant.ofEpochSecond(reset), firm = true, LiftSource.RESET_HEADER),
            LimitWindow.FIVE_HOUR,
        )
        assertEquals(cause, failure.cause)
        val at = Position(Moment.BEFORE_COMMIT, streamBegan = false)
        val line = HeadVoice("claude-splice", loginCommand = null).anyTurn.ending(failure.cause, at)
        val text = line.text
        assertTrue(text.contains(LocalTimeText().at(reset)), "the reset in the machine's zone: $text")
        assertFalse(ISO_INSTANT.containsMatchIn(text), "the reset is said as a person reads it, not as ISO: $text")
        assertFalse(text.contains('—'), "no em dash in text the client shows: $text")
        CLIENT_STOP_PHRASES.forEach { phrase ->
            assertFalse(text.lowercase().contains(phrase), "'$phrase' would end a persistent client's wait: $text")
        }
        assertEquals(text, "[splice] " + line.sentence, "the trace says what the client reads")
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

        val failure = assertEnds<UpstreamFailed> { client.posted(ctx(forwarded, notices), "{}") { "unreachable" } }

        assertEquals(1, calls.get(), "a spent plan window is not re-sent before the reset it named")
        assertEquals(429, failure.evidence.status)
        assertEquals(PLAN_BODY, failure.evidence.body, "the evidence keeps the upstream's words whole")
        assertTheLine(failure, reset)
        assertEquals(PlanLimit("five_hour", reset), client.planHold, "the hold is the upstream's own reset, exactly")
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

            val failure = assertEnds<UpstreamFailed> {
                client.posted(ctx(fakeAuth, mutableListOf(), Replies(OpenAiReplies)), "{}") { "unreachable" }
            }

            assertEquals(3, calls.get(), "V4-61: every attempt in the budget is spent")
            assertEquals(PLAN_BODY, failure.evidence.body, "the upstream's words pass through unchanged")
            assertEquals(0L, client.planHoldForMs)
            assertNull(client.planHold)
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

            val failure = assertEnds<UpstreamFailed> {
                client.posted(ctx(forwarded, mutableListOf()), "{}") { "unreachable" }
            }

            assertEquals(3, calls.get(), "no named reset, no plan hold: V4-61's schedule")
            assertEquals(PLAN_BODY, failure.evidence.body)
            assertEquals(0L, client.planHoldForMs)
        }

    // A turn waits only on its own failure, never on another turn's (Marcos, Oct 26): the next turn on a spent
    // window asks the provider itself, hears the window from its own refusal, and the first answer ends the hold.
    @Test
    fun `the next turn on a spent window asks the provider itself, and an answer ends the hold`() = runTest {
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
        val client = client(engine)
        assertEnds<UpstreamFailed> { client.posted(ctx(forwarded, notices), "{}") { "unreachable" } }

        val next = assertEnds<UpstreamFailed> {
            client.posted(ctx(forwarded, notices), "{}") { "unreachable" }
        }
        assertEquals(2, calls.get(), "the next turn reaches the provider, held by nothing the first one armed")
        assertTheLine(next, reset)

        limited.set(false)
        assertEquals("ok", client.posted(ctx(forwarded, notices), "{}") { "ok" })

        assertEquals(0L, client.planHoldForMs, "an answered turn ends the hold")
        assertNull(client.planHold)
    }

    @Test
    fun `a restart clears the horizon but the provider's plan hold outlives it`() = runTest {
        val engine = MockEngine { respond(PLAN_BODY, HttpStatusCode.TooManyRequests, planHeaders(resetInAnHour())) }
        val client = client(engine)
        assertEnds<UpstreamFailed> { client.posted(ctx(forwarded, mutableListOf()), "{}") { "unreachable" } }
        assertTrue(client.planHoldForMs > 0L, "precondition: the hold is live")

        client.clearRateLimitCooldown()

        assertTrue(client.planHoldForMs > 0L, "the upstream's statement is not splice's to forget")
        assertEquals(0L, client.rateLimitedForMs, "the gating horizon is what a restart clears")
    }

    private fun planHeaders(reset: Long) = headersOf(
        "anthropic-ratelimit-unified-status" to listOf("rejected"),
        "anthropic-ratelimit-unified-representative-claim" to listOf("five_hour"),
        "anthropic-ratelimit-unified-reset" to listOf("$reset"),
    )
}

// The words which end a persistent client's wait when they reach it.
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
private const val TOO_MANY_REQUESTS = 429
private const val MS = 1_000L
private val ISO_INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")
