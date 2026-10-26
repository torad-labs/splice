// NEW: failure-handling slice 2 (2026-10-26). What a provider's HTTP failure tells a person reaches the cause the
// line is written from. A 429 carries the reset it stated, from the plan headers or from its own body, or says it
// stated none. A 402, and a status the vendor names a quota exhaustion, is the payment the provider asks for, though
// every retry layer still sees the 429 V4-73 made it. The provider's words reach the cause only through
// ProviderWordsFilter, read from the provider's own message field and never from a sentence splice wrote.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.ClientAuthProvider
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.PersistedCause
import splice.core.failure.ProviderWordsFilter
import splice.core.usage.PlanLimit
import splice.core.util.ElapsedClock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private const val MS = 1_000L
private const val HOUR_S = 3_600L
private const val TOO_MANY_REQUESTS = 429
private const val PAYMENT_REQUIRED = 402
private const val FORBIDDEN = 403
private const val ATTEMPTS = 3

/** DeepSeek's 402 of Oct 24, 11:08:11 PM CT, in the shape the journal holds, with its request id. */
private const val DEEPSEEK_402 =
    """{"error":{"message":"Insufficient Balance (request_id: 49d5cc56-b2b7-42ed-8485-ac06076e79d4)",""" +
        """"type":"insufficient_balance_error","param":null,"code":"invalid_request_error"}}"""

/** xAI's 402 of Oct 20, 10:27:59 PM CT: its error is a string, and its second sentence carries URLs. */
private const val XAI_402 =
    """{"code":"personal-team-blocked:spending-limit","error":"You have run out of credits or need a Grok """ +
        """subscription. Add credits at https://grok.com/?_s=usage or upgrade at https://grok.com/supergrok."}"""

/** Anthropic's plan-limit body, whose only sentence is one of Claude Code's stop phrases. */
private const val PLAN_BODY =
    """{"type":"error","error":{"type":"rate_limit_error","message":"Extra usage is required to keep going."}}"""

class HttpFailureCauseTest {

    /** A provider that reads no plan family; [declared] names a 403 a quota exhaustion, as a vendor module may. */
    private class Provider(private val declared: Boolean = false) : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.ApiKey("k-cause", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "cause-stub", emptyMap())
        override fun isQuotaExhausted(status: Int, body: String): Boolean = declared && status == FORBIDDEN
    }

    private fun ctx(auth: RefreshableAuthProvider) = PostContext(
        url = "https://api.example.test/v1/chat/completions",
        auth = auth,
        extraHeaders = { emptyMap() },
        onRetry = { },
    )

    private fun client(engine: MockEngine) = UpstreamClient(
        totalTimeoutMs = 900_000L,
        maxRetries = ATTEMPTS,
        client = HttpClient(engine),
        pacing = RetryPacing(backoff = { _, _ -> }, dnsBackoff = { _ -> }),
        clock = ElapsedClock { 0L },
    )

    private data class Ended(val failure: UpstreamFailed, val calls: Int, val limitedForMs: Long)

    private suspend fun end(
        status: Int,
        body: String,
        auth: RefreshableAuthProvider = Provider(),
        headers: Headers = headersOf(),
    ): Ended {
        val calls = AtomicInteger()
        val client = client(
            MockEngine {
                calls.incrementAndGet()
                respond(body, HttpStatusCode.fromValue(status), headers)
            },
        )
        val failure = assertEnds<UpstreamFailed> { client.posted(ctx(auth), "{}") { "unreachable" } }
        return Ended(failure, calls.get(), client.rateLimitedForMs)
    }

    private fun secondsAhead(seconds: Long): Instant = Instant.ofEpochSecond(System.currentTimeMillis() / MS + seconds)

    private fun limited(failure: UpstreamFailed): FailureCause.Provider.Limited =
        assertInstanceOf(FailureCause.Provider.Limited::class.java, failure.cause)

    private fun planHeaders(claim: String, reset: Instant) = headersOf(
        "anthropic-ratelimit-unified-status" to listOf("rejected"),
        "anthropic-ratelimit-unified-representative-claim" to listOf(claim),
        "anthropic-ratelimit-unified-reset" to listOf("${reset.epochSecond}"),
    )

    @Test
    fun `a 429 whose plan headers name a spent window carries that reset, firm, and the window`() = runTest {
        val reset = secondsAhead(HOUR_S)

        val ended =
            end(TOO_MANY_REQUESTS, PLAN_BODY, ClientAuthProvider("claude-splice"), planHeaders("five_hour", reset))

        assertEquals(
            FailureCause.Provider.Limited(Lift.At(reset, firm = true, LiftSource.RESET_HEADER), LimitWindow.FIVE_HOUR),
            ended.failure.cause,
            "a stop phrase is no words a person may read, so nothing is quoted either",
        )
    }

    @Test
    fun `a weekly window and a model's weekly window are named as such`() = runTest {
        val reset = secondsAhead(HOUR_S)
        val claimed = ClientAuthProvider("claude-splice")

        val weekly = end(TOO_MANY_REQUESTS, PLAN_BODY, claimed, planHeaders("seven_day", reset))
        val model = end(TOO_MANY_REQUESTS, PLAN_BODY, claimed, planHeaders("seven_day_opus", reset))

        assertEquals(LimitWindow.WEEKLY, limited(weekly.failure).window)
        assertEquals(LimitWindow.MODEL_WEEKLY, limited(model.failure).window)
    }

    @Test
    fun `a window a provider reads from its body carries the reset the body stated`() = runTest {
        val reset = secondsAhead(HOUR_S)
        val reader = object : RefreshableAuthProvider by Provider() {
            override fun planLimitFromBody(body: String, nowEpochSeconds: Long): PlanLimit =
                PlanLimit("seven_day", reset.epochSecond)
        }

        val ended = end(TOO_MANY_REQUESTS, """{"error":{"type":"usage_limit_reached"}}""", reader)

        assertEquals(Lift.At(reset, firm = true, LiftSource.BODY), limited(ended.failure).lift)
        assertEquals(LimitWindow.WEEKLY, limited(ended.failure).window)
    }

    @Test
    fun `a 429 whose words state the reset as an instant, as Muse's do, carries that instant`() = runTest {
        val reset = secondsAhead(2 * HOUR_S)
        val muse = """{"error":{"message":"Subscription quota exhausted. Your usage window resets at $reset.",""" +
            """"type":"rate_limit_error","code":"rate_limit_exceeded"}}"""

        val ended = end(TOO_MANY_REQUESTS, muse)

        assertEquals(Lift.At(reset, firm = true, LiftSource.BODY), limited(ended.failure).lift)
        assertNull(limited(ended.failure).window, "a body's reset names no plan window")
    }

    @Test
    fun `Muse's 429 of Oct 25, 10 16 47 PM CT carries the reset its words state`() = runTest {
        // Trace 0768bed1a506: the body's message, as Muse wrote it.
        val muse = """{"error":{"message":"Subscription quota exhausted. Your usage window resets at """ +
            """2026-10-26T05:25:35Z.","type":"rate_limit_error","code":"rate_limit_exceeded"}}"""

        val lift = limited(end(TOO_MANY_REQUESTS, muse).failure).lift

        assertEquals(Lift.At(Instant.parse("2026-10-26T05:25:35Z"), firm = true, LiftSource.BODY), lift)
    }

    @Test
    fun `a 429 whose body states resets_at in epoch seconds carries that instant`() = runTest {
        val reset = secondsAhead(HOUR_S)
        val body = """{"error":{"type":"usage_limit_reached","message":"The usage limit has been reached",""" +
            """"resets_at":${reset.epochSecond}}}"""

        val ended = end(TOO_MANY_REQUESTS, body)

        assertEquals(Lift.At(reset, firm = true, LiftSource.BODY), limited(ended.failure).lift)
    }

    @Test
    fun `a 429 whose body echoes the rate-limit reset header carries that instant, in milliseconds or seconds`() =
        runTest {
            val reset = secondsAhead(HOUR_S)
            fun echoed(value: Long) = """{"error":{"message":"Rate limit exceeded: free-models-per-day","code":429,""" +
                """"metadata":{"headers":{"X-RateLimit-Limit":"50","X-RateLimit-Reset":"$value"}}}}"""

            val inMs = end(TOO_MANY_REQUESTS, echoed(reset.toEpochMilli()))
            val inSeconds = end(TOO_MANY_REQUESTS, echoed(reset.epochSecond))

            assertEquals(Lift.At(reset, firm = true, LiftSource.BODY), limited(inMs.failure).lift)
            assertEquals(Lift.At(reset, firm = true, LiftSource.BODY), limited(inSeconds.failure).lift)
        }

    @Test
    fun `a 429 whose body states only how long until the reset carries the instant it names from the answer`() =
        runTest {
            val before = Instant.ofEpochMilli(System.currentTimeMillis())
            val body = """{"error":{"message":"The usage limit has been reached","resets_in_seconds":$HOUR_S}}"""

            val lift = limited(end(TOO_MANY_REQUESTS, body).failure).lift

            val at = assertInstanceOf(Lift.At::class.java, lift)
            val after = Instant.ofEpochMilli(System.currentTimeMillis())
            assertTrue(
                !at.at.isBefore(before.plusSeconds(HOUR_S)) && !at.at.isAfter(after.plusSeconds(HOUR_S)),
                "an hour from when the answer came: $at",
            )
            assertEquals(LiftSource.BODY, at.source)
        }

    @Test
    fun `a 429 that states no reset says so, and keeps every attempt its schedule gives it`() = runTest {
        val ended = end(TOO_MANY_REQUESTS, """{"error":{"message":"Rate limit reached for this plan."}}""")

        assertEquals(Lift.Unstated, limited(ended.failure).lift)
        assertEquals(ATTEMPTS, ended.calls)
    }

    @Test
    fun `a 402 is the payment the provider asks for, in its own words, with its request id dropped`() = runTest {
        val ended = end(PAYMENT_REQUIRED, DEEPSEEK_402)

        assertEquals(
            FailureCause.Provider.NeedsPayment(
                Lift.Unstated,
                subscription = false,
                said = ProviderWordsFilter.of("Insufficient Balance."),
            ),
            ended.failure.cause,
        )
        assertEquals(PersistedCause.VENDOR_QUOTA_EXHAUSTED, ended.failure.cause.persisted)
    }

    @Test
    fun `a 402 still reaches every retry layer as the 429 V4-73 made it`() = runTest {
        val payment = end(PAYMENT_REQUIRED, DEEPSEEK_402)
        val limit = end(TOO_MANY_REQUESTS, DEEPSEEK_402)

        assertEquals(limit.calls, payment.calls, "the same attempts as a 429")
        assertEquals(TOO_MANY_REQUESTS, payment.failure.evidence.status)
        assertTrue(payment.limitedForMs > 0L, "the login is marked limited as a 429 marks it")
        assertEquals(limit.failure.layers, payment.failure.layers)
    }

    @Test
    fun `xAI's 402, whose error is a string, is quoted by its sentence without the one carrying URLs`() = runTest {
        val cause = end(PAYMENT_REQUIRED, XAI_402).failure.cause

        assertEquals(
            "You have run out of credits or need a Grok subscription.",
            assertInstanceOf(FailureCause.Provider.NeedsPayment::class.java, cause).said?.text,
        )
    }

    @Test
    fun `a status the vendor names a quota exhaustion is the payment it asks for too`() = runTest {
        val body = """{"error":{"code":"personal-team-blocked","message":"You have run out of credits."}}"""

        val cause = end(FORBIDDEN, body, Provider(declared = true)).failure.cause

        val said = ProviderWordsFilter.of("You have run out of credits.")
        assertEquals(FailureCause.Provider.NeedsPayment(Lift.Unstated, false, said), cause)
    }

    @Test
    fun `a status no vendor names a quota exhaustion keeps its own cause`() = runTest {
        val body = """{"error":{"code":"personal-team-blocked","message":"You have run out of credits."}}"""

        val cause = end(FORBIDDEN, body).failure.cause

        assertEquals(FailureCause.Provider.Forbidden(ProviderWordsFilter.of("You have run out of credits.")), cause)
    }
}
