// LAN share (docs/specs/lan-share.md, goal 5): a server that can't be reached is said with a reason the person can act
// on. The two timeouts java.net.http reports by class alone, pinned here over the class, since a real connect timeout
// needs a network that drops packets and a real answer timeout takes the full ten seconds; a refused port and a name
// that doesn't resolve are driven for real in LanShareTest.
package splice.share

import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException

class ShareHttpTest {
    private val http = ShareHttp(Dispatchers.IO)
    private val server = URI.create("http://torad-server.lan:3097/")

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
}
