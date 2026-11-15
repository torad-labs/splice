// NEW: server mode (docs/specs/server-mode.md, F1 `trusted_proxies`). Whose address a request came from. Behind a
// reverse proxy every connection comes from the proxy, and the client is in `X-Forwarded-For`, a header anyone can
// send. So it is believed only on a connection from an address in `[server] trusted_proxies`, and read from the right:
// the last address a trusted proxy did not add itself. From anyone else the header is ignored, and the connection's
// own address stands. Refused keys are said in the daemon log by that address, once each, then by count.
package splice.server

import splice.core.listen.IpLiterals
import splice.core.util.LogSafe
import splice.core.util.LogSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class ClientAddresses(trustedProxies: List<String>) {
    private val proxies = trustedProxies.mapNotNull(IpLiterals::prefix)

    /** The client's address: [peer], the connection's other end, unless that is a trusted proxy naming another in
     *  [forwardedFor] (every `X-Forwarded-For` value, joined by commas). */
    fun of(peer: String, forwardedFor: String?): String {
        if (!trusted(peer)) return peer
        val hops = forwardedFor.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
        // A hop that is no address at all is nobody's: the proxy's own address is the last one known.
        return hops.lastOrNull { !trusted(it) }?.takeIf { IpLiterals.parse(it) != null } ?: peer
    }

    private fun trusted(address: String): Boolean {
        val ip = IpLiterals.parse(address) ?: return false
        return proxies.any { it.holds(ip) }
    }
}

/** Says in [log] that a request was refused for its key, with the client's address: once per address, then by count,
 *  so a scanner trying keys is seen and cannot fill the log. The key itself is never part of a line. */
internal class RefusedCallers(private val log: LogSink) {
    private val named: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val unnamed = AtomicLong(0)

    fun refused(address: String, why: String) {
        val from = LogSafe.str(address)
        if (named.size < MAX_NAMED_CALLERS) {
            if (named.add(from)) log("[server] refused a request from $from: $why; later ones from it are not said\n")
        } else if (from !in named) {
            val count = unnamed.incrementAndGet()
            if (count and (count - 1) == 0L) {
                log("[server] refused $count requests from addresses past the first $MAX_NAMED_CALLERS for their key\n")
            }
        }
    }
}

// why: a team's own machines and its proxy are a handful of addresses; past 64 the callers are a scan, counted and
// said at each doubling, which bounds the log at one line per power of two.
private const val MAX_NAMED_CALLERS = 64
