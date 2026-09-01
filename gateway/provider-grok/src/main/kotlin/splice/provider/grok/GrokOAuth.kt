// NEW: xAI Grok OAuth — the SuperGrok / X-Premium+ browser login the official `grok` CLI uses
// (researched from xai-org/grok-build docs + the opencode-grok-auth / hermes-agent reference
// implementations). Authorization-code + PKCE (S256) against auth.x.ai with a loopback redirect,
// exactly like codex — only the endpoints/client-id/scope differ. Credentials land in
// ~/.grok/auth.json (shape-compatible enough to interop with the official CLI's tokens). The public
// desktop client id is not a secret (it's the CLI's, reused so no separate grok binary is needed).
package splice.provider.grok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.core.auth.CredentialExpiry
import splice.core.auth.Pkce
import splice.core.util.EnvReader
import splice.core.util.FormEncoding
import splice.core.util.JsonScalars
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// 48 = PKCE verifier byte length used by the grok CLI (base64url ~64 chars).
private const val PKCE_VERIFIER_BYTES = 48

// The refresh-token grant name doubles as the persisted token field key (the wire contract).
private const val WIRE_REFRESH_TOKEN = "refresh_token"

// FILE SCOPE ON PURPOSE: one configured Json parser shared by every call. As a member it would be
// rebuilt per GrokOAuth construction, and the callers construct one per login/refresh.
private val grokJson = Json { ignoreUnknownKeys = true }

/** The grok OAuth wire builders and response parsers. Stateless — collaborators construct one. */
public class GrokOAuth {

    public fun makeGrokPkce(random: SecureRandom = SecureRandom()): Pkce {
        val verifier = grokBase64Url(ByteArray(PKCE_VERIFIER_BYTES).also { random.nextBytes(it) })
        val challenge = grokBase64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return Pkce(verifier, challenge)
    }

    private fun grokBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Authorize URL with the grok-CLI param set + %20 (not +) encoding. */
    public fun buildGrokAuthorizeUrl(
        challenge: String,
        state: String,
        nonce: String,
        clientId: String,
        env: EnvReader,
        redirectUri: String = GrokOAuthEndpoints.REDIRECT_URI,
    ): String {
        val params = listOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "scope" to GrokOAuthEndpoints.SCOPE,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "nonce" to nonce,
            "plan" to "generic",
            "referrer" to "splice",
        )
        val query = params.joinToString("&") { (k, v) -> "$k=${FormEncoding.percentEncode(v)}" }
        return "${GrokOAuthEndpoints.authorizeUrl(env)}?$query"
    }

    /** Form body for the authorization-code exchange (x-www-form-urlencoded). */
    public fun grokCodeExchangeForm(
        code: String,
        verifier: String,
        challenge: String,
        clientId: String,
        redirectUri: String,
    ): String =
        FormEncoding.formEncode(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to verifier,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        )

    /** Form body for the refresh-token grant. */
    public fun grokRefreshForm(refreshToken: String, clientId: String): String =
        FormEncoding.formEncode(
            "grant_type" to WIRE_REFRESH_TOKEN,
            "client_id" to clientId,
            WIRE_REFRESH_TOKEN to refreshToken,
        )

    /** Parse a token endpoint response into the ~/.grok/auth.json object GrokAuthProvider reads. */
    public fun grokAuthJsonFromTokenResponse(
        responseBody: String,
        fallbackRefresh: String?,
        nowMs: Long,
        nowIso: String,
    ): JsonObject {
        val obj = jsonObjectOrEmpty(grokJson.parseToJsonElement(responseBody))
        val access = JsonScalars.str(obj, "access_token").orEmpty()
        val refresh = JsonScalars.str(obj, WIRE_REFRESH_TOKEN) ?: fallbackRefresh
        val expiresIn = JsonScalars.long(obj, "expires_in")
        return buildJsonObject {
            put(
                "tokens",
                buildJsonObject {
                    put("access_token", JsonPrimitive(access))
                    if (refresh != null) put(WIRE_REFRESH_TOKEN, JsonPrimitive(refresh))
                },
            )
            // DR-177: this was nowMs + expiresIn * 1000, which wrapped to a large NEGATIVE
            // instant for any absurd expires_in — the persisted file then read as expired on
            // every turn, and every turn refreshed. The file-local seconds constant went with
            // it: the conversion belongs to CredentialExpiry now, not to each provider.
            if (expiresIn != null) {
                put("expires", JsonPrimitive(CredentialExpiry.expiryFromNowMs(nowMs, expiresIn)))
            }
            put("last_refresh", JsonPrimitive(nowIso))
        }
    }

    private fun jsonObjectOrEmpty(el: JsonElement): JsonObject =
        el as? JsonObject ?: JsonObject(emptyMap())
}
