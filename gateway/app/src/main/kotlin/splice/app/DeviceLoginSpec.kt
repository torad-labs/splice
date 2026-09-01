// NEW: everything the device-authorization login needs for one provider.
// Split from DeviceLoginFlow.kt so the poller is not billed for the spec
// (concentration, 2026-08-19). Same-package FQCN is unchanged.
package splice.app

import java.nio.file.Path

/** Everything the device flow needs for one provider's login (built by LoginCommand per head). */
public data class DeviceLoginSpec(
    val head: String,
    val clientId: String,
    val deviceAuthUrl: String,
    val tokenUrl: String,
    val authPath: Path,
    /** X-Msh-* device identity headers sent on both OAuth calls. */
    val identityHeaders: Map<String, String>,
    /** token-endpoint success body → the auth.json content to persist. */
    val toAuthJson: AuthJsonFromResponse,
)
