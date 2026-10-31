// NEW: the Claude half of failover within one provider (operator ruling, Oct 3, 11:44 PM CT: "each provider gets a
// head, each head can have multiple subscriptions"). A client-auth head forwards the caller's own credential; once the
// head holds several logins, the login the pool selected must be the one that rides, or a held login's turn would go
// out on the caller's token anyway. The caller's credential stays untouched when it IS the selected login's.
package splice.head.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.auth.Credentials

class CallerCredentialTest {
    private val forwarded = mapOf(
        "Authorization" to "Bearer caller-token",
        "anthropic-beta" to "oauth-2025-04-20",
        "x-claude-code-session-id" to "s1",
    )

    @Test
    fun `a selected login that is another account's replaces the caller's credential`() {
        val sent = CallerCredential.over(forwarded, Credentials.Bearer("other-login"))

        assertEquals(mapOf("anthropic-beta" to "oauth-2025-04-20", "x-claude-code-session-id" to "s1"), sent)
    }

    @Test
    fun `the caller's own login rides untouched`() {
        assertEquals(forwarded, CallerCredential.over(forwarded, Credentials.Bearer("caller-token")))
        assertEquals(forwarded, CallerCredential.over(forwarded, Credentials.ClientForwarded))
    }

    @Test
    fun `every credential carrier goes, in any spelling, and nothing else does`() {
        val headers = mapOf("authorization" to "Bearer caller", "X-Api-Key" to "caller-key", "anthropic-version" to "1")

        assertEquals(mapOf("anthropic-version" to "1"), CallerCredential.over(headers, Credentials.Bearer("other")))
    }

    @Test
    fun `turn headers with no caller credential are left as they are`() {
        val headers = mapOf("x-routing" to "a")

        assertEquals(headers, CallerCredential.over(headers, Credentials.Bearer("own")))
    }
}
