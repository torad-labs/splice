// The coverage manifest as it stands before any page exists (row M1-04). Every
// name the denominator enumerates — Knob.kt entries, the six topology files'
// @SerialName values, and the routes of FEATURES.md sections 2.1 and 6 — gets
// exactly one disposition here, so the wall is green FROM THE FIRST PAGE and
// turns red the moment the daemon grows a key nobody dispositioned.
//
// `pending` names the M2 row that will replace the entry with a real
// disposition; a page's own `coverage.ts` overrides this file for that name.
// `excluded` names why the console will never show it.
import type { Disposition } from './checks';

type PendingGroup = {
  readonly kind: Disposition['kind'];
  readonly where: string;
  readonly names: readonly string[];
};

type ExcludedGroup = {
  readonly kind: Disposition['kind'];
  readonly reason: string;
  readonly names: readonly string[];
};

// Names are the parsed keys, exactly: enum entry names for knobs (not their
// `"port"` strings), @SerialName values for topology, normalized routes.
const PENDING: readonly PendingGroup[] = [
  {
    kind: 'knob',
    where: 'M2-05',
    // gateway/core/.../config/Knob.kt, every enum entry. The counts are printed
    // by the wall and recorded on the M1-04 ledger note, never restated here.
    names: [
      'PORT',
      'CHATGPT_API_BASE',
      'CODEX_AUTH_PATH',
      'PINNED_MODEL',
      'EFFORT',
      'SUMMARY',
      'SHOW_REASONING',
      'REPLAY_REASONING',
      'MIRROR_REASONING',
      'PROGRESS_LINE',
      'FOLD_REASONING_MODELS',
      'FOLD_MAX_CONTINUE',
      'FOLD_MARKER_TEXT',
      'FOLD_MAX_TIER',
      'TOOL_SURFACE',
      'QUOTA_POLL',
      'QUOTA_POLL_INTERVAL_MS',
      'MAX_INFLIGHT',
      'MAX_QUEUED',
      'UPSTREAM_RETRIES',
      'RETRY_BACKOFF_BASE_MS',
      'RETRY_BACKOFF_CAP_MS',
      'RETRY_BACKOFF_JITTER_PCT',
      'UPSTREAM_TIMEOUT_MS',
      'FIRST_BYTE_TIMEOUT_MS',
      'STREAM_IDLE_MS',
      'STALL_REANCHOR_MS',
      'AUTH_CACHE_MS',
      'DEBUG',
      'CONTEXT_WINDOW_OVERRIDE',
      'GROK_PORT',
      'GROK_MODEL',
      'XAI_API_BASE',
      'GROK_AUTH_PATH',
      'CONTROL_PORT',
      'USAGE_WARN_PCT',
      'USAGE_WARN_TOKENS_5H',
      'MCP_IDLE_TIMEOUT_MS',
      'MCP_MAX_SERVERS',
      'MCP_REQUEST_TIMEOUT_MS',
      'MCP_INITIALIZE_TIMEOUT_MS',
      'MAX_REQUEST_BYTES',
      'REQUEST_READ_TIMEOUT_MS',
      'MATERIALIZATION_PERMITS',
      'STATUSLINE_GIT_ROOTS',
    ],
  },
  {
    kind: 'topology',
    where: 'M2-05',
    names: [
      // topology/Topology.kt — DaemonConfig
      'control_port',
      'state_dir',
      'show_reasoning',
      'replay_reasoning',
      'mirror_reasoning',
      'fold_reasoning_models',
      'fold_max_continue',
      'fold_marker_text',
      'fold_max_tier',
      'mcp_hosting',
      'mcp_hosting_exclude',
      // topology/Topology.kt — ProviderConfig
      'base_url',
      'extra_headers',
      'extra_windows',
      'window_rules',
      'default_context_window',
      // topology/Topology.kt — HeadConfig
      'discovery_prefix',
      'pinned_model',
      'context_window',
      'system_prompt',
      'system_prompt_file',
      'system_prompt_mode',
      // topology/QuirksConfig.kt — QuirksConfig
      'account_id_header',
      'cache_key',
      'effort_ceiling',
      'summary_field',
      'compact_effort',
      'tool_choice',
      'reasoning_cache',
      'parallel_tool_calls',
      'websocket',
      'code_mode',
      'code_mode_workers',
      'code_mode_timeout_ms',
      'code_mode_heap',
      'zstd_request_body',
      'reasoning_effort',
      'tool_surface',
      'mfjs',
      'block_allowlist',
      'strip_cache_control',
      'synthesize_signatures',
      'map_thinking_adaptive',
      'strip_sampling_params',
      'reanchor_prefill',
      'tool_name_cap',
      // topology/QuirksConfig.kt — ToolSurfaceConfig
      'defer_prefixes',
      'min_deferred',
      'search_limit',
      'search_rounds',
      // topology/TopologySchema.kt — Dialect and ClaudeWrapperConfig
      'openai-responses',
      'openai-chat',
      'anthropic-passthrough',
      'config_dir',
      // prompt/HeadSystemPrompt.kt — SystemPromptMode
      'append',
      'replace',
      'strip',
      // model/TokenCost.kt — ModelRates
      'cache_read',
      'cache_write',
      // compaction/CompactionScope.kt declares no @SerialName, so it contributes none.
    ],
  },
  {
    kind: 'route',
    where: 'M2-01',
    // Fleet: head lifecycle plus the daemon-level restart the lifecycle row owns
    // (`/api/daemon/restart` is FEATURES.md section 4.2 "Daemon restart drains
    // turns first"; the M1-04 title's M2-01 list names the head routes only).
    names: [
      '/health',
      '/api/status',
      '/api/heads',
      '/api/heads/{head}/start',
      '/api/heads/{head}/stop',
      '/api/heads/{head}/restart',
      '/api/daemon/restart',
      '/api/usage',
    ],
  },
  {
    kind: 'route',
    where: 'M2-02',
    // `/api/sessions` carries the repo and team FIELDS (FEATURES.md section 6,
    // decided 2026-09-18) — there is no `/api/sessions/{id}/repo` route.
    names: [
      '/api/sessions',
      '/api/sessions/{id}/transcript',
      '/api/sessions/{id}/edges',
      '/api/teams/{id}/edges',
    ],
  },
  {
    kind: 'route',
    where: 'M2-03',
    names: ['/api/perf', '/api/perf/summary', '/api/perf/turns', '/api/logs/{head}'],
  },
  {
    kind: 'route',
    where: 'M2-04',
    names: [
      '/api/auth',
      '/api/auth/{head}/refresh',
      '/api/auth/{head}/login',
      '/api/auth/{head}/login/{id}',
      '/api/auth/{head}/switch',
      '/api/auth/{kind}/accounts/{label}',
      '/api/accounts',
    ],
  },
  {
    kind: 'route',
    where: 'M2-05',
    names: ['/api/config', '/api/topology', '/api/claude-head', '/api/claude-head/wrap', '/api/claude-head/unwrap'],
  },
  {
    kind: 'route',
    where: 'M2-06',
    names: ['/api/economics', '/api/compact', '/api/models'],
  },
  {
    kind: 'route',
    where: 'M2-07',
    names: [
      '/api/mcp',
      '/api/doctor',
      '/api/upgrade',
      '/api/heads/{head}/capture',
      '/api/budgets',
      '/api/alerts',
      '/api/playground',
    ],
  },
  {
    kind: 'route',
    where: 'M2-08',
    names: [
      '/api/teams',
      '/api/teams/{id}/sessions',
      '/api/teams/{id}/chat',
      '/api/teams/{id}/activity',
      '/api/teams/{id}/slots/{slot}/instructions',
      '/api/teams/{id}/archive',
      '/api/teams/{id}/economics',
      '/api/projects/{id}/files',
    ],
  },
  {
    kind: 'route',
    where: 'M3-01',
    names: ['/api/events'],
  },
];

const EXCLUDED: readonly ExcludedGroup[] = [
  {
    kind: 'route',
    reason: 'destructive, CLI only',
    names: ['/api/daemon/shutdown'],
  },
  {
    kind: 'route',
    reason: 'head-internal',
    names: ['/launch/{head}', '/statusline/{head}'],
  },
  {
    kind: 'route',
    // FEATURES.md section 2.1 row, added 2026-09-18: the hosted servers'
    // streamable-HTTP transport for clients, not an operator surface.
    reason: 'client transport, not an operator surface',
    names: ['/mcp/{name}'],
  },
  {
    kind: 'route',
    // The console's own artifact, not a daemon surface under it.
    reason: 'it is the console',
    names: ['/', '/dashboard'],
  },
];

export const dispositions: readonly Disposition[] = [
  ...PENDING.flatMap((group) =>
    group.names.map((name): Disposition => ({ kind: group.kind, name, disposition: 'pending', where: group.where })),
  ),
  ...EXCLUDED.flatMap((group) =>
    group.names.map((name): Disposition => ({ kind: group.kind, name, disposition: 'excluded', reason: group.reason })),
  ),
];
