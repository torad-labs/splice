// NEW: kimi's refresh result and auth-store snapshot both hold live tokens; both printed them.
package splice.provider.kimi

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KimiCredentialRedactionTest {
    @Test
    fun `a refresh result redacts both tokens and keeps the wire shape`() {
        val rendered = KimiRefreshedTokens(
            accessToken = SECRET,
            refreshToken = SECRET,
            expiresIn = 3600,
            scope = "all",
            tokenType = "Bearer",
        ).toString()
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("scope=all"), rendered)
        assertTrue(rendered.contains("tokenType=Bearer"), rendered)
    }

    @Test
    fun `the auth-store snapshot redacts both tokens and keeps both expiries`() {
        val rendered = KimiAuthStore.Snapshot(
            access = SECRET,
            refresh = null,
            expiresAtS = 9L,
            expiresInS = 8L,
        ).toString()
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("refresh=null"), rendered)
        assertTrue(rendered.contains("expiresAtS=9"), rendered)
        assertTrue(rendered.contains("expiresInS=8"), rendered)
    }

    private companion object {
        const val SECRET = "sk-live-REDACTION-CANARY-8f3a91c6"
    }
}
