// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 4.4): which URLs may be a peer's webhook. `https://` to any host, or
// `http://` only to this machine. Any other URL is refused when it is set, and again before each push.
package splice.sessions.peer.webhook

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

private const val PEER_SCHEME_HTTPS = "https"
private const val PEER_SCHEME_HTTP = "http"

public object WebhookUrl {
    private val loopbackHosts: Set<String> = setOf("127.0.0.1", "[::1]", "localhost")

    /** Why [url] may not be a webhook, or null when it may. */
    public fun refusal(url: String): String? {
        val uri = parse(url) ?: return "the URL is not a valid absolute URL"
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        return when {
            host == null -> "the URL has no host"
            scheme == PEER_SCHEME_HTTPS -> null
            scheme == PEER_SCHEME_HTTP && host in loopbackHosts -> null
            scheme == PEER_SCHEME_HTTP -> "http is allowed only to 127.0.0.1, [::1] or localhost; use https"
            else -> "the URL must start with https://"
        }
    }

    /** [url] as a URI when it may be a webhook, otherwise null. */
    internal fun allowed(url: String): URI? = parse(url)?.takeIf { refusal(url) == null }

    private fun parse(url: String): URI? = try {
        URI(url).takeIf { it.isAbsolute }
    } catch (_: URISyntaxException) {
        null
    }
}
