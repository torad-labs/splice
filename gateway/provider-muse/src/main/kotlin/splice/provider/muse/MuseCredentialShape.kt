// NEW: muse-oauth auth.json — flat access_token only; no refresh and no expiry on this kind.
package splice.provider.muse

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.spi.CredentialShape
import splice.spi.CredentialTokens

public class MuseCredentialShape : CredentialShape {
    override fun material(root: JsonObject): CredentialTokens = CredentialTokens(
        access = JsonScalars.str(root, "access_token"),
        refresh = null,
        expiresAtMs = null,
        refreshOptional = true,
    )
}
