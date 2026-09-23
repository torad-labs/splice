// NEW: muse-oauth auth.json — flat access_token only; no refresh and no expiry on this kind.
package splice.provider.muse

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.upstream.credentials.CredentialShape
import splice.upstream.credentials.CredentialTokens

public class MuseCredentialShape : CredentialShape {
    override fun material(root: JsonObject): CredentialTokens = CredentialTokens(
        access = JsonScalars.str(root, "access_token"),
        refresh = null,
        expiresAtMs = null,
        refreshOptional = true,
    )

    /** The minted key a turn presents (MuseAuthProvider.credentials reads the same `api_key`), not
     *  the access token it was minted from, which the Muse API refuses. */
    override fun presented(root: JsonObject): String? = JsonScalars.str(root, "api_key")
}
