// NEW: V4-164 (2026-09-19) — a transport failure named by what happened on the socket.
//
// "bonsai: upstream connection failed (no detail) — retry" was a REFUSED CONNECT: llama-server was
// not running (its transient unit died with the boot), and the JDK HttpClient throws
// java.net.ConnectException with a null message, so both surfaces that print a connection failure
// printed Throwable.message and got nothing. TransportFailures answers "may this be retried"; this
// file answers the other question — what the operator is told — from the same cause chain.
package splice.spi.transport

import splice.spi.StreamTornBeforeClient
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException

/** Throwable -> one line naming the failure and the endpoint it happened against. */
public object TransportFailureReason {

    /** [upstreamUrl] contributes host and port only — never a path or query, which can carry keys. */
    public fun of(e: Throwable, upstreamUrl: String?): String {
        // StreamTornBeforeClient is splice's own wrapper; its text says where, not what.
        val root = if (e is StreamTornBeforeClient) e.cause ?: e else e
        val chain = generateSequence(root) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        val where = endpoint(upstreamUrl)
        val named = chain.firstNotNullOfOrNull { reasonOf(it, where) }
        val detail = chain.firstNotNullOfOrNull { t -> t.message?.trim()?.takeIf { it.isNotEmpty() } }
        return when {
            // Throwable.toString() is the class name when there is no message — named, not reflected.
            named == null -> detail ?: "$root from $where, with no message"
            detail == null || named.contains(detail, ignoreCase = true) -> named
            else -> "$named: $detail"
        }
    }

    // Split the way TransportFailures splits its phases: the CONNECT half never got a byte onto the
    // wire, the STREAM half lost a connection that was already answering.
    private fun reasonOf(t: Throwable, where: String): String? = connectReason(t, where) ?: streamReason(t, where)

    // Order matters twice: Ktor's ConnectTimeoutException IS a ConnectException, and the JDK's
    // HttpConnectTimeoutException IS an HttpTimeoutException — the narrower name is tested first.
    private fun connectReason(t: Throwable, where: String): String? = when {
        t is UnresolvedAddressException || t is UnknownHostException -> "cannot resolve the host of $where"
        t is HttpConnectTimeoutException || t is io.ktor.client.network.sockets.ConnectTimeoutException ->
            "connecting to $where timed out"
        t is ConnectException ->
            "connection refused by $where — nothing is listening there; the server is down or still starting"
        else -> null
    }

    private fun streamReason(t: Throwable, where: String): String? = when {
        t is SocketTimeoutException || t is HttpTimeoutException -> "$where stopped responding (read timed out)"
        t is SSLException -> "the TLS handshake with $where failed"
        t is EOFException -> "$where closed the connection before the response ended"
        t is SocketException -> "the connection to $where broke"
        // The JDK's own literal for a server that accepted the socket and closed it unanswered.
        t is IOException && t.message == JDK_NO_RESPONSE ->
            "$where accepted the connection and closed it without answering"
        else -> null
    }

    private fun endpoint(url: String?): String {
        val parsed = try {
            url?.let(::URI)
        } catch (_: URISyntaxException) {
            null
        }
        val host = parsed?.host ?: return "the upstream"
        val port = parsed.port.takeIf { it > 0 } ?: if (parsed.scheme == "http") HTTP_PORT else HTTPS_PORT
        return "$host:$port"
    }

    private const val JDK_NO_RESPONSE = "HTTP/1.1 header parser received no bytes"
    private const val MAX_CAUSE_DEPTH = 8
    private const val HTTP_PORT = 80
    private const val HTTPS_PORT = 443
}
