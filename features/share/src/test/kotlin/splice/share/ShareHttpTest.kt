// LAN share (docs/specs/lan-share.md, goal 5): a server that can't be reached is said with a reason the person can act
// on. The two timeouts java.net.http reports by class alone, pinned here over the class, since a real connect timeout
// needs a network that drops packets and a real answer timeout takes the full ten seconds; a refused port and a name
// that doesn't resolve are driven for real in LanShareTest. The other connect failures are pinned over the chain
// java.net.http builds for each, a ConnectException over a ConnectException over the OS's own exception (its
// Utils.toConnectException), since a network or a host that is down can't be made in a test.
package splice.share

import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.URI
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.channels.ClosedChannelException

class ShareHttpTest {
    private val http = ShareHttp(Dispatchers.IO)
    private val server = URI.create("http://devbox.lan:3097/")

    @Test
    fun `a server that is off or asleep is said as nothing answering, and asked whether it is on`() {
        val said = http.why(server, HttpConnectTimeoutException("HTTP connect timed out"))
        assertTrue(said.contains("nothing answered within 3 s") && said.contains("is it on"), said)
    }

    @Test
    fun `a server that took the connection and never answered is said as that`() {
        val said = http.why(server, HttpTimeoutException("request timed out"))
        assertTrue(said.contains("sent no answer within 10 s"), said)
    }

    @Test
    fun `a connection the OS turned down is said with the OS's own reason, never as nothing listening`() {
        val reasons =
            listOf("Network is unreachable", "Host is down", "Address family not supported by protocol family")
        for (reason in reasons) {
            val said = http.why(server, connectFailure(reason, SocketException(reason)))
            assertTrue(said.contains(reason) && said.contains("devbox.lan"), said)
            assertFalse(said.contains("nothing is listening"), said)
        }
    }

    @Test
    fun `a refused port is still said as nothing listening, and a missing route as no route`() {
        val refused = http.why(server, connectFailure(null, ClosedChannelException()))
        assertTrue(refused.contains("nothing is listening on devbox.lan:3097"), refused)
        val noRoute = http.why(server, connectFailure("No route to host", NoRouteToHostException("No route to host")))
        assertTrue(noRoute.contains("this network has no route to devbox.lan"), noRoute)
    }

    /** What java.net.http throws for a connect that failed with [os]: a ConnectException over a ConnectException over
     *  it. */
    private fun connectFailure(message: String?, os: Throwable): ConnectException =
        ConnectException(message).apply { initCause(ConnectException(message).apply { initCause(os) }) }
}
