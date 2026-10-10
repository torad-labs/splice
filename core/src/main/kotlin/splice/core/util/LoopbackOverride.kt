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
                    "${LogSafe.str(place(default))} is used\n",
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

    /** [url] as a place to name in a log: its scheme, host and port, and nothing else. A URL splice
     *  falls back to is DERIVED from an operator-supplied issuer (CodexAuthFile.tokenUrl builds
     *  "${issuer}/oauth/token"), and a loopback issuer may carry userinfo —
     *  `http://user:private-token@127.0.0.1:1456` is accepted, so its password would otherwise reach the
     *  refusal line for a sibling variable (review of adf35c39e, finding 1). The path, query and
     *  fragment go too: none of them identifies the endpoint for a reader, and each can carry a token. A
     *  value that does not parse is named as the variable's own default, never echoed. */
    private fun place(url: String): String {
        val parsed = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return "its default endpoint"
        }
        val host = parsed.host ?: return "its default endpoint"
        val port = if (parsed.port >= 0) ":${parsed.port}" else ""
        val scheme = parsed.scheme?.let { "$it://" }.orEmpty()
        return "$scheme$host$port"
    }
}
