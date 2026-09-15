// NEW: the public auth-body classifier used by Muse mint 403 and grok refreshable 403.
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.FailureRules

class FailureRulesTest {

    private val rules = FailureRules()

    @Test
    fun `auth-body matches the grok 2026-07-18 signature and ignores plan 403s`() {
        AUTH_BODY_POSITIVES.forEach { body -> assertTrue(rules.isAuthFailureBody(body), body) }
        AUTH_BODY_NEGATIVES.forEach { body -> assertFalse(rules.isAuthFailureBody(body), body) }
    }
}

private val AUTH_BODY_POSITIVES = listOf(
    "unauthenticated",
    "unauthenticated:bad-credentials",
    "bad-credentials",
    "token invalid",
    "token is invalid",
    "token expired",
    "token is expired",
    "access token could not be validated",
    "oauth token could not be validated",
    "oauth2 token could not be validated",
)

private val AUTH_BODY_NEGATIVES = listOf(
    "",
    "plan limit exceeded",
    "permission denied",
    "quota exceeded",
    "tokens invalid",
)
