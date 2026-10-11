// NEW: the public auth-body classifier used by Muse mint 403 and grok refreshable 403.
package splice.upstream.failure

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.upstream.AuthFailureBodies

class FailureRulesTest {

    private val rules = FailureRules()

    @Test
    fun `auth-body matches the grok 2026-07-18 signature and ignores plan 403s`() {
        val samples = AuthFailureBodies()
        samples.positives.forEach { body -> assertTrue(rules.isAuthFailureBody(body), body) }
        samples.negatives.forEach { body -> assertFalse(rules.isAuthFailureBody(body), body) }
    }
}
