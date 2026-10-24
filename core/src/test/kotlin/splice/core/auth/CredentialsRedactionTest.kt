// NEW: Credentials.kt opens with "Secrets never leave these types unmasked", and until now that
// invariant was carried by the comment and by nothing else. Kotlin's GENERATED toString printed the
// bearer token, the api key and the PKCE verifier in full, so one interpolation — a log line, an
// exception, a JUnit assertion message — put a live credential into a file that gets uploaded.
// These arms grade the rendering, not the storage: equals, hashCode and copy are untouched.
package splice.core.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CredentialsRedactionTest {
    @Test
    fun `a bearer token never reaches its own toString, and the account still does`() {
        val rendered = Credentials.Bearer(SECRET, accountId = "acct-7").toString()
        assertTrue(rendered.startsWith("Bearer("), "not a rendering at all: $rendered")
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("acct-7"), "the non-secret field must stay readable: $rendered")
    }

    @Test
    fun `an api key never reaches its own toString, and the wire shape still does`() {
        val rendered = Credentials.ApiKey(SECRET, header = "X-Api-Key", prefix = "Token ").toString()
        assertTrue(rendered.startsWith("ApiKey("), "not a rendering at all: $rendered")
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("X-Api-Key") && rendered.contains("Token "), rendered)
    }

    @Test
    fun `the PKCE verifier is redacted and the challenge, which is public, is not`() {
        val rendered = Pkce(verifier = SECRET, challenge = "a-public-s256-digest").toString()
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("a-public-s256-digest"), "the challenge rides the authorize URL: $rendered")
    }

    @Test
    fun `interpolation is the leak path, and it is closed through the sealed type`() {
        // Exactly the shape that would land a credential in a JUnit XML: a failure message built out
        // of the credential. Graded through the SEALED supertype, because that is how call sites hold it.
        val bearer: Credentials = Credentials.Bearer(SECRET)
        val apiKey: Credentials = Credentials.ApiKey(SECRET)
        assertFalse("upstream refused $bearer".contains(SECRET))
        assertFalse("upstream refused $apiKey".contains(SECRET))
    }

    private companion object {
        /** Not a real credential. Distinctive enough that a substring match cannot pass by accident. */
        const val SECRET = "sk-live-REDACTION-CANARY-8f3a91c6"
    }
}
