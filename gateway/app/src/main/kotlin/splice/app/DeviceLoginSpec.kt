// NEW: everything the device-authorization login needs for one provider.
// Split from DeviceLoginFlow.kt so the poller is not billed for the spec
// (concentration, 2026-08-19). Same-package FQCN is unchanged.
package splice.app

import splice.app.auth.OAuthLoginAccount
import java.nio.file.Path

/** Runs after a successful credential write; the default is a no-op so kimi stays unchanged. */
public fun interface DeviceLoginFinalizer {
    public suspend operator fun invoke(authPath: Path, account: OAuthLoginAccount?)
}

/** Everything the device flow needs for one provider's login (built by LoginCommand per head). */
public data class DeviceLoginSpec(
    val head: String,
    val clientId: String,
    val deviceAuthUrl: String,
    val tokenUrl: String,
    val authPath: Path,
    /** Extra identity headers on both OAuth calls; LoginIo already sends Accept application/json. */
    val identityHeaders: Map<String, String>,
    /** token-endpoint success body → the auth.json content to persist. */
    val toAuthJson: AuthJsonFromResponse,
    val account: OAuthLoginAccount? = null,
    val afterPersist: DeviceLoginFinalizer = DeviceLoginFinalizer { _, _ -> },
)
