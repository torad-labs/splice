// NEW: the doctor AUTH section's pure verdict — probed heads plus the honest severity
// become rows. Split from DoctorAuth.kt so that I/O collaborator is not billed for a
// field-to-row fold (concentration HIGH, 2026-08-19). Reads no environment, no
// filesystem and no daemon.
package splice.diagnostics.doctor

import splice.core.auth.CredentialVerdict
import java.time.Instant

internal class DoctorAuthVerdict {

    internal fun credentialVerdict(heads: List<DoctorHeadAuth>, missingStatus: CheckStatus): List<DoctorCheck> {
        return heads.map { auth ->
            when {
                auth.present -> DoctorCheck(auth.key, CheckStatus.OK, credentialLabel(auth))
                // V4-220 item 6b: the caller's own login, rejected upstream; only the client can sign in again.
                auth.selfManaged -> DoctorCheck(
                    auth.key,
                    missingStatus,
                    "upstream rejected the forwarded Claude login${at(auth.daemonVerdict)}",
                    "run ${auth.command}, then /login inside it",
                )
                // Only genuine OAuth heads have a `<command> login` flow; api-key heads (env var known)
                // must never be sent to that dead end, so the guard is isOAuth, not envVar == null.
                auth.isOAuth -> DoctorCheck(auth.key, missingStatus, "not signed in", "${auth.command} login")
                else -> DoctorCheck(
                    auth.key,
                    missingStatus,
                    "${auth.envVar} is not set",
                    "export ${auth.envVar}=…   then: $FIX_RESTART",
                )
            }
        }
    }

    // The client-auth line reports what the head DECLARES, not what the daemon wired: doctor reads
    // the topology TOML and cannot see the providers. "splice holds no credential for this head"
    // was a claim about the running daemon that this process has no way to check — true whenever
    // declaration and wiring agree (anthropic-passthrough, the one arm that builds a
    // ClientAuthProvider), false on a dialect whose dispatch has no client arm and therefore keeps
    // an api-key provider plus the mgmt-key door. Naming the declaration is the honest form.
    // V4-220 item 6b: with the daemon read, the line says what upstream last answered instead.
    private fun credentialLabel(auth: DoctorHeadAuth): String = when {
        auth.selfManaged -> when (val verdict = auth.daemonVerdict) {
            is CredentialVerdict.Accepted -> "client-native — upstream accepted the forwarded login${at(verdict)}"
            CredentialVerdict.Unverified ->
                "client-native — no forwarded turn answered since the daemon started, so the login is unverified"
            else -> "client-native — declared auth.kind = client, so there is no key to set"
        }
        auth.envVar != null -> "${auth.envVar} is set"
        else -> "signed in"
    }

    private fun at(verdict: CredentialVerdict?): String =
        verdict?.atEpochMs?.let { " at ${Instant.ofEpochMilli(it)}" }.orEmpty()
}
