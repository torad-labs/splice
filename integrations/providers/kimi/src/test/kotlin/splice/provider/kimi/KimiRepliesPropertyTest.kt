// NEW: failure-handling slice 3. Kimi's two deviations, held over generated replies: a plan-tier answer at any status
// is Kimi's verdict on the plan, and a 429 naming its 5-hour window is a limit that names that window. Every other
// reply Kimi can send is the Anthropic standard's, so the deviation claims none of them (final-design.md section 12,
// D1's second property).
package splice.provider.kimi

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LimitWindow
import splice.core.failure.LoginOwner
import splice.core.failure.ProviderWordsFilter
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.SentKey
import java.time.Instant
import kotlin.random.Random

private const val RUNS = 2_000

/** The 429 of Oct 23, 3:41:54 PM CT, as Kimi sent it (audit-maps.md:1075): no Retry-After, its window in its words. */
private const val FIVE_HOUR_SAID =
    "You've reached your 5-hour usage limit. Your quota will reset when the current 5-hour window ends. " +
        "To continue now, purchase extra usage or upgrade your plan: " +
        "https://www.kimi.com/membership/subscription?tab=quota"

/** The two plan-tier answers KimiOAuth knows, each as Kimi words it. */
private val PLAN_TIER = listOf(
    "Your current subscription does not have access to this model",
    "Your plan supports only kimi-k3 up to 256k context",
)

/** Sentences that say nothing either deviation reads. */
private val PLAIN = listOf(
    "Your credit balance is too low to access the API",
    "Rate limit exceeded. Please try again later",
    "prompt is too long: 262144 tokens > 262144 maximum",
    "Internal server error",
    "Your request exceeded k3-256k model token limit: 262144",
)

private val STATUSES = listOf(400, 401, 402, 403, 404, 413, 429, 500, 502, 503, 504, 529)
private val TYPES = listOf(
    "invalid_request_error",
    "authentication_error",
    "permission_error",
    "rate_limit_error",
    "api_error",
)

private val NEVER_SENT = SentKey { _, _ -> false }

private fun body(type: String, message: String) = buildJsonObject {
    put("type", "error")
    putJsonObject("error") {
        put("type", type)
        put("message", message)
    }
}

private fun reply(
    status: Int,
    type: String,
    message: String,
    answeredAt: Instant,
    retryAfterS: Long? = null,
): FailedReply = FailedReply(
    status,
    retryAfterS?.let { mapOf("retry-after" to it.toString()) } ?: emptyMap(),
    body(type, message).toString(),
    LoginOwner.SPLICE,
    NEVER_SENT,
    answeredAt,
)

private fun answeredAt(random: Random): Instant =
    Instant.ofEpochSecond(1_793_000_000L + random.nextLong(10_000_000))

class KimiRepliesPropertyTest {
    @Test
    fun `a plan-tier answer is Kimi's verdict on the plan, at any status, in Kimi's own words`() {
        val random = Random(331)
        repeat(RUNS) {
            val said = PLAN_TIER.random(random)
            val status = STATUSES.random(random)
            val reply = reply(status, TYPES.random(random), said, answeredAt(random))

            assertEquals(
                FailureCause.Provider.Forbidden(ProviderWordsFilter.of(said)),
                KimiReplies.http(reply),
                reply.toString(),
            )
            // A plan a re-send meets again is no dead login: it never spends this turn's one refresh.
            assertFalse(KimiReplies.deadCredential(reply), reply.toString())
        }
    }

    @Test
    fun `Kimi's 5-hour limit names its window, at the lift the reply states`() {
        val random = Random(332)
        repeat(RUNS) {
            val at = answeredAt(random)
            val stated = if (random.nextInt(3) == 0) random.nextLong(1, 900) else null
            val reply = reply(429, "rate_limit_error", FIVE_HOUR_SAID, at, retryAfterS = stated)
            val lift = stated
                ?.let { Lift.At(at.plusSeconds(it), firm = true, LiftSource.RETRY_AFTER) }
                ?: Lift.Unstated

            assertEquals(
                FailureCause.Provider.Limited(lift, LimitWindow.FIVE_HOUR, ProviderWordsFilter.of(FIVE_HOUR_SAID)),
                KimiReplies.http(reply),
                reply.toString(),
            )
        }
    }

    @Test
    fun `the same 5-hour limit read in band names the same window`() {
        val random = Random(333)
        repeat(RUNS) {
            val payload = body("rate_limit_error", FIVE_HOUR_SAID)
            val event = FailedEvent("error", payload, LoginOwner.entries.random(random))

            assertEquals(
                FailureCause.Provider.Limited(
                    Lift.Unstated,
                    LimitWindow.FIVE_HOUR,
                    ProviderWordsFilter.of(FIVE_HOUR_SAID),
                ),
                KimiReplies.event(event),
            )
        }
    }

    @Test
    fun `every reply that lacks Kimi's two shapes is left to the Anthropic standard`() {
        val random = Random(334)
        repeat(RUNS) {
            val said = PLAIN.random(random)
            val status = STATUSES.random(random)
            val reply = reply(
                status,
                TYPES.random(random),
                said,
                answeredAt(random),
                retryAfterS = if (random.nextBoolean()) random.nextLong(1, 900) else null,
            ).copy(owner = LoginOwner.entries.random(random))

            assertNull(KimiReplies.http(reply), reply.toString())
            assertFalse(KimiReplies.deadCredential(reply), reply.toString())
            assertNull(KimiReplies.planLimit(reply), reply.toString())
            assertNull(KimiReplies.event(FailedEvent("error", body(TYPES.random(random), said), reply.owner)), said)
        }
    }
}
