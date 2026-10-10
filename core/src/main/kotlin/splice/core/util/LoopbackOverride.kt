// NEW: the one rule for an endpoint a test points elsewhere through the environment (Oct 10, 2026).
package splice.core.util

import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap

/**
 * A vendor endpoint that carries a credential (an OAuth token URL, an issuer, a usage probe) may be pointed at a
 * stand-in on this machine through the environment, and nowhere else. Each of these endpoints receives the
 * operator's own token or refresh token, so a variable naming another host would hand that token to whoever runs it.
 * A value whose host is not loopback is ignored, with one line in the log per variable, and the vendor's own
 * endpoint is used.
 */
public object LoopbackOverride {
    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")
    private val refused = ConcurrentHashMap.newKeySet<String>()

    /** [name]'s value when it names a loopback URL, else [default]. */
    public fun url(env: EnvReader, name: String, default: String, log: LogSink = LogSink(DaemonLog::write)): String {
        val named = env(name) ?: return default
        if (isLoopback(named)) return named
        if (refused.add(name)) {
            log(
                "[auth] ${LogSafe.str(name)} names a host other than this machine, so it is ignored and " +
                    "${LogSafe.str(default)} is used\n",
            )
        }
        return default
    }

    /** True for a URL whose host is this machine's loopback. A value that does not parse is not loopback. */
    public fun isLoopback(url: String): Boolean {
        val host = try {
            URI(url).host
        } catch (_: URISyntaxException) {
            null
        }
        return host?.lowercase() in loopbackHosts
    }
}
