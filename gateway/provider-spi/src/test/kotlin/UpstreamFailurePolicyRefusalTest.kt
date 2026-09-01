// DR-72 (soak-caught, twice live on claudex 2026-08-31): ChatGPT's cyber_policy content-flag
// refusal says "To get authorized for security work, join the Trusted Access..." — and the old
// auth regex's bare \bauth\w*\b matched "authorized", so a deterministic policy refusal reached
// Claude Code as authentication_error (re-login UX) while the credential was fine.
//
// 2026-09-01 (live, 242 turns in one day — 42 of them compactions): DR-72's api_error verdict was
// still RETRYABLE on the client side. Claude Code re-sends an api_error with backoff, so every
// policy refusal became a ~30s x N storm of the same 1.3MB transcript, and a compaction that
// tripped the flag could never complete. A content-policy refusal is a fact about the REQUEST —
// the vendor itself returns it as HTTP 400 / `invalid_request` when it arrives pre-stream — so a
// status-less (mid-stream) one must reach the client as invalid_request_error too: terminal, the
// vendor's own remedy text intact, no retry.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ErrorType
import splice.spi.FailureSource
import splice.spi.UpstreamFailureClassifier

// The production message from daemon.log (07:31:16), verbatim up to the truncation point.
private const val CYBER_POLICY_MSG =
    "This content was flagged for possible cybersecurity risk. If this seems wrong, try " +
        "rephrasing your request. To get authorized for security work, join the Trusted Access program."

class UpstreamFailurePolicyRefusalTest {

    @Test
    fun `a cyber_policy refusal is a terminal invalid_request, never auth and never retried`() {
        val r = UpstreamFailureClassifier.classify(FailureSource.SSE, CYBER_POLICY_MSG, code = "cyber_policy")
        assertEquals(ErrorType.INVALID_REQUEST, r.type, "a content flag is about the request, not the server: $r")
        assertFalse(r.transient, "a deterministic policy refusal is never re-POSTed: $r")
        assertTrue(r.message.contains("rephrasing"), "the vendor's remedy text must survive: $r")
    }

    // The Responses API's own error enum (openai-python ResponseError.code) names the other
    // prompt-level refusals; they are the same deterministic class and must land the same way.
    @Test
    fun `every documented prompt-refusal code is a terminal invalid_request`() {
        listOf("invalid_prompt", "bio_policy", "image_content_policy_violation", "CYBER_POLICY").forEach { code ->
            val r = UpstreamFailureClassifier.classify(FailureSource.SSE, "The prompt was rejected.", code = code)
            assertEquals(ErrorType.INVALID_REQUEST, r.type, code)
            assertFalse(r.transient, code)
        }
    }

    // Control: an UNKNOWN status-less code keeps DR-10's verdict (api_error, non-transient) — the
    // refusal allowlist is exact, not a heuristic on wording.
    @Test
    fun `an unknown status-less code is still a non-transient api_error - DR-10 control`() {
        val r = UpstreamFailureClassifier.classify(FailureSource.SSE, "The prompt was rejected.", code = "quantum_flux")
        assertEquals(ErrorType.API_ERROR, r.type, "$r")
        assertFalse(r.transient, "$r")
    }

    // Control: the pre-stream shape (Azure/OpenAI HTTP 400 carrying the same code) already lands
    // as invalid_request by status; the mid-stream fix must agree with it, not diverge from it.
    @Test
    fun `the HTTP 400 shape of the same refusal agrees with the mid-stream verdict`() {
        val body = """{"error":{"message":"$CYBER_POLICY_MSG","type":"invalid_request",""" +
            """"param":null,"code":"cyber_policy"}}"""
        val r = UpstreamFailureClassifier.classify(FailureSource.HTTP, body, status = 400)
        assertEquals(ErrorType.INVALID_REQUEST, r.type, "$r")
        assertTrue(r.message.contains("rephrasing"), "$r")
    }

    @Test
    fun `real auth failure wordings keep their classification - DR-72 control`() {
        listOf(
            "Unauthorized",
            "authentication_error token check failed",
            "auth failed for this request",
            "Missing authorization header",
            "You are not authorized to use this model",
            "token expired",
        ).forEach { text ->
            val r = UpstreamFailureClassifier.classify(FailureSource.SSE, text)
            assertEquals(ErrorType.AUTHENTICATION, r.type, text)
        }
    }

    // DR-83 (batches 6+7 review): DR-72's fixed alternation ended its authorization branch at a
    // word boundary, and underscore is a word character — every snake_case authorization_* code
    // (the shape vendor error.code fields take by construction) plus the -isation spelling and
    // authz stopped matching, the exact mirror image of the bug DR-72 fixed.
    @Test
    fun `snake_case authorization codes are auth failures - DR-83`() {
        listOf("authorization_error", "authorization_required", "authorization_failed").forEach { code ->
            val r = UpstreamFailureClassifier.classify(FailureSource.SSE, "request rejected", code = code)
            assertEquals(ErrorType.AUTHENTICATION, r.type, code)
        }
    }

    @Test
    fun `authorisation spelling and authz wordings are auth failures - DR-83`() {
        listOf("Authorisation required", "authorisation failed", "authz denied").forEach { text ->
            val r = UpstreamFailureClassifier.classify(FailureSource.SSE, text)
            assertEquals(ErrorType.AUTHENTICATION, r.type, text)
        }
    }
}
