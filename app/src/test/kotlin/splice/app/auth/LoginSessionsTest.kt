// LoginSessions is the off-request login-id/poll seam (POST /api/auth/{head}/login
// runs DeviceLoginFlow/OAuthLoginFlow OFF the request that started it; GET .../login/{id} reads
// back what has landed so far). The flow machinery itself is exercised directly in
// DeviceLoginObserverTest and OAuthLoginObserverTest — running a real LoginCommand attempt here
// would mean a real device/browser flow against a real provider, which is not a unit boundary.
// What belongs here is LoginSessions' own contract: an unknown id answers null rather than
// throwing, so GET .../login/{id} can turn it into a 404 by name (LoginRoutes.pollLogin) rather
// than a 500 from an unchecked map read.
package splice.app.auth

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.accounts.signin.LoginStart
import splice.topology.TopologyLoader

class LoginSessionsTest {
    @Test
    fun `polling an unknown login id answers null, never throws`() {
        val sessions = LoginSessions()

        assertNull(sessions.poll("no-such-login-id"))
    }

    // Only OAuth kinds take added logins; an api-key head stays single per provider (§11 Resolved).
    @Test
    fun `an api-key head refuses an added login by name`() = runBlocking {
        val topology = TopologyLoader.parse(
            """
            [providers.fw]
            dialect = "openai-chat"
            base_url = "http://localhost:1/v1"
            auth = { kind = "api-key", env = "FW_API_KEY" }
            [[providers.fw.models]]
            id = "m"
            context_window = 1000

            [heads.fw]
            provider = "fw"
            port = 3099
            discovery_prefix = "claude-fw--"
            pinned_model = "m"
            [heads.fw.claude]
            command = "claude-fw"
            """.trimIndent(),
        )
        val accounts = ConsoleAccountsImpl(topologies = ConsoleAccountsTopology { topology })

        val answered = accounts.startLogin("fw", null) { }

        assertEquals(LoginStart.UnsupportedAuthKind("api-key"), answered)
    }
}
