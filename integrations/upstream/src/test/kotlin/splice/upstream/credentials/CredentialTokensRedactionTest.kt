// NEW: CredentialTokens is the shape every OAuth kind's on-disk material is read into, so it holds
// a live access and refresh token by construction. The generated toString printed both.
package splice.upstream.credentials

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CredentialTokensRedactionTest {
    @Test
    fun `neither token reaches toString, and the expiry and presence still do`() {
        val rendered = CredentialTokens(
            access = SECRET,
            refresh = null,
            expiresAtMs = 1_700_000_000_000,
            refreshOptional = true,
        ).toString()
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("access=<redacted>"), rendered)
        assertTrue(rendered.contains("refresh=null"), rendered)
        assertTrue(rendered.contains("expiresAtMs=1700000000000"), rendered)
        assertTrue(rendered.contains("refreshOptional=true"), rendered)
    }

    private companion object {
        const val SECRET = "sk-live-REDACTION-CANARY-8f3a91c6"
    }
}
