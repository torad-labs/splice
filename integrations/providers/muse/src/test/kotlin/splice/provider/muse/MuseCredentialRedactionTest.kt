// NEW: muse carries credential material in four reachable types — the stored snapshot, the two
// revision-keyed verdicts, and the minted subscription key. Every one printed its secret.
// MintFlightResult carries one too and is file-private in MuseAuthProvider.kt, so it is covered by
// its override and not by an arm here; there is no way to construct it from a test.
package splice.provider.muse

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MuseCredentialRedactionTest {
    private val fields = buildJsonObject {
        put("access_token", JsonPrimitive(SECRET))
        put("api_key", JsonPrimitive(SECRET))
    }

    @Test
    fun `the stored snapshot redacts both secrets and the whole retained body`() {
        val rendered = MuseCredentialSnapshot(
            accessToken = SECRET,
            apiKey = null,
            fields = fields,
            identity = null,
        ).toString()
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("accessToken=<redacted>"), rendered)
        assertTrue(rendered.contains("apiKey=null"), rendered)
        // the body is secret WHOLE; only how much of it was parsed survives
        assertTrue(rendered.contains("fields=<redacted:2 key(s)>"), rendered)
    }

    @Test
    fun `a mint hold redacts the token it is keyed to and keeps its deadline`() {
        val rendered = MuseMintHold(identity = null, accessToken = SECRET, untilMs = 1234L).toString()
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("untilMs=1234"), rendered)
    }

    @Test
    fun `an inactive verdict redacts the token and keeps the URL an operator has to visit`() {
        val rendered =
            MuseInactiveVerdict(identity = null, accessToken = SECRET, actionUrl = "https://muse.example/billing")
                .toString()
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("https://muse.example/billing"), rendered)
    }

    @Test
    fun `a minted subscription key redacts the key and the response body`() {
        val rendered = MuseSubscriptionKey(apiKey = SECRET, fields = fields).toString()
        assertFalse(rendered.contains(SECRET), rendered)
        assertTrue(rendered.contains("<redacted:2 key(s)>"), rendered)
    }

    private companion object {
        const val SECRET = "sk-live-REDACTION-CANARY-8f3a91c6"
    }
}
