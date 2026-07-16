// NEW: `splice login <head>` — resolves the head's provider from the topology and runs the right
// OAuth browser flow (codex = ChatGPT, grok = xAI SuperGrok). Both write their CLI-compatible
// auth.json (~/.codex/auth.json, ~/.grok/auth.json), so a subsequent `claudex` / `claude-grok`
// launch is authenticated. :app is wall-exempt for println.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import splice.app.LoginSpec
import splice.app.OAuthLoginFlow
import splice.app.TopologyLoader
import splice.core.topology.Dialect
import splice.core.topology.Topology
import splice.provider.codex.CodexOAuthEndpoints
import splice.provider.codex.authJsonFromTokens
import splice.provider.codex.buildAuthorizeUrl
import splice.provider.codex.makePkce
import splice.provider.grok.GrokOAuthEndpoints
import splice.provider.grok.buildGrokAuthorizeUrl
import splice.provider.grok.grokAuthJsonFromTokenResponse
import splice.provider.grok.grokCodeExchangeForm
import splice.provider.grok.makeGrokPkce
import java.nio.file.Paths
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

public object LoginCommand {

    private val json = Json { ignoreUnknownKeys = true }
    private val env: (String) -> String? = System::getenv

    @Suppress("CyclomaticComplexMethod")
    public suspend fun login(headArg: String?) {
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
        val headKey = headArg ?: topology.heads.keys.firstOrNull()
        val head = headKey?.let { topology.heads[it] }
        val provider = head?.let { topology.providers[it.provider] }
        if (head == null || provider == null) {
            println("splice: unknown head '$headArg' (heads: ${topology.heads.keys})")
            return
        }
        val spec = when (provider.auth.kind) {
            "chatgpt-oauth" -> codexSpec(headKey, provider.baseUrl)
            "grok-oauth" -> grokSpec(headKey)
            else -> {
                println("splice: head '$headKey' uses ${provider.auth.kind} auth — no browser login for that kind.")
                return
            }
        }
        val ok = OAuthLoginFlow.run(spec)
        if (!ok) println("splice: login for '$headKey' did not complete.")
    }

    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun codexSpec(head: String, baseUrl: String): LoginSpec {
        val pkce = makePkce()
        val state = randomToken()
        val clientId = CodexOAuthEndpoints.clientId(env)
        val tokenUrl = "${baseUrl.removeSuffix("/codex")}/oauth/token"
        return LoginSpec(
            head = head,
            authorizeUrl = buildAuthorizeUrl(pkce.challenge, state, clientId, env),
            redirectPort = CodexOAuthEndpoints.REDIRECT_PORT,
            redirectPath = "/auth/callback",
            expectedState = state,
            tokenUrl = tokenUrl,
            exchangeForm = codexExchangeForm(clientId, pkce.verifier),
            authPath = Paths.get(TopologyLoader.expandHome("~/.codex/auth.json")),
            toAuthJson = { body -> codexAuthJson(body) },
        )
    }

    private fun codexExchangeForm(clientId: String, verifier: String): String = listOf(
        "grant_type" to "authorization_code",
        "code" to "{CODE}",
        "redirect_uri" to CodexOAuthEndpoints.REDIRECT_URI,
        "client_id" to clientId,
        "code_verifier" to verifier,
    ).joinToString("&") { (k, v) -> "$k=$v" }

    private fun codexAuthJson(body: String): String {
        val obj = json.parseToJsonElement(body).jsonObject
        fun s(k: String) = obj[k]?.jsonPrimitive?.content
        return authJsonFromTokens(
            idToken = s("id_token"),
            accessToken = s("access_token").orEmpty(),
            refreshToken = s("refresh_token"),
            apiKey = null,
            nowIso = Instant.now().toString(),
        ).toString()
    }

    private fun grokSpec(head: String): LoginSpec {
        val pkce = makeGrokPkce()
        val state = randomToken()
        val nonce = randomToken()
        val clientId = GrokOAuthEndpoints.clientId(env)
        return LoginSpec(
            head = head,
            authorizeUrl = buildGrokAuthorizeUrl(pkce.challenge, state, nonce, clientId, env),
            redirectPort = GrokOAuthEndpoints.REDIRECT_PORT,
            redirectPath = "/callback",
            expectedState = state,
            tokenUrl = GrokOAuthEndpoints.tokenUrl(env),
            exchangeForm = grokCodeExchangeForm(
                code = "{CODE}",
                verifier = pkce.verifier,
                challenge = pkce.challenge,
                clientId = clientId,
                redirectUri = GrokOAuthEndpoints.REDIRECT_URI,
            ),
            authPath = Paths.get(TopologyLoader.expandHome("~/.grok/auth.json")),
            toAuthJson = { body ->
                grokAuthJsonFromTokenResponse(
                    body,
                    fallbackRefresh = null,
                    nowMs = System.currentTimeMillis(),
                    nowIso = Instant.now().toString(),
                ).toString()
            },
        )
    }

    private const val TOKEN_BYTES = 24
}

/** Convenience: which heads even support browser login (for `splice login` with no arg help). */
public fun Topology.oauthHeads(): List<String> =
    heads.entries.filter { (_, h) ->
        providers[h.provider]?.let { it.dialect == Dialect.OPENAI_RESPONSES && it.auth.kind.endsWith("oauth") } == true
    }.map { it.key }
