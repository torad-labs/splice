// PORT-OF: server/src/config.mjs DEFAULTS + ENV_MAP + NUMBER_KEYS/BOOL_KEYS + RESTART_REQUIRED_KEYS
// @ 4ca99f7 — invariants: env alias order matters (first present name wins); maxInflight accepts
// unlimited/off/none/'' as 0; bool coercion is /^(1|true|yes|on)$/i; RESTART_REQUIRED =
// [port, grokPort, controlPort, upstreamTimeoutMs]. DELIBERATELY NOT PORTED (plan): the vestigial
// anthropicUpstream + claudeCredentialsPath keys (nothing read them; claudithos leftovers).
package splice.core.config

public enum class KnobKind { STRING, NUMBER, BOOL }

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
    ),
    CODEX_AUTH_PATH(
        "codexAuthPath",
        KnobKind.STRING,
        listOf("CODEX_AUTH_PATH"),
        "~/.codex/auth.json",
    ),
    PINNED_MODEL("pinnedModel", KnobKind.STRING, listOf("CLAUDEX_PINNED_MODEL", "CLAUDEX_MODEL"), "gpt-5.6-sol"),
    COMPACT_MODEL("compactModel", KnobKind.STRING, listOf("CLAUDEX_COMPACT_MODEL"), ""),
    EFFORT("effort", KnobKind.STRING, listOf("CLAUDEX_REASONING_EFFORT", "CODEX_REASONING_EFFORT"), null),
    SUMMARY("summary", KnobKind.STRING, listOf("CLAUDEX_REASONING_SUMMARY", "CODEX_REASONING_SUMMARY"), null),
    SHOW_REASONING("showReasoning", KnobKind.STRING, listOf("CLAUDEX_SHOW_REASONING", "CODEX_SHOW_REASONING"), "text"),
    REPLAY_REASONING(
        "replayReasoning",
        KnobKind.BOOL,
        listOf("CLAUDEX_REPLAY_REASONING", "CODEX_REPLAY_REASONING"),
        false,
    ),
    MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), 0L),
    UPSTREAM_RETRIES("upstreamRetries", KnobKind.NUMBER, listOf("CLAUDEX_UPSTREAM_RETRIES"), 2L),
    UPSTREAM_TIMEOUT_MS(
        "upstreamTimeoutMs",
        KnobKind.NUMBER,
        listOf("CLAUDEX_UPSTREAM_TIMEOUT_MS"),
        900_000L,
        restartRequired = true,
    ),
    FIRST_BYTE_TIMEOUT_MS("firstByteTimeoutMs", KnobKind.NUMBER, listOf("CLAUDEX_FIRST_BYTE_TIMEOUT_MS"), 300_000L),
    STREAM_IDLE_MS("streamIdleMs", KnobKind.NUMBER, listOf("CLAUDEX_STREAM_IDLE_MS"), 180_000L),
    AUTH_CACHE_MS("authCacheMs", KnobKind.NUMBER, listOf("CLAUDEX_AUTH_CACHE_MS"), 60_000L),
    DEBUG("debug", KnobKind.BOOL, listOf("CLAUDEX_DEBUG", "CODEX_PROXY_DEBUG"), false),
    CONTEXT_WINDOW_OVERRIDE("contextWindowOverride", KnobKind.NUMBER, listOf("CODEX_MODEL_CONTEXT_WINDOW"), null),
    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT"), 3100L, restartRequired = true),
    GROK_MODEL("grokModel", KnobKind.STRING, listOf("CLAUDE_GROK_MODEL", "CLAUDE_GROK_PINNED_MODEL"), "grok-4.5"),
    XAI_API_BASE("xaiApiBase", KnobKind.STRING, listOf("XAI_API_BASE"), "https://api.x.ai/v1"),
    GROK_AUTH_PATH(
        "grokAuthPath",
        KnobKind.STRING,
        listOf("GROK_AUTH_PATH"),
        "~/.local/share/claude-grok/auth.json",
    ),
    CONTROL_PORT(
        "controlPort",
        KnobKind.NUMBER,
        listOf("SPLICE_CONTROL_PORT", "CONTROL_PROXY_PORT"),
        3096L,
        restartRequired = true,
    ),
    USAGE_WARN_PCT("usageWarnPct", KnobKind.NUMBER, listOf("SPLICE_USAGE_WARN_PCT"), 80L),
    USAGE_WARN_TOKENS_5H("usageWarnTokens5h", KnobKind.NUMBER, listOf("SPLICE_USAGE_WARN_TOKENS_5H"), 0L),
    ;

    public companion object {
        public val byKey: Map<String, Knob> = entries.associateBy { it.key }
        public val restartRequiredKeys: List<String> = entries.filter { it.restartRequired }.map { it.key }
    }
}
