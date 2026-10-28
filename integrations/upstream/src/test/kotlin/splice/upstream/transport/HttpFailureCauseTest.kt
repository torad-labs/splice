// NEW: failure-handling slice 2 (2026-10-26). What a provider's HTTP failure tells a person reaches the cause the
// line is written from. A 429 that states no reset says so. A 402, and a status the provider's own reader names a
// spent account, is the payment the provider asks for, held as a limit is. The provider's words reach the cause only
// through ProviderWordsFilter, read from the provider's own message field and never from a sentence splice wrote.
//
// Failure-handling slice 3: the reply is read once, by the head's own reader (PostContext.replies), so what each API
// type and vendor states is pinned by that reader's own test: Anthropic's unified plan headers and its windows in
// AnthropicRepliesPropertyTest, the ChatGPT backend's usage_limit_reached body in CodexRepliesPropertyTest, and Muse's
// stated reset, advisory now, in MuseRepliesPropertyTest. The tests that read resets_at, resets_in_seconds and an
// echoed X-RateLimit-Reset from any 429 body pinned what the design removed (final-design.md section 7: any other 429's
// lift comes from its headers; OpenRouter's echoed reset is no longer read). The V4-73 status rewrite went too: a 402
// keeps its status, and every retry layer reads the cause.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.PersistedCause
import splice.core.failure.ProviderWordsFilter
import splice.core.util.ElapsedClock
import splice.upstream.Waiter
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedReply
import splice.upstream.failure.ProviderDeviation
import splice.upstream.failure.Replies
import java.util.concurrent.atomic.AtomicInteger
import splice.upstream.testing.OpenAiTestReplies as OpenAiReplies

private const val TOO_MANY_REQUESTS = 429
private const val PAYMENT_REQUIRED = 402
private const val FORBIDDEN = 403

/** A 429 with no reset stated: one send, then one after each of the burst schedule's four waits (2, 4, 8 and 15 s). */
private const val ATTEMPTS = 5

/** DeepSeek's 402 of Oct 24, 11:08:11 PM CT, in the shape the journal holds, with its request id. */
private const val DEEPSEEK_402 =
    """{"error":{"message":"Insufficient Balance (request_id: 49d5cc56-b2b7-42ed-8485-ac06076e79d4)",""" +
        """"type":"insufficient_balance_error","param":null,"code":"invalid_request_error"}}"""

/** xAI's 402 of Oct 20, 10:27:59 PM CT: its error is a string, and its second sentence carries URLs. */
private const val XAI_402 =
    """{"code":"personal-team-blocked:spending-limit","error":"You have run out of credits or need a Grok """ +
        """subscription. Add credits at https://grok.com/?_s=usage or upgrade at https://grok.com/supergrok."}"""

class HttpFailureCauseTest {

    private class Provider : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.ApiKey("k-cause", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "cause-stub", emptyMap())
    }

    /** A provider whose own reader names its 403 a spent account, as a vendor module may declare. */
    private val spentAccount403 = Replies(
        OpenAiReplies,
        object : ProviderDeviation {
            override fun http(reply: FailedReply): FailureCause.Provider? = reply.takeIf { it.status == FORBIDDEN }
                ?.let { FailureCause.Provider.NeedsPayment(Lift.Unstated, false, ErrorMessage.said(it.body)) }
        },
    )

    private fun ctx(replies: Replies) = PostContext(
        url = "https://api.example.test/v1/chat/completions",
        auth = Provider(),
        extraHeaders = { emptyMap() },
        replies = replies,
        observers = PostObservers(onRetry = { }),
    )

    private fun client(engine: MockEngine) = UpstreamClient(
        totalTimeoutMs = 900_000L,
        client = HttpClient(engine),
        pacing = RetryPacing(waiter = Waiter { }),
        clock = ElapsedClock { 0L },
    )

    private data class Ended(val failure: UpstreamFailed, val calls: Int, val limitedForMs: Long)

    private suspend fun end(status: Int, body: String, replies: Replies = Replies(OpenAiReplies)): Ended {
        val calls = AtomicInteger()
        val client = client(
            MockEngine {
                calls.incrementAndGet()
                respond(body, HttpStatusCode.fromValue(status), headersOf())
            },
        )
        val failure = assertEnds<UpstreamFailed> { client.posted(ctx(replies), "{}") { "unreachable" } }
        return Ended(failure, calls.get(), client.rateLimitedForMs)
    }

    private fun limited(failure: UpstreamFailed): FailureCause.Provider.Limited =
        assertInstanceOf(FailureCause.Provider.Limited::class.java, failure.cause)

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
    fun `a 402 ends at once, marks its login limited and keeps the status the provider sent`() = runTest {
        val payment = end(PAYMENT_REQUIRED, DEEPSEEK_402)

        // Section 8: a spent account with no time stated holds for credit (V4-73), and until slice 5 that hold ends
        // the post at once with today's retryable wire, where a bare 429 is first waited out on the burst schedule.
        assertEquals(1, payment.calls, "a spent account is not sent again on its login")
        assertTrue(payment.limitedForMs > 0L, "the login is marked limited as a 429 marks it")
        assertEquals(PAYMENT_REQUIRED, payment.failure.evidence.status, "no layer rewrites the status it came with")
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
    fun `a status the provider's own reader names a spent account is the payment it asks for too`() = runTest {
        val body = """{"error":{"code":"personal-team-blocked","message":"You have run out of credits."}}"""

        val cause = end(FORBIDDEN, body, spentAccount403).failure.cause

        val said = ProviderWordsFilter.of("You have run out of credits.")
        assertEquals(FailureCause.Provider.NeedsPayment(Lift.Unstated, false, said), cause)
    }

    @Test
    fun `a status no reader names a spent account keeps its own cause`() = runTest {
        val body = """{"error":{"code":"personal-team-blocked","message":"You have run out of credits."}}"""

        val cause = end(FORBIDDEN, body).failure.cause

        assertEquals(FailureCause.Provider.Forbidden(ProviderWordsFilter.of("You have run out of credits.")), cause)
    }
}
