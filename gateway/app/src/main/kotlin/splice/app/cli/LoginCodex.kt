// NEW: ChatGPT / Codex browser-login spec. Split from LoginCommand.kt so
// that file is not billed for three vendor OAuth surfaces at once
// (concentration HIGH, 2026-08-19).
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.app.LoginSpec
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.provider.codex.CodexOAuth
import splice.provider.codex.CodexOAuthEndpoints
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

internal class LoginCodex {

    private val oauth = CodexOAuth()
    private val json = Json { ignoreUnknownKeys = true }
    private val env: EnvReader = EnvReader(System::getenv)

    internal fun spec(head: String, authPath: Path): LoginSpec {
        val pkce = oauth.makePkce()
        val state = randomToken()
        val clientId = CodexOAuthEndpoints.clientId(env)
        return LoginSpec(
            head = head,
            authorizeUrl = oauth.buildAuthorizeUrl(pkce.challenge, state, clientId, env),
            redirectPort = CodexOAuthEndpoints.REDIRECT_PORT,
            redirectPath = "/auth/callback",
            expectedState = state,
            // The OAuth token endpoint is the ISSUER's (auth.openai.com), not the API base_url —
            // env-overridable via CODEX_OAUTH_TOKEN_URL, matching the daemon's refresh path.
            tokenUrl = CodexOAuthEndpoints.tokenUrl(env),
            exchangeForm = { code ->
                oauth.codexCodeExchangeForm(code, pkce.verifier, clientId, CodexOAuthEndpoints.REDIRECT_URI)
            },
            authPath = authPath,
            toAuthJson = { body -> authJson(body) },
        )
    }

    private fun authJson(body: String): String {
        val obj = json.parseToJsonElement(body).jsonObject
        fun s(k: String) = JsonScalars.str(obj, k)
        return oauth.authJsonFromTokens(
            idToken = s("id_token"),
            accessToken = s("access_token").orEmpty(),
            refreshToken = s("refresh_token"),
            apiKey = null,
            nowIso = Instant.now().toString(),
        ).toString()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

private const val TOKEN_BYTES = 24
