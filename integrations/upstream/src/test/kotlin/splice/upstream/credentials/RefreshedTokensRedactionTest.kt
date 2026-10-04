// NEW: the shared refresh result every provider's token endpoint funnels through. All three fields
// are secrets and the generated toString printed them; PRESENCE is the only part worth reading in a
// log, so it is the only part kept.
package splice.upstream.credentials

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RefreshedTokensRedactionTest {
    @Test
    fun `no token reaches toString, and the presence of each one still does`() {
        val rendered = RefreshedTokens(accessToken = SECRET, refreshToken = null, idToken = SECRET).toString()
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("accessToken=<redacted>"), rendered)
        assertTrue(rendered.contains("idToken=<redacted>"), rendered)
        // Absence is diagnostic and is not a secret: a refresh that came back without a refresh
        // token is the thing an operator needs to see in the log.
        assertTrue(rendered.contains("refreshToken=null"), rendered)
    }

    private companion object {
        const val SECRET = "sk-live-REDACTION-CANARY-8f3a91c6"
    }
}
