// NEW: V4-419 at the transport. A failure caused by a spent PLAN window carries that window, so the turn's ending can
// record a plan-limit outcome and speak the reset: the ending is the only thing that crosses from the retry loop
// to the turn (RetryPolicy.giveUp returns it for each turn that meets the 429; since Oct 26 no turn is held behind
// another's, so every turn meets its own). Before this the window was dropped into the body text and the turn ended
// error:upstream-failed, "wait a moment and retry", for a plan spent until a day six days out (Marlin, f7f1e9308;
// daemon.log:39520). A burst 429 that names no reset carries nothing, so it keeps every path it had.
package splice.upstream.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.PlanLimit
import splice.core.usage.PlanLimits
import splice.core.util.ElapsedClock
import splice.upstream.Waiter
import splice.upstream.failure.FailedReply
import splice.upstream.failure.OpenAiReplies
import splice.upstream.failure.ProviderDeviation
import splice.upstream.failure.Replies
import splice.upstream.retry.BURST_WAITS
import java.util.concurrent.atomic.AtomicInteger

private const val MS = 1_000L
private const val SIX_DAYS_S = 6L * 24 * 3_600
private const val HOUR_S = 3_600L

// ChatGPT's live weekly-limit body (Sep 28, 5:11 PM CT), reset made relative.
private fun usageLimitBody(reset: Long) =
    """{"error":{"type":"usage_limit_reached","plan_type":"pro","resets_at":$reset,""" +
        """"resets_in_seconds":$SIX_DAYS_S,"limit_window_minutes":10080}}"""

private const val BURST_BODY = """{"error":{"type":"rate_limit_exceeded","message":"slow down"}}"""

class UpstreamFailurePlanLimitTest {

    private val auth = object : RefreshableAuthProvider {
        override suspend fun credentials(): Credentials? = Credentials.ApiKey("k", "x-api-key", "")
        override suspend fun refresh(): Credentials? = null
        override suspend fun describe(): AuthDescription = AuthDescription(true, "body-reader", emptyMap())
    }

    /** A body-reading provider's reader, as codex's is (CodexReplies): a usage_limit_reached body with a reset ahead
     *  names a 7-day window. */
    private val bodyReader = Replies(
        OpenAiReplies,
        object : ProviderDeviation {
            override fun planLimit(reply: FailedReply): PlanLimit? =
                Regex(""""resets_at":(\d+)""").find(reply.body)?.groupValues?.get(1)?.toLong()
                    ?.takeIf { reply.body.contains("usage_limit_reached") && it > reply.answeredAt.epochSecond }
                    ?.let { PlanLimit("seven_day", it) }
        },
    )

    /** A reader of Anthropic's unified plan headers, as AnthropicReplies reads them (pinned in its own test). */
    private val unifiedHeaders = Replies(
        OpenAiReplies,
        object : ProviderDeviation {
            override fun planLimit(reply: FailedReply): PlanLimit? =
                PlanLimits.of({ name -> reply.header(name) }, reply.answeredAt.epochSecond)
        },
    )

    private fun ctx(replies: Replies) = PostContext(
        url = "https://api.example.test/v1/responses",
        auth = auth,
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

    private fun secondsNow(): Long = System.currentTimeMillis() / MS

    @Test
    fun `the turn that meets a 429 naming a spent window carries that window`() = runTest {
        val reset = secondsNow() + SIX_DAYS_S
        val engine = MockEngine { respond(usageLimitBody(reset), HttpStatusCode.TooManyRequests, headersOf()) }

        val failure = assertEnds<UpstreamFailed> { client(engine).posted(ctx(bodyReader), "{}") { "unreachable" } }

        assertEquals(429, failure.evidence.status)
        assertEquals(PlanLimit("seven_day", reset), failure.planLimit)
    }

    @Test
    fun `an Anthropic unified-header window is carried the same way`() = runTest {
        val reset = secondsNow() + HOUR_S
        val engine = MockEngine {
            respond(
                """{"type":"error","error":{"type":"rate_limit_error","message":"x"}}""",
                HttpStatusCode.TooManyRequests,
                headersOf(
                    "anthropic-ratelimit-unified-status" to listOf("rejected"),
                    "anthropic-ratelimit-unified-representative-claim" to listOf("five_hour"),
                    "anthropic-ratelimit-unified-reset" to listOf("$reset"),
                ),
            )
        }

        val failure = assertEnds<UpstreamFailed> {
            client(engine).posted(ctx(unifiedHeaders), "{}") { "unreachable" }
        }

        assertEquals(PlanLimit("five_hour", reset), failure.planLimit)
    }

    @Test
    fun `a burst 429 that names no reset carries no window`() = runTest {
        val calls = AtomicInteger()
        val engine = MockEngine {
            calls.incrementAndGet()
            respond(BURST_BODY, HttpStatusCode.TooManyRequests, headersOf())
        }

        val failure = assertEnds<UpstreamFailed> { client(engine).posted(ctx(bodyReader), "{}") { "unreachable" } }

        assertEquals(1 + BURST_WAITS, calls.get(), "the burst schedule is untouched")
        assertEquals(429, failure.evidence.status)
        assertNull(failure.planLimit)
    }
}
