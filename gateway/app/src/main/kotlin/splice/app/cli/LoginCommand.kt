// NEW: `splice login <head>` — resolves the head's provider from the topology and runs the right
// OAuth browser flow (codex = ChatGPT, grok = xAI SuperGrok). Both write their CLI-compatible
// auth.json (~/.codex/auth.json, ~/.grok/auth.json), so a subsequent `claudex` / `claude-grok`
// launch is authenticated. Vendor spec builders live in LoginCodex/LoginGrok/LoginKimi/LoginMuse
// (concentration HIGH, 2026-08-19). :app is wall-exempt for println.
package splice.app.cli

import splice.app.DeviceLoginFlow
import splice.app.LoginIo
import splice.app.LoginObserver
import splice.app.LoginSpec
import splice.app.OAuthLoginFlow
import splice.app.TopologyLoader
import splice.app.auth.OAuthAccountRefused
import splice.app.auth.OAuthLoginAccount
import splice.core.topology.AuthKindRegistry
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyMessages
import splice.core.util.SafeFailureText
import java.nio.file.Path
import java.nio.file.Paths

/** The `login` verb as a cohesive unit of behavior (Kotlin style law, 2026-08-15: main sources
 *  carry no top-level functions). Every member keeps the old function's name, so the diff at each
 *  call site is a receiver insertion. */
internal class LoginCommand {

    private val codex = LoginCodex()
    private val grok = LoginGrok()
    private val kimi = LoginKimi()
    private val muse = LoginMuse()
    private val loginIo = LoginIo()

    internal suspend fun login(headArg: String?, label: String? = null): Boolean {
        val topology = TopologyLoader.loadOrMaterialize(TopologyLoader.configPath())
        val headKey = resolveHeadKey(headArg, topology) ?: return false
        val providerKey = topology.heads[headKey]?.provider
        val provider = providerKey?.let { topology.providers[it] }
        if (provider == null) {
            println("splice: unknown head '$headKey' (heads: ${topology.heads.keys})")
            return false
        }
        val result = runLoginAttempt(headKey, provider, topology, label)
        if (!result.ok) println("splice: login for '$headKey' did not complete.")
        loginIo.writeLoginOutcome(headKey, result.ok, result.account)
        return result.ok
    }

    // kimi uses the RFC 8628 device flow (no loopback); codex/grok use the browser-loopback flow;
    // api-key heads get a masked terminal prompt into the KeyStore (no browser anywhere).
    // Internal for the DR-97 arm (the head-key derivation is a call-site contract).
    internal suspend fun runLoginFlow(
        headKey: String,
        provider: ProviderConfig,
        topology: Topology,
        label: String? = null,
    ): Boolean = runLoginAttempt(headKey, provider, topology, label).ok

    /** V4-132: the console's login-id/poll seam (LoginSessions) reuses THIS dispatch rather than
     *  re-deriving auth-kind -> flow routing a second time — [runLoginAttempt] is already the
     *  single source [login] and [runLoginFlow] share. Widened to `internal` (from `private`) and
     *  given an [observer] for exactly that call; every existing call site passes null and is
     *  unaffected. api-key heads have no off-request answer (the flow needs an interactive
     *  console), so an observed api-key login degrades to [LoginResult] false through the same
     *  `System.console() == null` branch [splice.app.LoginIo.apiKeyLogin] already takes headless —
     *  never a crash, never a second dispatch table. */
    internal suspend fun runLoginAttempt(
        headKey: String,
        provider: ProviderConfig,
        topology: Topology,
        label: String?,
        observer: LoginObserver? = null,
    ): LoginResult = try {
        when (provider.auth.kind) {
            "kimi-oauth" -> {
                val spec = kimi.spec(headKey, oauthAuthPath(provider), label)
                LoginResult(DeviceLoginFlow.run(spec, observer = observer), spec.account)
            }
            "muse-oauth" -> {
                val spec = muse.spec(headKey, oauthAuthPath(provider), label)
                LoginResult(DeviceLoginFlow.run(spec, observer = observer), spec.account)
            }
            // DR-97: the HEAD key, not the provider key — the daemon reads
            // effectiveApiKeyEnv(ctx.key), so the prompt must store under that var.
            "api-key" -> if (label == null) {
                LoginResult(loginIo.apiKeyLogin(headKey, provider))
            } else {
                println("splice: --label is only supported for OAuth heads")
                LoginResult(false)
            }
            else -> {
                val spec = specFor(headKey, topology, label)
                if (spec == null) LoginResult(false) else LoginResult(OAuthLoginFlow.run(spec, observer), spec.account)
            }
        }
    } catch (e: OAuthAccountRefused) {
        println("splice: ${e.reason}")
        LoginResult(false)
    } catch (e: IllegalArgumentException) {
        println("splice: ${SafeFailureText.render(e)}")
        LoginResult(false)
    }

    // internal (V4-132): LoginSessions (splice.app) reads .ok/.account off runLoginAttempt's result.
    internal data class LoginResult(val ok: Boolean, val account: OAuthLoginAccount? = null)

    private fun resolveHeadKey(headArg: String?, topology: Topology): String? {
        if (headArg != null) {
            // Accept the topology key or the wrapper command (`<command> login` passes argv[0]).
            val keys = topology.resolveHeadKeys(headArg)
            keys.singleOrNull()?.let { return it }
            println(
                if (keys.isEmpty()) {
                    "splice: unknown head '$headArg' (heads: ${topology.heads.keys})"
                } else {
                    "splice: " + TopologyMessages.ambiguousHeadMessage(headArg, keys)
                },
            )
            return null
        }
        // No arg: pick the sole sign-in-capable head, else make the user choose — never silently
        // sign into whichever head happens to be declared first.
        val signIn = signInHeads(topology)
        return signIn.singleOrNull() ?: run {
            if (signIn.isEmpty()) {
                println("splice: no sign-in-capable heads in the topology.")
            } else {
                println("splice: which head? " + signIn.joinToString(", ") { "$it login" })
            }
            null
        }
    }

    private fun specFor(headKey: String, topology: Topology, label: String?): LoginSpec? {
        val head = topology.heads[headKey]
        val provider = head?.let { topology.providers[it.provider] }
        if (head == null || provider == null) {
            println("splice: unknown head '$headKey' (heads: ${topology.heads.keys})")
            return null
        }
        return when (provider.auth.kind) {
            "chatgpt-oauth" -> codex.spec(headKey, oauthAuthPath(provider), label)
            "grok-oauth" -> grok.spec(headKey, oauthAuthPath(provider), label)
            else -> {
                println("splice: head '$headKey' uses ${provider.auth.kind} auth — no browser login for that kind.")
                null
            }
        }
    }

    /** The file a login writes: the provider's explicit auth.file, else the registry's splice-owned
     *  default for its kind (AuthKind header, 2026-09-05). The native apps' files are never a
     *  fallback here — an operator opts into one by naming it. Only reached for registered OAuth
     *  kinds, whose default is non-null by construction. */
    internal fun oauthAuthPath(provider: ProviderConfig): Path {
        val file = provider.auth.file
            ?: requireNotNull(AuthKindRegistry.defaultAuthFileFor(provider.auth.kind)) {
                "no default credential file for auth kind '${provider.auth.kind}'"
            }
        return Paths.get(TopologyLoader.expandHome(file))
    }

    /** Which heads support ANY sign-in flow (browser OAuth or api-key prompt) — keyed off auth.kind,
     *  matching login()'s own dispatch. HD-20 banned extension declarations; `Topology` lives in
     *  :core, outside this slice's fence, so it cannot host the function as a member either. The
     *  former receiver is therefore the first PARAMETER — same name, same body, one call site. */
    internal fun signInHeads(topology: Topology): List<String> =
        topology.heads.entries.filter { (_, h) ->
            val kind = topology.providers[h.provider]?.auth?.kind
            kind?.endsWith("oauth") == true || kind == "api-key"
        }.map { it.key }
}
