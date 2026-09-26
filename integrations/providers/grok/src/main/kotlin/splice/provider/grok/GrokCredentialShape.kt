// NEW: grok-oauth auth.json — tokens.access_token / tokens.refresh_token, root expires in ms.
package splice.provider.grok

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.upstream.credentials.CredentialShape
import splice.upstream.credentials.CredentialTokens

public class GrokCredentialShape : CredentialShape {
    override fun material(root: JsonObject): CredentialTokens {
        val tokens = root["tokens"] as? JsonObject ?: JsonObject(emptyMap())
        return CredentialTokens(
            access = JsonScalars.str(tokens, "access_token"),
            refresh = JsonScalars.str(tokens, "refresh_token"),
            expiresAtMs = JsonScalars.long(root, "expires"),
        )
    }
}
