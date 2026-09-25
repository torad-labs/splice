// NEW (CH-5, campaign claude-head): a head that holds NO credential. The contract is mostly about
// what must NOT happen — no null (which the transport reads as auth-missing and fails the turn
// before sending), no invented secret, and no refresh-and-retry on a 401 that belongs to the caller.
package splice.core.auth

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.util.WallClock

private const val ANSWERED_AT = 1_790_000_000_000L

class ClientAuthProviderTest {

    private val provider = ClientAuthProvider("claude-splice")

    // A null here would raise UpstreamAuthMissing and kill the turn before the request goes out —
    // and there is no missing credential: the one that matters arrives on the inbound call.
    @Test
    fun `credentials are the forward-mode marker, never null`() = runTest {
        assertEquals(Credentials.ClientForwarded, provider.credentials())
    }

    @Test
    fun `describe reports a present, client-native credential without inventing fields`() = runTest {
        val described = provider.describe()
        assertTrue(described.present)
        assertEquals("client", described.kind)
        assertEquals("claude-splice", described.fields["head"])
        assertEquals("inbound request", described.fields["source"])
    }

    // V4-220 item 6b: before any forwarded turn is answered splice cannot know, and says so.
    @Test
    fun `a head no forwarded turn was answered on is unverified, not missing`() = runTest {
        val described = provider.describe()
        assertEquals(CredentialVerdict.Unverified, described.verdict)
        assertTrue(described.present, "unverified is not known to be missing")
    }

    @Test
    fun `a 401 is the forwarded login rejected, dated`() = runTest {
        val clocked = ClientAuthProvider("claude-splice", WallClock { ANSWERED_AT })
        clocked.upstreamAnswered(401, success = false)
        val described = clocked.describe()
        assertEquals(CredentialVerdict.Rejected(ANSWERED_AT), described.verdict)
        assertFalse(described.present)
    }

    @Test
    fun `a success is the forwarded login accepted, dated`() = runTest {
        val clocked = ClientAuthProvider("claude-splice", WallClock { ANSWERED_AT })
        clocked.upstreamAnswered(401, success = false)
        clocked.upstreamAnswered(200, success = true)
        assertEquals(CredentialVerdict.Accepted(ANSWERED_AT), clocked.describe().verdict)
        assertTrue(clocked.describe().present)
    }

    // A 403 is a valid login refused a resource; a 429, a 5xx or a 400 is about the turn, not the login.
    @Test
    fun `an answer that says nothing about the login leaves the last verdict standing`() = runTest {
        val clocked = ClientAuthProvider("claude-splice", WallClock { ANSWERED_AT })
        clocked.upstreamAnswered(200, success = true)
        listOf(403, 429, 500, 400).forEach { status -> clocked.upstreamAnswered(status, success = false) }
        assertEquals(CredentialVerdict.Accepted(ANSWERED_AT), clocked.describe().verdict)
    }

    @Test
    fun `the verdict reads back off its wire words`() {
        assertEquals(CredentialVerdict.Rejected(ANSWERED_AT), CredentialVerdict.of("rejected", ANSWERED_AT))
        assertEquals(CredentialVerdict.Accepted(ANSWERED_AT), CredentialVerdict.of("accepted", ANSWERED_AT))
        assertEquals(CredentialVerdict.Unverified, CredentialVerdict.of("unverified", null))
        assertEquals(CredentialVerdict.Held, CredentialVerdict.of("held", null))
        assertEquals(null, CredentialVerdict.of("rejected", null), "a dated verdict without its date")
        assertEquals(null, CredentialVerdict.of(null, null))
    }

    @Test
    fun `refresh is inert and still yields the forward-mode marker`() = runTest {
        assertEquals(Credentials.ClientForwarded, provider.refresh())
    }

    // A 401 on this head is the CALLER's credential being rejected upstream, never a stale splice
    // token — refreshing and retrying can only spend a second upstream call to fail identically.
    @Test
    fun `an auth failure never triggers the refresh-and-retry dance`() {
        assertFalse(provider.allowRefreshAfterFailure(401, ""))
        assertFalse(provider.allowRefreshAfterFailure(403, """{"error":"token expired"}"""))
    }
}
