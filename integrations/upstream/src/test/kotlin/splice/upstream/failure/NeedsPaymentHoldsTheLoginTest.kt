// V4-73, read once (failure-handling slice 3): an account that cannot pay is the provider's NeedsPayment, read by the
// head's own reader where the reply arrives, and the retry loop holds it as the limit it is: it marks the login limited
// so the next turn waits or moves, and it spends no refresh, because billing is not a login. The status stays the one
// the provider sent; the line a person reads is written from the cause (slice 2), so no layer rewrites it to a 429.
//
// This replaces QuotaExhaustionIs429Test, which pinned the rewrite of a declared 403 and of every 402 into a 429 with
// its own notice. The CONTROL still matters as much as the two readings: a 403 no reader names a spent account stays a
// permission refusal, or genuine permission failures would quietly read as rate limits.
package splice.upstream.failure

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.upstream.ClientFrameEmitted
import splice.upstream.transport.PostContext
import splice.upstream.transport.PostObservers
import splice.upstream.transport.PostRecovery
import splice.upstream.transport.RetryPacing
import splice.upstream.transport.UpstreamClient
import splice.upstream.transport.UpstreamFailed
import splice.upstream.transport.assertEnds
import splice.upstream.transport.posted
import java.util.concurrent.atomic.AtomicInteger

/** A billing 403 body the way grok spelled it before xAI moved it to a 402. */
private const val SPENDING_LIMIT_BODY =
    """{"error":{"code":"personal-team-blocked","message":"spending-limit: you have run out of credits"}}"""

class NeedsPaymentHoldsTheLoginTest {
    private class CountingAuth(private val refreshes: AtomicInteger) : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials = Credentials.Bearer("tok-quota", "acct")
        override suspend fun refresh(): Credentials {
            refreshes.incrementAndGet()
            return credentials()
        }

        override suspend fun describe(): AuthDescription = AuthDescription(true, "quota-stub")
    }

    /** A provider whose own reader names its billing 403 a spent account, as xAI's did until it answered 402. */
    private val spentAccount403 = object : ProviderDeviation {
        override fun http(reply: FailedReply): FailureCause.Provider? = reply.takeIf { it.status == 403 }?.let {
            FailureCause.Provider.NeedsPayment(Lift.Unstated, false, ErrorMessage.said(it.body))
        }
    }

    private fun ctx(replies: Replies, refreshes: AtomicInteger) = PostContext(
        url = "https://api.example.test/v1",
        auth = CountingAuth(refreshes),
        extraHeaders = { emptyMap() },
        replies = replies,
        observers = PostObservers(onRetry = {}),
        recovery = PostRecovery(clientFrameEmitted = ClientFrameEmitted { true }),
    )

    private fun clientOver(status: Int) = UpstreamClient(
        totalTimeoutMs = 5_000,
        maxRetries = 1,
        client = HttpClient(MockEngine { respond(SPENDING_LIMIT_BODY, HttpStatusCode.fromValue(status), headersOf()) }),
        pacing = RetryPacing(backoff = { _, _ -> }, dnsBackoff = { _ -> }),
    )

    @Test
    fun `a 402 is an account that cannot pay for every provider, and it holds the login`() = runTest {
        val refreshes = AtomicInteger()
        val client = clientOver(402)

        val ended = assertEnds<UpstreamFailed> {
            client.posted(ctx(Replies(OpenAiReplies), refreshes), "{}") { "unreachable" }
        }

        assertTrue(ended.cause is FailureCause.Provider.NeedsPayment, "deepseek answers Insufficient Balance as 402")
        assertEquals(402, ended.evidence.status, "the evidence keeps the status the provider sent")
        assertTrue(client.rateLimitedForMs > 0L, "an account that cannot pay marks its login limited")
        assertEquals(0, refreshes.get(), "billing is not a login: no refresh is spent on it")
    }

    @Test
    fun `a 403 the provider's own reader names a spent account holds the login the same way`() = runTest {
        val refreshes = AtomicInteger()
        val client = clientOver(403)

        val ended = assertEnds<UpstreamFailed> {
            client.posted(ctx(Replies(OpenAiReplies, spentAccount403), refreshes), "{}") { "unreachable" }
        }

        assertTrue(ended.cause is FailureCause.Provider.NeedsPayment, "${ended.cause}")
        assertEquals(403, ended.evidence.status)
        assertTrue(client.rateLimitedForMs > 0L, "a spent account marks its login limited like any limit")
        assertEquals(0, refreshes.get())
    }

    @Test
    fun `a 403 no reader names a spent account stays a permission refusal - the control`() = runTest {
        val refreshes = AtomicInteger()
        val client = clientOver(403)

        val ended = assertEnds<UpstreamFailed> {
            client.posted(ctx(Replies(OpenAiReplies), refreshes), "{}") { "unreachable" }
        }

        assertTrue(ended.cause is FailureCause.Provider.Forbidden, "${ended.cause}")
        assertEquals(0L, client.rateLimitedForMs, "a permission refusal must NOT arm the horizon")
        assertEquals(0, refreshes.get())
    }
}
