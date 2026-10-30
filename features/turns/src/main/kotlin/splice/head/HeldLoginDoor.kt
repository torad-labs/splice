// NEW: PR #247 review, Oct 29, 2026 (security). On a head that forwards the caller's own Claude login and also holds
// logins of its own, the pool puts one of those in place of whatever the caller forwards (CallerCredential.over), so
// the head's door is all that stands between a caller and the operator's logins. It stood open: another account on
// this machine posting `Bearer x`, or the LAN share's word, to the head's loopback port ran turns on his logins with no
// key at all. ClientAuth asks this door once a call has passed its own-key check.
package splice.head

import io.ktor.server.application.ApplicationCall
import splice.core.auth.CredentialKey
import splice.core.client.LAUNCH_PROOF_HEADER
import splice.core.client.SESSION_HEADER
import splice.head.admission.AdmissionResponses
import java.security.MessageDigest

/**
 * A caller is served once it is proven splice's: it names the head's turn key in [LAUNCH_PROOF_HEADER], as a session
 * splice launched and the LAN share's relay do, or it forwards a login the pool already holds, as a session launched
 * before the proof existed does (it signed in from the same file the pool reads). A key a rotation retired proves
 * nothing. A head that holds no login of its own forwards the caller's own as it is, spends nothing of the
 * operator's, and asks for no proof.
 */
internal class HeldLoginDoor(
    private val deps: HeadDeps,
    private val responses: AdmissionResponses,
    private val refusedKeys: RefusedKeyLog,
) {
    /** Whether [call], which forwards [forwarded], may run here. A refusal is answered and said here. */
    suspend fun opens(call: ApplicationCall, forwarded: Map<String, String>): Boolean {
        if (proven(call, forwarded)) return true
        refusedKeys.refused(call.request.headers[SESSION_HEADER], KeyRefusal.NOT_LAUNCHED)
        responses.respondUnauthorized(call)
        return false
    }

    private fun proven(call: ApplicationCall, forwarded: Map<String, String>): Boolean {
        val pool = deps.quotaBundle.accountPool?.takeIf { it.active } ?: return true
        val named = call.request.headers.getAll(LAUNCH_PROOF_HEADER).orEmpty().any { isTurnKey(it.trim()) }
        val presented = CredentialKey.fromHeaders(forwarded)
        return named || presented != null && pool.members.any { it.auth.observedCredentialKey() == presented }
    }

    /** Constant-time, as ClientAuth compares its keys: length first, since [MessageDigest.isEqual] is constant-time
     *  only for inputs of one length. */
    private fun isTurnKey(presented: String): Boolean {
        val expected = deps.tokens.inferenceToken.toByteArray(Charsets.UTF_8)
        val bytes = presented.toByteArray(Charsets.UTF_8)
        return bytes.size == expected.size && MessageDigest.isEqual(bytes, expected)
    }
}
