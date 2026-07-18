// PORT-OF: server/src/config.mjs DEFAULTS + ENV_MAP + NUMBER_KEYS/BOOL_KEYS + RESTART_REQUIRED_KEYS
// @ 4ca99f7 — invariants: env alias order matters (first present name wins); maxInflight accepts
// unlimited/off/none/'' as 0; bool coercion is /^(1|true|yes|on)$/i; RESTART_REQUIRED =
// [port, grokPort, controlPort, upstreamTimeoutMs]. DELIBERATELY NOT PORTED (plan): the vestigial
// anthropicUpstream + claudeCredentialsPath keys (nothing read them; claudithos leftovers).
package splice.core.config

public enum class KnobKind { STRING, NUMBER, BOOL }

// HONESTY (audit 2026-07-18): nearly every knob is SNAPSHOTTED at Daemon.start into constructed
// objects (providers, watchdog budgets, auth caches, warn thresholds) — so nearly every knob is
// restartRequired. The ONLY genuinely hot knob is maxInflight (read via lambda per admission).
// If you make a knob live-read, remove its restartRequired flag in the same commit.
public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
    public val envNames: List<String>,
    public val default: Any?,
    public val restartRequired: Boolean = false,
) {
    PORT("port", KnobKind.NUMBER, listOf("CODEX_PROXY_PORT"), 3099L, restartRequired = true),
    CHATGPT_API_BASE(
        "chatgptApiBase",
        KnobKind.STRING,
        listOf("CHATGPT_API_BASE"),
        "https://chatgpt.com/backend-api/codex",
        restartRequired = true,
    ),
    CODEX_AUTH_PATH(
        "codexAuthPath",
        KnobKind.STRING,
        listOf("CODEX_AUTH_PATH"),
        "~/.codex/auth.json",
        restartRequired = true,
    ),
    PINNED_MODEL(
        "pinnedModel",
        KnobKind.STRING,
        listOf("CLAUDEX_PINNED_MODEL", "CLAUDEX_MODEL"),
        "gpt-5.6-sol",
        restartRequired = true,
    ),
    COMPACT_MODEL(
        "compactModel",
        KnobKind.STRING,
        listOf("CLAUDEX_COMPACT_MODEL"),
        "",
        restartRequired = true,
    ),
    EFFORT(
        "effort",
        KnobKind.STRING,
        listOf("CLAUDEX_REASONING_EFFORT", "CODEX_REASONING_EFFORT"),
        null,
        restartRequired = true,
    ),

    // Default detailed: the fullest public reasoning text the Responses backends expose.
    SUMMARY(
        "summary",
        KnobKind.STRING,
        listOf("CLAUDEX_REASONING_SUMMARY", "CODEX_REASONING_SUMMARY"),
        "detailed",
        restartRequired = true,
    ),
    SHOW_REASONING(
        "showReasoning",
        KnobKind.STRING,
        listOf("CLAUDEX_SHOW_REASONING", "CODEX_SHOW_REASONING"),
        "text",
        restartRequired = true,
    ),

    // OFF for every head (codex/grok/openai). Input-injecting prior encrypted CoT thins fresh
    // reasoning depth (~4x measured). Include-encrypted handle is separate and still ON when
    // showReasoning is on. Opt in only with CLAUDEX_REPLAY_REASONING=1.
    REPLAY_REASONING(
        "replayReasoning",
        KnobKind.BOOL,
        listOf("CLAUDEX_REPLAY_REASONING", "CODEX_REPLAY_REASONING"),
        false,
        restartRequired = true,
    ),
    MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), 0L),
    UPSTREAM_RETRIES(
        "upstreamRetries",
        KnobKind.NUMBER,
        listOf("CLAUDEX_UPSTREAM_RETRIES"),
        2L,
        restartRequired = true,
    ),
    UPSTREAM_TIMEOUT_MS(
        "upstreamTimeoutMs",
        KnobKind.NUMBER,
        listOf("CLAUDEX_UPSTREAM_TIMEOUT_MS"),
        900_000L,
        restartRequired = true,
    ),
    FIRST_BYTE_TIMEOUT_MS(
        "firstByteTimeoutMs",
        KnobKind.NUMBER,
        listOf("CLAUDEX_FIRST_BYTE_TIMEOUT_MS"),
        300_000L,
        restartRequired = true,
    ),
    STREAM_IDLE_MS(
        "streamIdleMs",
        KnobKind.NUMBER,
        listOf("CLAUDEX_STREAM_IDLE_MS"),
        180_000L,
        restartRequired = true,
    ),
    AUTH_CACHE_MS(
        "authCacheMs",
        KnobKind.NUMBER,
        listOf("CLAUDEX_AUTH_CACHE_MS"),
        60_000L,
        restartRequired = true,
    ),
    DEBUG(
        "debug",
        KnobKind.BOOL,
        listOf("CLAUDEX_DEBUG", "CODEX_PROXY_DEBUG"),
        false,
        restartRequired = true,
    ),
    CONTEXT_WINDOW_OVERRIDE(
        "contextWindowOverride",
        KnobKind.NUMBER,
        listOf("CODEX_MODEL_CONTEXT_WINDOW"),
        null,
        restartRequired = true,
    ),
    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT"), 3100L, restartRequired = true),
    GROK_MODEL(
        "grokModel",
        KnobKind.STRING,
        listOf("CLAUDE_GROK_MODEL", "CLAUDE_GROK_PINNED_MODEL"),
        "grok-4.5",
        restartRequired = true,
    ),
    XAI_API_BASE(
        "xaiApiBase",
        KnobKind.STRING,
        listOf("XAI_API_BASE"),
        "https://api.x.ai/v1",
        restartRequired = true,
    ),
    GROK_AUTH_PATH(
        "grokAuthPath",
        KnobKind.STRING,
        listOf("GROK_AUTH_PATH"),
        "~/.local/share/claude-grok/auth.json",
        restartRequired = true,
    ),
    CONTROL_PORT(
        "controlPort",
        KnobKind.NUMBER,
        listOf("SPLICE_CONTROL_PORT", "CONTROL_PROXY_PORT"),
        3096L,
        restartRequired = true,
    ),
    USAGE_WARN_PCT(
        "usageWarnPct",
        KnobKind.NUMBER,
        listOf("SPLICE_USAGE_WARN_PCT"),
        80L,
        restartRequired = true,
    ),
    USAGE_WARN_TOKENS_5H(
        "usageWarnTokens5h",
        KnobKind.NUMBER,
        listOf("SPLICE_USAGE_WARN_TOKENS_5H"),
        0L,
        restartRequired = true,
    ),
    ;

    public companion object {
        public val byKey: Map<String, Knob> = entries.associateBy { it.key }
        public val restartRequiredKeys: List<String> = entries.filter { it.restartRequired }.map { it.key }
    }
}
