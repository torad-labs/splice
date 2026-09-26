// NEW: what sign-in surface a head gets — the /login command + UX wording, and for api-key
// heads the bare-token capture spec (factored out of Daemon.kt, detekt LargeClass).
// OAuth heads get the browser flow; api-key heads get the masked-prompt wording plus — only for
// providers with a known, prose-safe token shape — the capture hook. Labels and token patterns
// live on AuthKind / ApiKeyProviderRegistry rows (V4-21).
package splice.app.auth

import splice.client.login.TokenCaptureSpec
import splice.core.topology.ApiKeyProviderRegistry
import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig

internal const val API_KEY = "api-key"

private val PORTABLE_WRAPPER_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/** [command] is the /login surface's (`<wrapper> login`, blank where splice plants no /login).
 *  [credentialFix] is what a turn's 401 names ("— run: <this>"): the OAuth login, or for an api-key
 *  head `splice key set <ENV>` on the var the daemon reads, taken on its next request with no restart
 *  (V4-227). Blank where splice has no fix to name, so the upstream's own message stands. */
internal data class SignInPlan(
    val command: String,
    val label: String,
    val viaBrowser: Boolean,
    val tokenCapture: TokenCaptureSpec?,
    val credentialFix: String,
)

/** Builds the per-head [SignInPlan]. A constructed collaborator rather than a free function
 *  (Kotlin style law, 2026-08-15); stateless, so Daemon holds one and the tests build their own. */
internal class SignInPlanner {

    internal fun signInPlan(providerCfg: ProviderConfig, head: HeadConfig, key: String): SignInPlan {
        val wrapper = head.claude.command ?: key
        val command = "$wrapper login"
        return when (val kind = AuthKindRegistry.from(providerCfg.auth.kind)) {
            is AuthKind.OAuth -> oauthSignIn(wrapper, kind.signInLabel)
            AuthKind.Client -> SignInPlan("", kind.signInLabel, false, tokenCapture = null, credentialFix = "")
            null -> if (providerCfg.auth.kind == API_KEY) {
                apiKeySignIn(providerCfg, head, command, key)
            } else {
                SignInPlan("", "", viaBrowser = true, tokenCapture = null, credentialFix = "")
            }
        }
    }

    private fun oauthSignIn(wrapper: String, label: String): SignInPlan {
        require(wrapper.matches(PORTABLE_WRAPPER_NAME)) {
            "OAuth wrapper must be a bare portable command name: '$wrapper'"
        }
        val login = "$wrapper login"
        return SignInPlan(login, label, viaBrowser = true, tokenCapture = null, credentialFix = login)
    }

    /** The api-key branch, split out so [signInPlan] stays under detekt's complexity ceiling. */
    private fun apiKeySignIn(providerCfg: ProviderConfig, head: HeadConfig, command: String, key: String): SignInPlan {
        val row = ApiKeyProviderRegistry.row(head.provider)
        val label = row?.label ?: head.provider
        // Capture only where the token shape is KNOWN and unambiguous (v1: OpenRouter). The token
        // SHAPE is the provider's; the env var is the HEAD's (DR-97) — every daemon arm and doctor
        // read effectiveApiKeyEnv(ctx.key), and a provider-key derivation stored the captured key
        // under a var nothing reads (login success, head 401s, doctor "not set").
        val env = providerCfg.auth.effectiveApiKeyEnv(key)
        val capture = row?.tokenPattern?.let { pattern -> TokenCaptureSpec(env, pattern, label) }
        // EVERY head keeps /login — each one has its own sign-in path, and being in the topology is
        // what makes it supported. What differs is only the WORDING: a head that can capture a pasted
        // token gets the in-session path; one that cannot is told to run `<command> login` in a
        // terminal. Neither spawns anything, because a detached api-key login has no TTY.
        // (ONE PROVIDER AT A TIME applies to CAPTURE patterns — not to whether /login exists.)
        val fix = "splice key set $env"
        return SignInPlan(command, label, viaBrowser = false, tokenCapture = capture, credentialFix = fix)
    }
}
