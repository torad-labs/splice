// NEW: xAI SuperGrok browser-login spec. Split from LoginCommand.kt so
// that file is not billed for three vendor OAuth surfaces at once
// (concentration HIGH, 2026-08-19).
package splice.oauth.grok

import splice.core.topology.AuthKind
import splice.core.util.EnvReader
import splice.oauth.LoginSpec
import splice.oauth.LoopbackCallback
import splice.oauth.OAuthAccountFiles
import splice.oauth.TokenExchange
import splice.provider.grok.GrokOAuth
import splice.provider.grok.GrokOAuthEndpoints
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

public class LoginGrok {

    private val oauth = GrokOAuth()
    private val env: EnvReader = EnvReader(System::getenv)

    public fun spec(head: String, authPath: Path, label: String? = null): LoginSpec {
        val pkce = oauth.makeGrokPkce()
        val state = randomToken()
        val nonce = randomToken()
        val clientId = GrokOAuthEndpoints.clientId(env)
        val account = OAuthAccountFiles().loginAccount(AuthKind.GrokOAuth, authPath, label)
        return LoginSpec(
            head = head,
            authorizeUrl = oauth.buildGrokAuthorizeUrl(pkce.challenge, state, nonce, clientId, env),
            callback = LoopbackCallback(
                port = GrokOAuthEndpoints.REDIRECT_PORT,
                path = "/callback",
                expectedState = state,
            ),
            exchange = TokenExchange(
                url = GrokOAuthEndpoints.tokenUrl(env),
                form = { code ->
                    oauth.grokCodeExchangeForm(
                        code = code,
                        verifier = pkce.verifier,
                        challenge = pkce.challenge,
                        clientId = clientId,
                        redirectUri = GrokOAuthEndpoints.REDIRECT_URI,
                    )
                },
                toAuthJson = { body ->
                    oauth.grokAuthJsonFromTokenResponse(
                        body,
                        fallbackRefresh = null,
                        nowMs = System.currentTimeMillis(),
                        nowIso = Instant.now().toString(),
                    ).toString()
                },
            ),
            authPath = authPath,
            account = account,
        )
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

private const val TOKEN_BYTES = 24
