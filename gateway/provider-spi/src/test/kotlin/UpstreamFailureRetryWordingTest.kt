// DR-71 (codex adjudication probe, 2026-08-31): six symmetric holes in the statusless
// retry-wording heuristic and the structured-code allowlist, red 6/6 on the pre-fix classifier —
// em/en-dash and line-break clause cuts, the "retry" invitation synonym, "service is unavailable"
// wording, engine/model overload codes, the 120-char clause budget, and won't/will-not negators.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
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
        // Overload is the vendor's own verdict, so it surfaces as the type the client retries on.
        assertEquals(ErrorType.OVERLOADED, coded("engine_overloaded").type)
    }

    // 2026-09-01 20:56, claudex compaction (830KB upstream body): the backend's response.failed
    // carried code server_is_overloaded — "Our servers are currently overloaded. Please try again
    // later." — and the exact-list idiom made it a non-transient api_error: no reissue, no salvage,
    // "compaction failed" in Claude Code. codex-rs (PR #31058) names server_is_overloaded and
    // slow_down as the backend's two capacity codes and retries them patiently on their own budget;
    // here any code spelling overload is capacity, surfaced as OVERLOADED so the client retries
    // with backoff instead of ending the turn. Red 3/3 on the allowlist classifier.
    @Test
    fun `the backend's capacity codes are transient overloaded, not api_error`() {
        for (code in listOf("server_is_overloaded", "slow_down", "SERVER_IS_OVERLOADED")) {
            val r = coded(code)
            assertEquals(ErrorType.OVERLOADED, r.type, code)
            assertTrue(r.transient, code)
        }
    }

    @Test
    fun `a capacity code outranks message wording that reads deterministic`() {
        val r = UpstreamFailureClassifier.classify(
            FailureSource.SSE,
            "Do not retry this request",
            code = "server_is_overloaded",
        )
        assertEquals(ErrorType.OVERLOADED, r.type)
        assertTrue(r.transient)
    }

    // The pre-stream shape of the same verdict: HTTP 503 with the structured code in the body.
    @Test
    fun `an HTTP 503 carrying a capacity code is overloaded, not a generic 5xx api_error`() {
        val r = UpstreamFailureClassifier.classify(
            FailureSource.HTTP,
            """{"error":{"code":"server_is_overloaded","message":"Our servers are currently overloaded."}}""",
            status = 503,
        )
        assertEquals(ErrorType.OVERLOADED, r.type)
        assertTrue(r.transient)
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
