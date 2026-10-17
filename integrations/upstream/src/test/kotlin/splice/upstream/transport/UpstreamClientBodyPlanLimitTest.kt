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
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import splice.core.util.LocalTimeText
import splice.upstream.retry.MAX_RATE_LIMIT_COOLDOWN_MS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val MS = 1_000L
private const val SIX_DAYS_S = 6L * 24 * 3_600
private val ISO_INSTANT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")

// The body ChatGPT sent on the live weekly limit (Sep 28, 5:11 PM CT), reset made relative.
private fun liveBody(reset: Long) =
    """{"error":{"type":"usage_limit_reached","plan_type":"pro","resets_at":$reset,""" +
        """"resets_in_seconds":$SIX_DAYS_S,"limit_window_minutes":10080}}"""

/** V4-377 at the transport: a provider that names its spent window in the 429 BODY (the port's
 *  planLimitFromBody, which the codex provider implements) gets V4-233's plan hold: one attempt, our
 *  sentence naming the reset, a plan-hold notice, and a probe once the cooldown clamp lifts. The
 *  double reads the body the way codex does, so the vendor parse itself is pinned in provider-codex. */
class UpstreamClientBodyPlanLimitTest {

    /** A body-reading provider: the live body names a 7-day window; anything else names none. */
    private val bodyReader = object : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials? = Credentials.ApiKey("k", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "body-reader", emptyMap())
        override fun planLimitFromBody(body: String, nowEpochSeconds: Long): PlanLimit? =
            Regex(""""resets_at":(\d+)""").find(body)?.groupValues?.get(1)?.toLong()
                ?.takeIf { body.contains("usage_limit_reached") && it > nowEpochSeconds }
                ?.let { PlanLimit("seven_day", it) }
    }

    private fun ctx(auth: RefreshableAuthProvider, notices: MutableList<String>) = PostContext(
        url = "https://api.example.test/v1/responses",
        auth = auth,
        extraHeaders = { emptyMap() },
        onRetry = { notices.add(it) },
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
        assertEquals(429, failure.status)
        assertTrue(
            failure.body.contains("7-day window is used up until ${LocalTimeText().at(reset)}"),
            failure.body,
        )
        assertFalse(ISO_INSTANT.containsMatchIn(failure.body), "the reset is not an ISO instant: ${failure.body}")
        assertFalse(failure.body.contains('—'), "no em dash in text the client shows: ${failure.body}")
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
        assertEquals(burst, failure.body)
        assertEquals(0L, client.planHoldForMs)
        assertNull(client.planHold)
    }

    @Test
    fun `after the cooldown clamp lifts the next turn probes upstream once and an answer ends the hold`() = runTest {
        val now = AtomicLong(0L)
        val calls = AtomicInteger()
        val limited = AtomicBoolean(true)
        val reset = resetInSixDays()
        val engine = MockEngine {
            calls.incrementAndGet()
            if (limited.get()) {
                respond(liveBody(reset), HttpStatusCode.TooManyRequests, headersOf())
            } else {
                respond("ok", HttpStatusCode.OK, headersOf())
            }
        }
        val notices = mutableListOf<String>()
        val client = client(engine, ElapsedClock { now.get() })
        assertEnds<UpstreamFailed> { client.posted(ctx(bodyReader, notices), "{}") { "unreachable" } }

        assertEnds<UpstreamFailed> { client.posted(ctx(bodyReader, notices), "{}") { "unreachable" } }
        assertEquals(1, calls.get(), "a follower inside the horizon never reaches upstream")

        now.set(MAX_RATE_LIMIT_COOLDOWN_MS + 1)
        limited.set(false)
        assertEquals("ok", client.posted(ctx(bodyReader, notices), "{}") { "ok" })

        assertEquals(2, calls.get(), "the turn after the lift is the probe, and it went out")
        assertTrue(notices.any { it.startsWith("plan hold: probing upstream") }, notices.toString())
        assertEquals(0L, client.planHoldForMs)
    }
}
