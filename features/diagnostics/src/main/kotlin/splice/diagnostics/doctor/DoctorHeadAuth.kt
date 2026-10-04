// NEW: one probed head's credential state. Split from DoctorAuth.kt so the I/O/verdict
// collaborator is not billed for a field group (concentration HIGH, 2026-08-19).
package splice.diagnostics.doctor

import splice.core.auth.CredentialVerdict

internal data class DoctorHeadAuth(
    val key: String,
    val command: String,
    val envVar: String?,
    val isOAuth: Boolean,
    val present: Boolean,
    /** The CALLER supplies the credential; splice holds none, so there is nothing to configure. */
    val selfManaged: Boolean = false,
    /** V4-220 item 6b: what the running daemon says upstream last answered a self-managed head's
     *  forwarded login; null when the daemon was not read (stopped, or no mgmt key). */
    val daemonVerdict: CredentialVerdict? = null,
)
