// DR-72 (soak-caught, twice live on claudex 2026-08-31): ChatGPT's cyber_policy content-flag
// refusal says "To get authorized for security work, join the Trusted Access..." — and the old
// auth regex's bare \bauth\w*\b matched "authorized", so a deterministic policy refusal reached
// Claude Code as authentication_error (re-login UX) while the credential was fine. Policy
// refusals must fall through to a non-transient api_error carrying the vendor's own text.
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
    fun `a cyber_policy refusal is not an authentication failure - DR-72`() {
        val r = UpstreamFailureClassifier.classify(FailureSource.SSE, CYBER_POLICY_MSG, code = "cyber_policy")
        assertEquals(ErrorType.API_ERROR, r.type, "a content flag is not an auth failure: $r")
        assertFalse(r.transient, "a deterministic policy refusal is never re-POSTed: $r")
        assertTrue(r.message.contains("rephrasing"), "the vendor's remedy text must survive: $r")
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
