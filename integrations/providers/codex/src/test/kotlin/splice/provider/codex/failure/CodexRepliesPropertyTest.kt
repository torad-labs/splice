// NEW: failure-handling slice 3. The ChatGPT backend's three deviations from the Responses standard, held over
// generated replies: a usage_limit_reached 429 names the plan window it spent, a Trusted-Access policy code is a
// refusal, and a websocket connection limit is an overload. Every other answer is the standard's row, so the
// deviation claims none of them (final-design.md section 12, D1's second property).
package splice.provider.codex.failure

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.LoginOwner
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.RefusedBy
import splice.core.usage.PlanLimit
import splice.dialect.responses.failure.ResponsesReplies
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.SentKey
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val RUNS = 2_000

/** The 5-hour and weekly ChatGPT windows, as a usage_limit_reached body writes limit_window_minutes. */
private const val FIVE_HOUR_MINUTES = 300L
private const val WEEK_MINUTES = 10_080L

/** The policy codes the ChatGPT backend answers a Trusted-Access refusal with (DR-72, live on claudex Aug 31). */
private val POLICY_CODES = listOf("cyber_policy", "bio_policy")

/** Its websocket connection limit. */
private const val WS_LIMIT = "websocket_connection_limit_reached"

/** Codes the Responses standard reads, so the deviation says nothing about them. */
private val STANDARD_CODES = listOf(
    "server_error", "rate_limit_exceeded", "insufficient_quota", "context_length_exceeded", "invalid_prompt",
    "server_is_overloaded", "slow_down", "invalid_api_key", "model_not_found", "",
)

private val STATUSES = listOf(400, 401, 402, 403, 404, 429, 500, 502, 503)

private val NEVER_SENT = SentKey { _, _ -> false }

private fun errorBody(code: String, message: String, type: String = "invalid_request_error") = buildJsonObject {
    putJsonObject("error") {
        put("message", message)
        put("type", type)
        put("code", code)
    }
}.toString()

/** A usage_limit_reached body as ChatGPT sends it: its plan, its reset and the window's length. */
private fun usageLimitBody(plan: String, resetsAt: Long, minutes: Long?, message: String) = buildJsonObject {
    put("type", "usage_limit_reached")
    put("plan_type", plan)
    put("resets_at", resetsAt)
    minutes?.let { put("limit_window_minutes", it) }
    put("message", message)
}.toString()

private fun reply(status: Int, body: String, at: Instant, owner: LoginOwner = LoginOwner.SPLICE): FailedReply =
    FailedReply(status, emptyMap(), body, owner, NEVER_SENT, at)

private fun answeredAt(random: Random): Instant =
    Instant.ofEpochSecond(1_793_000_000L + random.nextLong(10_000_000))

class CodexRepliesPropertyTest {
    @Test
    fun `a usage_limit_reached 429 is a limit until the reset it named, naming the window it spent`() {
        val random = Random(351)
        repeat(RUNS) {
            val at = answeredAt(random)
            val minutes = listOf(FIVE_HOUR_MINUTES, WEEK_MINUTES, 60L, null).random(random)
            val ahead = random.nextLong(60, TimeUnit.MINUTES.toSeconds(minutes ?: WEEK_MINUTES))
            val resetsAt = at.epochSecond + ahead
            val said = "You've hit your usage limit. Try again later."
            val reply = reply(429, usageLimitBody("pro", resetsAt, minutes, said), at)
            val window = when (minutes) {
                FIVE_HOUR_MINUTES -> LimitWindow.FIVE_HOUR
                WEEK_MINUTES -> LimitWindow.WEEKLY
                else -> LimitWindow.OTHER
            }

            assertEquals(
                FailureCause.Provider.Limited(
                    Lift.At(Instant.ofEpochSecond(resetsAt), firm = true, LiftSource.BODY),
                    window,
                    ProviderWordsFilter.of(said),
                ),
                CodexReplies.http(reply),
                reply.toString(),
            )
            // Until slice 5 reads the window off the cause, the hold still reads the body's own claim.
            assertEquals(
                PlanLimit(
                    claim = when (minutes) {
                        FIVE_HOUR_MINUTES -> "five_hour"
                        WEEK_MINUTES -> "seven_day"
                        null -> "usage"
                        else -> "$minutes-minute"
                    },
                    resetEpochSeconds = resetsAt,
                    windowSeconds = minutes?.let(TimeUnit.MINUTES::toSeconds),
                ),
                CodexReplies.planLimit(reply),
                reply.toString(),
            )
        }
    }

    @Test
    fun `a Trusted-Access policy code is a refusal, over HTTP and in band alike`() {
        val random = Random(352)
        repeat(RUNS) {
            val code = POLICY_CODES.random(random)
            val said = "To get authorized for security work, join the Trusted Access program."
            val body = errorBody(code, said)
            val expected = FailureCause.Provider.Refused(RefusedBy.POLICY, ProviderWordsFilter.of(said))

            assertEquals(expected, CodexReplies.http(reply(STATUSES.random(random), body, answeredAt(random))), body)
            assertEquals(
                expected,
                CodexReplies.event(failed(body, random)),
                body,
            )
        }
    }

    @Test
    fun `a websocket connection limit is an overload, not a refusal of the request`() {
        val random = Random(353)
        repeat(RUNS) {
            val said = "Too many websocket connections"
            val body = errorBody(WS_LIMIT, said, type = "rate_limit_error")
            val words = ProviderWordsFilter.of(said)
            val expected = FailureCause.Provider.Overloaded(Lift.Unstated, loading = false, words)

            assertEquals(expected, CodexReplies.http(reply(STATUSES.random(random), body, answeredAt(random))), body)
            assertEquals(
                expected,
                CodexReplies.event(failed(body, random)),
                body,
            )
        }
    }

    @Test
    fun `every reply that lacks those three shapes is left to the Responses standard`() {
        val random = Random(354)
        repeat(RUNS) {
            val code = STANDARD_CODES.random(random)
            val body = errorBody(code, "Something the standard already reads")
            val reply = reply(STATUSES.random(random), body, answeredAt(random), LoginOwner.entries.random(random))

            assertNull(CodexReplies.http(reply), reply.toString())
            assertNull(CodexReplies.planLimit(reply), reply.toString())
            assertNull(CodexReplies.event(FailedEvent(ResponsesReplies.FAILED_EVENT, parsed(body), reply.owner)), body)
        }
    }
}

/** The same error as a response.failed event, read in band. */
private fun failed(body: String, random: Random) =
    FailedEvent(ResponsesReplies.FAILED_EVENT, parsed(body), LoginOwner.entries.random(random))

/** The event payload a body arrives as in band: the same object, as the stream carried it. */
private fun parsed(body: String) =
    kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
