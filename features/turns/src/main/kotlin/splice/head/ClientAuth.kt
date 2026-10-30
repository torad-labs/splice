// PORT-OF: splice/gateway/head/HeadServer.kt (authorize, forwardedClientHeaders,
// FORWARDED_CLIENT_HEADERS) @ 1caedd6 — invariants unchanged: the mgmt-key front door and the
// inbound-header allowlist, moved as ONE unit because they are one security decision — the same
// deps.policy.forwardClientAuth flag (derived from the resolved ClientAuthProvider, never a config string)
// decides both whether the local check is bypassed and whether caller credentials ride upstream.
// Split out (HD-24) so that pairing is greppable and the security audit has one file to read.
package splice.head

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import splice.core.auth.BearerScheme
import splice.core.auth.ForeignHostLog
import splice.core.auth.LoopbackHost
import splice.core.client.SESSION_HEADER
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.perf.LivenessProbe
import splice.head.admission.AdmissionResponses
import splice.head.transport.ListValuedHeaders
import java.security.MessageDigest

/** See [ClientAuth.forwardedClientHeaders]: the caller's credential plus the wire knobs it chose.
 *  Was `HeadDeps.FORWARDED_CLIENT_HEADERS` (an `internal val` in a public companion); same name,
 *  now file-scope and NARROWED to `private` — ClientAuth.kt is its only reader.
 *  FILE SCOPE ON PURPOSE: one shared immutable allowlist, read per client-auth turn.
 *
 *  Each name maps to the Claude Code variable that writes it (v0.4.0 review round 2): when splice's
 *  own key rides in one, that variable is what the operator unsets, and the refusal blamed
 *  ANTHROPIC_AUTH_TOKEN for all four. One declaration, so a header cannot be forwarded unnamed. */
private val FORWARDED_CLIENT_HEADERS: Map<String, String> = mapOf(
    "Authorization" to "ANTHROPIC_AUTH_TOKEN",
    "x-api-key" to "ANTHROPIC_API_KEY",
    "anthropic-version" to "ANTHROPIC_CUSTOM_HEADERS",
    "anthropic-beta" to "ANTHROPIC_CUSTOM_HEADERS",
)

/** The allowlisted names that carry a caller's credential, in any scheme. */
private val CREDENTIAL_HEADERS: List<String> = listOf("Authorization", "x-api-key")

/** Identity authored by the client, not by splice. SDK metadata is forwarded as the x-stainless- family. */
private val CLIENT_IDENTITY_HEADERS: Set<String> = setOf(
    "user-agent",
    "x-app",
    "x-claude-code-session-id",
    "anthropic-dangerous-direct-browser-access",
)

/** Which of a head's doors a route opens. */
internal enum class Door {
    /** A caller who may run inference here: a local answer (models, count_tokens) needs nothing more. */
    CALLER,

    /** A turn that goes upstream: [CALLER], and on a head that only forwards, a credential to forward. */
    UPSTREAM,

    /** An operator route that is not a turn (GET /wire): the management key alone. */
    OPERATOR,
}

/** The work a head route does once its door has opened. */
internal fun interface RouteBody {
    suspend operator fun invoke()
}

/** The head's client-auth seam: who may call this listener, and what of theirs rides upstream. */
internal class ClientAuth(
    private val deps: HeadDeps,
    private val responses: AdmissionResponses,
    private val foreignHosts: ForeignHostLog,
    /** The head holds no credential of its own (ClientAuthProvider): every upstream credential is
     *  the caller's, so a call that presents none has nothing upstream could accept. */
    private val forwardsOnly: Boolean = false,
    /** Where a refused local credential is said (Oct 26, 2026). HeadServer's writes under the head's key; this default
     *  writes the same lines to [deps]'s log as they are, for a ClientAuth a test builds around a seam. */
    private val refusedKeys: RefusedKeyLog = RefusedKeyLog(deps.log, deps.seams.clock),
) {
    // Splits each forwarded value into scheme/credential/parameter tokens for the own-key check.
    // The FULL RFC 7235 delimiter class, not just whitespace (third DR-30 redo): auth-params
    // delimit with "=", DQUOTE and "," — `Digest response=<key>` hid the key from a whitespace
    // split while the raw header still forwarded verbatim. ";" and "'" ride along for legacy
    // scheme syntax. The mgmt key is splice-authored hex, so no delimiter can occur INSIDE it:
    // this split can surface the key but never break it.
    private val authDelimiterRe = Regex("[\\s,=;'\"]+")

    /** Who a head that holds logins of its own serves them to (PR #247 review, Oct 29). */
    private val heldLogins = HeldLoginDoor(deps, responses, refusedKeys)

    /** Runs [handler] only when [call] opens [door]. A refused call is answered here, with the door's own status and
     *  body, and [handler] never runs: a route cannot reach its handler past a verdict it ignored
     *  (kt-control-route-guarded accepts this call alone as a head route's door). */
    suspend fun guarded(call: ApplicationCall, door: Door, handler: RouteBody) {
        val opened = when (door) {
            Door.CALLER -> authorize(call)
            Door.UPSTREAM -> authorizeUpstream(call)
            Door.OPERATOR -> authorizeOperator(call)
        }
        if (opened) handler()
    }

    private suspend fun authorize(call: ApplicationCall): Boolean {
        // A client-auth head has NO splice-held credential to protect: the turn key is what a
        // launched client reads through its key helper once its own credentials were removed, and this
        // head does the opposite — it leaves the client's native auth intact and forwards it. Comparing the
        // inbound header against splice's keys would therefore reject exactly the requests this
        // head exists to serve. ONE exception, below: splice's own keys are never a credential this
        // head may forward. A call that forwards also needs a credential: [authorizeUpstream].
        if (deps.policy.forwardClientAuth) return allowUnlessOwnKey(call)
        // The turn key is what a launched client holds; the management key still runs a turn so a
        // session launched before the v0.4.0 split keeps working until it is relaunched.
        val presented = presentedCredential(call)
        if (matchesInferenceToken(presented) || matchesOperatorToken(presented)) return true
        // Said, with why and never with the key (Oct 26, 2026): this refusal wrote nothing, and a session that day's
        // key rotation stranded was refused for 19 minutes while daemon.log stayed quiet.
        val why = when {
            !carriesCredential(call) -> KeyRefusal.NO_CREDENTIAL
            presented != null && deps.tokens.retired(presented) -> KeyRefusal.RETIRED
            else -> KeyRefusal.NOT_SPLICES
        }
        refusedKeys.refused(call.request.headers[SESSION_HEADER], why)
        responses.respondUnauthorized(call)
        return false
    }

    /**
     * v0.4.0: which NAME the caller used to reach this listener. Binding 127.0.0.1 stops other
     * machines, not a page in the operator's own browser that rebinds its name to loopback (DNS
     * rebinding) — and on a client-auth head that page is a caller [authorize] serves. Its requests
     * name the attacker in Host, the one thing rebinding cannot change, so they are refused (403)
     * before any route runs, and said in the head's log once per name. The predicate is the control
     * plane's too ([LoopbackHost]).
     */
    suspend fun admitsHost(call: ApplicationCall): Boolean {
        val host = call.request.headers[HttpHeaders.Host]
        if (LoopbackHost.admits(host)) return true
        foreignHosts.refused(host.orEmpty())
        responses.respondForeignHost(call)
        return false
    }

    /** Scheme parsing shared with MgmtKey.matchesBearer (core bearerToken) — the two planes drifted
     *  on case-sensitivity when each carried its own regex. Either spelling counts: a caller may
     *  present the key as a bearer or as x-api-key, and both ride upstream from the allowlist. */
    private fun presentedCredential(call: ApplicationCall): String? =
        BearerScheme.bearerToken(call.request.headers[HttpHeaders.Authorization])
            ?: call.request.headers["x-api-key"]

    /**
     * V4-173: the guard for an OPERATOR route (GET /wire), which is not a turn. [authorize] answers
     * "may this caller run inference here?", and on a client-auth head that is "anyone with their
     * own upstream credential" — the right policy for a turn and the wrong one for reading the
     * bodies the head sent on other people's behalf. This route admits the management key alone,
     * on every head kind, so what opens it is what `splice` itself holds and nothing a session has.
     */
    private suspend fun authorizeOperator(call: ApplicationCall): Boolean {
        // The OPERATOR key alone (v0.4.0): the turn key is in every launched session's environment,
        // so accepting it here handed any session every other session's upstream bodies.
        if (matchesOperatorToken(presentedCredential(call))) return true
        responses.respondUnauthorized(call)
        return false
    }

    /**
     * The open door, minus the one caller it must never serve (DR-30).
     *
     * splice's own keys are not upstream credentials — sending one to the vendor spends nothing,
     * authenticates nothing, and leaks a local secret to a third party. It reaches this seam by
     * accident rather than by malice: until Oct 26, 2026 LaunchService planted ANTHROPIC_AUTH_TOKEN=<turn key>
     * for every non-native head (a LAN share's remote launch still plants its node's bearer, and a session launched
     * before then still holds it), `app/src/main/dist/bin/splice-launch` execs `env` WITHOUT -i, and a native head's
     * unset list is empty by design — so a native head launched from inside such a session inherits that bearer.
     * Forwarding it would ALSO mean the caller's real credential never rides, turning a fixable environment slip
     * into an opaque vendor 401. Refusing here says which.
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
     *
     * The values checked are the values [forwardedClientHeaders] sends, read from it (v0.4.0 review):
     * checking `headers[name]`, the FIRST line, while the forwarder sends the first NON-BLANK one let
     * an empty line ahead of the key pass the check and the key ride upstream.
     *
     * A key a rotation retired counts as splice's too (PR #247 review round 4): a session launched before the key
     * helper holds its old turn key after `splice rotate-key`, and forwarding that key brought back the vendor's 401
     * and Claude Code's re-sends of it.
     */

    /** [authorize] for a call that goes upstream (a turn). On a head that only forwards, a caller
     *  presenting NO credential, in any scheme, is answered here as every other head answers it:
     *  upstream could only say 401, and that 401 would be recorded as the forwarded login rejected
     *  (ClientAuthProvider.upstreamAnswered) on a call that carried no login. The daemon's own
     *  credential-less liveness probe (TurnPathProbeLoop) rode upstream that way every 30s and
     *  signed the head out (2026-10-01). A head holding its own credential still serves such a call,
     *  and a local answer (count_tokens, models) never needs one. */
    private suspend fun authorizeUpstream(call: ApplicationCall): Boolean {
        // Header presence only REFUSES dispatch. No credential or body can turn it into hidden work.
        if (call.request.headers.getAll(LivenessProbe.PROBE_HEADER_NAME) != null) {
            responses.respondRefusal(call, FailureCause.Splice.Declined(Decline.LivenessProbe))
            return false
        }
        if (!authorize(call)) return false
        val accepted = !forwardsCallersOnly() || carriesCredential(call)
        if (!accepted) {
            refusedKeys.refused(call.request.headers[SESSION_HEADER], KeyRefusal.NO_CREDENTIAL)
            responses.respondUnauthorized(call)
        }
        return accepted
    }

    private fun forwardsCallersOnly(): Boolean = deps.policy.forwardClientAuth && forwardsOnly

    private fun carriesCredential(call: ApplicationCall): Boolean =
        CREDENTIAL_HEADERS.any { !call.request.headers[it].isNullOrBlank() }

    private suspend fun allowUnlessOwnKey(call: ApplicationCall): Boolean {
        val carriers = forwardedClientHeaders(call).filterValues { value ->
            value.split(authDelimiterRe).any {
                matchesInferenceToken(it) || matchesOperatorToken(it) || deps.tokens.retired(it)
            }
        }.keys
        // PR #247 review, Oct 29: past splice's own keys, a head holding logins of its own asks who the caller is.
        if (carriers.isEmpty()) return heldLogins.opens(call, forwardedClientHeaders(call))
        val headers = carriers.joinToString(", ")
        val variables = carriers.map { FORWARDED_CLIENT_HEADERS[it] ?: "ANTHROPIC_CUSTOM_HEADERS" }.distinct()
        deps.log(
            "[auth] refused a turn on a client-auth head that presented one of splice's own keys (the " +
                "management key or the turn key) in $headers: " +
                "${variables.joinToString(" and ")} in the launching environment carried it and shadowed the " +
                "caller's own credential; unset it (or launch from a clean shell) so this head can forward yours\n",
        )
        // The line names the variables to unset, which is what the person acts on; the headers stay in the log.
        responses.respondRefusal(call, FailureCause.Splice.Declined(Decline.SpliceKeyAsLogin(variables)))
        return false
    }

    private fun matchesInferenceToken(presented: String?): Boolean =
        constantTimeEquals(presented, deps.tokens.inferenceToken)

    private fun matchesOperatorToken(presented: String?): Boolean =
        constantTimeEquals(presented, deps.tokens.operatorToken)

    /** Constant-time compare against one of this head's own keys. Length is checked first because
     *  [MessageDigest.isEqual] is only constant-time for equal-length inputs. */
    private fun constantTimeEquals(presented: String?, expected: String): Boolean {
        val presentedBytes = presented?.toByteArray(Charsets.UTF_8) ?: return false
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        if (presentedBytes.size != expectedBytes.size) return false
        return MessageDigest.isEqual(presentedBytes, expectedBytes)
    }

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
        return (FORWARDED_CLIENT_HEADERS.keys + identity).mapNotNull { name ->
            forwardedValue(call, name)?.let { name to it }
        }.toMap()
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
