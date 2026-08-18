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
