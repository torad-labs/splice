// WALLS for the /login stdin paste fallback (2026-08-01). A loopback callback can simply never
// arrive — a browser on another machine, an SSH session, a container without a shared localhost,
// a provider redirecting to a different port. xAI's own CLI accepts BOTH channels for exactly this
// reason ("OIDC: waiting for auth code (loopback + stdin)"); without a second channel the only
// outcome is a silent five-minute timeout, which is the symptom being reported.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.app.OAuthLoginFlow
import splice.core.util.FormEncoding

class OAuthPasteFallbackTest {

    /** The realistic paste: the whole redirect URL straight out of the browser bar. */
    @Test
    fun `a pasted redirect URL yields its code`() {
        assertEquals(
            "abc123XYZ",
            OAuthLoginFlow.extractCode("http://127.0.0.1:1455/auth/callback?code=abc123XYZ&state=s1"),
        )
        assertEquals(
            "abc123XYZ",
            OAuthLoginFlow.extractCode("  http://localhost:8080/callback?state=s1&code=abc123XYZ  "),
            "order must not matter, and surrounding whitespace is normal when pasting",
        )
    }

    /** Some providers put the code in the fragment rather than the query. */
    @Test
    fun `a fragment-delivered code is found too`() {
        assertEquals("frag-code-1", OAuthLoginFlow.extractCode("http://127.0.0.1:1455/cb#code=frag-code-1"))
    }

    @Test
    fun `a pasted URL code is decoded once before form encoding`() {
        val encoded = "code%2Fpart%3Dvalue%2525"
        val code = OAuthLoginFlow.extractCode("http://127.0.0.1/callback?code=$encoded&state=s1")

        assertEquals("code/part=value%25", code)
        assertEquals("code=$encoded", FormEncoding.formEncode("code" to code.orEmpty()))
    }

    /** The other realistic paste: the user copies just the code out of the URL. */
    @Test
    fun `a bare code is accepted`() {
        assertEquals("ac_01HXYZabcdef", OAuthLoginFlow.extractCode("ac_01HXYZabcdef"))
    }

    /** Anything that is plainly not a code must be REJECTED, so the reader can re-prompt rather
     *  than exchanging a stray keystroke and burning the login attempt. */
    @Test
    fun `noise is rejected rather than exchanged`() {
        assertNull(OAuthLoginFlow.extractCode(""), "a bare Enter is not a code")
        assertNull(OAuthLoginFlow.extractCode("   "), "whitespace is not a code")
        assertNull(OAuthLoginFlow.extractCode("y"), "a stray keystroke is not a code")
        assertNull(OAuthLoginFlow.extractCode("no thanks"), "prose is not a code")
        assertNull(
            OAuthLoginFlow.extractCode("https://accounts.x.ai/sign-in"),
            "a URL with no code parameter must not be mistaken for a bare code",
        )
    }
}
