// NEW: one probed head's credential state. Split from DoctorAuth.kt so the I/O/verdict
// collaborator is not billed for a field group (concentration HIGH, 2026-08-19).
package splice.app.cli

internal data class HeadAuth(
    val key: String,
    val command: String,
    val envVar: String?,
    val isOAuth: Boolean,
    val present: Boolean,
    /** The CALLER supplies the credential; splice holds none, so there is nothing to configure. */
    val selfManaged: Boolean = false,
)
