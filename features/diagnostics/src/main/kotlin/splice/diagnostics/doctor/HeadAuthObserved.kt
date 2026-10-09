// NEW: what the running daemon has seen of one head's credential, grouped so [DoctorHeadAuth] stays
// inside detekt's constructor width.
package splice.diagnostics.doctor

import splice.core.auth.CredentialVerdict
import splice.core.head.ProviderAnswer

/** [daemonVerdict] is what the running daemon says upstream last answered a self-managed head's
 *  forwarded login (V4-220 item 6b); null when the daemon was not read (stopped, or no mgmt key).
 *  [lastRefusal] is the head's latest credential refusal the daemon retained, if any. */
internal data class HeadAuthObserved(
    val daemonVerdict: CredentialVerdict? = null,
    val lastRefusal: ProviderAnswer? = null,
)
