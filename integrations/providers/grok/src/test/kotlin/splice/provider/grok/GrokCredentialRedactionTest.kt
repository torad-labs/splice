// NEW: grok's auth cache and its refresh result both hold live tokens; both printed them.
package splice.provider.grok

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrokCredentialRedactionTest {
    @Test
    fun `the cached access token never reaches toString`() {
        val rendered = GrokAuthJson.Snapshot(access = SECRET, expiresAtMs = 42L).toString()
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("expiresAtMs=42"), rendered)
    }

    @Test
    fun `a refresh result redacts both tokens and keeps presence and expiry`() {
        val rendered = GrokRefreshedTokens(
            accessToken = SECRET,
            refreshToken = null,
            expiresIn = 3600,
        ).toString()
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("accessToken=<redacted>"), rendered)
        assertTrue(rendered.contains("refreshToken=null"), rendered)
        assertTrue(rendered.contains("expiresIn=3600"), rendered)
    }

    private companion object {
        const val SECRET = "sk-live-REDACTION-CANARY-8f3a91c6"
    }
}
