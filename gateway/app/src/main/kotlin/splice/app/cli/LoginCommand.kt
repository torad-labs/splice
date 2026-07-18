// NEW: `splice login <head>` — resolves the head's provider from the topology and runs the right
// OAuth browser flow (codex = ChatGPT, grok = xAI SuperGrok). Both write their CLI-compatible
// auth.json (~/.codex/auth.json, ~/.grok/auth.json), so a subsequent `claudex` / `claude-grok`
// launch is authenticated. :app is wall-exempt for println.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.app.DeviceLoginFlow
import splice.app.DeviceLoginSpec
import splice.app.LoginSpec
import splice.app.OAuthLoginFlow
import splice.app.TopologyLoader
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.str
import splice.provider.codex.CodexOAuthEndpoints
import splice.provider.codex.authJsonFromTokens
import splice.provider.codex.buildAuthorizeUrl
import splice.provider.codex.codexCodeExchangeForm
import splice.provider.codex.makePkce
import splice.provider.grok.GrokOAuthEndpoints
import splice.provider.grok.buildGrokAuthorizeUrl
import splice.provider.grok.grokAuthJsonFromTokenResponse
import splice.provider.grok.grokCodeExchangeForm
import splice.provider.grok.makeGrokPkce
import splice.provider.kimi.KimiDeviceIdentity
import splice.provider.kimi.KimiOAuthEndpoints
import splice.provider.kimi.kimiAuthJsonFromTokenResponse
import java.nio.file.Paths
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

private val json = Json { ignoreUnknownKeys = true }
private val env: (String) -> String? = System::getenv

internal suspend fun login(headArg: String?): Boolean {
    val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
    val headKey = resolveHeadKey(headArg, topology) ?: return false
    val provider = topology.heads[headKey]?.let { topology.providers[it.provider] }
    if (provider == null) {
        println("splice: unknown head '$headKey' (heads: ${topology.heads.keys})")
        return false
    }
    val ok = runLoginFlow(headKey, provider, topology)
    if (!ok) println("splice: login for '$headKey' did not complete.")
    return ok
}

// kimi uses the RFC 8628 device flow (no loopback); codex/grok use the browser-loopback flow.
private suspend fun runLoginFlow(headKey: String, provider: ProviderConfig, topology: Topology): Boolean =
    when (provider.auth.kind) {
        "kimi-oauth" -> DeviceLoginFlow.run(kimiDeviceSpec(headKey, provider))
        else -> specFor(headKey, topology)?.let { OAuthLoginFlow.run(it) } ?: false
    }

private fun resolveHeadKey(headArg: String?, topology: Topology): String? {
    // No arg: pick the sole browser-login head, else make the user choose — never silently
    // sign into whichever head happens to be declared first.
    val oauth = topology.oauthHeads()
    return headArg ?: oauth.singleOrNull() ?: run {
        if (oauth.isEmpty()) {
            println("splice: no browser-login heads in the topology.")
        } else {
            println("splice: which head? " + oauth.joinToString(", ") { "$it login" })
        }
        null
    }
}

private fun specFor(headKey: String, topology: Topology): LoginSpec? {
    val head = topology.heads[headKey]
    val provider = head?.let { topology.providers[it.provider] }
    if (head == null || provider == null) {
        println("splice: unknown head '$headKey' (heads: ${topology.heads.keys})")
        return null
    }
    return when (provider.auth.kind) {
        "chatgpt-oauth" -> codexSpec(headKey)
        "grok-oauth" -> grokSpec(headKey)
        else -> {
            println("splice: head '$headKey' uses ${provider.auth.kind} auth — no browser login for that kind.")
            null
        }
    }
}

private fun randomToken(): String {
    val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun codexSpec(head: String): LoginSpec {
    val pkce = makePkce()
    val state = randomToken()
    val clientId = CodexOAuthEndpoints.clientId(env)
    return LoginSpec(
        head = head,
        authorizeUrl = buildAuthorizeUrl(pkce.challenge, state, clientId, env),
        redirectPort = CodexOAuthEndpoints.REDIRECT_PORT,
        redirectPath = "/auth/callback",
        expectedState = state,
        // The OAuth token endpoint is the ISSUER's (auth.openai.com), not the API base_url —
        // env-overridable via CODEX_OAUTH_TOKEN_URL, matching the daemon's refresh path.
        tokenUrl = CodexOAuthEndpoints.tokenUrl(env),
        exchangeForm = { code ->
            codexCodeExchangeForm(code, pkce.verifier, clientId, CodexOAuthEndpoints.REDIRECT_URI)
        },
        authPath = Paths.get(TopologyLoader.expandHome("~/.codex/auth.json")),
        toAuthJson = { body -> codexAuthJson(body) },
    )
}

private fun codexAuthJson(body: String): String {
    val obj = json.parseToJsonElement(body).jsonObject
    fun s(k: String) = obj.str(k)
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
        exchangeForm = { code ->
            grokCodeExchangeForm(
                code = code,
                verifier = pkce.verifier,
                challenge = pkce.challenge,
                clientId = clientId,
                redirectUri = GrokOAuthEndpoints.REDIRECT_URI,
            )
        },
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

private fun kimiDeviceSpec(head: String, provider: ProviderConfig): DeviceLoginSpec {
    val authPath = Paths.get(TopologyLoader.expandHome(provider.auth.file ?: "~/.kimi/credentials/kimi-code.json"))
    val identity = KimiDeviceIdentity(deviceIdPath = authPath.resolveSibling("device_id"))
    return DeviceLoginSpec(
        head = head,
        clientId = KimiOAuthEndpoints.CLIENT_ID,
        deviceAuthUrl = KimiOAuthEndpoints.deviceAuthorizationUrl(env),
        tokenUrl = KimiOAuthEndpoints.tokenUrl(env),
        authPath = authPath,
        identityHeaders = identity.headers(),
        toAuthJson = { body -> kimiAuthJsonFromTokenResponse(body, System.currentTimeMillis()).toString() },
    )
}

private const val TOKEN_BYTES = 24

/** Which heads support browser login — keyed off auth.kind, matching login()'s own dispatch. */
public fun Topology.oauthHeads(): List<String> =
    heads.entries.filter { (_, h) ->
        providers[h.provider]?.auth?.kind?.endsWith("oauth") == true
    }.map { it.key }
