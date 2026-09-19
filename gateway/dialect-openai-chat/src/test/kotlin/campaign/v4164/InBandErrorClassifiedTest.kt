// NEW: V4-164 — the chat dialect's in-band error event goes through the classifier every other
// path uses. It was the one dialect of three that kept only error.message and called every in-band
// error UPSTREAM_REPORTED, so the wire type the client keys its retry on was api_error whatever the
// vendor had said: an overflow was retried identically instead of compacted, a rate limit looked
// like a server fault, and llama-server's full KV pool reached the banner as bare text.
package campaign.v4164

import driveEvents
import ev
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.core.turn.FailureCause
import splice.core.turn.TurnOutcome

class InBandErrorClassifiedTest {

    private suspend fun failureOf(json: String): TurnOutcome.Failure = driveEvents(ev(json)) as TurnOutcome.Failure

    // Mutant (for all three below): the pre-V4-164 router — keep error.message, hard-code
    // UPSTREAM_REPORTED. Every cause assertion goes red; the unrecognised case below stays green.
    @Test
    fun `llama-server's full KV pool is named and wired as the class the client retries`() = runTest {
        val f = failureOf(
            """{"error":{"code":500,"message":"Context size has been exceeded.","type":"server_error"}}""",
        )

        assertEquals(FailureCause.UPSTREAM_STATUS_5XX, f.cause)
        assertEquals(ErrorType.OVERLOADED, f.type)
        assertTrue(f.message.contains("KV cache is full"), f.message)
        assertFalse(f.permanent, "a 5xx heals: advertised as retryable")
    }

    // V4-167. Mutant: never carry the verdict (V4-164). A typed vendor error that identical bytes
    // reproduce went out as the overloaded_error Claude Code re-sends, up to 300 times.
    @Test
    fun `a typed in-band error the same bytes reproduce is not advertised as retryable`() = runTest {
        val f = failureOf("""{"error":{"message":"Invalid prompt: the prompt was flagged","type":"invalid_prompt"}}""")

        assertTrue(f.permanent)
    }

    // V4-167. Mutant: any integer code is a status (V4-164). A vendor's own code "1301" read as
    // HTTP 1301, a 5xx, and was retried whatever it meant.
    @Test
    fun `a vendor's own numeric code is its kind, not an HTTP status`() = runTest {
        val f = failureOf("""{"error":{"code":"1301","message":"the request was rejected"}}""")

        assertNotEquals(FailureCause.UPSTREAM_STATUS_5XX, f.cause)
    }

    @Test
    fun `an in-band overflow reaches the client as the prompt-too-long it compacts on`() = runTest {
        val f = failureOf(
            """{"error":{"message":"This model's maximum context length is 128000 tokens. However, your messages """ +
                """resulted in 131072 tokens.","type":"invalid_request_error","param":"messages","code":"context_length_exceeded"}}""",
        )

        assertEquals(FailureCause.REQUEST_TOO_LARGE, f.cause)
        assertEquals(ErrorType.INVALID_REQUEST, f.type)
        assertTrue(f.message.contains("prompt is too long"), f.message)
    }

    @Test
    fun `an in-band rate limit is a rate limit`() = runTest {
        val f = failureOf(
            """{"error":{"message":"Rate limit reached for requests","type":"requests","code":"rate_limit_exceeded"}}""",
        )

        assertEquals(FailureCause.VENDOR_RATE_LIMITED, f.cause)
        assertEquals(ErrorType.RATE_LIMIT, f.type)
    }

    // The byte-identity half: a shape the classifier has no rule for keeps the cause, and so the
    // wire, it had before this row — the classifier's own floor is UPSTREAM_REPORTED.
    @Test
    fun `an unrecognised in-band error keeps the cause and wire it had`() = runTest {
        val f = failureOf("""{"error":{"message":"something the classifier has no rule for"}}""")

        assertEquals(FailureCause.UPSTREAM_REPORTED, f.cause)
        assertEquals("chat backend: something the classifier has no rule for", f.message)
        // V4-167. Mutant: carry the verdict for every event. One with no type and no status keeps the
        // retryable wire V4-164 promised it.
        assertFalse(f.permanent)
    }
}
