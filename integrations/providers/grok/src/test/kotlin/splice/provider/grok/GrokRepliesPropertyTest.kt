// NEW: failure-handling slice 3. xAI's one deviation, held over generated replies: a 403 in xAI's expiry words is a
// dead credential, which renews the login once as a 401 does. Its spending-limit 402 and its refused images are the
// standard's rows now, so the deviation claims neither (final-design.md section 12, D1's second property).
package splice.provider.grok

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.LoginOwner
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedReply
import splice.upstream.failure.SentKey
import java.time.Instant
import kotlin.random.Random

private const val RUNS = 2_000

/** How xAI spells a token it no longer accepts (the grok-dead-head incident of 2026-07-18), each as a whole body. */
private val EXPIRED = listOf(
    """{"code":"unauthenticated","msg":"bad-credentials"}""",
    """{"error":"The OAuth2 access token could not be validated"}""",
    """{"error":{"message":"token is expired","code":"expired_token"}}""",
    """{"error":{"message":"The access token could not be validated","type":"invalid_request_error"}}""",
)

/** xAI's other 403s and 402s, which the standard reads: the spending limit of Oct 20, 10:27:59 PM CT included. */
private val OTHER = listOf(
    """{"code":"personal-team-blocked:spending-limit","error":"Your team has run out of credits"}""",
    """{"error":{"code":"invalid_image","message":"Image dimensions 1x1 are too small."}}""",
    """{"error":{"message":"You need a Grok subscription to use this model","code":"forbidden"}}""",
    """{"error":{"message":"Internal server error","code":"server_error"}}""",
)

private val STATUSES = listOf(400, 401, 402, 403, 404, 429, 500, 502, 503)

private val NEVER_SENT = SentKey { _, _ -> false }

private fun reply(status: Int, body: String, owner: LoginOwner, at: Instant): FailedReply =
    FailedReply(status, emptyMap(), body, owner, NEVER_SENT, at)

private fun answeredAt(random: Random): Instant =
    Instant.ofEpochSecond(1_793_000_000L + random.nextLong(10_000_000))

class GrokRepliesPropertyTest {
    @Test
    fun `a 403 in xAI's expiry words is a dead credential, read as the rejected login it is`() {
        val random = Random(341)
        repeat(RUNS) {
            val body = EXPIRED.random(random)
            val owner = LoginOwner.entries.random(random)
            val reply = reply(403, body, owner, answeredAt(random))

            assertTrue(GrokReplies.deadCredential(reply), body)
            assertEquals(
                // xAI words it in the envelope it sends, which is what a line may quote: the message of an error
                // object, or an error that is itself a string, never the JSON around it.
                FailureCause.Provider.AuthRejected(owner, ErrorMessage.said(body)),
                GrokReplies.http(reply),
                body,
            )
        }
    }

    @Test
    fun `the same expiry words at any other status are left to the standard`() {
        val random = Random(342)
        repeat(RUNS) {
            val body = EXPIRED.random(random)
            val status = STATUSES.filter { it != 403 }.random(random)
            val reply = reply(status, body, LoginOwner.entries.random(random), answeredAt(random))

            assertFalse(GrokReplies.deadCredential(reply), "$status $body")
            assertNull(GrokReplies.http(reply), "$status $body")
        }
    }

    @Test
    fun `a spending limit, a refused image and every other answer are the standard's rows`() {
        val random = Random(343)
        repeat(RUNS) {
            val body = OTHER.random(random)
            val reply = reply(STATUSES.random(random), body, LoginOwner.entries.random(random), answeredAt(random))

            assertNull(GrokReplies.http(reply), reply.toString())
            assertFalse(GrokReplies.deadCredential(reply), reply.toString())
            assertNull(GrokReplies.planLimit(reply), reply.toString())
        }
    }
}
