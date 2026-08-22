// PORT-OF: splice/gateway/head/HeadServer.kt (authorize, forwardedClientHeaders,
// FORWARDED_CLIENT_HEADERS) @ 1caedd6 — invariants unchanged: the mgmt-key front door and the
// inbound-header allowlist, moved as ONE unit because they are one security decision — the same
// deps.forwardClientAuth flag (the head's auth KIND, never a config string) decides both whether
// the local check is bypassed and whether the caller's own credential rides upstream. Split out
// (HD-24) so that pairing is greppable and the security audit has one file to read.
package splice.gateway.head

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import splice.core.auth.BearerScheme
import java.security.MessageDigest

/** See [ClientAuth.forwardedClientHeaders]: the caller's credential plus the wire knobs it chose.
 *  Was `HeadDeps.FORWARDED_CLIENT_HEADERS` (an `internal val` in a public companion); same name,
 *  now file-scope and NARROWED to `private` — ClientAuth.kt is its only reader.
 *  FILE SCOPE ON PURPOSE: one shared immutable allowlist, read per client-auth turn. */
private val FORWARDED_CLIENT_HEADERS: List<String> = listOf(
    "Authorization",
    "x-api-key",
    "anthropic-version",
    "anthropic-beta",
)

/** The head's client-auth seam: who may call this listener, and what of theirs rides upstream. */
internal class ClientAuth(
    private val deps: HeadDeps,
    private val responses: AdmissionResponses,
) {
    suspend fun authorize(call: ApplicationCall): Boolean {
        // A client-auth head has NO splice-held credential to protect: the mgmt key is what the
        // launcher plants in a client whose own credentials it replaced, and this head does the
        // opposite — it leaves the client's native auth intact and forwards it. Comparing the
        // inbound header against the mgmt key would therefore reject exactly the requests this
        // head exists to serve. The listener is loopback-only, and an unauthenticated caller
        // simply forwards no valid upstream credential and gets the upstream's own 401.
        if (deps.forwardClientAuth) return true
        // Scheme parsing shared with MgmtKey.matchesBearer (core bearerToken) — the two planes
        // drifted on case-sensitivity when each carried its own regex.
        val bearer = BearerScheme.bearerToken(call.request.headers[HttpHeaders.Authorization])
        val presented = bearer ?: call.request.headers["x-api-key"]
        val expectedBytes = deps.inferenceToken.toByteArray(Charsets.UTF_8)
        val presentedBytes = presented?.toByteArray(Charsets.UTF_8)
        val allowed = presentedBytes != null &&
            presentedBytes.size == expectedBytes.size &&
            MessageDigest.isEqual(presentedBytes, expectedBytes)
        if (allowed) return true
        responses.respondUnauthorized(call)
        return false
    }

    /** The inbound headers a client-auth head forwards upstream, by EXPLICIT allowlist.
     *
     *  An allowlist, never "forward everything": Host, Content-Length, Accept-Encoding and friends
     *  describe the hop to the gateway, not the hop to the vendor, and copying them corrupts the
     *  upstream request. What rides is the caller's credential and the two Anthropic wire knobs it
     *  chose — exactly what Claude Code would have sent had it called the vendor directly. */
    fun forwardedClientHeaders(call: ApplicationCall): Map<String, String> =
        FORWARDED_CLIENT_HEADERS.mapNotNull { name ->
            call.request.headers[name]?.let { name to it }
        }.toMap()
}
