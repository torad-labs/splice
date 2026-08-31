// PORT-OF: splice/gateway/head/HeadServer.kt (authorize, forwardedClientHeaders,
// FORWARDED_CLIENT_HEADERS) @ 1caedd6 — invariants unchanged: the mgmt-key front door and the
// inbound-header allowlist, moved as ONE unit because they are one security decision — the same
// deps.forwardClientAuth flag (derived from the resolved ClientAuthProvider, never a config string)
// decides both whether the local check is bypassed and whether caller credentials ride upstream.
// Split out (HD-24) so that pairing is greppable and the security audit has one file to read.
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
    // Splits an Authorization value into scheme/credential/parameter tokens for the own-key check.
    // The FULL RFC 7235 delimiter class, not just whitespace (third DR-30 redo): auth-params
    // delimit with "=", DQUOTE and "," — `Digest response=<key>` hid the key from a whitespace
    // split while the raw header still forwarded verbatim. ";" and "'" ride along for legacy
    // scheme syntax. The mgmt key is splice-authored hex, so no delimiter can occur INSIDE it:
    // this split can surface the key but never break it.
    private val authDelimiterRe = Regex("[\\s,=;'\"]+")

    suspend fun authorize(call: ApplicationCall): Boolean {
        // A client-auth head has NO splice-held credential to protect: the mgmt key is what the
        // launcher plants in a client whose own credentials it replaced, and this head does the
        // opposite — it leaves the client's native auth intact and forwards it. Comparing the
        // inbound header against the mgmt key would therefore reject exactly the requests this
        // head exists to serve. The listener is loopback-only, and an unauthenticated caller
        // simply forwards no valid upstream credential and gets the upstream's own 401.
        // ONE exception, below: the mgmt key itself is never a credential this head may forward.
        if (deps.forwardClientAuth) return allowUnlessOwnKey(call)
        if (matchesInferenceToken(presentedCredential(call))) return true
        responses.respondUnauthorized(call)
        return false
    }

    /** Scheme parsing shared with MgmtKey.matchesBearer (core bearerToken) — the two planes drifted
     *  on case-sensitivity when each carried its own regex. Either spelling counts: a caller may
     *  present the key as a bearer or as x-api-key, and both ride upstream from the allowlist. */
    private fun presentedCredential(call: ApplicationCall): String? =
        BearerScheme.bearerToken(call.request.headers[HttpHeaders.Authorization])
            ?: call.request.headers["x-api-key"]

    /**
     * The open door, minus the one caller it must never serve (DR-30).
     *
     * splice's own inference token is not an upstream credential — sending it to the vendor spends
     * nothing, authenticates nothing, and leaks a local secret to a third party. It reaches this
     * seam by accident rather than by malice: LaunchService plants ANTHROPIC_AUTH_TOKEN=<mgmt key>
     * for every non-native head, `bin/splice-launch` execs `env` WITHOUT -i, and a native head's
     * unset list is empty by design — so a native head launched from inside another head's session
     * inherits that bearer. Forwarding it would ALSO mean the caller's real credential never rides,
     * turning a fixable environment slip into an opaque vendor 401. Refusing here says which.
     *
     * EVERY forwardable spelling is checked, not the one [presentedCredential] would pick (DR-30
     * redo): [forwardedClientHeaders] sends Authorization AND x-api-key when both are present, so
     * a caller whose Authorization bearer is its own token could still ride the mgmt key upstream
     * in x-api-key. And the Authorization value is compared TOKEN-WISE, not as one string or one
     * parsed Bearer: `Bearer <key>`, a schemeless `<key>`, `Basic <key>`, or any other scheme all
     * forward the raw header verbatim, so the key must not appear as ANY token of it — under the
     * full auth-param delimiter class, not just whitespace (second redo — the Basic spelling
     * slipped the raw-equality check; third redo — `Digest response=<key>` slipped the
     * whitespace-only split while the raw header still carried the key to the vendor).
     */
    private suspend fun allowUnlessOwnKey(call: ApplicationCall): Boolean {
        val forwardable =
            call.request.headers[HttpHeaders.Authorization].orEmpty().split(authDelimiterRe) +
                listOfNotNull(call.request.headers["x-api-key"])
        if (forwardable.none { matchesInferenceToken(it) }) return true
        deps.log(
            "[auth] refused a turn on a client-auth head that presented splice's own management key — " +
                "ANTHROPIC_AUTH_TOKEN is set in the launching environment and shadowed the caller's own " +
                "credential; unset it (or launch from a clean shell) so this head can forward yours\n",
        )
        responses.respondUnauthorized(
            call,
            "splice's management key is not an upstream credential — this head forwards your own " +
                "Anthropic credential, so unset ANTHROPIC_AUTH_TOKEN in the environment that launched it",
        )
        return false
    }

    /** Constant-time compare against this head's own inference token. Length is checked first
     *  because [MessageDigest.isEqual] is only constant-time for equal-length inputs. */
    private fun matchesInferenceToken(presented: String?): Boolean {
        val presentedBytes = presented?.toByteArray(Charsets.UTF_8) ?: return false
        val expectedBytes = deps.inferenceToken.toByteArray(Charsets.UTF_8)
        if (presentedBytes.size != expectedBytes.size) return false
        return MessageDigest.isEqual(presentedBytes, expectedBytes)
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
