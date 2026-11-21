// PORT-OF: splice/gateway/head/HeadServer.kt (authorize, forwardedClientHeaders,
// FORWARDED_CLIENT_HEADERS) @ 1caedd6 — invariants unchanged: the mgmt-key front door and the
// inbound-header allowlist, which are one security decision — the same deps.policy.forwardClientAuth flag (derived
// from the resolved ClientAuthProvider, never a config string) decides both whether the local check is bypassed and
// whether caller credentials ride upstream. Split out (HD-24) so that pairing is greppable; the upstream half now
// lives in CallerHeaders.kt, beside the door that does not apply to a gateway turn (GatewayUpstreamHeaders).
package splice.head

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import splice.core.auth.BearerScheme
import splice.core.auth.CredentialKey
import splice.core.auth.ForeignHostLog
import splice.core.auth.LoopbackHost
import splice.core.client.SESSION_HEADER
import splice.core.config.LAUNCH_PROOF_HEADER
import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.perf.LivenessProbe
import splice.core.topology.ForwardHeaders
import splice.head.admission.AdmissionResponses
import java.security.MessageDigest

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
    /** The headers this head's provider names in forward_headers (Nov 14, 2026), which ride on from the caller on any
     *  head, whether or not it forwards the caller's login. None by default. */
    forwarded: ForwardHeaders = ForwardHeaders(emptyList()),
) : UpstreamHeaders {
    /** splice's own keys, found in what a caller sends (OwnKeys). */
    private val ownKeys = OwnKeys(deps.tokens)

    /** What of a caller's request rides upstream: the other half of this one security decision, in its own class so a
     *  gateway can answer the same question without a head's door. */
    private val callers = CallerHeaders(deps, forwarded)

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
        if (ownKeys.isTurnKey(presented) || ownKeys.isManagementKey(presented)) return true
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
        if (ownKeys.isManagementKey(presentedCredential(call))) return true
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
        credentialHeaders.any { !call.request.headers[it].isNullOrBlank() }

    private suspend fun allowUnlessOwnKey(call: ApplicationCall): Boolean {
        val carriers = callers.forwardedClientHeaders(call).filterValues(ownKeys::carriedIn).keys
        // PR #247 review, Oct 29: past splice's own keys, a head holding logins of its own asks who the caller is.
        if (carriers.isEmpty()) return heldLogins.opens(call, callers.forwardedClientHeaders(call))
        val headers = carriers.joinToString(", ")
        val variables = carriers.map { forwardedHeaderVariables[it] ?: "ANTHROPIC_CUSTOM_HEADERS" }.distinct()
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

    /** Every header of [call] that rides upstream on this head ([CallerHeaders.upstreamHeaders]). */
    override fun upstreamHeaders(call: ApplicationCall): Map<String, String> = callers.upstreamHeaders(call)
}

/**
 * A caller is served once it is proven splice's: it names the head's turn key in [LAUNCH_PROOF_HEADER], as a session
 * splice launched and the LAN share's relay do, or it forwards a login the pool already holds, as a session launched
 * before the proof existed does (it signed in from the same file the pool reads). A key a rotation retired proves
 * nothing. A head that holds no login of its own forwards the caller's own as it is, spends nothing of the
 * operator's, and asks for no proof.
 *
 * PR #247 review, Oct 29, 2026 (security). On a head that forwards the caller's own Claude login and also holds logins
 * of its own, the pool puts one of those in place of whatever the caller forwards (CallerCredential.over), so the head's
 * door is all that stands between a caller and the operator's logins. It stood open: another account on this machine
 * posting `Bearer x`, or the LAN share's word, to the head's loopback port ran turns on his logins with no key at all.
 * ClientAuth asks this door once a call has passed its own-key check.
 *
 * PR #247 review round 2, Oct 30 (security): the door reads the pool when the headers arrive, and the login is chosen
 * once the body is read, so a login can join in between. The door's verdict rides on the call ([HeldLoginVerdict]),
 * and admission refuses a turn it did not prove whenever the pool it selects from holds a login of its own.
 */
internal class HeldLoginDoor(
    private val deps: HeadDeps,
    private val responses: AdmissionResponses,
    private val refusedKeys: RefusedKeyLog,
) {
    /** Whether [call], which forwards [forwarded], may run here. A refusal is answered and said here.
     *
     *  PR #247 review round 3, Oct 30: a proof a rotation retired opens nothing, but splice did start that session, so
     *  it reads that its splice key is not valid and to start again from splice, as `splice sessions relaunch` does,
     *  never that it was started another way. */
    suspend fun opens(call: ApplicationCall, forwarded: Map<String, String>): Boolean {
        if (proven(call, forwarded)) return true
        val session = call.request.headers[SESSION_HEADER]
        if (proofs(call).any(deps.tokens::retired)) {
            refusedKeys.refused(session, KeyRefusal.RETIRED)
            responses.respondUnauthorized(call)
        } else {
            refusedKeys.refused(session, KeyRefusal.NOT_LAUNCHED)
            responses.respondNotLaunched(call)
        }
        return false
    }

    private fun proofs(call: ApplicationCall): List<String> =
        call.request.headers.getAll(LAUNCH_PROOF_HEADER).orEmpty().map(String::trim)

    /** TRUE for a proven caller, which [HeldLoginVerdict] records, or for any caller while the head holds no login
     *  of its own, which admission still refuses a held login unless it was proven. */
    private fun proven(call: ApplicationCall, forwarded: Map<String, String>): Boolean {
        val pool = deps.quotaBundle.accountPool ?: return true
        val holds = pool.active
        val named = proofs(call).any(::isTurnKey)
        val presented = CredentialKey.fromHeaders(forwarded)
        val proven = named || presented != null && pool.members.any { it.auth.observedCredentialKey() == presented }
        if (proven) HeldLoginVerdict.prove(call)
        return proven || !holds
    }

    /** Constant-time, as ClientAuth compares its keys: length first, since [MessageDigest.isEqual] is constant-time
     *  only for inputs of one length. */
    private fun isTurnKey(presented: String): Boolean {
        val expected = deps.tokens.inferenceToken.toByteArray(Charsets.UTF_8)
        val bytes = presented.toByteArray(Charsets.UTF_8)
        return bytes.size == expected.size && MessageDigest.isEqual(bytes, expected)
    }
}

/** The held-login door's verdict, carried from the headers to account selection on the call itself, so a login that
 *  joins while the body arrives serves only a caller the door proved (PR #247 review round 2, Oct 30). */
internal object HeldLoginVerdict {
    private val proven = AttributeKey<Unit>("splice.held-login-proven")

    fun prove(call: ApplicationCall) {
        call.attributes.put(proven, Unit)
    }

    /** Whether the door proved [call] splice's. A call no door saw is not proven. */
    fun proven(call: ApplicationCall): Boolean = proven in call.attributes
}
