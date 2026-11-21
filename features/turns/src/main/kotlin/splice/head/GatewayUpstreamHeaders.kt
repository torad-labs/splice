// NEW: gateway-no-heads. The credential boundary of a gateway turn. The gateway admitted its caller at its own door
// with a key of its own (Desktop's, the terminal's, a LAN node's or a team's), and that key opens the gateway and
// nothing else: it is not this provider's credential, and it must never be sent to it. A head's turn forwards the
// caller's own login when its provider holds none (CallerHeaders); a gateway turn never does. What it runs on is the
// credential the provider holds (a key, a sign-in, a command's token, or an account added to splice), and a provider
// that holds none is answered honestly before anything is sent (LocalRefusal.refuseIfNodeWordUnheld).
package splice.head

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import splice.core.topology.SHARE_NODE_TOKEN

/** [callers]' headers for a gateway turn, without the call's own credential in any spelling. Where the provider
 *  forwards a caller's login ([forwardsCallerLogin]) the credential slot instead holds the node's word
 *  ([SHARE_NODE_TOKEN]), which opens nothing upstream: it is how every turn that carries no Claude login of anyone's,
 *  a LAN node's and the door's, has always said so, so a held account displaces it (CallerCredential.over) and a
 *  provider with no account to put in its place refuses the turn instead of sending it with none. */
internal class GatewayUpstreamHeaders(
    private val callers: UpstreamHeaders,
    private val forwardsCallerLogin: Boolean,
) : UpstreamHeaders {
    override fun upstreamHeaders(call: ApplicationCall): Map<String, String> {
        val rest = callers.upstreamHeaders(call).filterKeys { name ->
            credentialHeaders.none { carrier -> carrier.equals(name, ignoreCase = true) }
        }
        return if (forwardsCallerLogin) rest + (HttpHeaders.Authorization to "Bearer $SHARE_NODE_TOKEN") else rest
    }
}
