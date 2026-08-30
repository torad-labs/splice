// NEW (CH-5, campaign claude-head): the two header decisions a client-auth head depends on.
// Forward mode means splice holds no credential and the CALLER's auth rides untouched — so the
// transport must write nothing of its own, and the final header set must not carry the same header
// twice under different casing (Ktor appends; HTTP names are case-insensitive).
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials
import splice.spi.HeaderRules

class UpstreamClientForwardModeTest {

    @Test
    fun `forward mode writes no auth header of its own`() {
        assertEquals(emptyMap<String, String>(), HeaderRules().authHeaders(Credentials.ClientForwarded))
    }

    @Test
    fun `credential-holding heads are unchanged`() {
        assertEquals(
            mapOf("Authorization" to "Bearer tok"),
            HeaderRules().authHeaders(Credentials.Bearer("tok")),
        )
        assertEquals(
            mapOf("x-api-key" to "secret"),
            HeaderRules().authHeaders(Credentials.ApiKey("secret", header = "x-api-key", prefix = "")),
        )
        assertEquals(
            mapOf("Authorization" to "Bearer plain"),
            HeaderRules().authHeaders(Credentials.ApiKey("plain")),
        )
    }

    // The duplicate-header trap: a configured default and a forwarded value for the SAME header,
    // spelled differently. Unmerged, both reach the wire.
    //
    // SCOPE, because this proves the merge FUNCTION and not the call site (review 2026-08-28,
    // PR 99): the map below is hand-ordered config-then-forwarded, so it pins last-wins and says
    // nothing about whether the real request builder inserts the configured default first. Two
    // places outside this module own that half, and both are pinned rather than assumed —
    // TurnPreparation.kt:54 does the merge as `prepared.extraHeaders + forwardedClientHeaders`
    // (operand order named as an invariant in that file's own header), and
    // :gateway's HeadServerClientAuthTest, `a client-auth head forwards the caller's credential and
    // wire knobs upstream, once`, drives a real head against a real upstream and asserts the
    // caller's anthropic-version beats the provider's configured default at the wire, exactly once.
    // A second end-to-end test here would need a cross-module dependency to say the same thing.
    @Test
    fun `a forwarded header replaces a configured default that differs only in casing`() {
        val merged = HeaderRules().dedupeCaseInsensitive(
            linkedMapOf(
                "anthropic-version" to "2023-06-01", // provider config default
                "Anthropic-Version" to "2024-10-22", // forwarded from the caller
            ),
        )
        assertEquals(1, merged.size)
        assertEquals("2024-10-22", merged.values.single())
    }

    @Test
    fun `distinct headers all survive and keep their casing`() {
        val merged = HeaderRules().dedupeCaseInsensitive(
            linkedMapOf(
                "Accept" to "text/event-stream",
                "anthropic-version" to "2023-06-01",
                "X-Msh-Device-Id" to "dev-1",
            ),
        )
        assertEquals(3, merged.size)
        assertEquals("text/event-stream", merged["Accept"])
        assertTrue(merged.containsKey("X-Msh-Device-Id"), "surviving entry keeps its original casing")
    }

    @Test
    fun `an empty header set stays empty`() {
        assertEquals(emptyMap<String, String>(), HeaderRules().dedupeCaseInsensitive(emptyMap()))
    }
}
