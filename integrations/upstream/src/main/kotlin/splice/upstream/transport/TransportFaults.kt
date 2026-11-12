// NEW: failure-handling slice 3 (2026-10-26). A transport failure read ONCE, where it is caught, into the Path cause
// the turn ends with (final-design.md sections 6 and 7: "TransportFaults.of(error, local) returns Unreachable").
//
// It takes the cause half of TransportFailureReason, whose other half is the log's line (TransportFaultLine, which
// keeps its text). Before this, each site that needed a cause decided one: TurnConnEnd.emitConnReset read REFUSED only
// when a perf counter had already seen a refused LOOPBACK connect in this process, so a provider that refused the
// connection ended as "closed the connection before answering"; SseRoundDriver's declined tear hard-coded
// RESET_BEFORE_REPLY; and a failed TLS handshake had no reading anywhere.
//
// V4-167, measured on JDK 21 and kept here: the client wraps EVERY connect failure in a ConnectException, so the class
// thrown never says which one it was and the chain's last link does.
//   refused      ConnectException(null) -> ConnectException(null) -> ClosedChannelException(null)
//   bad host     ConnectException(null) -> ConnectException(null) -> UnresolvedAddressException(null)
package splice.upstream.transport

import io.ktor.client.network.sockets.ConnectTimeoutException
import splice.core.failure.FailureCause
import splice.core.failure.Unreach
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.nio.channels.ClosedChannelException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException

/** Reads a failure the transport raised: no answer came, so the path broke, and [Unreach] says how. */
public object TransportFaults {
    /** What [error] says happened on the way to the provider. [local] is true when the address is this machine's own
     *  runtime, which the line names as the model server rather than the provider (PathSentences.unreachable).
     *
     *  Narrower than the design's sketch on purpose: every failure raised instead of an answer is [Unreach]able, and
     *  the callers that ask "was this refused" then need no cast. */
    public fun of(error: Throwable, local: Boolean): FailureCause.Path.Unreachable =
        FailureCause.Path.Unreachable(why(FailureChain.links(error).toList()), local)

    /**
     * The one reading, in the order the chain answers it: a name that did not resolve wherever it sits (the JDK buries
     * it under two ConnectExceptions), then a request the upstream stopped taking (V4-272: the write bound throws a
     * SocketTimeoutException so every transport reader still sees one), then a handshake that failed, then a connect
     * that ran out of time, then a refusal, and otherwise no reply at all — every reset, close and timeout a
     * connection that was answering can end with, and the floor for a class splice cannot name.
     */
    private fun why(chain: List<Throwable>): Unreach = when {
        chain.any { it is UnresolvedAddressException || it is UnknownHostException } -> Unreach.DNS
        chain.any { it is RequestWriteStalled } -> Unreach.WRITE_STALLED
        chain.any { it is SSLException } -> Unreach.TLS
        // Before the refusal: ktor's own connect timeout IS a ConnectException, and a timeout is no refusal — the
        // remedy would be the opposite, since nothing says the server is down.
        chain.any { it is ConnectTimeoutException || it is HttpConnectTimeoutException } -> Unreach.RESET_BEFORE_REPLY
        chain.any { refused(it, chain) } -> Unreach.REFUSED
        else -> Unreach.RESET_BEFORE_REPLY
    }

    /** A refusal: the JDK's own signature (a ConnectException over a closed channel), or an engine that says so in
     *  the OS's words. A ConnectException over anything else failed for its own reason, which is not a refusal. */
    private fun refused(t: Throwable, chain: List<Throwable>): Boolean {
        if (t !is ConnectException) return false
        val said = t.message?.contains(REFUSED_WORDS, ignoreCase = true) == true
        return said || chain.any { it is ClosedChannelException }
    }
}

/** How an engine that does not use the JDK's signature says a refusal: the OS's text, relayed. */
private const val REFUSED_WORDS = "Connection refused"
