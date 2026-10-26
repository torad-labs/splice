// NEW: V4-242 (2026-09-26) — ForeignCredential's rule, cell by cell. Every key here is a fake in the
// shape an upstream masks one: a visible head, a run of stars, a visible tail.
package splice.upstream.failure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.core.failure.FailureCause
import splice.core.failure.HeadVoice
import splice.core.failure.Moment
import splice.core.failure.PersistedCause
import splice.core.failure.Position
import splice.core.failure.ServerFault
import splice.core.turn.ErrorType
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ForeignCredentialTest {
    private val bearer = Credentials.Bearer("eyFakeSignInToken.v4242", "acct-v4242")
    private val apiKey = Credentials.ApiKey("sk-own0-fake-key-v4242-tail")

    private fun rejection(masked: String): String =
        """{"error":{"message":"Incorrect API key provided: $masked. You can find your API key at """ +
            """https://platform.openai.com/account/api-keys.","type":"invalid_request_error",""" +
            """"code":"invalid_api_key"}}"""

    @Test
    fun `a masked key no sign-in token can be is foreign`() {
        assertTrue(ForeignCredential.named(rejection(FOREIGN), bearer))
    }

    @Test
    fun `a masked key another API key cannot be is foreign`() {
        assertTrue(ForeignCredential.named(rejection(FOREIGN), apiKey))
    }

    @Test
    fun `a masked key the sent API key can be is not foreign`() {
        assertFalse(ForeignCredential.named(rejection("sk-own0" + STARS + "tail"), apiKey))
    }

    @Test
    fun `a head that matches with a tail that does not is still foreign`() {
        assertTrue(ForeignCredential.named(rejection("sk-own0" + STARS + "Zq9x"), apiKey))
    }

    @Test
    fun `a body that names no masked key says nothing`() {
        assertFalse(ForeignCredential.named("""{"error":{"message":"invalid_api_key: Unauthorized"}}""", bearer))
        assertFalse(ForeignCredential.named(null, bearer))
    }

    @Test
    fun `a credential splice does not hold says nothing`() {
        assertFalse(ForeignCredential.named(rejection(FOREIGN), Credentials.ClientForwarded))
        assertFalse(ForeignCredential.named(rejection(FOREIGN), null))
    }

    @Test
    fun `a short run of stars is not a masked key`() {
        assertFalse(ForeignCredential.named(rejection("sk-test0***Zq9x"), bearer))
    }

    @Test
    fun `a foreign 401 becomes the upstream's own transient failure`() {
        val body = rejection(FOREIGN)
        val classified = UpstreamFailureClassifier.classify(FailureSource.HTTP, body, UNAUTHORIZED)
        assertEquals(ErrorType.AUTHENTICATION, classified.type, "the classifier reads every 401 as the sign-in")

        val failure = ForeignCredential.upstreamsOwn(classified, body, bearer)

        assertEquals(ErrorType.API_ERROR, failure.type)
        assertTrue(failure.transient, "the upstream refused itself and may stop")
        assertEquals(PersistedCause.UPSTREAM_REPORTED, failure.cause.persisted)
        assertEquals(UNAUTHORIZED, failure.status)
        assertEquals(ServerFault.FOREIGN_KEY, (failure.cause as FailureCause.Provider.ServerError).fault)
    }

    // Slice 2: a person reads the line SpliceLines writes from the cause, and the log keeps the provider's own words.
    // No sentence of splice's carries the masked key anywhere, and the words a line may quote hold no part of it.
    @Test
    fun `a foreign 401 carries no fragment of the key it names to a person, and the log keeps the provider's words`() {
        val body = rejection(FOREIGN)
        val classified = UpstreamFailureClassifier.classify(FailureSource.HTTP, body, UNAUTHORIZED)

        val failure = ForeignCredential.upstreamsOwn(classified, body, bearer)

        assertEquals(classified.message, failure.message, "the log reads what the provider said, and nothing added")
        val said = failure.cause.said?.text.orEmpty()
        listOf("sk-test0", "Zq9x", "*").forEach { fragment -> assertFalse(fragment in said, said) }
        val at = Position(Moment.BEFORE_COMMIT, streamBegan = false)
        val voice = HeadVoice("claude-codex", "claude-codex login", clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        val line = voice.anyTurn.ending(failure.cause, at).text
        listOf("sk-test0", "Zq9x", "*").forEach { fragment -> assertFalse(fragment in line, line) }
    }

    @Test
    fun `a 401 naming the sent key, or any other failure, is left as it was`() {
        val own = rejection("sk-own0" + STARS + "tail")
        val ownFailure = UpstreamFailureClassifier.classify(FailureSource.HTTP, own, UNAUTHORIZED)
        assertSame(ownFailure, ForeignCredential.upstreamsOwn(ownFailure, own, apiKey))

        val limited = UpstreamFailureClassifier.classify(FailureSource.HTTP, rejection(FOREIGN), TOO_MANY_REQUESTS)
        assertSame(limited, ForeignCredential.upstreamsOwn(limited, rejection(FOREIGN), bearer))
    }
}

private const val UNAUTHORIZED = 401
private const val TOO_MANY_REQUESTS = 429
private val STARS = "*".repeat(40)
private val FOREIGN = "sk-test0" + STARS + "Zq9x"
