// NEW: failure-handling slice 4 (2026-10-27). Anthropic's answer that a Claude account must accept updated terms is a
// block on the account, held over generated replies: whatever status or type carries it, it names the step and where
// the owner takes it. Every other reply a Claude login meets is the Messages standard's, so the deviation claims none.
package splice.provider.anthropic

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.failure.AccountBlock
import splice.core.failure.FailureCause
import splice.core.failure.LoginOwner
import splice.core.failure.OwnerStep
import splice.core.failure.ProviderWordsFilter
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.SentKey
import java.time.Instant
import kotlin.random.Random

private const val RUNS = 2_000

/** Anthropic's answer to claude-splice at 2:52:10 PM CT on Oct 27, 2026, verbatim (daemon.log). */
private const val UPDATED_TERMS =
    "We've updated our Consumer Terms and Privacy Policy. You'll need to accept them in claude.ai with the email " +
        "in /status to continue."

/** Where the owner of a Claude account accepts Anthropic's terms. */
private const val CLAUDE_AI = "claude.ai"

/** Sentences a Claude login meets that say nothing about the account's standing. */
private val PLAIN = listOf(
    "messages.0.content: Input should be a valid list",
    "prompt is too long: 212345 tokens > 200000 maximum",
    "Your credit balance is too low to access the Anthropic API.",
    "This request would exceed your account's rate limit. Please try again later.",
    "OAuth token has expired. Please obtain a new token or refresh your existing token.",
    "Overloaded",
    "Internal server error",
    "Updated terms of service apply to API keys created after March 1.",
)

private val STATUSES = listOf(400, 401, 403, 404, 413, 429, 500, 529)
private val TYPES = listOf(
    "invalid_request_error",
    "authentication_error",
    "permission_error",
    "rate_limit_error",
    "api_error",
    "overloaded_error",
)

private val NEVER_SENT = SentKey { _, _ -> false }

/** The block the updated-terms answer puts on the account it was sent to. */
private val ACCEPT_TERMS = AccountBlock(OwnerStep.ACCEPT_TERMS, CLAUDE_AI)

private fun body(type: String, message: String) = buildJsonObject {
    put("type", "error")
    putJsonObject("error") {
        put("type", type)
        put("message", message)
    }
}

private fun reply(status: Int, type: String, message: String, owner: LoginOwner, at: Instant): FailedReply =
    FailedReply(status, emptyMap(), body(type, message).toString(), owner, NEVER_SENT, at)

private fun answeredAt(random: Random): Instant = Instant.ofEpochSecond(1_793_000_000L + random.nextLong(10_000_000))

class ClaudeRepliesPropertyTest {
    @Test
    fun `the updated-terms answer blocks the account until its owner accepts the terms, at any status`() {
        val random = Random(4101)
        repeat(RUNS) {
            val owner = LoginOwner.entries.random(random)
            val reply = reply(STATUSES.random(random), TYPES.random(random), UPDATED_TERMS, owner, answeredAt(random))

            assertEquals(
                FailureCause.Provider.Forbidden(ProviderWordsFilter.of(UPDATED_TERMS), ACCEPT_TERMS),
                ClaudeReplies.http(reply),
                reply.toString(),
            )
            // An account its owner must act on is no dead credential: renewing the login meets the same answer.
            assertFalse(ClaudeReplies.deadCredential(reply), reply.toString())
        }
    }

    @Test
    fun `the same answer in band blocks the account the same way`() {
        val random = Random(4102)
        repeat(RUNS) {
            val owner = LoginOwner.entries.random(random)
            val event = FailedEvent("error", body(TYPES.random(random), UPDATED_TERMS), owner)

            assertEquals(
                FailureCause.Provider.Forbidden(ProviderWordsFilter.of(UPDATED_TERMS), ACCEPT_TERMS),
                ClaudeReplies.event(event),
                event.toString(),
            )
        }
    }

    @Test
    fun `every reply that does not name the updated terms is left to the Messages standard`() {
        val random = Random(4103)
        repeat(RUNS) {
            val said = PLAIN.random(random)
            val owner = LoginOwner.entries.random(random)
            val reply = reply(STATUSES.random(random), TYPES.random(random), said, owner, answeredAt(random))

            assertNull(ClaudeReplies.http(reply), reply.toString())
            assertFalse(ClaudeReplies.deadCredential(reply), reply.toString())
            assertNull(ClaudeReplies.planLimit(reply), reply.toString())
            assertNull(ClaudeReplies.event(FailedEvent("error", body(TYPES.random(random), said), owner)), said)
        }
    }
}
