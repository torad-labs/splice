// NEW: V4-174 — redaction is by NAME CLASS, so a credential header this proxy has never sent
// before lands redacted with no edit to the rule; the names an incident is read from ride whole.
package splice.upstream.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeaderRedactionTest {

    @Test
    fun `every credential-class name is redacted, whatever its casing or vendor prefix`() {
        listOf(
            "Authorization", "authorization", "x-api-key", "X-Goog-Api-Key", "Cookie", "Set-Cookie",
            "X-Session-Token", "x-auth-token", "Proxy-Authorization", "x-client-secret", "X-Amz-Signature",
            "x-password", "x-credential",
        ).forEach { name -> assertTrue(HeaderRedaction.isSecret(name), name) }
    }

    @Test
    fun `the headers an incident is diagnosed from ride whole`() {
        listOf(
            "content-type", "anthropic-version", "retry-after", "x-request-id", "anthropic-ratelimit-unified-reset",
            "openai-processing-ms", "content-encoding", "user-agent", "x-codex-turn-id",
        ).forEach { name -> assertFalse(HeaderRedaction.isSecret(name), name) }
    }

    @Test
    fun `redact keeps names and order, replaces only the secret values`() {
        val redacted = HeaderRedaction.redact(
            linkedMapOf("anthropic-version" to "2023-06-01", "x-api-key" to "sk-live", "x-request-id" to "r1"),
        )
        assertEquals(listOf("anthropic-version", "x-api-key", "x-request-id"), redacted.keys.toList())
        assertEquals(
            mapOf("anthropic-version" to "2023-06-01", "x-api-key" to "[redacted]", "x-request-id" to "r1"),
            redacted,
        )
        assertFalse(redacted.values.any { it.contains("sk-live") })
    }
}
