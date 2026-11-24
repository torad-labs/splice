// NEW: head-catalog (docs/specs/door-default.md, decision 2). What puts a Claude Code in its gateway mode, the way it
// runs as a client of any LLM gateway (code.claude.com/docs/en/llm-gateway-protocol.md, read Nov 19, 2026): the
// gateway's address as ANTHROPIC_BASE_URL (it speaks the Anthropic Messages format), its key as ANTHROPIC_AUTH_TOKEN (a
// bearer, so no API-key approval prompt shows), model discovery on, so `/model` is filled from the gateway's
// /v1/models, and the model the session starts on. Discovery does not run while any provider switch is set, so every
// one is taken out of what the session inherits, with the Mantle door's variables, every other Anthropic credential,
// and every variable that lays a model list of its own beside the gateway's (the tiers, the small model, a custom
// option). Facts of Claude Code's dialect, so they live here with the others.
package splice.client.desktop

import splice.client.ClaudeModelLists

public class GatewayMode {
    /** What a gateway-mode launch sets: [base] the gateway's address, [key] its key, and [model] the selector the
     *  session starts on, when there is one. */
    public fun env(base: String, key: String, model: String?): Map<String, String> =
        settings(base, model) + (GATEWAY_TOKEN to key)

    /** What a gateway-mode launch sets that is no secret: [env] without the key, which a `--settings` overlay may carry
     *  (#791): an overlay is an argument every local user can read, and the key stays in the process environment. */
    public fun settings(base: String, model: String?): Map<String, String> = buildMap {
        put(GATEWAY_BASE_URL, base)
        put(GATEWAY_DISCOVERY, "1")
        model?.let { put(DESKTOP_MODEL, it) }
    }

    /** What a gateway-mode launch takes out of the environment it inherits: every way past the gateway, and every
     *  model list but the gateway's. */
    public val unset: List<String> =
        (providerSwitches + ALL_SWITCHES).distinct() + CREDENTIALS + FIRST_PARTY_ENV + ClaudeModelLists.variables

    /** [unset] as a settings file's `env` spells it taken out: a person's settings `env` is applied over the process
     *  environment, so a variable this launch unsets can come back from there. Each switch is `0`, which Claude Code
     *  reads as off, and every other variable is empty, which it reads as not set (#791). No value here is a secret. */
    public val cleared: Map<String, String> = unset.associateWith { if (it in ALL_SWITCHES) SWITCH_OFF else "" }

    /** Whether [env], what a launch sets, puts its session in gateway mode: a base URL, a bearer, discovery on, and
     *  no provider switch. */
    public fun on(env: Map<String, String>): Boolean =
        env[GATEWAY_BASE_URL] != null && env[GATEWAY_TOKEN] != null && env[GATEWAY_DISCOVERY] == "1" &&
            ALL_SWITCHES.none { env[it].orEmpty() !in setOf("", "0") }
}

/** Where a gateway-mode session sends its turns, its key, and the switch that has it ask the gateway for `/model`. */
public const val GATEWAY_BASE_URL: String = "ANTHROPIC_BASE_URL"
public const val GATEWAY_TOKEN: String = "ANTHROPIC_AUTH_TOKEN"
public const val GATEWAY_DISCOVERY: String = "CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY"

/** The headers Claude Code adds to every request, one per line, where a launch names its client wall to the gateway. */
public const val GATEWAY_HEADERS: String = "ANTHROPIC_CUSTOM_HEADERS"

/** Claude Code's word that ANTHROPIC_BASE_URL is Anthropic's own API behind another address (read in the 2.1.326
 *  client, where it is that variable's declared companion): set only by a head's launch that reaches Anthropic, and
 *  taken out of a session on the gateway, which is not Anthropic. */
public const val FIRST_PARTY_ENV: String = "_CLAUDE_CODE_ASSUME_FIRST_PARTY_BASE_URL"

// Claude Code's provider switches: discovery does not run while one is set.
private val ALL_SWITCHES =
    listOf("CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY", "CLAUDE_CODE_USE_MANTLE")

// How a settings file turns a provider switch off.
private const val SWITCH_OFF = "0"

// The other credentials Claude Code would send in place of the gateway's key.
private val CREDENTIALS = listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_OAUTH_REFRESH_TOKEN")
