// NEW: failure-handling slice 3. Muse's deviations from the Responses standard, held over generated replies: its 429's
// stated reset is SOFT, because requests were served under it; a body naming the account's plan limit is Muse's
// verdict on the plan, whatever status spells it; and a key mint that grants nothing refuses the login with its own
// reason instead of reading as no login at all. Everything else is the standard's row (final-design.md section 12,
// D1's second property).
package splice.provider.muse

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.LiftSource
import splice.core.failure.LoginOwner
import splice.core.failure.ProviderWordsFilter
import splice.upstream.failure.FailedReply
import splice.upstream.failure.SentKey
import java.time.Instant
import kotlin.random.Random

private const val RUNS = 2_000

/** The hold a rate-limited mint takes, and its ceiling, as MuseMintVerdict records them. */
private const val MINT_RATE_HOLD_MS = 60_000L
private const val MINT_MAX_HOLD_MS = 3_600_000L

private val NEVER_SENT = SentKey { _, _ -> false }

/** A granted mint, whose key the turn sends. */
private val GRANTED_KEY = MuseSubscriptionKey(apiKey = "muse-inference-key", fields = JsonObject(emptyMap()))

private val STATUSES = listOf(400, 401, 402, 403, 404, 429, 500, 502, 503)

/** Bodies that name no plan limit and no reset. */
private val PLAIN = listOf(
    """{"error":{"message":"Internal server error","type":"server_error"}}""",
    """{"error":{"message":"The model is not available","code":"model_not_found"}}""",
    """{"detail":"Bad Request"}""",
)

private fun reply(
    status: Int,
    body: String,
    at: Instant,
    headers: Map<String, String> = emptyMap(),
    owner: LoginOwner = LoginOwner.SPLICE,
): FailedReply = FailedReply(status, headers, body, owner, NEVER_SENT, at)

private fun answeredAt(random: Random): Instant =
    Instant.ofEpochSecond(1_793_000_000L + random.nextLong(10_000_000))

class MuseRepliesPropertyTest {
    // The 429 of Oct 25, 10:16:47 PM CT named a reset 2 h 8 min out and carried Retry-After 7,728,000 ms, and yet
    // four of fourteen requests in the next seven minutes were served (audit-verdicts.md:186-197). Its reset is
    // Muse's word about its window, not a closed door, so the limit holds with probes rather than firmly.
    @Test
    fun `Muse's 429 is a limit until the reset it states, softly, so a probe may be served before it`() {
        val random = Random(361)
        repeat(RUNS) {
            val at = answeredAt(random)
            val resetAt = at.plusSeconds(random.nextLong(60, 30_000))
            val said = "Subscription quota exhausted. Your usage window resets at $resetAt."
            val body = """{"error":{"message":"$said","code":"rate_limit_exceeded"},""" +
                """"resets_at":${resetAt.epochSecond}}"""
            val pushbackS = random.nextLong(1, 8_000)
            val headers = if (random.nextBoolean()) mapOf("retry-after" to pushbackS.toString()) else mapOf()

            assertEquals(
                FailureCause.Provider.Limited(
                    Lift.At(Instant.ofEpochSecond(resetAt.epochSecond), firm = false, LiftSource.BODY),
                    null,
                    ProviderWordsFilter.of(said),
                ),
                MuseReplies.http(reply(429, body, at, headers)),
                body,
            )
        }
    }

    @Test
    fun `a 429 that states no reset of its own takes its Retry-After, softly`() {
        val random = Random(362)
        repeat(RUNS) {
            val at = answeredAt(random)
            val seconds = random.nextLong(1, 90_000)
            val said = "Subscription quota exhausted"
            val body = """{"error":{"message":"$said","code":"rate_limit_exceeded"}}"""

            assertEquals(
                FailureCause.Provider.Limited(
                    Lift.At(at.plusSeconds(seconds), firm = false, LiftSource.RETRY_AFTER),
                    null,
                    ProviderWordsFilter.of(said),
                ),
                MuseReplies.http(reply(429, body, at, mapOf("Retry-After" to seconds.toString()))),
                body,
            )
        }
    }

    // Muse states its reset two ways: `resets_at` in epoch seconds, and the instant its message names (both are in
    // the 429 of Oct 25). Either alone is the reset. One already past when the answer came states none, so the
    // Retry-After stands in its place.
    @Test
    fun `either of Muse's two spellings alone states the reset, and a reset already past states none`() {
        val random = Random(366)
        repeat(RUNS) {
            val at = answeredAt(random)
            val resetAt = at.plusSeconds(random.nextLong(-30_000, 30_000))
            val inMessage = random.nextBoolean()
            val said = if (inMessage) "Your usage window resets at $resetAt." else "Subscription quota exhausted."
            val epoch = if (inMessage) "" else ""","resets_at":${resetAt.epochSecond}"""
            val body = """{"error":{"code":"rate_limit_exceeded","message":"$said"$epoch}}"""
            val pushbackS = random.nextLong(1, 8_000)
            val lift = if (resetAt > at) {
                Lift.At(resetAt, firm = false, LiftSource.BODY)
            } else {
                Lift.At(at.plusSeconds(pushbackS), firm = false, LiftSource.RETRY_AFTER)
            }

            assertEquals(
                FailureCause.Provider.Limited(lift, null, ProviderWordsFilter.of(said)),
                MuseReplies.http(reply(429, body, at, mapOf("retry-after" to pushbackS.toString()))),
                body,
            )
        }
    }

    @Test
    fun `a body naming the account's plan limit is Muse's verdict on the plan, in Muse's words`() {
        val random = Random(363)
        repeat(RUNS) {
            val said = "Your account has reached its plan limit for this model"
            val body = """{"error":{"message":"$said","code":"forbidden"}}"""
            // Muse sent this as a 403 (audit-maps.md:668); a 401 spelling of it is no dead login either.
            val status = listOf(401, 403).random(random)

            assertEquals(
                FailureCause.Provider.Forbidden(ProviderWordsFilter.of(said)),
                MuseReplies.http(reply(status, body, answeredAt(random))),
                body,
            )
        }
    }

    @Test
    fun `a key mint that grants nothing refuses the login with the reason the mint gave`() {
        val random = Random(364)
        repeat(RUNS) {
            val nowMs = answeredAt(random).toEpochMilli()
            val retryAfterMs = if (random.nextInt(3) == 0) random.nextLong(0, 9_000_000) else null
            val held = (retryAfterMs ?: MINT_RATE_HOLD_MS).coerceIn(MINT_RATE_HOLD_MS, MINT_MAX_HOLD_MS)

            assertEquals(
                FailureCause.Provider.NeedsPayment(Lift.Unstated, subscription = true),
                MuseReplies.mintRefusal(MuseMintAttempt.SubscriptionRequired(actionUrl = null), nowMs),
            )
            assertEquals(
                FailureCause.Provider.Limited(
                    Lift.At(Instant.ofEpochMilli(nowMs + held), firm = false, LiftSource.BODY),
                    null,
                ),
                MuseReplies.mintRefusal(MuseMintAttempt.RateLimited(retryAfterMs), nowMs),
                "retryAfterMs=$retryAfterMs",
            )
            assertEquals(
                FailureCause.Provider.Forbidden(),
                MuseReplies.mintRefusal(MuseMintAttempt.Denied("the mint said no"), nowMs),
            )
            assertEquals(
                FailureCause.Provider.AuthRejected(LoginOwner.SPLICE),
                MuseReplies.mintRefusal(MuseMintAttempt.InvalidAccountToken, nowMs),
            )
            // A mint that granted a key refuses nothing: the turn sends it.
            assertNull(MuseReplies.mintRefusal(MuseMintAttempt.Granted(GRANTED_KEY), nowMs))
        }
    }

    @Test
    fun `every other reply, and a granted mint, are left to the Responses standard`() {
        val random = Random(365)
        repeat(RUNS) {
            val body = PLAIN.random(random)
            val status = STATUSES.filter { it != 429 }.random(random)
            val reply = reply(status, body, answeredAt(random), owner = LoginOwner.entries.random(random))

            assertNull(MuseReplies.http(reply), reply.toString())
            assertNull(MuseReplies.planLimit(reply), reply.toString())
        }
    }
}
