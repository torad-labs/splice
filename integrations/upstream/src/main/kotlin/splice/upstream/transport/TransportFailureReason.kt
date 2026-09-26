// NEW: V4-164 (2026-09-19) — a transport failure named by what happened on the socket.
//
// "bonsai: upstream connection failed (no detail) — retry" was a REFUSED CONNECT: llama-server was
// not running (its transient unit died with the boot), and the JDK HttpClient throws
// java.net.ConnectException with a null message, so both surfaces that print a connection failure
// printed Throwable.message and got nothing. TransportFailures answers "may this be retried"; this
// file answers the other question — what the operator is told — from the same cause chain.
//
// V4-167 (review, 2026-09-19): the JDK client wraps EVERY connect failure in ConnectException, so the
// outer class does not say which one it was; the chain's last link does (measured, JDK 21):
//   refused      ConnectException(null) -> ConnectException(null) -> ClosedChannelException(null)
//   bad host     ConnectException(null) -> ConnectException(null) -> UnresolvedAddressException(null)
//   no route     ConnectException("No route to host") -> ... -> NoRouteToHostException
// V4-164 named the first link, so a DNS failure and an unroutable host both read "connection refused
// … nothing is listening there" and sent the operator to start a server. And Ktor's timeout text
// carries "[url=<the full request url>", which the detail now reports as host and port only.
package splice.upstream.transport

import splice.upstream.retry.MS_PER_S
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
import java.nio.channels.ClosedChannelException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException

/** Throwable -> one line naming the failure and the endpoint it happened against. */
public object TransportFailureReason {

    /** [upstreamUrl] contributes host and port only — never a path or query, which can carry keys. */
    public fun of(e: Throwable, upstreamUrl: String?): String {
        // StreamTornBeforeClient is splice's own wrapper; its text says where, not what.
        val root = if (e is StreamTornBeforeClient) e.cause ?: e else e
        val chain = FailureChain.links(root).toList()
        // V4-307: named before anything else; the IOException OkHttp wraps it in says only "canceled".
        chain.firstOrNull(FailureChain::refusedThreadStart)?.let { return refusal(it) }
        val where = endpoint(upstreamUrl)
        val named = headline(chain, where)
        val detail = chain.firstNotNullOfOrNull { t -> t.message?.trim()?.takeIf { it.isNotEmpty() } }
            ?.replace(KTOR_URL, "url=$where")
        return when {
            // Throwable.toString() is the class name when there is no message — named, not reflected.
            named == null -> detail ?: "$root from $where, with no message"
            detail == null || named.contains(detail, ignoreCase = true) -> named
            else -> "$named: $detail"
        }
    }

    // A failed resolve is named wherever it sits: the JDK buries it under two ConnectExceptions.
    // So is a stalled write (V4-272): ktor wraps it in a socket timeout that would read as a read one.
    private fun headline(chain: List<Throwable>, where: String): String? {
        val stalled = chain.firstNotNullOfOrNull { it as? RequestWriteStalled }
        return when {
            chain.any(::unresolved) -> "cannot resolve the host of $where"
            // V4-289: "stopped taking", because it may have taken part of it; and no promise of a resend,
            // because this line also ends the turn when the last attempt stalls.
            stalled != null ->
                "$where stopped taking the request for ${stalled.stalledMs / MS_PER_S}s: the write stalled " +
                    "(the network dropped, or the upstream stopped reading)"
            else -> chain.firstNotNullOfOrNull { reasonOf(it, chain, where) }
        }
    }

    // Split the way TransportFailures splits its phases: the CONNECT half never got a byte onto the
    // wire, the STREAM half lost a connection that was already answering.
    private fun reasonOf(t: Throwable, chain: List<Throwable>, where: String): String? =
        connectReason(t, chain, where) ?: streamReason(t, where)

    private fun unresolved(t: Throwable): Boolean = t is UnresolvedAddressException || t is UnknownHostException

    // Order matters twice: Ktor's ConnectTimeoutException IS a ConnectException, and the JDK's
    // HttpConnectTimeoutException IS an HttpTimeoutException — the narrower name is tested first. A
    // ConnectException is a refusal only over the JDK's refusal signature (see the header); over
    // anything else its own text says what happened, so it is appended as the detail.
    private fun connectReason(t: Throwable, chain: List<Throwable>, where: String): String? = when {
        t is HttpConnectTimeoutException || t is io.ktor.client.network.sockets.ConnectTimeoutException ->
            "connecting to $where timed out"
        t is ConnectException && chain.any { it is ClosedChannelException } ->
            "connection refused by $where: nothing is listening there; the server is down or still starting"
        t is ConnectException -> "could not connect to $where"
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

    /** V4-307: [refused] with what it stopped, read from the frame nearest the refusal that names the thread
     *  it was for; with none of those, the refusal alone. */
    private fun refusal(refused: Throwable): String {
        val stopped = refused.stackTrace.firstNotNullOfOrNull { frame ->
            REFUSAL_STOPS.firstOrNull { (owner, _) -> frame.className.startsWith(owner) }?.second
        }
        return if (stopped == null) REFUSED_THREAD_START else "$REFUSED_THREAD_START: $stopped"
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

    // V4-307: the endpoint is left out of a refused thread start: it did not cause this.
    private const val REFUSED_THREAD_START =
        "the host refused splice a new thread (a process or thread limit was reached)"

    // V4-307: what a refused thread start stopped is said with it, and it depends on the thread. Both owners
    // below count or mark a start before making it and never recover from a refused one, so restarting
    // splice is the remedy: OkHttp's task runner (TaskRunner.startAnotherThread, okhttp 5.3.2), whose one
    // upstream task is the pool's reaper; and okio's timeout watchdog (AsyncTimeout.insertIntoQueue, okio
    // 3.17.0 sets its sentinel, then starts the thread). What still ends a stalled turn runs on no okio
    // thread: the socket's own read timeout (RealConnection.newCodec sets SO_TIMEOUT), ktor's request
    // timeout, the request-write watch and the turn watchdog, which cancel the call from their own threads.
    private val REFUSAL_STOPS = listOf(
        "okhttp3.internal.concurrent.TaskRunner" to
            "idle upstream connections are no longer evicted until splice restarts",
        "okio.AsyncTimeout" to
            "okio's timeouts stay off until splice restarts; splice's own caps still end a stalled turn",
    )

    // Ktor's HttpTimeout messages: "Connect timeout has expired [url=<request url>, connect_timeout=…]".
    private val KTOR_URL = Regex("""url=[^,\]]*""")

    // why: a URL with no explicit port means the scheme's registered default (RFC 9110 §4.2.1/4.2.2),
    // and the message names the port the client actually dialled.
    private const val HTTP_PORT = 80

    // why: the registered https default (RFC 9110 §4.2.2), for the same reason as HTTP_PORT.
    private const val HTTPS_PORT = 443
}
