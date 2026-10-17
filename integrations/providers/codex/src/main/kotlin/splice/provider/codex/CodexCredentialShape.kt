// NEW: chatgpt-oauth auth.json — tokens.access_token / tokens.refresh_token, expiry from the JWT exp.
package splice.provider.codex

import kotlinx.serialization.json.JsonObject
import splice.core.auth.CredentialExpiry
import splice.core.util.JsonScalars
import splice.upstream.credentials.CredentialShape
import splice.upstream.credentials.CredentialTokens

public class CodexCredentialShape : CredentialShape {
    private val jwt = CodexOAuth()

    override fun material(root: JsonObject): CredentialTokens {
        val tokens = root["tokens"] as? JsonObject ?: JsonObject(emptyMap())
        val access = JsonScalars.str(tokens, "access_token")
        return CredentialTokens(
            access = access,
            refresh = JsonScalars.str(tokens, "refresh_token"),
            expiresAtMs = JsonScalars.long(jwt.decodeJwtClaims(access), "exp")
                ?.let(CredentialExpiry::epochSecondsToMs),
        )
    }
}
