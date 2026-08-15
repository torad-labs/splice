// NEW: what sign-in surface a head gets — the /login command + UX wording, and for api-key
// heads the bare-token capture spec (factored out of Daemon.kt, detekt LargeClass).
// OAuth heads get the browser flow; api-key heads get the masked-prompt wording plus — only for
// providers with a known, prose-safe token shape — the capture hook. The OAuth kind constants
// live in Daemon.kt (same package); API_KEY is only needed here.
package splice.app

import splice.core.launch.TokenCaptureSpec
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.effectiveApiKeyEnv

internal const val API_KEY = "api-key"

// Display labels for api-key providers (the /login UX + capture-hook reasons).
private val API_KEY_LABELS = mapOf(
    "openrouter" to "OpenRouter",
    "moonshot" to "Moonshot",
    "fireworks" to "Fireworks",
    "openai" to "OpenAI",
)

// Bare-token capture patterns (bash ERE, matched as the WHOLE prompt). Only shapes that cannot
// collide with ordinary prose get an entry — everything else falls back to masked login only.
private val API_KEY_TOKEN_PATTERNS = mapOf(
    "openrouter" to "sk-or-[A-Za-z0-9_-]{20,}",
)

internal data class SignInPlan(
    val command: String,
    val label: String,
    val viaBrowser: Boolean,
    val tokenCapture: TokenCaptureSpec?,
)

/** The api-key branch, split out so [signInPlan] stays under detekt's complexity ceiling. */
private fun apiKeySignIn(providerCfg: ProviderConfig, head: HeadConfig, command: String): SignInPlan {
    val label = API_KEY_LABELS[head.provider] ?: head.provider
    // Capture only where the token shape is KNOWN and unambiguous (v1: OpenRouter).
    val capture = API_KEY_TOKEN_PATTERNS[head.provider]?.let { pattern ->
        TokenCaptureSpec(effectiveApiKeyEnv(head.provider, providerCfg.auth), pattern, label)
    }
    // EVERY head keeps /login — each one has its own sign-in path, and being in the topology is
    // what makes it supported. What differs is only the WORDING: a head that can capture a pasted
    // token gets the in-session path; one that cannot is told to run `<command> login` in a
    // terminal. Neither spawns anything, because a detached api-key login has no TTY.
    // (ONE PROVIDER AT A TIME applies to CAPTURE patterns — not to whether /login exists.)
    return SignInPlan(command, label, viaBrowser = false, tokenCapture = capture)
}

internal fun signInPlan(providerCfg: ProviderConfig, head: HeadConfig, key: String): SignInPlan {
    val command = "${head.claude.command ?: key} login"
    return when (providerCfg.auth.kind) {
        CHATGPT_OAUTH -> SignInPlan(command, "Codex (ChatGPT)", viaBrowser = true, tokenCapture = null)
        GROK_OAUTH -> SignInPlan(command, "Grok (xAI)", viaBrowser = true, tokenCapture = null)
        KIMI_OAUTH -> SignInPlan(command, "Kimi (Moonshot)", viaBrowser = true, tokenCapture = null)
        API_KEY -> apiKeySignIn(providerCfg, head, command)
        // A client-auth head has NO splice-run sign-in, and the command must be EMPTY — not a
        // plausible-looking one. A non-blank command makes LoginInterception.wire plant splice's
        // own /login into this head's config dir plus a UserPromptSubmit hook, and that flow ends
        // at `<command> login`, which for this kind prints "no browser login for that kind" and
        // fails forever. On the ONE head that keeps the client's native /login enabled, that is a
        // decoy competing with the door that actually works. Empty also drops TurnDriver's
        // "— run: <command>" clause from a 401, so the upstream's own message stands instead of an
        // instruction that cannot succeed.
        CLIENT -> SignInPlan("", "Claude (client's own login)", viaBrowser = false, tokenCapture = null)
        else -> SignInPlan("", "", viaBrowser = true, tokenCapture = null)
    }
}
