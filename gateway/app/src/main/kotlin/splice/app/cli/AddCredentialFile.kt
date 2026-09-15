// NEW: v0.4.0 FEATURES.md §1 — is a stored OAuth credential USABLE, not merely present. A file that
// parses and carries token material where ITS KIND keeps it (chatgpt and grok under "tokens", kimi
// flat) plus a refresh token or an expiry still ahead lets `splice add` skip the sign-in; an empty,
// gutted, decoy-shaped or dead file must not. Reads the file once, names no byte of it, never goes to
// the network: the bar is "the daemon could serve or refresh this", which is what each provider needs.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.auth.CredentialExpiry
import splice.core.topology.AuthKind
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.provider.codex.CodexOAuth
import java.nio.file.Files
import java.nio.file.Path

private const val ACCESS_TOKEN = "access_token"
private const val REFRESH_TOKEN = "refresh_token"
private const val TOKENS = "tokens"
private const val SIGN_IN = "sign in again"

/** What a kind's file carries: the access token, the refresh token if any, the expiry if any. */
private data class TokenMaterial(val access: String?, val refresh: String?, val expiresAtMs: Long?)

internal class AddCredentialFile(private val json: Json = Json { ignoreUnknownKeys = true }) {
    private val jwt = CodexOAuth()

    /** Null when the file can serve or refresh; otherwise the reason, with the fix being a sign-in. */
    fun problem(path: Path, kind: String, nowMs: Long = System.currentTimeMillis()): String? {
        val root = Cancellables.runCatchingCancellable { json.parseToJsonElement(Files.readString(path)).jsonObject }
            .getOrNull() ?: return "the stored credential does not parse — $SIGN_IN"
        val material = material(kind, root) ?: return "splice add cannot judge a $kind credential — $SIGN_IN"
        return when {
            material.access.isNullOrEmpty() ->
                "the stored credential holds no access token where $kind keeps it — $SIGN_IN"
            kind == AuthKind.MuseOAuth.wire -> null
            !material.refresh.isNullOrEmpty() -> null
            material.expiresAtMs != null && material.expiresAtMs > nowMs -> null
            else -> "the stored credential holds no refresh token and no expiry still ahead — $SIGN_IN"
        }
    }

    /** Each kind's EXACT shape, read the way its provider reads it: chatgpt's expiry is the access
     *  JWT's exp, grok's a root "expires" in ms, kimi's a root "expires_at" in seconds. An object
     *  elsewhere in the file carrying token-looking fields is not a credential. */
    private fun material(kind: String, root: JsonObject): TokenMaterial? = when (kind) {
        AuthKind.ChatgptOAuth.wire -> tokens(root).let { t ->
            val access = JsonScalars.str(t, ACCESS_TOKEN)
            TokenMaterial(access, JsonScalars.str(t, REFRESH_TOKEN), jwtExpiryMs(access))
        }
        AuthKind.GrokOAuth.wire -> tokens(root).let { t ->
            val expiresMs = JsonScalars.long(root, "expires")
            TokenMaterial(JsonScalars.str(t, ACCESS_TOKEN), JsonScalars.str(t, REFRESH_TOKEN), expiresMs)
        }
        AuthKind.KimiOAuth.wire -> TokenMaterial(
            JsonScalars.str(root, ACCESS_TOKEN),
            JsonScalars.str(root, REFRESH_TOKEN),
            JsonScalars.long(root, "expires_at")?.let(CredentialExpiry::epochSecondsToMs),
        )
        AuthKind.MuseOAuth.wire -> TokenMaterial(JsonScalars.str(root, ACCESS_TOKEN), null, null)
        else -> null
    }

    private fun tokens(root: JsonObject): JsonObject = root[TOKENS] as? JsonObject ?: JsonObject(emptyMap())

    private fun jwtExpiryMs(access: String?): Long? =
        JsonScalars.long(jwt.decodeJwtClaims(access), "exp")?.let(CredentialExpiry::epochSecondsToMs)
}
