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
import splice.core.config.KeyStore
import splice.core.config.StatePaths
import splice.core.launch.LoginOutcomeFile
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.ambiguousHeadMessage
import splice.core.topology.effectiveApiKeyEnv
import splice.core.util.str
import splice.provider.codex.CodexOAuth
import splice.provider.codex.CodexOAuthEndpoints
import splice.provider.grok.GrokOAuth
import splice.provider.grok.GrokOAuthEndpoints
import splice.provider.kimi.KimiDeviceIdentity
import splice.provider.kimi.KimiOAuth
import splice.provider.kimi.KimiOAuthEndpoints
import java.nio.file.Path
import java.nio.file.Paths
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

// FILE SCOPE ON PURPOSE: both are process-wide singletons the spec builders read. As class members
// they would be rebuilt for every LoginCommand instance (setup constructs one per run), and `env`
// in particular is the single System::getenv seam the endpoint helpers are handed.
private val json = Json { ignoreUnknownKeys = true }
private val env: (String) -> String? = System::getenv

/** The `login` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions). Every member keeps the old function's name, so the diff at each
 *  call site is a receiver insertion. */
internal class LoginCommand {

    // The vendor OAuth wire builders (HD-M5 moved them off file scope onto their own types).
    private val codexOAuth = CodexOAuth()
    private val grokOAuth = GrokOAuth()
    private val kimiOAuth = KimiOAuth()

    internal suspend fun login(headArg: String?): Boolean {
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
        val headKey = resolveHeadKey(headArg, topology) ?: return false
        val providerKey = topology.heads[headKey]?.provider
        val provider = providerKey?.let { topology.providers[it] }
        if (provider == null) {
            println("splice: unknown head '$headKey' (heads: ${topology.heads.keys})")
            return false
        }
        val ok = runLoginFlow(headKey, providerKey, provider, topology)
        if (!ok) println("splice: login for '$headKey' did not complete.")
        // THE RECEIPT (2026-08-01). /login runs this detached, so everything printed above is lost and
        // the session that asked never learns the result. One line on disk, read back by the head's
        // /login hook on the next prompt — the only channel that also works for kimi, whose device flow
        // has no browser redirect to confirm in. Written for BOTH outcomes: "it failed" is the message
        // a user most needs and least gets today.
        LoginOutcomeFile.write(
            StatePaths().stateDir,
            headKey,
            if (ok) {
                "signed in — this session is using the new credentials."
            } else {
                "sign-in did not complete. Run `$headKey login` in a terminal to see why."
            },
        )
        return ok
    }

    // kimi uses the RFC 8628 device flow (no loopback); codex/grok use the browser-loopback flow;
    // api-key heads get a masked terminal prompt into the KeyStore (no browser anywhere).
    private suspend fun runLoginFlow(
        headKey: String,
        providerKey: String,
        provider: ProviderConfig,
        topology: Topology,
    ): Boolean =
        when (provider.auth.kind) {
            "kimi-oauth" -> DeviceLoginFlow.run(kimiDeviceSpec(headKey, provider))
            "api-key" -> apiKeyLogin(providerKey, provider)
            else -> specFor(headKey, topology)?.let { OAuthLoginFlow.run(it) } ?: false
        }

    // Masked read into ~/.config/splice/keys.toml — the key never hits shell history, ps, or a
    // transcript. Live daemons pick it up on the next request; restart only refreshes status.
    private fun apiKeyLogin(providerKey: String, provider: ProviderConfig): Boolean {
        val envVar = effectiveApiKeyEnv(providerKey, provider.auth)
        val console = System.console()
        val value = when {
            console == null -> {
                println("splice: no interactive console — pipe it instead:")
                println("  printf '%s' \"\$KEY\" | splice key set $envVar --stdin")
                null
            }
            else -> console.readPassword("$providerKey API key ($envVar): ")?.let { String(it).trim() }
        }
        if (value != null && value.isEmpty()) println("splice: empty key — nothing stored.")
        return !value.isNullOrEmpty() && runCatching {
            val store = KeyStore(KeyStore.defaultPath())
            store.write(envVar, value)
            println("$envVar stored to ${store.path} (0600) — live daemons pick it up on the next request.")
        }.onFailure { System.err.println("splice: failed to store key: ${it.message}") }.isSuccess
    }

    private fun resolveHeadKey(headArg: String?, topology: Topology): String? {
        if (headArg != null) {
            // Accept the topology key or the wrapper command (`<command> login` passes argv[0]).
            val keys = topology.resolveHeadKeys(headArg)
            keys.singleOrNull()?.let { return it }
            println(
                if (keys.isEmpty()) {
                    "splice: unknown head '$headArg' (heads: ${topology.heads.keys})"
                } else {
                    "splice: " + ambiguousHeadMessage(headArg, keys)
                },
            )
            return null
        }
        // No arg: pick the sole sign-in-capable head, else make the user choose — never silently
        // sign into whichever head happens to be declared first.
        val signIn = topology.signInHeads()
        return signIn.singleOrNull() ?: run {
            if (signIn.isEmpty()) {
                println("splice: no sign-in-capable heads in the topology.")
            } else {
                println("splice: which head? " + signIn.joinToString(", ") { "$it login" })
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
            "chatgpt-oauth" -> codexSpec(headKey, provider)
            "grok-oauth" -> grokSpec(headKey, provider)
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

    private fun codexSpec(head: String, provider: ProviderConfig): LoginSpec {
        val pkce = codexOAuth.makePkce()
        val state = randomToken()
        val clientId = CodexOAuthEndpoints.clientId(env)
        return LoginSpec(
            head = head,
            authorizeUrl = codexOAuth.buildAuthorizeUrl(pkce.challenge, state, clientId, env),
            redirectPort = CodexOAuthEndpoints.REDIRECT_PORT,
            redirectPath = "/auth/callback",
            expectedState = state,
            // The OAuth token endpoint is the ISSUER's (auth.openai.com), not the API base_url —
            // env-overridable via CODEX_OAUTH_TOKEN_URL, matching the daemon's refresh path.
            tokenUrl = CodexOAuthEndpoints.tokenUrl(env),
            exchangeForm = { code ->
                codexOAuth.codexCodeExchangeForm(code, pkce.verifier, clientId, CodexOAuthEndpoints.REDIRECT_URI)
            },
            authPath = oauthAuthPath(provider, "~/.codex/auth.json"),
            toAuthJson = { body -> codexAuthJson(body) },
        )
    }

    private fun codexAuthJson(body: String): String {
        val obj = json.parseToJsonElement(body).jsonObject
        fun s(k: String) = obj.str(k)
        return codexOAuth.authJsonFromTokens(
            idToken = s("id_token"),
            accessToken = s("access_token").orEmpty(),
            refreshToken = s("refresh_token"),
            apiKey = null,
            nowIso = Instant.now().toString(),
        ).toString()
    }

    private fun grokSpec(head: String, provider: ProviderConfig): LoginSpec {
        val pkce = grokOAuth.makeGrokPkce()
        val state = randomToken()
        val nonce = randomToken()
        val clientId = GrokOAuthEndpoints.clientId(env)
        return LoginSpec(
            head = head,
            authorizeUrl = grokOAuth.buildGrokAuthorizeUrl(pkce.challenge, state, nonce, clientId, env),
            redirectPort = GrokOAuthEndpoints.REDIRECT_PORT,
            redirectPath = "/callback",
            expectedState = state,
            tokenUrl = GrokOAuthEndpoints.tokenUrl(env),
            exchangeForm = { code ->
                grokOAuth.grokCodeExchangeForm(
                    code = code,
                    verifier = pkce.verifier,
                    challenge = pkce.challenge,
                    clientId = clientId,
                    redirectUri = GrokOAuthEndpoints.REDIRECT_URI,
                )
            },
            authPath = oauthAuthPath(provider, "~/.grok/auth.json"),
            toAuthJson = { body ->
                grokOAuth.grokAuthJsonFromTokenResponse(
                    body,
                    fallbackRefresh = null,
                    nowMs = System.currentTimeMillis(),
                    nowIso = Instant.now().toString(),
                ).toString()
            },
        )
    }

    internal fun oauthAuthPath(provider: ProviderConfig, fallback: String): Path =
        Paths.get(TopologyLoader.expandHome(provider.auth.file ?: fallback))

    private fun kimiDeviceSpec(head: String, provider: ProviderConfig): DeviceLoginSpec {
        val authPath =
            Paths.get(TopologyLoader.expandHome(provider.auth.file ?: "~/.kimi/credentials/kimi-code.json"))
        val identity = KimiDeviceIdentity(deviceIdPath = authPath.resolveSibling("device_id"))
        return DeviceLoginSpec(
            head = head,
            clientId = KimiOAuthEndpoints.CLIENT_ID,
            deviceAuthUrl = KimiOAuthEndpoints.deviceAuthorizationUrl(env),
            tokenUrl = KimiOAuthEndpoints.tokenUrl(env),
            authPath = authPath,
            identityHeaders = identity.headers(),
            toAuthJson = { body ->
                kimiOAuth.kimiAuthJsonFromTokenResponse(body, System.currentTimeMillis()).toString()
            },
        )
    }

    /** Which heads support ANY sign-in flow (browser OAuth or api-key prompt) — keyed off auth.kind,
     *  matching login()'s own dispatch. Stays an EXTENSION (now a member one) so `topology
     *  .signInHeads()` reads unchanged at its call site; `Topology` lives in :core, outside this
     *  slice's fence, so it cannot host the function itself. */
    internal fun Topology.signInHeads(): List<String> =
        heads.entries.filter { (_, h) ->
            val kind = providers[h.provider]?.auth?.kind
            kind?.endsWith("oauth") == true || kind == "api-key"
        }.map { it.key }
}

private const val TOKEN_BYTES = 24
