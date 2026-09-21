// NEW: V4-132 — LoginSessions is the off-request login-id/poll seam (POST /api/auth/{head}/login
// runs DeviceLoginFlow/OAuthLoginFlow OFF the request that started it; GET .../login/{id} reads
// back what has landed so far). The flow machinery itself is exercised directly in
// DeviceLoginObserverTest and OAuthLoginObserverTest — running a real LoginCommand attempt here
// would mean a real device/browser flow against a real provider, which is not a unit boundary.
// What belongs here is LoginSessions' own contract: an unknown id answers null rather than
// throwing, so GET .../login/{id} can turn it into a 404 by name (AuthRoutes.pollLogin) rather
// than a 500 from an unchecked map read.
package splice.app.auth

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LoginSessionsTest {
    @Test
    fun `polling an unknown login id answers null, never throws`() {
        val sessions = LoginSessions()

        assertNull(sessions.poll("no-such-login-id"))
    }
}
