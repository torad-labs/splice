// NEW: failure-handling slice 3. The two properties that hold across API types (final-design.md section 7, D1): the
// foreign-key rule reads the same 401 the same way whatever API type answered it, and never on a login splice does
// not hold; and a provider's Replies adds nothing of its own to a reply its deviation does not claim, so every head
// of one API type reads a standard reply the same way.
//
// The third D1 property, that each deviation returns nothing on every standard reply, is asserted in the module that
// declares the deviation (KimiRepliesPropertyTest and its four siblings), where it is visible.
package splice.app.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.LoginOwner
import splice.core.failure.ServerFault
import splice.dialect.anthropic.failure.AnthropicReplies
import splice.dialect.chat.failure.ChatReplies
import splice.dialect.responses.failure.ResponsesReplies
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedReply
import splice.upstream.failure.Replies
import splice.upstream.failure.ReplyReader
import splice.upstream.failure.SentKey
import java.time.Instant
import kotlin.random.Random

private const val RUNS = 1_000

/** The three API types a head of splice speaks, each with the reader that reads its failures. */
private val API_TYPES: List<Pair<String, ReplyReader>> = listOf(
    "anthropic" to AnthropicReplies,
    "chat" to ChatReplies,
    "responses" to ResponsesReplies,
)

/** The statuses and bodies a provider answers with, none of them a 401 naming a masked key. */
private val STATUSES = listOf(400, 402, 403, 404, 413, 429, 500, 502, 503, 504, 529)

private val BODIES = listOf(
    """{"error":{"type":"invalid_request_error","message":"prompt is too long: 300000 tokens > 200000 maximum"}}""",
    """{"error":{"type":"rate_limit_error","message":"Rate limit reached for this model"}}""",
    """{"error":{"message":"The model `gpt-5.9` does not exist","code":"model_not_found"}}""",
    """{"error":{"message":"You have run out of credits","code":"insufficient_quota"}}""",
    """{"error":{"message":"The server had an error","type":"server_error"}}""",
    """<html><head><title>502 Bad Gateway</title></head></html>""",
)

/** A masked key, as every provider writes one: a visible head, a run of stars, a visible tail. */
private fun maskedKey(random: Random): String {
    val head = listOf("sk-svcac", "sk-proj", "xai-abc", "key-9f2").random(random)
    val tail = listOf("fvMA", "7Qd1", "zzTT", "a91c").random(random)
    return "$head${"*".repeat(random.nextInt(4, 12))}$tail"
}

private fun answeredAt(random: Random): Instant =
    Instant.ofEpochSecond(1_793_000_000L + random.nextLong(10_000_000))

/** A 401 whose body names [keys] and nothing else of its sender. */
private fun rejectedLogin(keys: List<String>, owner: LoginOwner, at: Instant, sent: SentKey): FailedReply =
    FailedReply(
        401,
        emptyMap(),
        """{"error":{"type":"authentication_error","message":"Incorrect API key provided: """ +
            keys.joinToString(", ") + """. Find your key in your account settings."}}""",
        owner,
        sent,
        at,
    )

class ReadOnceD1PropertyTest {
    // V4-242: during the Codex outage of Sep 25 the provider answered 401 invalid_api_key naming a masked service
    // key the account never sent, and the turn told the person their sign-in had expired and took the login out of
    // the pool. Any provider can fail its own check this way, so the rule holds on every API type.
    @Test
    fun `a 401 naming only masked keys this login never sent is the provider's own failure, on every API type`() {
        val random = Random(381)
        repeat(RUNS) {
            val keys = List(random.nextInt(1, 4)) { maskedKey(random) }
            val reply = rejectedLogin(keys, LoginOwner.SPLICE, answeredAt(random), SentKey { _, _ -> false })
            val expected = FailureCause.Provider.ServerError(ServerFault.FOREIGN_KEY, ErrorMessage.said(reply.body))

            API_TYPES.forEach { (name, standard) ->
                assertEquals(expected, Replies(standard).http(reply), "$name: ${reply.body}")
            }
        }
    }

    @Test
    fun `a 401 naming a key this login could be is the rejected login it is, on every API type`() {
        val random = Random(382)
        repeat(RUNS) {
            val keys = List(random.nextInt(1, 4)) { maskedKey(random) }
            // The sent credential answers for exactly one of the keys the body names, as the real comparison does.
            val mine = keys.random(random)
            val sent = SentKey { head, tail -> mine.startsWith(head) && mine.endsWith(tail) }
            val reply = rejectedLogin(keys, LoginOwner.SPLICE, answeredAt(random), sent)

            API_TYPES.forEach { (name, standard) ->
                val read = Replies(standard).http(reply)
                assertEquals(FailureCause.Provider.AuthRejected(LoginOwner.SPLICE, read.said), read, name)
            }
        }
    }

    @Test
    fun `a forwarded client login's 401 is never read as the provider's own failure`() {
        val random = Random(383)
        repeat(RUNS) {
            // splice never reads a forwarded login, so no masked key can be compared against it: the reply is the
            // client's own rejected login, and the client is the one who can fix it.
            val keys = List(random.nextInt(1, 4)) { maskedKey(random) }
            val reply = rejectedLogin(keys, LoginOwner.CLIENT, answeredAt(random), SentKey { _, _ -> false })

            API_TYPES.forEach { (name, standard) ->
                val read = Replies(standard).http(reply)
                assertNotEquals(ServerFault.FOREIGN_KEY, (read as? FailureCause.Provider.ServerError)?.fault, name)
                assertEquals(FailureCause.Provider.AuthRejected(LoginOwner.CLIENT, read.said), read, name)
            }
        }
    }

    @Test
    fun `a provider with no deviation reads every other reply exactly as its API type's standard does`() {
        val random = Random(384)
        repeat(RUNS) {
            val reply = FailedReply(
                STATUSES.random(random),
                if (random.nextBoolean()) mapOf("retry-after" to random.nextLong(1, 900).toString()) else emptyMap(),
                BODIES.random(random),
                LoginOwner.entries.random(random),
                SentKey { _, _ -> false },
                answeredAt(random),
            )

            API_TYPES.forEach { (name, standard) ->
                assertEquals(standard.http(reply), Replies(standard).http(reply), "$name: $reply")
                assertEquals(standard.planLimit(reply), Replies(standard).planLimit(reply), "$name: $reply")
            }
        }
    }
}
