// NEW: V4-242 (2026-09-26) — the foreign-key rule, cell by cell. Every key here is a fake in the shape an upstream
// masks one: a visible head, a run of stars, a visible tail.
//
// Failure-handling slice 3: the rule is read once, where the reply arrives, by the head's Replies (the foreign-key rule
// every API type shares), from the credential the attempt sent (ForeignCredential.sentKey). The cases that pinned the
// classifier's rewrite of a reading it had already made (upstreamsOwn) are gone with that rewrite.
package splice.upstream.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.failure.FailureCause
import splice.core.failure.HeadVoice
import splice.core.failure.LoginOwner
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.ServerFault
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ForeignCredentialTest {
    private val bearer = Credentials.Bearer("eyFakeSignInToken.v4242", "acct-v4242")
    private val apiKey = Credentials.ApiKey("sk-own0-fake-key-v4242-tail")
    private val replies = Replies(OpenAiReplies)

    private fun rejection(masked: String): String =
        """{"error":{"message":"Incorrect API key provided: $masked. You can find your API key at """ +
            """https://platform.openai.com/account/api-keys.","type":"invalid_request_error",""" +
            """"code":"invalid_api_key"}}"""

    /** The reading of a 401 with [body], sent with [sent] by a login splice holds unless [owner] says otherwise. */
    private fun read(
        body: String,
        sent: Credentials?,
        owner: LoginOwner = LoginOwner.SPLICE,
        status: Int = UNAUTHORIZED,
    ): FailureCause.Provider = replies.http(
        FailedReply(status, emptyMap(), body, owner, ForeignCredential.sentKey(sent), Instant.EPOCH),
    )

    private fun foreign(cause: FailureCause.Provider): Boolean =
        (cause as? FailureCause.Provider.ServerError)?.fault == ServerFault.FOREIGN_KEY

    @Test
    fun `a masked key no sign-in token can be is foreign`() {
        assertTrue(foreign(read(rejection(FOREIGN), bearer)))
    }

    @Test
    fun `a masked key another API key cannot be is foreign`() {
        assertTrue(foreign(read(rejection(FOREIGN), apiKey)))
    }

    @Test
    fun `a masked key the sent API key can be is not foreign`() {
        val cause = read(rejection("sk-own0" + STARS + "tail"), apiKey)
        assertEquals(FailureCause.Provider.AuthRejected::class, cause::class, "$cause")
    }

    @Test
    fun `a head that matches with a tail that does not is still foreign`() {
        assertTrue(foreign(read(rejection("sk-own0" + STARS + "Zq9x"), apiKey)))
    }

    @Test
    fun `a body that names no masked key says nothing`() {
        assertFalse(foreign(read("""{"error":{"message":"invalid_api_key: Unauthorized"}}""", bearer)))
        assertFalse(foreign(read("", bearer)))
    }

    @Test
    fun `a credential splice does not hold says nothing`() {
        assertFalse(foreign(read(rejection(FOREIGN), Credentials.ClientForwarded, LoginOwner.CLIENT)))
        assertFalse(foreign(read(rejection(FOREIGN), null)))
    }

    @Test
    fun `a short run of stars is not a masked key`() {
        assertFalse(foreign(read(rejection("sk-test0***Zq9x"), bearer)))
    }

    @Test
    fun `only a 401 is read by the rule`() {
        assertFalse(foreign(read(rejection(FOREIGN), bearer, status = TOO_MANY_REQUESTS)))
    }

    // Slice 2: a person reads the line SpliceLines writes from the cause. No sentence of splice's carries the masked
    // key anywhere, and the words a line may quote hold no part of it.
    @Test
    fun `a foreign 401 carries no fragment of the key it names to a person`() {
        val cause = read(rejection(FOREIGN), bearer)

        val said = cause.said?.text.orEmpty()
        listOf("sk-test0", "Zq9x", "*").forEach { fragment -> assertFalse(fragment in said, said) }
        val at = Position(Moment.BEFORE_COMMIT, streamBegan = false)
        val voice = HeadVoice("claude-codex", "claude-codex login", clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        val line = voice.anyTurn.ending(cause, at).text
        listOf("sk-test0", "Zq9x", "*").forEach { fragment -> assertFalse(fragment in line, line) }
    }
}

private const val UNAUTHORIZED = 401
private const val TOO_MANY_REQUESTS = 429
private val STARS = "*".repeat(40)
private val FOREIGN = "sk-test0" + STARS + "Zq9x"
