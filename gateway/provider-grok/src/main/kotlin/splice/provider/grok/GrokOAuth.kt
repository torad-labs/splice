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
import splice.core.util.FormEncoding
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

public object GrokOAuthEndpoints {
    public const val DEFAULT_CLIENT_ID: String = "b1a00492-073a-47ea-816f-4c329264a828"
    public const val REDIRECT_PORT: Int = 56121
    public const val REDIRECT_URI: String = "http://127.0.0.1:56121/callback"
    public const val SCOPE: String = "openid profile email offline_access grok-cli:access api:access"

    public fun issuer(env: (String) -> String?): String =
        (env("GROK_OAUTH_ISSUER") ?: "https://auth.x.ai").trimEnd('/')

    public fun authorizeUrl(env: (String) -> String?): String =
        env("GROK_OAUTH_AUTHORIZE_URL") ?: "${issuer(env)}/oauth2/authorize"

    // discovery would resolve this, but the CLI's endpoint is stable; env-overridable for safety.
    public fun tokenUrl(env: (String) -> String?): String =
        env("GROK_OAUTH_TOKEN_URL") ?: "${issuer(env)}/oauth2/token"

    public fun clientId(env: (String) -> String?): String =
        env("GROK_OAUTH_CLIENT_ID") ?: DEFAULT_CLIENT_ID
}

// 48 = PKCE verifier byte length used by the grok CLI (base64url ~64 chars).
private const val PKCE_VERIFIER_BYTES = 48

public fun makeGrokPkce(random: SecureRandom = SecureRandom()): Pkce {
    val verifier = grokBase64Url(ByteArray(PKCE_VERIFIER_BYTES).also { random.nextBytes(it) })
    val challenge = grokBase64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    return Pkce(verifier, challenge)
}

public data class Pkce(val verifier: String, val challenge: String)

private fun grokBase64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** Authorize URL with the grok-CLI param set + %20 (not +) encoding. */
public fun buildGrokAuthorizeUrl(
    challenge: String,
    state: String,
    nonce: String,
    clientId: String,
    env: (String) -> String?,
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

// The refresh-token grant name doubles as the persisted token field key (the wire contract).
private const val WIRE_REFRESH_TOKEN = "refresh_token"

/** Form body for the refresh-token grant. */
public fun grokRefreshForm(refreshToken: String, clientId: String): String =
    FormEncoding.formEncode(
        "grant_type" to WIRE_REFRESH_TOKEN,
        "client_id" to clientId,
        WIRE_REFRESH_TOKEN to refreshToken,
    )

private val grokJson = Json { ignoreUnknownKeys = true }

/** Parse a token endpoint response into the ~/.grok/auth.json object GrokAuthProvider reads. */
public fun grokAuthJsonFromTokenResponse(
    responseBody: String,
    fallbackRefresh: String?,
    nowMs: Long,
    nowIso: String,
): JsonObject {
    val obj = grokJson.parseToJsonElement(responseBody).jsonObjectOrEmpty()
    val access = (obj["access_token"] as? JsonPrimitive)?.content.orEmpty()
    val refresh = (obj[WIRE_REFRESH_TOKEN] as? JsonPrimitive)?.content ?: fallbackRefresh
    val expiresIn = (obj["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull()
    return buildJsonObject {
        put(
            "tokens",
            buildJsonObject {
                put("access_token", JsonPrimitive(access))
                if (refresh != null) put(WIRE_REFRESH_TOKEN, JsonPrimitive(refresh))
            },
        )
        if (expiresIn != null) put("expires", JsonPrimitive(nowMs + expiresIn * MS_PER_S))
        put("last_refresh", JsonPrimitive(nowIso))
    }
}

private const val MS_PER_S = 1000L

private fun JsonElement.jsonObjectOrEmpty(): JsonObject =
    this as? JsonObject ?: JsonObject(emptyMap())
