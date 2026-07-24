// NEW (review gap K, 2026-07-23): bearerToken is the ONE scheme parser both the control plane
// (MgmtKey.matchesBearer) and inference (HeadServer.authorize) delegate to. The control copy once
// rejected lowercase `bearer` until the two parsers were unified; pin the case-insensitivity and the
// negatives so that exact drift can never silently return.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.auth.bearerToken

class BearerTokenTest {

    @Test
    fun `lowercase and mixed-case bearer schemes are accepted`() {
        assertEquals("tok", bearerToken("bearer tok"))
        assertEquals("tok", bearerToken("Bearer tok"))
        assertEquals("tok", bearerToken("BEARER tok"))
        assertEquals("tok", bearerToken("BeArEr    tok"))
    }

    @Test
    fun `surrounding whitespace on the header and token is trimmed`() {
        assertEquals("tok", bearerToken("  bearer   tok  "))
    }

    @Test
    fun `non-bearer, malformed, and missing headers are rejected`() {
        assertNull(bearerToken("Basic tok"))
        assertNull(bearerToken("bearertok")) // no scheme delimiter
        assertNull(bearerToken("bearer")) // scheme only, no token
        assertNull(bearerToken("bearer   ")) // scheme + only whitespace
        assertNull(bearerToken(""))
        assertNull(bearerToken(null))
    }
}
