// NEW: kimi-oauth auth.json — flat access_token / refresh_token, expires_at in seconds.
package splice.provider.kimi

import kotlinx.serialization.json.JsonObject
import splice.core.auth.CredentialExpiry
import splice.core.util.JsonScalars
import splice.spi.CredentialShape
import splice.spi.CredentialTokens

public class KimiCredentialShape : CredentialShape {
    override fun material(root: JsonObject): CredentialTokens = CredentialTokens(
        access = JsonScalars.str(root, "access_token"),
        refresh = JsonScalars.str(root, "refresh_token"),
        expiresAtMs = JsonScalars.long(root, "expires_at")?.let(CredentialExpiry::epochSecondsToMs),
    )
}
