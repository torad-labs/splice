// NEW: one probed head's credential state. Split from DoctorAuth.kt so the I/O/verdict
// collaborator is not billed for a field group (concentration HIGH, 2026-08-19).
package splice.diagnostics.doctor

import splice.core.auth.CredentialVerdict
import splice.core.head.ProviderAnswer

internal data class DoctorHeadAuth(
    val key: String,
    val command: String,
    val envVar: String?,
    val isOAuth: Boolean,
    val present: Boolean,
    /** The CALLER supplies the credential; splice holds none, so there is nothing to configure. */
    val selfManaged: Boolean = false,
    val observed: HeadAuthObserved = HeadAuthObserved(),
)

/** [daemonVerdict] is what the running daemon says upstream last answered a self-managed head's
 *  forwarded login (V4-220 item 6b); null when the daemon was not read (stopped, or no mgmt key).
 *  [lastRefusal] is the head's latest credential refusal the daemon retained, if any. */
internal data class HeadAuthObserved(
    val daemonVerdict: CredentialVerdict? = null,
    val lastRefusal: ProviderAnswer? = null,
)
