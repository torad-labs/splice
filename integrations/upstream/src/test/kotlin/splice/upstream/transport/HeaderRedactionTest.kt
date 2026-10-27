// NEW: V4-174 — redaction is by NAME CLASS, so a credential header this proxy has never sent
// before lands redacted with no edit to the rule; the names an incident is read from ride whole.
//
// Fix 4 of the Oct 27 Responses fixes: a header a body or a stream carries as a JSON member goes the same way, and a
// refusal that quotes the headers it refused carries them inside a JSON string, where each nesting doubles the
// backslash before every quote. No list of nestings covers what a backend may send, so the property is stated over
// generated ones: at any nesting, a secret-named member keeps its name and loses its value, and a member an incident
// is read from keeps both.
package splice.upstream.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How deep a member is quoted: 0 is the body itself, 1 a string in it, and so on. */
private const val NESTINGS = 5
private const val SECRET = "x-codex-turn-state"
private const val TOKEN = "a-routing-token-no-one-may-read"
private const val KEPT = "x-request-id"
private const val KEPT_VALUE = "req-01HQ"

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
    fun `at every nesting a secret member loses its value and a diagnostic member keeps it`() {
        repeat(NESTINGS) { nesting ->
            val quote = "\\".repeat((1 shl nesting) - 1) + "\""
            val members = """{${quote}$SECRET$quote:${quote}$TOKEN$quote,""" +
                """${quote}$KEPT$quote:${quote}$KEPT_VALUE$quote}"""
            val text = """{"error":{"type":"invalid_request_error","message":"the headers $members name no turn"}}"""

            val redacted = HeaderRedaction.redactText(text)

            assertFalse(TOKEN in redacted, "nesting $nesting keeps a turn state: $redacted")
            assertTrue("$SECRET$quote:${quote}${HeaderRedaction.REDACTED}" in redacted, "nesting $nesting: $redacted")
            assertTrue("$KEPT$quote:${quote}$KEPT_VALUE" in redacted, "nesting $nesting drops a kept value")
        }
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
