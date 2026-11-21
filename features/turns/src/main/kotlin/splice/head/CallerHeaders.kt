// What of a caller's request rides upstream with its turn: the half of ClientAuth that decides what leaves, moved out
// unchanged so a gateway can answer the same question (GatewayUpstreamHeaders) without a head's door. The same
// deps.policy.forwardClientAuth flag still decides both: whether ClientAuth's local check is bypassed, and whether the
// caller's credentials ride upstream from here.
package splice.head

import io.ktor.server.application.ApplicationCall
import splice.core.topology.ForwardHeaders
import splice.head.transport.ListValuedHeaders

/** What of a call rides upstream beside its turn. */
internal fun interface UpstreamHeaders {
    fun upstreamHeaders(call: ApplicationCall): Map<String, String>
}

/** See [CallerHeaders.forwardedClientHeaders]: the caller's credential plus the wire knobs it chose.
 *  Was `HeadDeps.FORWARDED_CLIENT_HEADERS` (an `internal val` in a public companion); same name,
 *  now file-scope. One shared immutable allowlist, read per client-auth turn.
 *
 *  Each name maps to the Claude Code variable that writes it (v0.4.0 review round 2): when splice's
 *  own key rides in one, that variable is what the operator unsets, and the refusal blamed
 *  ANTHROPIC_AUTH_TOKEN for all four. One declaration, so a header cannot be forwarded unnamed. */
internal val forwardedHeaderVariables: Map<String, String> = mapOf(
    "Authorization" to "ANTHROPIC_AUTH_TOKEN",
    "x-api-key" to "ANTHROPIC_API_KEY",
    "anthropic-version" to "ANTHROPIC_CUSTOM_HEADERS",
    "anthropic-beta" to "ANTHROPIC_CUSTOM_HEADERS",
)

/** The allowlisted names that carry a caller's credential, in any scheme. */
internal val credentialHeaders: List<String> = listOf("Authorization", "x-api-key")

/** Identity authored by the client, not by splice. SDK metadata is forwarded as the x-stainless- family. */
private val CLIENT_IDENTITY_HEADERS: Set<String> = setOf(
    "user-agent",
    "x-app",
    "x-claude-code-session-id",
    "anthropic-dangerous-direct-browser-access",
)

internal class CallerHeaders(
    private val deps: HeadDeps,
    /** The headers this head's provider names in forward_headers (Nov 14, 2026), which ride on from the caller on any
     *  head, whether or not it forwards the caller's login. None by default. */
    private val forwarded: ForwardHeaders = ForwardHeaders(emptyList()),
) : UpstreamHeaders {
    /** splice's own keys, found in what a caller sends (OwnKeys). */
    private val ownKeys = OwnKeys(deps.tokens)

    /** The inbound headers a client-auth head forwards upstream, by EXPLICIT allowlist.
     *
     *  An allowlist, never "forward everything": Host, Content-Length, Accept-Encoding and friends
     *  describe the hop to the gateway, not the hop to the vendor, and copying them corrupts the
     *  upstream request. What rides is the caller's credential, Anthropic wire knobs and genuine
     *  client identity, exactly as chosen by the client, never synthesized from splice's version. */
    fun forwardedClientHeaders(call: ApplicationCall): Map<String, String> {
        val identity = call.request.headers.names().filter {
            it.lowercase() in CLIENT_IDENTITY_HEADERS || it.startsWith("x-stainless-", ignoreCase = true)
        }
        return (forwardedHeaderVariables.keys + identity).mapNotNull { name ->
            forwardedValue(call, name)?.let { name to it }
        }.toMap()
    }

    /** Every header of [call] that rides upstream on this head: the ones its provider's forward_headers names, on any
     *  head, and on a head that forwards the caller's login, that login and its wire knobs too
     *  ([forwardedClientHeaders]), which win over a named header of the same name. */
    override fun upstreamHeaders(call: ApplicationCall): Map<String, String> {
        val login = if (deps.policy.forwardClientAuth) forwardedClientHeaders(call) else emptyMap()
        return namedClientHeaders(call) + login
    }

    /** The headers the provider's forward_headers names, as the caller sent them (Nov 14, 2026). A company gateway
     *  in front of the provider reads them: the tags a person's Claude Code adds through ANTHROPIC_CUSTOM_HEADERS. One
     *  that carries one of splice's own keys stays here, and the log names the header: splice's keys never leave the
     *  machine, and dropping the one header lets the turn go on where refusing it would stop the session. */
    private fun namedClientHeaders(call: ApplicationCall): Map<String, String> {
        val sent = forwarded.carried.mapNotNull { name -> forwardedValue(call, name)?.let { name to it } }
        val (own, rest) = sent.partition { (_, value) -> ownKeys.carriedIn(value) }
        if (own.isNotEmpty()) {
            deps.log("[auth] did not forward ${own.joinToString { it.first }}: it carried one of splice's own keys\n")
        }
        return rest.toMap()
    }

    /** One allowlisted header's value as it should ride upstream.
     *
     *  Claude Code sends its beta flags as REPEATED `anthropic-beta` lines, and `headers[name]`
     *  returns only the first, so reading it that way silently dropped every flag but one — the
     *  caller asked for four betas and the vendor was told about one. Read every line and, for a
     *  list-valued field, rejoin them; for a singleton field keep the first, which is the only
     *  reading that cannot invent a value. */
    private fun forwardedValue(call: ApplicationCall, name: String): String? {
        val lines = call.request.headers.getAll(name)?.filter { it.isNotBlank() }.orEmpty()
        if (lines.isEmpty()) return null
        // The repeated lines of a list-valued field are one value, rejoined (ListValuedHeaders names which).
        return if (name in ListValuedHeaders.names) {
            lines.joinToString(ListValuedHeaders.FIELD_LINE_SEPARATOR)
        } else {
            lines.first()
        }
    }
}
