// NEW: kimi's refresh result and auth-store snapshot both hold live tokens; both printed them.
package splice.provider.kimi

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class KimiCredentialRedactionTest {
    @Test
    fun `a refresh result never prints its tokens`() {
        val rendered = KimiRefreshedTokens(
            accessToken = SECRET,
            refreshToken = SECRET,
            expiresIn = 3600,
            scope = "all",
            tokenType = "Bearer",
        ).toString()
        assertFalse(rendered.contains(SECRET), rendered)
    }

    @Test
    fun `the auth-store snapshot never prints its tokens`() {
        val rendered = KimiAuthStore.Snapshot(
            access = SECRET,
            refresh = null,
            expiresAtS = 9L,
            expiresInS = 8L,
        ).toString()
        assertFalse(rendered.contains(SECRET), rendered)
    }

    private companion object {
        const val SECRET = "sk-live-REDACTION-CANARY-8f3a91c6"
    }
}
