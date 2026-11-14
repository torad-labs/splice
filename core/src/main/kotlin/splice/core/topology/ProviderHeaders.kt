// NEW (Nov 14, 2026): what a provider's table adds to the headers splice sends it, past its static extra_headers.
// People whose company gateway sits in front of a provider asked for two things: to pass on the headers their Claude
// Code is set to send (ANTHROPIC_CUSTOM_HEADERS), and for a gateway to see which splice, and which version, a request
// came through, so it can turn away an old one. forward_headers follows LiteLLM's and Portkey's named forwarding, off
// until a table names a header. send_version writes RFC 9110's Via line (section 7.6.3), the field a gateway that
// forwards a request is meant to add, and leaves the caller's own User-Agent as it was.
package splice.core.topology

import splice.core.GATEWAY_VERSION

/** The Via line send_version adds: the protocol the request reached splice on, splice's name, and its version as the
 *  comment RFC 9110 allows for naming the software. */
internal const val SPLICE_VIA: String = "1.1 splice (splice/$GATEWAY_VERSION)"

/** Headers that never ride on from a caller, whatever a table names, lowercase. A login, because the caller's would
 *  replace the provider's, and in a session splice launched it holds splice's own key. And the headers that describe
 *  the caller's hop to splice rather than splice's hop to the provider, which corrupt the request when copied. */
private val NEVER_FORWARDED: Set<String> = setOf(
    "authorization", "proxy-authorization", "x-api-key", "api-key", "x-goog-api-key", "cookie",
    "host", "content-length", "content-type", "content-encoding", "transfer-encoding", "accept-encoding",
    "connection", "keep-alive", "proxy-connection", "te", "trailer", "upgrade", "via",
)

/** A provider's `forward_headers`: the caller's headers, by exact name, that ride on to this provider, on any head and
 *  any dialect. Empty by default, so nothing a caller sends rides on unless the table names it. */
public class ForwardHeaders(configured: List<String>) {
    /** The named headers, trimmed, each name once whatever its case. */
    public val names: List<String> =
        configured.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)

    /** The names that can never ride on, as the table wrote them. A finding at boot, so none is dropped quietly. */
    public val refused: List<String> get() = names.filter { it.lowercase() in NEVER_FORWARDED }

    /** The names that do ride on, lowercase. */
    public val carried: List<String> get() = names.map(String::lowercase).filterNot { it in NEVER_FORWARDED }

    /** The boot finding for provider [key] when its table names a header that never rides on, so the name refuses boot
     *  with every other finding instead of being dropped while the file reads as if it forwarded it. */
    public fun finding(key: String): TopologyFinding? = if (refused.isEmpty()) {
        null
    } else {
        TopologyFinding(
            "providers.$key.forward_headers",
            "names a login or connection header (Authorization, x-api-key, Host, Content-Length and the like), " +
                "which never rides on from a caller; remove it, or set a fixed value in extra_headers",
        )
    }
}
