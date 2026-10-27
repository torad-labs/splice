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
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.FailureCause
import splice.core.failure.HeadVoice
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.LocalTimeText
import splice.upstream.failure.FailedReply
import splice.upstream.failure.OpenAiReplies
import splice.upstream.failure.ProviderDeviation
import splice.upstream.failure.Replies
import splice.upstream.retry.MAX_RATE_LIMIT_COOLDOWN_MS
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private const val MS = 1_000L
private const val SIX_DAYS_S = 6L * 24 * 3_600
private val ISO_INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")

// The body ChatGPT sent on the live weekly limit (Sep 28, 5:11 PM CT), reset made relative.
private fun liveBody(reset: Long) =
    """{"error":{"type":"usage_limit_reached","plan_type":"pro","resets_at":$reset,""" +
        """"resets_in_seconds":$SIX_DAYS_S,"limit_window_minutes":10080}}"""

/** V4-377 at the transport: a provider whose reader names its spent window in the 429 BODY (its Replies'
 *  deviation since failure-handling slice 3, which CodexReplies implements; the login provider's
 *  planLimitFromBody until then) gets V4-233's plan hold: one attempt, a cause naming the reset its body
 *  stated and the window, whose line says that reset, and a plan-hold notice. The next turn asks the
 *  provider itself (Marcos, Oct 26), as UpstreamClientPlanLimitTest shows for the header-named window. The
 *  double reads the body the way codex does, so the vendor parse itself is pinned in provider-codex. */
class UpstreamClientBodyPlanLimitTest {

    private val auth = object : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials? = Credentials.ApiKey("k", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "body-reader", emptyMap())
    }

    /** A body-reading provider's reader: the live body names a 7-day window; anything else names none. */
    private val bodyReader = Replies(
        OpenAiReplies,
        object : ProviderDeviation {
            override fun http(reply: FailedReply): FailureCause.Provider? = planLimit(reply)?.let {
                val lift = Lift.At(Instant.ofEpochSecond(it.resetEpochSeconds), firm = true, LiftSource.BODY)
                FailureCause.Provider.Limited(lift, LimitWindow.WEEKLY)
            }

            override fun planLimit(reply: FailedReply): PlanLimit? =
                Regex(""""resets_at":(\d+)""").find(reply.body)?.groupValues?.get(1)?.toLong()
                    ?.takeIf { reply.body.contains("usage_limit_reached") && it > reply.answeredAt.epochSecond }
                    ?.let { PlanLimit("seven_day", it) }
        },
    )

    private fun ctx(replies: Replies, notices: MutableList<String>) = PostContext(
        url = "https://api.example.test/v1/responses",
        auth = auth,
        extraHeaders = { emptyMap() },
        replies = replies,
        observers = PostObservers(onRetry = { notices.add(it) }),
    )

    private fun client(engine: MockEngine, clock: ElapsedClock = ElapsedClock { 0L }) = UpstreamClient(
        totalTimeoutMs = 900_000L,
        maxRetries = 4,
        client = HttpClient(engine),
        pacing = RetryPacing(backoff = { _, _ -> }),
        clock = clock,
    )

    private fun resetInSixDays(): Long = System.currentTimeMillis() / MS + SIX_DAYS_S

    @Test
    fun `a body naming the spent weekly window is sent once, held to the reset and told to the client`() = runTest {
        val calls = AtomicInteger()
        val reset = resetInSixDays()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond(liveBody(reset), HttpStatusCode.TooManyRequests, headersOf())
        }
        val notices = mutableListOf<String>()
        val client = client(engine)

        val failure = assertEnds<UpstreamFailed> { client.posted(ctx(bodyReader, notices), "{}") { "unreachable" } }

        assertEquals(1, calls.get(), "today this is 4 attempts and about 45 s of holds")
        assertEquals(429, failure.evidence.status)
        assertEquals(liveBody(reset), failure.evidence.body, "the evidence keeps the provider's body whole")
        val lift = Lift.At(Instant.ofEpochSecond(reset), firm = true, LiftSource.BODY)
        assertEquals(FailureCause.Provider.Limited(lift, LimitWindow.WEEKLY), failure.cause)
        val at = Position(Moment.BEFORE_COMMIT, streamBegan = false)
        val text = HeadVoice("claudex", "claudex login").anyTurn.ending(failure.cause, at).text
        assertTrue(text.contains(LocalTimeText().at(reset)), "the reset in the machine's zone: $text")
        assertFalse(ISO_INSTANT.containsMatchIn(text), "the reset is not an ISO instant: $text")
        assertFalse(text.contains('—'), "no em dash in text the client shows: $text")
        assertEquals(PlanLimit("seven_day", reset), client.planHold)
        assertTrue(client.planHoldForMs > (SIX_DAYS_S - 10) * MS, "held to the named reset: ${client.planHoldForMs}")
        assertTrue(client.rateLimitedForMs in 1L..MAX_RATE_LIMIT_COOLDOWN_MS, "the horizon keeps NF-01's clamp")
        assertTrue(notices.any { it.startsWith("429 plan limit:") }, notices.toString())
    }

    @Test
    fun `a burst 429 with no named window keeps the full retry schedule and its words`() = runTest {
        val calls = AtomicInteger()
        val burst = """{"error":{"type":"rate_limit_exceeded","message":"slow down"}}"""
        val engine = MockEngine {
            calls.incrementAndGet()
            respond(burst, HttpStatusCode.TooManyRequests, headersOf())
        }
        val client = client(engine)

        val failure = assertEnds<UpstreamFailed> {
            client.posted(ctx(bodyReader, mutableListOf()), "{}") { "unreachable" }
        }

        assertEquals(4, calls.get(), "V4-61: every attempt in the budget is spent")
        assertEquals(burst, failure.evidence.body)
        assertEquals(0L, client.planHoldForMs)
        assertNull(client.planHold)
    }
}
