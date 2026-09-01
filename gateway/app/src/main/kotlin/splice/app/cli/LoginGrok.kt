// NEW: xAI SuperGrok browser-login spec. Split from LoginCommand.kt so
// that file is not billed for three vendor OAuth surfaces at once
// (concentration HIGH, 2026-08-19).
package splice.app.cli

import splice.app.LoginSpec
import splice.core.util.EnvReader
import splice.provider.grok.GrokOAuth
import splice.provider.grok.GrokOAuthEndpoints
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

internal class LoginGrok {

    private val oauth = GrokOAuth()
    private val env: EnvReader = EnvReader(System::getenv)

    internal fun spec(head: String, authPath: Path): LoginSpec {
        val pkce = oauth.makeGrokPkce()
        val state = randomToken()
        val nonce = randomToken()
        val clientId = GrokOAuthEndpoints.clientId(env)
        return LoginSpec(
            head = head,
            authorizeUrl = oauth.buildGrokAuthorizeUrl(pkce.challenge, state, nonce, clientId, env),
            redirectPort = GrokOAuthEndpoints.REDIRECT_PORT,
            redirectPath = "/callback",
            expectedState = state,
            tokenUrl = GrokOAuthEndpoints.tokenUrl(env),
            exchangeForm = { code ->
                oauth.grokCodeExchangeForm(
                    code = code,
                    verifier = pkce.verifier,
                    challenge = pkce.challenge,
                    clientId = clientId,
                    redirectUri = GrokOAuthEndpoints.REDIRECT_URI,
                )
            },
            authPath = authPath,
            toAuthJson = { body ->
                oauth.grokAuthJsonFromTokenResponse(
                    body,
                    fallbackRefresh = null,
                    nowMs = System.currentTimeMillis(),
                    nowIso = Instant.now().toString(),
                ).toString()
            },
        )
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

private const val TOKEN_BYTES = 24
