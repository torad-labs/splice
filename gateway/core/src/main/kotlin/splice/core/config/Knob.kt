// PORT-OF: server/src/config.mjs DEFAULTS + ENV_MAP + NUMBER_KEYS/BOOL_KEYS + RESTART_REQUIRED_KEYS
// @ pre-public-port-baseline — invariants: env alias order matters (first present name wins); maxInflight accepts
// unlimited/off/none/'' as 0; bool coercion is /^(1|true|yes|on)$/i; RESTART_REQUIRED =
// [port, grokPort, controlPort, upstreamTimeoutMs]. DELIBERATELY NOT PORTED (plan): the vestigial
// anthropicUpstream + claudeCredentialsPath keys (nothing read them; claudithos leftovers).
package splice.core.config

// KnobKind + knobsByKey + restartRequiredKnobKeys live in KnobKind.kt
// (concentration, 2026-08-19).

// HONESTY (audit 2026-07-18): nearly every knob is SNAPSHOTTED at Daemon.start into constructed
// objects (providers, watchdog budgets, auth caches, warn thresholds) — so nearly every knob is
// restartRequired. The genuinely hot knobs are maxInflight and maxQueued (both read via a live
// lambda per admission, straight into InflightGate). If you make a knob live-read, remove its
// restartRequired flag in the same commit.
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

    // (COMPACT_MODEL removed 2026-07-20: it was dead — never wired to the request builder — AND a
    //  footgun against the cache law: compaction MUST run on the session's own model+effort or the
    //  warm prompt-cache prefix is invalidated ("compaction ate my subscription"). Pinned by test.)
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

    // OFF for every head (codex/grok/openai). Input-injecting prior opaque encrypted reasoning items thins fresh
    // reasoning depth (~4x measured). Include-encrypted handle is separate and still ON when
    // showReasoning is on. Opt in only with CLAUDEX_REPLAY_REASONING=1.
    REPLAY_REASONING(
        "replayReasoning",
        KnobKind.BOOL,
        listOf("CLAUDEX_REPLAY_REASONING", "CODEX_REPLAY_REASONING"),
        false,
        restartRequired = true,
    ),

    // The transcript mirror ("[reasoning summary]" text block, L2) is operator-locked OFF.
    // Provider-native reasoning still displays as thinking blocks, but splice never authors a
    // summary into the transcript or sends that synthetic block back upstream.
    MIRROR_REASONING(
        "mirrorReasoning",
        KnobKind.BOOL,
        listOf("CLAUDEX_MIRROR_REASONING"),
        false,
        restartRequired = true,
    ),

    // Reasoning-continuation folding (codex 518n-2 "dumbing down" fix). The fold set is the codex
    // models that TRUNCATE their own chain-of-thought at reasoning_tokens == 518n-2 (luna/terra/5.5,
    // NOT sol); a comma list so the operator can edit it. Detection replays the round's encrypted
    // reasoning with a "Continue thinking..." marker until the model finishes cleanly, capped by
    // fold_max_continue rounds and fold_max_tier (n). OFF for any model not in the set.
    FOLD_REASONING_MODELS(
        "foldReasoningModels",
        KnobKind.STRING,
        listOf("CLAUDEX_FOLD_REASONING_MODELS"),
        "gpt-5.6-luna,gpt-5.6-terra,gpt-5.5",
        restartRequired = true,
    ),
    FOLD_MAX_CONTINUE(
        "foldMaxContinue",
        KnobKind.NUMBER,
        listOf("CLAUDEX_FOLD_MAX_CONTINUE"),
        3L,
        restartRequired = true,
    ),
    FOLD_MARKER_TEXT(
        "foldMarkerText",
        KnobKind.STRING,
        listOf("CLAUDEX_FOLD_MARKER_TEXT"),
        "Continue thinking...",
        restartRequired = true,
    ),
    FOLD_MAX_TIER(
        "foldMaxTier",
        KnobKind.NUMBER,
        listOf("CLAUDEX_FOLD_MAX_TIER"),
        6L,
        restartRequired = true,
    ),

    // Daemon-wide tool-surface kill switch. ONE-WAY OFF ONLY: "off" forces every head back to the
    // full eager tool array without editing TOML; any other value honours each provider's own
    // [providers.*.quirks.tool_surface] table. It can never turn the feature ON — forcing it on
    // globally would arm tool_search for grok/openai heads whose backends do not serve it.
    TOOL_SURFACE("toolSurface", KnobKind.STRING, listOf("CLAUDEX_TOOL_SURFACE"), "auto", restartRequired = true),

    // Daemon-wide plan-usage poll switch, same one-way shape as TOOL_SURFACE. Subscription heads
    // (ChatGPT, Kimi, SuperGrok) poll their provider's usage endpoint every five minutes with the
    // operator's own bearer so the status line's 5h/7d bars are right from the first tick. "off"
    // stops every poller without editing TOML; the bars then draw only from the rate-limit
    // headers each round already carries. Any other value keeps polling.
    QUOTA_POLL("quotaPoll", KnobKind.STRING, listOf("CLAUDEX_QUOTA_POLL"), "auto", restartRequired = true),

    // Per-head admission (each head is a different backend/account). Bounded by default since the
    // 2026-07-19 storm: unlimited (0) let ~650 concurrent streams OOM the 1G heap. NF-02: default
    // 12 (was 100) — splice's own perf-JSONL measurement (config/splice.example.toml: 0.3% turn
    // failure at inflight<=14, 11% at 38, 67% at 100) sits INSIDE the 0.3% band with headroom
    // over kimi's proven 8. The ceiling belongs to the upstream ACCOUNT (Daemon.kt reasoning);
    // high-capacity backends (vLLM, enterprise keys) raise it per head via [heads.*.overrides]
    // or opt out with 0 = unlimited. Hot-PATCHable, no restart.
    MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), 12L),
    MAX_QUEUED("maxQueued", KnobKind.NUMBER, listOf("CLAUDEX_MAX_QUEUED"), 512L),
    UPSTREAM_RETRIES(
        "upstreamRetries",
        KnobKind.NUMBER,
        listOf("CLAUDEX_UPSTREAM_RETRIES"),
        // 4 attempts matches the surveyed harness floor (codex 4, gemini/Claude Code higher);
        // the old default of 2 with ~200ms total backoff still failed turns on 2-3s blips (G4b).
        4L,
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

    // The mid-output stall detector, and the one timer the reference client also keeps. codex-rs
    // (@63fe5a6, model-provider-info/src/lib.rs:26) sets DEFAULT_STREAM_IDLE_TIMEOUT_MS = 300_000
    // and applies it ONLY to the receive side, as timeout(idle_timeout, ws_stream.next()). We ran
    // 180_000 against the same backend and paid for it: on 2026-09-01 the idle tier alone ended 129
    // compactions, each one a whole transcript re-read that had already begun streaming. 300_000
    // matches the reference and equals our own firstByteTimeoutMs, so a stream is now judged by one
    // number before and after its first frame. Lower it per head when a head wants a tighter stall.
    STREAM_IDLE_MS(
        "streamIdleMs",
        KnobKind.NUMBER,
        listOf("CLAUDEX_STREAM_IDLE_MS"),
        300_000L,
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
        "grok-4.6",
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
        // DR-79: must agree with AuthKind.GrokOAuth's registry default — login writes there, the
        // arm reads here, and the spike-era ~/.local/share/claude-grok path made a head omitting
        // auth.file 401 forever while doctor said signed-in (pinned by the registry-agreement arm).
        "~/.grok/auth.json",
        restartRequired = true,
    ),
    CONTROL_PORT(
        "controlPort",
        KnobKind.NUMBER,
        listOf("SPLICE_CONTROL_PORT", "CONTROL_PROXY_PORT"),
        3096L,
        restartRequired = true,
    ),
    USAGE_WARN_PCT("usageWarnPct", KnobKind.NUMBER, listOf("SPLICE_USAGE_WARN_PCT"), 80L, restartRequired = true),
    USAGE_WARN_TOKENS_5H(
        "usageWarnTokens5h",
        KnobKind.NUMBER,
        listOf("SPLICE_USAGE_WARN_TOKENS_5H"),
        0L,
        restartRequired = true,
    ),

    // Extra trusted roots (colon-separated absolute paths) for the statusline git-branch lookup.
    // Default empty: only $HOME and /tmp are trusted, so repos elsewhere (devcontainer /workspace,
    // /srv layouts) show no branch segment — unauthenticated /statusline must never exec
    // `git -C` against an untrusted path (review 2026-07-22).
    STATUSLINE_GIT_ROOTS(
        "statuslineGitRoots",
        KnobKind.STRING,
        listOf("CLAUDEX_STATUSLINE_GIT_ROOTS"),
        "",
    ),
}
