// NEW: grok's auth cache and its refresh result both hold live tokens; both printed them.
package splice.provider.grok

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class GrokCredentialRedactionTest {
    @Test
    fun `the cached access token never reaches toString`() {
        val rendered = GrokAuthJson.Snapshot(access = SECRET, expiresAtMs = 42L).toString()
        assertFalse(rendered.contains(SECRET), rendered)
    }

    @Test
    fun `a refresh result never prints its tokens`() {
        val rendered = GrokRefreshedTokens(
            accessToken = SECRET,
            refreshToken = null,
            expiresIn = 3600,
        ).toString()
        assertFalse(rendered.contains(SECRET), rendered)
    }

    private companion object {
        const val SECRET = "sk-live-REDACTION-CANARY-8f3a91c6"
    }
}
