// NEW (CH-5, campaign claude-head): a head that holds NO credential. The contract is mostly about
// what must NOT happen — no null (which the transport reads as auth-missing and fails the turn
// before sending), no invented secret, and no refresh-and-retry on a 401 that belongs to the caller.
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.ClientAuthProvider
import splice.core.auth.Credentials

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
