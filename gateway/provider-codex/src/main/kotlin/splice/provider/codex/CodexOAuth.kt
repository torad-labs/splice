// PORT-OF: server/src/auth/codex-oauth.mjs + codex-login.mjs @ 4ca99f7 — invariants: the PUBLIC
// codex CLI client id + issuer (reusing them is what mints a ChatGPT-subscription token with no
// separate codex binary); auth.json is byte-shape-compatible with the real codex CLI (shared
// credential, shared refresh path); PKCE S256; authorize URL param ORDER + %20 encoding matched
// to the CLI (URLSearchParams' `+`-for-space mangles the scope into missing_required_parameter);
// account_id from the id_token JWT claim https://api.openai.com/auth.chatgpt_account_id; cached
// read keyed on path+mtime+TTL; single-flight refresh; introspection never exposes token
// material (masked account id). SEAM: the HTTP client + env reader + clock are injected.
package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.FormEncoding
import splice.core.util.runCatchingCancellable
import splice.core.util.str
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

public object CodexOAuthEndpoints {
    public const val DEFAULT_CLIENT_ID: String = "app_EMoamEEZ73f0CkXaXp7hrann"
    public const val REDIRECT_PORT: Int = 1455
    public const val REDIRECT_URI: String = "http://localhost:1455/auth/callback"
    public const val SCOPE: String =
        "openid profile email offline_access api.connectors.read api.connectors.invoke"

    public fun issuer(env: (String) -> String?): String =
        (env("CODEX_OAUTH_ISSUER") ?: "https://auth.openai.com").trimEnd('/')

    public fun tokenUrl(env: (String) -> String?): String =
        env("CODEX_OAUTH_TOKEN_URL") ?: "${issuer(env)}/oauth/token"

    public fun authorizeUrl(env: (String) -> String?): String =
        env("CODEX_OAUTH_AUTHORIZE_URL") ?: "${issuer(env)}/oauth/authorize"

    public fun clientId(env: (String) -> String?): String =
        env("CODEX_OAUTH_CLIENT_ID") ?: DEFAULT_CLIENT_ID

    public fun originator(env: (String) -> String?): String =
        env("CODEX_OAUTH_ORIGINATOR") ?: "codex_cli_rs"
}

public data class Pkce(val verifier: String, val challenge: String)

private fun base64url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

// 32 = PKCE verifier byte length per RFC 7636.
private const val PKCE_VERIFIER_BYTES = 32

public fun makePkce(random: SecureRandom = SecureRandom()): Pkce {
    val verifier = base64url(ByteArray(PKCE_VERIFIER_BYTES).also { random.nextBytes(it) })
    val challenge = base64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    return Pkce(verifier, challenge)
}

/** Authorize URL with the codex-CLI param set, ORDER, and %20 (not +) encoding. */
public fun buildAuthorizeUrl(
    challenge: String,
    state: String,
    clientId: String,
    env: (String) -> String?,
    redirectUri: String = CodexOAuthEndpoints.REDIRECT_URI,
): String {
    val params = listOf(
        "response_type" to "code",
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "scope" to CodexOAuthEndpoints.SCOPE,
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
        "id_token_add_organizations" to "true",
        "codex_cli_simplified_flow" to "true",
        "state" to state,
        "originator" to CodexOAuthEndpoints.originator(env),
    )
    val query = params.joinToString("&") { (k, v) -> "$k=${FormEncoding.percentEncode(v)}" }
    return "${CodexOAuthEndpoints.authorizeUrl(env)}?$query"
}

/** Form body for the authorization-code exchange (x-www-form-urlencoded, RFC3986-encoded values).
 *  Mirrors the Node reference's URLSearchParams body; the real code is encoded at exchange time so a
 *  code containing reserved chars (`&`, `=`, `+`, `%`) can never corrupt or inject into the body. */
public fun codexCodeExchangeForm(
    code: String,
    verifier: String,
    clientId: String,
    redirectUri: String,
): String = FormEncoding.formEncode(
    "grant_type" to "authorization_code",
    "code" to code,
    "redirect_uri" to redirectUri,
    "client_id" to clientId,
    "code_verifier" to verifier,
)

private val jwtJson = Json { ignoreUnknownKeys = true }

public fun decodeJwtClaims(jwt: String?): JsonObject {
    val payload = jwt.orEmpty().split(".").getOrNull(1) ?: return JsonObject(emptyMap())
    // ast-grep-ignore: kt-no-silent-result-collapse -- empty claims IS the contract for a malformed jwt; the absence surfaces loudly as a missing account-id header
    return runCatchingCancellable {
        // STANDARD decoder AFTER normalizing -_ to +/ — padBase64 converts to the standard
        // alphabet, so getUrlDecoder() (which REJECTS +/) failed on virtually every real
        // id_token and ChatGPT-Account-ID was never sent (audit 2026-07-18, JVM-repro'd).
        val decoded = Base64.getDecoder().decode(payload.padBase64()).toString(Charsets.UTF_8)
        jwtJson.parseToJsonElement(decoded).jsonObject
    }.getOrDefault(JsonObject(emptyMap()))
}

// 4 = base64 quantum (encoded length is always a multiple of 4).
private const val BASE64_QUANTUM = 4

private fun String.padBase64(): String {
    val normalized = replace('-', '+').replace('_', '/')
    val pad = (BASE64_QUANTUM - normalized.length % BASE64_QUANTUM) % BASE64_QUANTUM
    return normalized + "=".repeat(pad)
}

public fun accountIdFromIdToken(idToken: String?): String? =
    (decodeJwtClaims(idToken)["https://api.openai.com/auth"] as? JsonObject)
        ?.str("chatgpt_account_id")

/** Token strings -> the ~/.codex/auth.json object CodexAuthProvider reads (CLI-compatible shape). */
public fun authJsonFromTokens(
    idToken: String?,
    accessToken: String,
    refreshToken: String?,
    apiKey: String?,
    nowIso: String,
): JsonObject {
    val accountId = accountIdFromIdToken(idToken)
    return buildJsonObject {
        if (apiKey != null) put("OPENAI_API_KEY", JsonPrimitive(apiKey))
        put(
            "tokens",
            buildJsonObject {
                if (idToken != null) put("id_token", JsonPrimitive(idToken))
                put("access_token", JsonPrimitive(accessToken))
                if (refreshToken != null) put("refresh_token", JsonPrimitive(refreshToken))
                if (accountId != null) put("account_id", JsonPrimitive(accountId))
            },
        )
        put("last_refresh", JsonPrimitive(nowIso))
    }
}
