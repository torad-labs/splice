// PORT-OF: server/src/config.mjs DEFAULTS + ENV_MAP + NUMBER_KEYS/BOOL_KEYS + RESTART_REQUIRED_KEYS
// @ pre-public-port-baseline — invariants: env alias order matters (first present name wins); maxInflight accepts
// unlimited/off/none/'' as 0; bool coercion is /^(1|true|yes|on)$/i, and since V4-286 false is
// /^(0|false|no|off)$/i and anything else is refused by name (BoolKnobWords); RESTART_REQUIRED =
// [port, grokPort, controlPort, upstreamTimeoutMs]. DELIBERATELY NOT PORTED (plan): the vestigial
// anthropicUpstream + claudeCredentialsPath keys (nothing read them; claudithos leftovers).
package splice.core.config

// KnobKind + knobsByKey + restartRequiredKnobKeys live in KnobKind.kt
// (concentration, 2026-08-19).

// HONESTY (audit 2026-07-18): nearly every knob is SNAPSHOTTED at Daemon.start into constructed
// objects (providers, watchdog budgets, auth caches, warn thresholds) — so nearly every knob is
// restartRequired. A knob is LIVE only when something re-reads it AT THE ENFORCEMENT POINT through a
// reader; what follows is a reading of the ones that do, taken 2026-10-10, and not a gate: maxInflight
// and maxQueued (LiveLimit, per admission), requestReadTimeoutMs (RequestReadBudgetMs, per body read),
// maxRequestBytes (RequestByteCap, per request, in the head path and the ingress guard both),
// materializationHeapBytes (MaterializationBudgetBytes, per admission, which the gate caps into the
// MaterializedByteCap that same guard asks), historyRetentionDays (RetentionDays, per read),
// firstByteTimeoutMs and stallReanchorMs (LiveWatchdogBudget, per turn; the first also bounds each request write
// through WriteBoundMs). Away from the turn: transcriptView (SessionTranscriptViewEnabled, per console route)
// and the warn pair usageWarnPct and usageWarnTokens5h (UsageWarningSource, per ask). Recount these by reading
// the reader lambdas over getConfig() in :app, never off this line. Make a knob live-read and it joins this
// line and loses its restartRequired flag, in that same commit.
public enum class Knob(
    public val key: String,
    public val kind: KnobKind,
    public val envNames: List<String>,
    private val typedDefault: KnobDefault,
    public val restartRequired: Boolean = false,
    /** Set only through [heads.KEY.overrides]: no env alias, no global TOML, no state file, no PATCH
     *  reaches it (ConfigService). For a knob that turns on keeping a head's conversations, where
     *  the per-head table is the one switch `splice doctor` reads and names (v0.4.0 prompt-review). */
    public val headOnly: Boolean = false,
) {
    PORT("port", KnobKind.NUMBER, listOf("CODEX_PROXY_PORT"), KnobDefault.Count(3099L), restartRequired = true),
    CHATGPT_API_BASE(
        "chatgptApiBase",
        KnobKind.STRING,
        listOf("CHATGPT_API_BASE"),
        KnobDefault.Text("https://chatgpt.com/backend-api/codex"),
        restartRequired = true,
    ),
    CODEX_AUTH_PATH(
        "codexAuthPath",
        KnobKind.STRING,
        listOf("CODEX_AUTH_PATH"),
        // The registry's splice-owned default (AuthKind header, 2026-09-05) — referenced, not copied,
        // so the knob and the file `splice login` writes cannot drift apart (the DR-79 class).
        KnobDefault.ChatgptLoginFile,
        restartRequired = true,
    ),
    PINNED_MODEL(
        "pinnedModel",
        KnobKind.STRING,
        listOf("CLAUDEX_PINNED_MODEL", "CLAUDEX_MODEL"),
        KnobDefault.Text("gpt-6-sol"),
        restartRequired = true,
    ),

    // (COMPACT_MODEL removed 2026-07-20: it was dead — never wired to the request builder — AND a
    //  footgun against the cache law: compaction MUST run on the session's own model+effort or the
    //  warm prompt-cache prefix is invalidated ("compaction ate my subscription"). Pinned by test.)
    EFFORT(
        "effort",
        KnobKind.STRING,
        listOf("CLAUDEX_REASONING_EFFORT", "CODEX_REASONING_EFFORT"),
        KnobDefault.None,
        restartRequired = true,
    ),

    // Default detailed: the fullest public reasoning text the Responses backends expose.
    SUMMARY(
        "summary",
        KnobKind.STRING,
        listOf("CLAUDEX_REASONING_SUMMARY", "CODEX_REASONING_SUMMARY"),
        KnobDefault.Text("detailed"),
        restartRequired = true,
    ),
    SHOW_REASONING(
        "showReasoning",
        KnobKind.STRING,
        listOf("CLAUDEX_SHOW_REASONING", "CODEX_SHOW_REASONING"),
        KnobDefault.Text("text"),
        restartRequired = true,
    ),

    // OFF for every head (codex/grok/openai). Input-injecting prior opaque encrypted reasoning items thins fresh
    // reasoning depth (~4x measured). Include-encrypted handle is separate and still ON when
    // showReasoning is on. Opt in only with CLAUDEX_REPLAY_REASONING=1.
    REPLAY_REASONING(
        "replayReasoning",
        KnobKind.BOOL,
        listOf("CLAUDEX_REPLAY_REASONING", "CODEX_REPLAY_REASONING"),
        KnobDefault.Flag(false),
        restartRequired = true,
    ),

    // The transcript mirror ("[reasoning summary]" text block, L2) is operator-locked OFF.
    // Provider-native reasoning still displays as thinking blocks, but splice never authors a
    // summary into the transcript or sends that synthetic block back upstream.
    MIRROR_REASONING(
        "mirrorReasoning",
        KnobKind.BOOL,
        listOf("CLAUDEX_MIRROR_REASONING"),
        KnobDefault.Flag(false),
        restartRequired = true,
    ),

    // splice's status line on a turn that has gone quiet: "holding this turn open. 4m20s into the
    // turn, no answer from gpt-6-astra yet." A row served buffered end to end (gpt-6-astra, probed
    // 2026-09-06) shows the user a blank spinner for 5-12 minutes with nothing to distinguish a
    // working turn from a hung one; this is the proxy answering that, and it is NOT the mirror
    // above — no reasoning summary, no claim about the model's thinking, only about the wait.
    PROGRESS_LINE(
        "progressLine",
        KnobKind.BOOL,
        listOf("CLAUDEX_PROGRESS_LINE", "SPLICE_PROGRESS_LINE"),
        KnobDefault.Flag(true),
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
        KnobDefault.Text("gpt-5.6-luna,gpt-5.6-terra,gpt-5.5"),
        restartRequired = true,
    ),
    FOLD_MAX_CONTINUE(
        "foldMaxContinue",
        KnobKind.NUMBER,
        listOf("CLAUDEX_FOLD_MAX_CONTINUE"),
        KnobDefault.Count(3L),
        restartRequired = true,
    ),
    FOLD_MARKER_TEXT(
        "foldMarkerText",
        KnobKind.STRING,
        listOf("CLAUDEX_FOLD_MARKER_TEXT"),
        KnobDefault.Text("Continue thinking..."),
        restartRequired = true,
    ),
    FOLD_MAX_TIER(
        "foldMaxTier",
        KnobKind.NUMBER,
        listOf("CLAUDEX_FOLD_MAX_TIER"),
        KnobDefault.Count(6L),
        restartRequired = true,
    ),

    // Daemon-wide tool-surface kill switch. ONE-WAY OFF ONLY: "off" forces every head back to the
    // full eager tool array without editing TOML; any other value honours each provider's own
    // [providers.*.quirks.tool_surface] table. It can never turn the feature ON — forcing it on
    // globally would arm tool_search for grok/openai heads whose backends do not serve it.
    TOOL_SURFACE(
        "toolSurface",
        KnobKind.STRING,
        listOf("CLAUDEX_TOOL_SURFACE"),
        KnobDefault.Text("auto"),
        restartRequired = true,
    ),

    // Daemon-wide plan-usage poll switch, same one-way shape as TOOL_SURFACE. Subscription heads
    // (ChatGPT, Kimi, SuperGrok) poll their provider's usage endpoint every five minutes with the
    // operator's own bearer so the status line's 5h/7d bars are right from the first tick. "off"
    // stops every poller without editing TOML; the bars then draw only from the rate-limit
    // headers each round already carries. Any other value keeps polling.
    QUOTA_POLL(
        "quotaPoll",
        KnobKind.STRING,
        listOf("CLAUDEX_QUOTA_POLL"),
        KnobDefault.Text("auto"),
        restartRequired = true,
    ),

    // V4-110: how often a subscription head re-polls its provider's plan-usage endpoint, in
    // milliseconds. Same five-minute cadence the poller always ran; floored in ConfigCoercion so an
    // operator's too-fast value cannot hammer the provider's usage endpoint.
    QUOTA_POLL_INTERVAL_MS(
        "quotaPollIntervalMs",
        KnobKind.NUMBER,
        listOf("SPLICE_QUOTA_POLL_INTERVAL_MS"),
        typedDefault = KnobDefault.Count(300_000L),
        restartRequired = true,
    ),

    // Per-head admission (each head is a different backend/account). Bounded by default since the
    // 2026-07-19 storm: unlimited (0) let ~650 concurrent streams OOM the 1G heap. NF-02: default
    // 12 (was 100) — splice's own perf-JSONL measurement (app/src/main/resources/splice.example.toml: 0.3% turn
    // failure at inflight<=14, 11% at 38, 67% at 100) sits INSIDE the 0.3% band with headroom
    // over kimi's proven 8. The ceiling belongs to the upstream ACCOUNT (Daemon.kt reasoning);
    // high-capacity backends (vLLM, enterprise keys) raise it per head via [heads.*.overrides]
    // or opt out with 0 = unlimited. Hot-PATCHable, no restart.
    MAX_INFLIGHT("maxInflight", KnobKind.NUMBER, listOf("CLAUDEX_MAX_INFLIGHT"), KnobDefault.Count(12L)),
    MAX_QUEUED("maxQueued", KnobKind.NUMBER, listOf("CLAUDEX_MAX_QUEUED"), KnobDefault.Count(512L)),

    // V4-354: one GLOBAL live switch for reading the redacted conversation Claude Code already wrote
    // locally. ON by default because it collects no new bytes. Exact request/response bodies belong to
    // per-head TRACE below (on by default since the operator's 2026-09-28 ruling, off per head);
    // this switch neither changes TRACE nor exposes its instructions, tools or raw headers.
    TRANSCRIPT_VIEW("transcriptView", KnobKind.BOOL, listOf(), KnobDefault.Flag(true)),
    UPSTREAM_RETRIES(
        "upstreamRetries",
        KnobKind.NUMBER,
        listOf("CLAUDEX_UPSTREAM_RETRIES"),
        // 4 attempts matches the surveyed harness floor (codex 4, gemini/Claude Code higher);
        // the old default of 2 with ~200ms total backoff still failed turns on 2-3s blips (G4b).
        KnobDefault.Count(4L),
        restartRequired = true,
    ),

    // V4-110 retry curve, promoted beside upstreamRetries. The DEFAULT IS THE GENERIC BOUNDED
    // CURVE: every UNPREDICTED failure backs off on 200ms-base / 10s-cap / ±10%-jitter — never a
    // bare failure and never a made-up cause. Known errors keep their SPECIFIC plans (DNS 1s/2s/4s,
    // 429 Retry-After); this is the bounded floor everything else falls onto.
    RETRY_BACKOFF_BASE_MS(
        "retryBackoffBaseMs",
        KnobKind.NUMBER,
        listOf("SPLICE_RETRY_BACKOFF_BASE_MS"),
        typedDefault = KnobDefault.Count(200L),
        restartRequired = true,
    ),
    RETRY_BACKOFF_CAP_MS(
        "retryBackoffCapMs",
        KnobKind.NUMBER,
        listOf("SPLICE_RETRY_BACKOFF_CAP_MS"),
        typedDefault = KnobDefault.Count(10_000L),
        restartRequired = true,
    ),
    RETRY_BACKOFF_JITTER_PCT(
        "retryBackoffJitterPct",
        KnobKind.NUMBER,
        listOf("SPLICE_RETRY_BACKOFF_JITTER_PCT"),
        typedDefault = KnobDefault.Count(10L),
        restartRequired = true,
    ),
    UPSTREAM_TIMEOUT_MS(
        "upstreamTimeoutMs",
        KnobKind.NUMBER,
        listOf("CLAUDEX_UPSTREAM_TIMEOUT_MS"),
        KnobDefault.Count(900_000L),
        restartRequired = true,
    ),

    // Through WatchdogBudget, the watchdog's FIRST-OUTPUT tier: a probe, not a verdict. It is not a
    // socket timeout; a read waits up to UPSTREAM_TIMEOUT_MS (V4-125). 90_000 with STREAM_IDLE_MS below
    // and for the same reason; read that entry, including the 129-compaction scar it keeps.
    // Named rather than positional because §magic-number blesses this spelling (see STALL_REANCHOR_MS).
    FIRST_BYTE_TIMEOUT_MS(
        "firstByteTimeoutMs",
        KnobKind.NUMBER,
        listOf("CLAUDEX_FIRST_BYTE_TIMEOUT_MS"),
        typedDefault = KnobDefault.Count(90_000L),
    ),

    // The mid-output stall detector, and the one timer the reference client also keeps. codex-rs
    // (@63fe5a6, model-provider-info/src/lib.rs:26) sets DEFAULT_STREAM_IDLE_TIMEOUT_MS = 300_000
    // and applies it ONLY to the receive side, as timeout(idle_timeout, ws_stream.next()). We ran
    // 180_000 against the same backend and paid for it: on 2026-09-01 the idle tier alone ended 129
    // compactions, each one a whole transcript re-read that had already begun streaming. That scar is
    // why 300_000 was pinned here, and it is KEPT in this comment on purpose — a reader who finds the
    // number changed with the accident deleted has been handed the reasoning that caused it.
    //
    // 2026-09-18 (V4-125) — 90_000, and this is NOT a return to the 129. It is a different mechanism
    // wearing a smaller number, and the difference is the whole point of that row: a tier breach
    // stopped being a VERDICT. Silence past the tier now asks the round's path pulse whether the peer
    // is alive; a path that answers is HELD — polled on, never reaped short of the whole-turn cap —
    // and a path that cannot answer is discovered by its own read error, which the re-anchor
    // machinery already owns. The 129 died because 180_000 was the last word anyone said about them.
    // 90_000 is a question, asked sooner, of a watchdog that no longer ends turns: it buys how long
    // the operator waits before the proxy starts healing rather than how long a silent backend is
    // tolerated, and five minutes of the former is experienced as a hang. Lower it per head for a
    // tighter stall; a head that genuinely needs the long wait names 300_000 in its own config with
    // a reason, which is the honest place for it now.
    STREAM_IDLE_MS(
        "streamIdleMs",
        KnobKind.NUMBER,
        listOf("CLAUDEX_STREAM_IDLE_MS"),
        typedDefault = KnobDefault.Count(90_000L),
        restartRequired = true,
    ),

    // V4-116. The MID-OUTPUT STALL RE-ANCHOR tier: how long a round may sit silent AFTER the client
    // has seen content before splice stops waiting and resumes the turn itself (cancel the round,
    // re-POST from the salvage as an assistant prefill). 20s because a breach here is not a verdict,
    // it is the trigger of a repair the client cannot see: the measured deepseek stall (session
    // b10459ba, 2026-09-17) burned the whole 300_000 STREAM_IDLE_MS tier before ending a turn that
    // was continuable the entire time. NOT a replacement for STREAM_IDLE_MS — arm it for a head
    // whose upstream has been MEASURED to continue from a prefill (reanchor_prefill), because for a
    // head that cannot be prefilled an early reap has nothing to resume into and only costs a
    // slow-but-alive generation. STREAM_IDLE_MS stays that head's floor. Keep it at or above
    // 3 x the poll floor (250ms) or the poller samples it too late to matter.
    STALL_REANCHOR_MS(
        "stallReanchorMs",
        KnobKind.NUMBER,
        listOf("CLAUDEX_STALL_REANCHOR_MS"),
        // Named, not positional: §magic-number (the write-time mirror of detekt's MagicNumber)
        // blocks a NEW bare literal in a call argument, and this is the spelling it blesses. The
        // sibling entries above pass only because their literals are pre-existing.
        typedDefault = KnobDefault.Count(20_000L),
    ),
    AUTH_CACHE_MS(
        "authCacheMs",
        KnobKind.NUMBER,
        listOf("CLAUDEX_AUTH_CACHE_MS"),
        KnobDefault.Count(60_000L),
        restartRequired = true,
    ),
    DEBUG(
        "debug",
        KnobKind.BOOL,
        listOf("CLAUDEX_DEBUG", "CODEX_PROXY_DEBUG"),
        KnobDefault.Flag(false),
        restartRequired = true,
    ),
    CONTEXT_WINDOW_OVERRIDE(
        "contextWindowOverride",
        KnobKind.NUMBER,
        listOf("CODEX_MODEL_CONTEXT_WINDOW"),
        KnobDefault.None,
        restartRequired = true,
    ),
    GROK_PORT("grokPort", KnobKind.NUMBER, listOf("GROK_PROXY_PORT"), KnobDefault.Count(3100L), restartRequired = true),
    GROK_MODEL(
        "grokModel",
        KnobKind.STRING,
        listOf("CLAUDE_GROK_MODEL", "CLAUDE_GROK_PINNED_MODEL"),
        KnobDefault.Text("grok-4.7"),
        restartRequired = true,
    ),
    XAI_API_BASE(
        "xaiApiBase",
        KnobKind.STRING,
        listOf("XAI_API_BASE"),
        KnobDefault.Text("https://api.x.ai/v1"),
        restartRequired = true,
    ),
    GROK_AUTH_PATH(
        "grokAuthPath",
        KnobKind.STRING,
        listOf("GROK_AUTH_PATH"),
        // DR-79: must agree with AuthKind.GrokOAuth's registry default — login writes there, the
        // arm reads here, and the spike-era ~/.local/share/claude-grok path made a head omitting
        // auth.file 401 forever while doctor said signed-in (pinned by the registry-agreement arm).
        // Since 2026-09-05 the value IS the registry's, and it is splice's own file, not ~/.grok's.
        KnobDefault.GrokLoginFile,
        restartRequired = true,
    ),
    CONTROL_PORT(
        "controlPort",
        KnobKind.NUMBER,
        listOf("SPLICE_CONTROL_PORT", "CONTROL_PROXY_PORT"),
        KnobDefault.Count(3096L),
        restartRequired = true,
    ),
    USAGE_WARN_PCT(
        "usageWarnPct",
        KnobKind.NUMBER,
        listOf("SPLICE_USAGE_WARN_PCT"),
        KnobDefault.Count(80L),
    ),
    USAGE_WARN_TOKENS_5H(
        "usageWarnTokens5h",
        KnobKind.NUMBER,
        listOf("SPLICE_USAGE_WARN_TOKENS_5H"),
        KnobDefault.Count(0L),
    ),

    // ── shared MCP hosting (v0.4.0, FEATURES.md §8) ────────────────────────────────────────────
    // One McpHost serves every head; these four shape its lifecycle. Daemon-global, read once at
    // ControlPlane.start — a per-head override is meaningless but harmless. The [daemon] spellings
    // (mcp_idle_timeout_ms etc.) are the same knob under DaemonConfig's @SerialName transliteration.
    MCP_IDLE_TIMEOUT_MS(
        "mcpIdleTimeoutMs",
        KnobKind.NUMBER,
        listOf("SPLICE_MCP_IDLE_TIMEOUT_MS"),
        typedDefault = KnobDefault.Count(1_800_000L),
        restartRequired = true,
    ),
    MCP_MAX_SERVERS(
        "mcpMaxServers",
        KnobKind.NUMBER,
        listOf("SPLICE_MCP_MAX_SERVERS"),
        typedDefault = KnobDefault.Count(32L),
        restartRequired = true,
    ),
    MCP_REQUEST_TIMEOUT_MS(
        "mcpRequestTimeoutMs",
        KnobKind.NUMBER,
        listOf("SPLICE_MCP_REQUEST_TIMEOUT_MS"),
        typedDefault = KnobDefault.Count(1_800_000L),
        restartRequired = true,
    ),
    MCP_INITIALIZE_TIMEOUT_MS(
        "mcpInitializeTimeoutMs",
        KnobKind.NUMBER,
        listOf("SPLICE_MCP_INITIALIZE_TIMEOUT_MS"),
        typedDefault = KnobDefault.Count(60_000L),
        restartRequired = true,
    ),

    // ── request materialization (v0.4.0) ───────────────────────────────────────────────────────
    // The largest request BODY splice will decode/translate, in bytes; past it the client gets a
    // 413 rather than splice reading an unbounded body. Read per head from getConfig (HeadDeps).
    // Why 32 MiB (V4-374): the Messages API's own limit (platform.claude.com/docs/en/api/errors,
    // "Request size limits"), so splice is never the tighter hop for an Anthropic-shaped request; at
    // 8 MiB a screenshot-heavy session died with the client's canned "Request too large (max 32MB)".
    MAX_REQUEST_BYTES(
        "maxRequestBytes",
        KnobKind.NUMBER,
        listOf("SPLICE_MAX_REQUEST_BYTES"),
        typedDefault = KnobDefault.Count(32 * 1024 * 1024L),
    ),
    REQUEST_READ_TIMEOUT_MS(
        "requestReadTimeoutMs",
        KnobKind.NUMBER,
        listOf("SPLICE_REQUEST_READ_TIMEOUT_MS"),
        typedDefault = KnobDefault.Count(30_000L),
    ),

    // PROCESS-SHARED heap bytes for decoding/translating across the daemon, not a request count.
    // Zero derives the budget from JVM maximum heap minus the measured 384 MiB everyday resident.
    // RequestMaterializationGate caps overrides at that spare heap and reserves 6.5 times each
    // declared body (208 MiB measured for 32 MiB; unknown length reserves maxRequestBytes).
    // RequestHeapBudgetTest sends both everyday and full-size bursts through the real head.
    MATERIALIZATION_HEAP_BYTES(
        "materializationHeapBytes",
        KnobKind.NUMBER,
        listOf("SPLICE_MATERIALIZATION_HEAP_BYTES"),
        typedDefault = KnobDefault.Count(0L),
    ),

    // Extra trusted roots (colon-separated absolute paths) for the statusline git-branch lookup.
    // Default empty: only $HOME and /tmp are trusted, so repos elsewhere (devcontainer /workspace,
    // /srv layouts) show no branch segment — unauthenticated /statusline must never exec
    // `git -C` against an untrusted path (review 2026-07-22).
    STATUSLINE_GIT_ROOTS(
        "statuslineGitRoots",
        KnobKind.STRING,
        listOf("CLAUDEX_STATUSLINE_GIT_ROOTS"),
        KnobDefault.Text(""),
    ),

    // V4-176: THE SUPERVISION CONTRACT, as two names the operator supplies rather than two constants
    // splice asserts about the box it happens to run on.
    //
    // splice RESTARTS INTO a systemd user unit it does not own, and it SPAWNS hosted MCP servers into
    // a slice it does not create. Both requirements are real and neither is splice's to satisfy: a
    // unit that brings the process back is why `POST /api/daemon/restart` may drain at all, and a
    // slice with a memory ceiling is why a hosted server cannot eat the box. What was wrong was
    // spelling them as facts — one hardcoded unit name, one hardcoded slice name, and prose naming
    // the private tool that supplies them here. A reader packaging splice for their own machine could
    // act on none of it.
    //
    // Named by config, they become a documented integration point: whatever the reader's supervision
    // is called, they say so once. The defaults are what this repo's own install.sh layout produces,
    // so a box that changes neither needs no config at all, and a box that has neither still runs —
    // the daemon refuses the drain in words (DaemonRoutes) and says hosted children are uncapped
    // (McpContainment) instead of pretending.
    SUPERVISOR_UNIT(
        "supervisorUnit",
        KnobKind.STRING,
        listOf("SPLICE_SUPERVISOR_UNIT"),
        KnobDefault.Text("splice.service"),
        restartRequired = true,
    ),
    MCP_SLICE(
        "mcpSlice",
        KnobKind.STRING,
        listOf("SPLICE_MCP_SLICE"),
        KnobDefault.Text("app-mcp.slice"),
        restartRequired = true,
    ),

    // V4-130 (FEATURES.md 6): how many UTC days of message edges (`activity/edges-YYYY-MM-DD.jsonl`
    // under the state dir) are kept. Whole day files older than the window are deleted; today counts as
    // one of the days, and a window under two is kept as two, the UTC days one local day spans (V4-285:
    // the Teams chat reads the viewer's local day). Daemon-wide. V4-261: activity labels
    // (`activity/activity-YYYY-MM-DD.jsonl`) are not governed here. They are kept for today and
    // yesterday, the window of the one console view that reads them, the Teams page's Activity feed (the
    // viewer's local day, TeamsReads.activity), because a label carries file names, commands and search
    // patterns (ActivityStore.kt).
    ACTIVITY_RETENTION_DAYS(
        "activityRetentionDays",
        KnobKind.NUMBER,
        listOf("SPLICE_ACTIVITY_RETENTION_DAYS"),
        typedDefault = KnobDefault.Count(90L),
        restartRequired = true,
    ),

    // V4-367: a daemon-wide switch for storing SendMessage metadata. Existing day files remain until
    // retention or an explicit delete; false stops new appends after the next restart.
    MESSAGE_EDGES(
        "messageEdges",
        KnobKind.BOOL,
        listOf("SPLICE_MESSAGE_EDGES"),
        KnobDefault.Flag(true),
        restartRequired = true,
    ),

    // V4-130: the per-head switch for the activity label store. `*` stores every head, an empty value
    // stores none, otherwise a comma-separated list of head keys. Message edges use their own switch.
    ACTIVITY_STORE_HEADS(
        "activityStoreHeads",
        KnobKind.STRING,
        listOf("SPLICE_ACTIVITY_STORE_HEADS"),
        KnobDefault.Text("*"),
        restartRequired = true,
    ),

    // V4-173: the per-head upstream wire tap — how many of the most recent upstream REQUEST BODIES
    // the head keeps, in memory only, for `splice wire <head>`. OFF BY DEFAULT (0) and opt-in by
    // construction: a body carries the user's whole conversation, so nothing is kept unless an
    // operator names a count for a head, nothing is ever written to disk, and a restart forgets it.
    // Set through [heads.KEY.overrides], never the global view, so turning it on for one head keeps
    // every other head's bodies unkept.
    WIRE_TAP(
        "wireTap",
        KnobKind.NUMBER,
        listOf(), // head-only: no env alias
        typedDefault = KnobDefault.Count(0L),
        restartRequired = true,
        headOnly = true,
    ),

    // V4-174/V4-387: every head's FULL TRACE records each request, upstream attempt, response
    // and client frame in owner-only UTC day files. On by default (operator 2026-09-28) so a
    // failed session is replayable second by second. Only [heads.KEY.overrides] trace = false opts
    // this head out; doctor names every head that is off. No global or env switch may silence it.
    TRACE(
        "trace",
        KnobKind.BOOL,
        listOf(), // head-only: no env alias
        KnobDefault.Flag(true),
        restartRequired = true,
        headOnly = true,
    ),

    // V4-174: how many UTC days of trace files a traced head keeps; a day file is deleted at the UTC
    // midnight it leaves the window, or at the next start if the daemon was down (V4-273). Today
    // counts as one of the days.
    TRACE_RETENTION_DAYS(
        "traceRetentionDays",
        KnobKind.NUMBER,
        listOf("SPLICE_TRACE_RETENTION_DAYS"),
        typedDefault = KnobDefault.Count(7L),
        restartRequired = true,
    ),

    // V4-174/V4-387: the longest body a trace record keeps whole, in characters. Raising the
    // default to 16 Mi keeps measured 1M-token turns whole; larger bodies remain explicitly
    // truncated, so a runaway record cannot grow without bound.
    TRACE_MAX_BODY_CHARS(
        "traceMaxBodyChars",
        KnobKind.NUMBER,
        listOf("SPLICE_TRACE_MAX_BODY_CHARS"),
        typedDefault = KnobDefault.Count(16L shl 20),
        restartRequired = true,
    ),

    // V4-133, FEATURES.md §5/§6: PUT /api/budgets lets an operator create a budget with no
    // explicit action; this is what it gets. Hot (read per PUT, never snapshotted) so an operator
    // can tighten the default without a restart.
    BUDGET_DEFAULT_ACTION(
        "budgetDefaultAction",
        KnobKind.STRING,
        listOf("SPLICE_BUDGET_DEFAULT_ACTION"),
        KnobDefault.Text("warn"),
    ),

    // V4-133: how many days of rolled-out perf generations every head keeps in <state>/perf-archive
    // (PerfStats' rotation archive, wired by ManagedHeadFactory; kt-perf-history-archived keeps it
    // so). Same shape as activityRetentionDays: whole archived files older than the window are swept
    // on the next rotation. 0 turns the archive off: the one-generation rotate of before.
    //
    // SUPERSEDED, NOT RETIRED (Oct 10, 2026): historyRetentionDays below is the one window a person
    // sets, and it covers these records AND the hourly totals. This key stays readable because an
    // install that predates the setting must keep the window it already had — including the 90 days
    // it had by default. It is the CARRY-OVER source, read only when the new key is absent
    // (SpliceConfig.historyWindow); nothing writes it any more.
    PERF_ARCHIVE_RETENTION_DAYS(
        "perfArchiveRetentionDays",
        KnobKind.NUMBER,
        listOf("SPLICE_PERF_ARCHIVE_RETENTION_DAYS"),
        typedDefault = KnobDefault.Count(90L),
        restartRequired = true,
    ),

    // NEW Oct 10, 2026: the ONE history window (console Settings > Your data) — the hourly totals
    // and the request records, as one setting, because "how far back can I read this" is one
    // question about one set of days.
    //
    // DEFAULT IS ABSENT ON PURPOSE. A number here would become every upgrade's new window, and a
    // shorter one deletes history nobody asked it to (Marlin, Oct 10: an upgrade never shortens
    // history on its own, so a 90 stays 90). Absent means "nobody has set this", which
    // SpliceConfig.historyWindow answers from the records window the install already had. A FRESH
    // install gets HISTORY_DEFAULT_DAYS written into its starter splice.toml, where the person can
    // read the number that governs their disk.
    //
    // STRING, NOT NUMBER: `forever` is a value a person may choose, and the one choice that is not
    // a window should not have to be spelled as one. ConfigCoercion refuses any other word by name.
    // NO RESTART. Every reader of this window reads it through KeptHistory at the moment it needs
    // it — each head's hourly store and its archive sweep, the backfill's reach, the retention the
    // console declares, and the history row itself. Marcos asked twice for changes to apply without
    // a restart, and the console's rule is that a change reads "Applied" once (Marlin, Oct 10,
    // 2026): a person who shortens their history has shortened it, and the save on Settings > Your
    // data trims every store to the moment it showed them. Saying restart-required here would be
    // asking for one that nothing needs.
    HISTORY_RETENTION_DAYS(
        "historyRetentionDays",
        KnobKind.STRING,
        listOf("SPLICE_HISTORY_RETENTION_DAYS"),
        typedDefault = KnobDefault.None,
    ),
    ;

    /** The untyped default the config layers read. A reader that expects a kind uses [count], [text] or [flag]. */
    internal val default: Any? get() = typedDefault.raw()

    /** The count this knob defaults to. A knob whose default is another kind fails here, by name. */
    public fun count(): Long = (typedDefault as? KnobDefault.Count)?.value ?: kindError("a count")

    /** The text this knob defaults to. A knob whose default is another kind fails here, by name. */
    public fun text(): String = typedDefault.raw() as? String ?: kindError("text")

    /** The flag this knob defaults to. A knob whose default is another kind fails here, by name. */
    public fun flag(): Boolean = (typedDefault as? KnobDefault.Flag)?.value ?: kindError("a flag")

    private fun kindError(kind: String): Nothing = error("$key defaults to ${typedDefault.raw()}, not $kind")
}
