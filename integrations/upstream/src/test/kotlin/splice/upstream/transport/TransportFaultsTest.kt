// NEW: failure-handling slice 3 (2026-10-26). A transport failure is read ONCE, where it is caught, into the Path
// cause the turn ends with — so the person's line says what happened on the socket and the retry plan acts on it.
//
// What this replaces, read from the sites that each decided for themselves: TurnConnEnd.emitConnReset read REFUSED
// only when a perf counter had seen a refused loopback connect in this process, so a refused connect to a provider
// (or one whose counter had not fired) ended as "closed the connection before answering"; a failed TLS handshake had
// no reading at all; and a stalled write read as a reset wherever it was caught outside the write bound's own site.
//
// V4-167, measured on JDK 21: the client wraps EVERY connect failure in a ConnectException, so the class thrown never
// says which one it was and the chain's last link does. These cells use those chains.
package splice.upstream.transport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.failure.FailureCause
import splice.core.failure.Unreach
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.nio.channels.ClosedChannelException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class TransportFaultsTest {

    private fun read(e: Throwable, local: Boolean = false): FailureCause.Path.Unreachable =
        TransportFaults.of(e, local)

    /** The JDK client's connect failure: two ConnectExceptions over the link that says what happened. */
    private fun jdk(last: Throwable, message: String? = null): ConnectException =
        ConnectException(message).apply { initCause(ConnectException(message).apply { initCause(last) }) }

    // The one a person acts on: a model server that is not running. The reading cannot depend on a process-wide
    // counter having seen the port, which is what made a remote refusal read as a reset.
    @Test
    fun `a refused connect is read as refused, for a provider as for this machine`() {
        assertEquals(
            FailureCause.Path.Unreachable(Unreach.REFUSED, local = true),
            read(jdk(ClosedChannelException()), local = true),
        )
        assertEquals(
            FailureCause.Path.Unreachable(Unreach.REFUSED, local = false),
            read(jdk(ClosedChannelException())),
        )
    }

    // An engine that does not use the JDK's signature says it in the OS's words instead.
    @Test
    fun `a refusal an engine states in words is read as refused`() {
        assertEquals(Unreach.REFUSED, read(IOException(ConnectException("Connection refused"))).why)
    }

    // A host that does not exist must never read as a server that is not running: the remedy is the address, not
    // starting anything. The JDK buries the resolve failure under two ConnectExceptions.
    @Test
    fun `a host that does not resolve is read as a name, not a refusal`() {
        assertEquals(Unreach.DNS, read(jdk(UnresolvedAddressException())).why)
        assertEquals(Unreach.DNS, read(UnknownHostException("api.x.ai")).why)
    }

    @Test
    fun `a handshake that failed is read as TLS, wherever it sits in the chain`() {
        assertEquals(Unreach.TLS, read(SSLHandshakeException("certificate expired")).why)
        assertEquals(Unreach.TLS, read(IOException(SSLException("handshake_failure"))).why)
    }

    // V4-272: the write bound throws a SocketTimeoutException so every transport reader still sees one, and its own
    // type so a request the upstream stopped taking is not told as a reply that never came.
    @Test
    fun `a stalled write is read as a stalled write, not as a reset`() {
        val stalled = RequestWriteStalled(90_000, SocketTimeoutException("timeout"))

        assertEquals(Unreach.WRITE_STALLED, read(stalled).why)
        assertEquals(Unreach.WRITE_STALLED, read(IOException(stalled)).why)
    }

    // Ktor's ConnectTimeoutException IS a ConnectException, and a timeout is not a refusal: nothing refused the
    // connection, so nobody is told to start a server that may be running.
    @Test
    fun `a connect timeout is never read as a refusal`() {
        val ktor = io.ktor.client.network.sockets.ConnectTimeoutException("Connect timeout has expired", null)

        assertEquals(Unreach.RESET_BEFORE_REPLY, read(ktor).why)
        assertEquals(Unreach.RESET_BEFORE_REPLY, read(HttpConnectTimeoutException("timed out")).why)
    }

    // The floor: a connection that broke, closed or timed out with no answer is the same reading, and an unknown
    // class takes it too — a failure splice cannot name is never named as one of the four it can.
    @Test
    fun `a connection that gave no reply is read as no reply, known class or not`() {
        assertEquals(Unreach.RESET_BEFORE_REPLY, read(SocketException("Connection reset")).why)
        assertEquals(Unreach.RESET_BEFORE_REPLY, read(EOFException()).why)
        assertEquals(Unreach.RESET_BEFORE_REPLY, read(IOException("HTTP/1.1 header parser received no bytes")).why)
        assertEquals(Unreach.RESET_BEFORE_REPLY, read(IllegalStateException("boom")).why)
    }

    // local rides through every arm, not only the refusal: the line names this machine's model server for all of them.
    @Test
    fun `the local flag is carried by whichever reading the chain gets`() {
        assertEquals(true, read(IOException("reset"), local = true).local)
        assertEquals(true, read(jdk(UnresolvedAddressException()), local = true).local)
        assertEquals(false, read(SSLHandshakeException("x"), local = false).local)
    }
}
