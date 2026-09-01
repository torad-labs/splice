// DR-71 (codex adjudication probe, 2026-08-31): six symmetric holes in the statusless
// retry-wording heuristic and the structured-code allowlist, red 6/6 on the pre-fix classifier —
// em/en-dash and line-break clause cuts, the "retry" invitation synonym, "service is unavailable"
// wording, engine/model overload codes, the 120-char clause budget, and won't/will-not negators.
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.FailureSource
import splice.spi.UpstreamFailureClassifier

class UpstreamFailureRetryWordingTest {

    private fun sse(text: String) = UpstreamFailureClassifier.classify(FailureSource.SSE, text)

    private fun coded(code: String) =
        UpstreamFailureClassifier.classify(FailureSource.SSE, "the engine had a problem", code = code)

    @Test
    fun `an em-dash cut fresh retry clause is an invitation, not negated - DR-71`() {
        assertTrue(sse("Your request cannot be processed — please try again later").transient)
    }

    @Test
    fun `a line-break cut fresh retry clause is an invitation, not negated - DR-71`() {
        assertTrue(sse("This upload can't be parsed\nPlease try again").transient)
    }

    @Test
    fun `please retry is the same invitation as try again, negation-aware - DR-71`() {
        assertTrue(sse("Something went wrong. Please retry.").transient)
        assertFalse(sse("Do not retry this request").transient)
    }

    @Test
    fun `service is unavailable is a qualified outage, region restriction is not - DR-71`() {
        assertTrue(sse("The service is unavailable").transient)
        assertFalse(sse("The selected model is unavailable in your region").transient)
    }

    @Test
    fun `a clause longer than the old 120-char budget still carries its negation - DR-71`() {
        val filler = "because the request payload has been permanently rejected by the safety system " +
            "and stored for review by the abuse team under reference identifier 123456789"
        assertFalse(sse("Do not $filler try again").transient)
    }

    @Test
    fun `wont and will-not negate the retry invitation - DR-71`() {
        assertFalse(sse("This request won't succeed if you try again").transient)
        assertFalse(sse("The system will not accept it if you retry").transient)
    }

    @Test
    fun `engine and model overload codes are allowlisted transients - DR-71`() {
        assertTrue(coded("engine_overloaded").transient)
        assertTrue(coded("model_overloaded").transient)
    }

    // DR-71 redo (codex red-repro): the negation bridge caps at 2000 chars but the heuristic used
    // to scan the UNTRUNCATED message — an invitation past the bridge's reach was still seen by
    // tryAgainRe, so a clause whose visible (displayed) half is pure negation read as transient.
    // The heuristic now classifies exactly the take(MAX_MESSAGE) view the operator sees.
    @Test
    fun `a retry invitation beyond the message budget cannot outrun its negation - DR-71 redo`() {
        assertFalse(sse("Do not " + "x".repeat(2050) + " retry").transient)
    }
}
