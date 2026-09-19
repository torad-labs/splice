// NEW: V4-164 — every transport failure splice knows is named by what happened on the socket and
// against which endpoint. The operator's banner was "bonsai: upstream connection failed (no detail)
// — retry": llama-server was down, and the JDK client's refused connect carries a null message.
package campaign.v4164

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.spi.StreamTornBeforeClient
import splice.spi.transport.TransportFailureReason
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException

class TransportFailureReasonTest {

    private val bonsai = "http://127.0.0.1:8099/v1/chat/completions"

    private fun reason(e: Throwable, url: String = bonsai) = TransportFailureReason.of(e, url)

    // Mutant: drop the ConnectException arm. The message falls to the class-name floor and this
    // cell goes red — the refused connect is the operator's own failure, so it is pinned exactly.
    @Test
    fun `a refused connect with no message names the endpoint and why`() {
        assertEquals(
            "connection refused by 127.0.0.1:8099 — nothing is listening there; the server is down or still starting",
            reason(ConnectException()),
        )
    }

    @Test
    fun `splice's own tear wrapper is seen through to the socket's failure`() {
        val text = reason(StreamTornBeforeClient(ConnectException()))

        assertTrue(text.startsWith("connection refused by 127.0.0.1:8099"), text)
        assertFalse(text.contains("stream torn"), text)
    }

    // Mutant: test ConnectException before the timeout arm. Ktor's ConnectTimeoutException IS a
    // ConnectException, so a timeout would read as a refusal — the opposite remedy.
    @Test
    fun `a connect timeout is a timeout, not a refusal`() {
        val ktor = io.ktor.client.network.sockets.ConnectTimeoutException("Connect timeout has expired", null)

        assertEquals("connecting to 127.0.0.1:8099 timed out: Connect timeout has expired", reason(ktor))
        assertTrue(reason(HttpConnectTimeoutException("x")).startsWith("connecting to 127.0.0.1:8099 timed out"))
    }

    @Test
    fun `each known class is named`() {
        val xai = "https://api.x.ai/v1/responses"

        // The JDK's detail is the bare host, already named — it is not repeated.
        assertEquals("cannot resolve the host of api.x.ai:443", reason(UnknownHostException("api.x.ai"), xai))
        assertEquals(
            "the connection to api.x.ai:443 broke: Connection reset",
            reason(SocketException("Connection reset"), xai),
        )
        assertEquals("api.x.ai:443 closed the connection before the response ended", reason(EOFException(), xai))
        assertEquals(
            "127.0.0.1:8099 accepted the connection and closed it without answering: " +
                "HTTP/1.1 header parser received no bytes",
            reason(IOException("HTTP/1.1 header parser received no bytes")),
        )
    }

    // The endpoint is host and port only: a base_url may carry a key in its path or query.
    @Test
    fun `the url's path and query never reach the message`() {
        val text = reason(ConnectException(), "http://10.0.0.5:9000/v1/chat/completions?key=s3cret")

        assertTrue(text.contains("10.0.0.5:9000"), text)
        assertFalse(text.contains("s3cret"), text)
        assertFalse(text.contains("/v1"), text)
    }

    // The floor: an unknown class keeps its own text, and with none it is named — never "no detail".
    @Test
    fun `an unknown failure keeps its text, and without one names its class`() {
        assertEquals("boom", reason(IOException("boom")))
        assertEquals("java.io.IOException from 127.0.0.1:8099, with no message", reason(IOException()))
        assertEquals("java.io.IOException from the upstream, with no message", reason(IOException(), "not a url"))
    }
}
