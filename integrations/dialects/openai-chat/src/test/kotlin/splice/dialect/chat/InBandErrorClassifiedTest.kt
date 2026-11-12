// NEW: V4-164 — the chat dialect's in-band error event goes through the reading every other
// path uses. It was the one dialect of three that kept only error.message and called every in-band
// error UPSTREAM_REPORTED, so the wire type the client keys its retry on was api_error whatever the
// vendor had said: an overflow was retried identically instead of compacted, and a rate limit looked
// like a server fault. Since failure-handling slice 3 that reading is the head's own (ChatReplies), the
// one its HTTP replies get, where the shared classifier read it until then.
package splice.dialect.chat

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.ClientWire
import splice.core.failure.PersistedCause
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome

class InBandErrorClassifiedTest {

    private suspend fun failureOf(json: String): TurnOutcome.Failure = driveEvents(ev(json)) as TurnOutcome.Failure

    // Failure-handling slice 3: a status written in-band reads as its HTTP twin does, a 500 as a server error that
    // heals. The shared classifier's rewrite of llama-server's words ("KV cache is full", LlamaCppErrors) and its
    // relabel to an overload are gone: after content a cause keeps its own type (final-design.md section 11), and
    // the evidence keeps the server's own words.
    @Test
    fun `an in-band 500 is a server error that heals`() = runTest {
        val f = failureOf(
            """{"error":{"code":500,"message":"Context size has been exceeded.","type":"server_error"}}""",
        )

        assertEquals(PersistedCause.UPSTREAM_STATUS_5XX, f.cause.persisted)
        assertEquals(ErrorType.API_ERROR, ClientWire.ownType(f.cause))
        assertTrue(f.evidence.message.contains("Context size has been exceeded."), f.evidence.message)
        assertFalse(f.cause.permanent, "a 5xx heals: advertised as retryable")
    }

    // V4-167. Mutant: never carry the verdict (V4-164). A typed vendor error that identical bytes
    // reproduce went out as the overloaded_error Claude Code re-sends, up to 300 times.
    @Test
    fun `a typed in-band error the same bytes reproduce is not advertised as retryable`() = runTest {
        val f = failureOf("""{"error":{"message":"Invalid prompt: the prompt was flagged","type":"invalid_prompt"}}""")

        assertTrue(f.cause.permanent)
    }

    // V4-167. Mutant: any integer code is a status (V4-164). A vendor's own code "1301" read as
    // HTTP 1301, a 5xx, and was retried whatever it meant.
    @Test
    fun `a vendor's own numeric code is its kind, not an HTTP status`() = runTest {
        val f = failureOf("""{"error":{"code":"1301","message":"the request was rejected"}}""")

        assertNotEquals(PersistedCause.UPSTREAM_STATUS_5XX, f.cause.persisted)
    }

    @Test
    fun `an in-band overflow reaches the client as the prompt-too-long it compacts on`() = runTest {
        val f = failureOf(
            """{"error":{"message":"This model's maximum context length is 128000 tokens. However, your messages """ +
                """resulted in 131072 tokens.","type":"invalid_request_error","param":"messages","code":""" +
                """"context_length_exceeded"}}""",
        )

        assertEquals(PersistedCause.REQUEST_TOO_LARGE, f.cause.persisted)
        assertEquals(ErrorType.INVALID_REQUEST, ClientWire.ownType(f.cause))
        // The line the client reads says the prompt is too long, composed from the cause (ProviderSentences); the
        // evidence keeps the provider's own words, where the shared classifier rewrote them until slice 3.
        assertTrue(f.evidence.message.contains("maximum context length is 128000 tokens"), f.evidence.message)
    }

    @Test
    fun `an in-band rate limit is a rate limit`() = runTest {
        val f = failureOf(
            """{"error":{"message":"Rate limit reached for requests","type":"requests","code":""" +
                """"rate_limit_exceeded"}}""",
        )

        assertEquals(PersistedCause.VENDOR_RATE_LIMITED, f.cause.persisted)
        assertEquals(ErrorType.RATE_LIMIT, ClientWire.ownType(f.cause))
    }

    // The byte-identity half: a shape no row names keeps the cause, and so the wire, it had before
    // this row — the reading's own floor is Unrecognized, persisted as UPSTREAM_REPORTED.
    @Test
    fun `an unrecognised in-band error keeps the cause and wire it had`() = runTest {
        val f = failureOf("""{"error":{"message":"something the classifier has no rule for"}}""")

        assertEquals(PersistedCause.UPSTREAM_REPORTED, f.cause.persisted)
        assertEquals("chat backend: something the classifier has no rule for", f.evidence.message)
        // V4-167. Mutant: carry the verdict for every event. One with no type and no status keeps the
        // retryable wire V4-164 promised it.
        assertFalse(f.cause.permanent)
    }
}
