// NEW (review gap K, 2026-07-23): bearerToken is the ONE scheme parser both the control plane
// (MgmtKey.matchesBearer) and inference (HeadServer.authorize) delegate to. The control copy once
// rejected lowercase `bearer` until the two parsers were unified; pin the case-insensitivity and the
// negatives so that exact drift can never silently return.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.auth.BearerScheme

class BearerTokenTest {

    @Test
    fun `lowercase and mixed-case bearer schemes are accepted`() {
        assertEquals("tok", BearerScheme.bearerToken("bearer tok"))
        assertEquals("tok", BearerScheme.bearerToken("Bearer tok"))
        assertEquals("tok", BearerScheme.bearerToken("BEARER tok"))
        assertEquals("tok", BearerScheme.bearerToken("BeArEr    tok"))
    }

    @Test
    fun `surrounding whitespace on the header and token is trimmed`() {
        assertEquals("tok", BearerScheme.bearerToken("  bearer   tok  "))
    }

    @Test
    fun `non-bearer, malformed, and missing headers are rejected`() {
        assertNull(BearerScheme.bearerToken("Basic tok"))
        assertNull(BearerScheme.bearerToken("bearertok")) // no scheme delimiter
        assertNull(BearerScheme.bearerToken("bearer")) // scheme only, no token
        assertNull(BearerScheme.bearerToken("bearer   ")) // scheme + only whitespace
        assertNull(BearerScheme.bearerToken(""))
        assertNull(BearerScheme.bearerToken(null))
    }
}
