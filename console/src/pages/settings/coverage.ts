// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads.
//
// Every one of them was `pending: M2-05` in the baseline; this file is the row honouring that.
// The disposition says what the CONSOLE does with the name, not what the daemon does with it.
//
// Two vocabularies meet here and they are not the same one. The denominator parses KNOBS by their
// enum entry name (`PORT`) and TOPOLOGY keys by their `@SerialName` value (`control_port`), because
// those are the identifiers the sources of record declare. The console's form is keyed by the
// config KEY (`port`), which is what the daemon's payload carries and what the operator types.
// Both are listed below, each in its own group, because the wall counts the first and the page
// renders the second.
import type { Disposition } from '@shared/coverage';

type State = 'editable' | 'read-only';

function entries(kind: Disposition['kind'], state: State, names: readonly string[], reason?: string): Disposition[] {
  return names.map((name) => ({
    kind,
    name,
    disposition: state,
    ...(reason === undefined ? {} : { reason }),
  }));
}

/**
 * The runtime knobs (FEATURES 2.2). Editable through `PATCH /api/config`, which is the route the
 * console already has; `hot` is read from the payload's `restart_required_keys` and never from
 * this file, so a knob that becomes live needs no edit here.
 */
const EDITABLE_KNOBS = [
  'PORT', 'CODEX_AUTH_PATH', 'PINNED_MODEL', 'EFFORT', 'SUMMARY', 'SHOW_REASONING',
  'REPLAY_REASONING', 'MIRROR_REASONING', 'PROGRESS_LINE', 'FOLD_REASONING_MODELS',
  'FOLD_MAX_CONTINUE', 'FOLD_MARKER_TEXT', 'FOLD_MAX_TIER', 'TOOL_SURFACE', 'QUOTA_POLL',
  'QUOTA_POLL_INTERVAL_MS', 'MAX_INFLIGHT', 'MAX_QUEUED', 'UPSTREAM_RETRIES',
  'RETRY_BACKOFF_BASE_MS', 'RETRY_BACKOFF_CAP_MS', 'RETRY_BACKOFF_JITTER_PCT',
  'UPSTREAM_TIMEOUT_MS', 'FIRST_BYTE_TIMEOUT_MS', 'STREAM_IDLE_MS', 'STALL_REANCHOR_MS',
  'AUTH_CACHE_MS', 'DEBUG', 'CONTEXT_WINDOW_OVERRIDE', 'GROK_AUTH_PATH', 'CONTROL_PORT',
  'USAGE_WARN_PCT', 'USAGE_WARN_TOKENS_5H', 'MAX_REQUEST_BYTES', 'REQUEST_READ_TIMEOUT_MS',
  'MATERIALIZATION_PERMITS', 'STATUSLINE_GIT_ROOTS',
  // Arrived with the per-head activity stores (f9e19d00), after this manifest was written at 45
  // names, and the wall went red on its next run — which is the wall working: the denominator is
  // parsed from Knob.kt, so the daemon growing a key is a red console until someone says what the
  // console does with it. Both are ordinary PATCH-able knobs; their restart-required flag reaches
  // the page from `restart_required_keys`, never from here.
  'ACTIVITY_RETENTION_DAYS', 'ACTIVITY_STORE_HEADS',
  // V4-174: the trace's retention and body cap. Ordinary restart-required knobs; they turn nothing on.
  'TRACE_RETENTION_DAYS', 'TRACE_MAX_BODY_CHARS',
  // V4-133 (console daemon table stakes): the budget default action fills a bare PUT /api/budgets
  // row and takes effect on the next PUT (no restart); the perf-archive retention days is ordinary
  // restart-required, read only once a head names an archiveDir. Both PATCH-able like any knob.
  'BUDGET_DEFAULT_ACTION', 'PERF_ARCHIVE_RETENTION_DAYS',
  // V4-176: the two names that make splice's supervision requirement an integration point instead
  // of an assertion about one box — the systemd user unit it restarts into and the slice hosted MCP
  // servers are spawned into. splice owns neither; it reads the names and reports what it finds.
  // Ordinary restart-required knobs the console edits like any other.
  'SUPERVISOR_UNIT', 'MCP_SLICE',
] as const;

/** The four shared-MCP host limits. They shape one McpHost read once at ControlPlane.start, which
 *  makes them restart-required like 47 other knobs, not read-only: the rack edits them and prints
 *  "restart to apply", and the MCP page shows them and points here. (They were declared read-only
 *  while the rack rendered them editable, so this file and the page disagreed.) */
const MCP_KNOBS = ['MCP_IDLE_TIMEOUT_MS', 'MCP_MAX_SERVERS', 'MCP_REQUEST_TIMEOUT_MS', 'MCP_INITIALIZE_TIMEOUT_MS'] as const;

/** The ChatGPT-login and Grok-login knobs. These were declared "legacy single-head, edit the
 *  topology", which the daemon contradicts: HeadBuildInputs remaps EVERY ChatGPT-login and
 *  Grok-login head and provider through CodexLegacyKnobs / GrokLegacyKnobs, which copy these
 *  values OVER the port, pinned model and base URL the topology declares. The knob is the live
 *  control and the topology field is the one that is never read, so the rack edits them. */
const LEGACY_KNOBS = ['CHATGPT_API_BASE', 'GROK_PORT', 'GROK_MODEL', 'XAI_API_BASE'] as const;

/** The topology keys the forms write (FEATURES 2.3). Every edit is boot-only, which is what the
 *  page's backup-and-restart note is about. */
const EDITABLE_TOPOLOGY = [
  // `summary` and `effort` are `[daemon]` keys of FEATURES 2.3 but carry NO `@SerialName` in
  // Topology.kt, so the denominator does not enumerate them and they are not declared here. The
  // forms still show them, because the file carries them; the wall counts what the SOURCE declares.
  'control_port', 'state_dir', 'show_reasoning', 'replay_reasoning',
  'mirror_reasoning', 'fold_reasoning_models', 'fold_max_continue', 'fold_marker_text',
  'fold_max_tier', 'mcp_hosting', 'mcp_hosting_exclude',
  'base_url', 'extra_headers', 'extra_windows', 'window_rules', 'default_context_window',
  'discovery_prefix', 'pinned_model', 'context_window', 'system_prompt', 'system_prompt_file',
  'system_prompt_mode',
  'account_id_header', 'cache_key', 'effort_ceiling', 'summary_field', 'tool_choice',
  'reasoning_cache', 'parallel_tool_calls', 'websocket', 'code_mode', 'code_mode_workers',
  'code_mode_timeout_ms', 'code_mode_heap', 'code_mode_models', 'zstd_request_body', 'reasoning_effort',
  // Arrived with V4-163 (token reporting on local heads), the same way the two activity knobs
  // above did: a daemon key is a red console until someone says what the console does with it.
  // An ordinary boolean quirk the topology forms write, so it is editable like its neighbours.
  'stream_usage',
  // V4-165 (slot affinity on a llama-server head): an ordinary boolean quirk, editable like stream_usage.
  'slot_affinity',
  // d217408d (`splice models`): where a provider publishes its model list when that is not where
  // its dialect says. A plain provider string, editable like base_url; no turn ever reads it.
  'models_url',
  'tool_surface', 'mfjs', 'block_allowlist', 'strip_cache_control', 'synthesize_signatures',
  'map_thinking_adaptive', 'strip_sampling_params', 'reanchor_prefill', 'tool_name_cap',
  'defer_prefixes', 'min_deferred', 'search_limit', 'search_rounds',
  'config_dir', 'cache_read', 'cache_write',
] as const;

/**
 * The denominator's topology names that are not keys at all: the three `Dialect` values and the
 * three `SystemPromptMode` values are what a field may BE, not fields the file carries. The forms
 * offer them as choices inside `dialect` and `system_prompt_mode`; there is nothing to set by
 * their own name, which is exactly what read-only means here.
 */
const DIALECT_VALUES = ['openai-responses', 'openai-chat', 'anthropic-passthrough'] as const;
const MODE_VALUES = ['append', 'replace', 'strip'] as const;

/** Retired in code (2026-09-05) but still PARSED, so a config carrying it fails loudly at load.
 *  The daemon's `QuirksConfig.init` refuses it by name; the console must not offer it. */
const RETIRED_TOPOLOGY = ['compact_effort'] as const;

export const dispositions: readonly Disposition[] = [
  ...entries('knob', 'editable', EDITABLE_KNOBS),
  ...entries('knob', 'editable', MCP_KNOBS),
  ...entries('knob', 'editable', LEGACY_KNOBS),
  // v0.4.0 prompt-review: the wire tap (V4-173) and the trace (V4-174) keep a head's whole
  // conversations, so the daemon takes them from [heads.KEY.overrides] alone (Knob.headOnly) and
  // refuses them in PATCH. A head's view writes exactly that table, so they are editable there and
  // read-only in the global view (KnobCopy.headOnly).
  ...entries('knob', 'read-only', ['WIRE_TAP', 'TRACE'], 'per head only, in [heads.KEY.overrides]'),

  ...entries('topology', 'editable', EDITABLE_TOPOLOGY),
  ...entries('topology', 'read-only', DIALECT_VALUES, 'a dialect value, chosen per provider'),
  ...entries('topology', 'read-only', MODE_VALUES, 'a system prompt mode value'),
  ...entries('topology', 'read-only', RETIRED_TOPOLOGY, 'retired, the daemon refuses it'),

  // The routes this page reads and writes: `/api/config` (GET and PATCH) and `/api/topology`
  // (GET and PUT, the topology editor; V4-128 shipped it and the entry said pending until the
  // served-route wall made that fail, M4-06).
  //
  // V4-175: the three Claude head routes moved off `pending`. V4-129 shipped them and these
  // entries stayed as they were — the same staleness class as an allowlist entry for a fixture
  // that was edited: a disposition that says "not built yet" about a route the page is calling is
  // a manifest describing an earlier tree, and nothing fails while it does.
  { kind: 'route', name: '/api/config', disposition: 'editable' },
  { kind: 'route', name: '/api/topology', disposition: 'editable' },
  { kind: 'route', name: '/api/claude-head', disposition: 'read-only', reason: 'a status read; the mode changes through the two action routes beside it' },
  { kind: 'route', name: '/api/claude-head/wrap', disposition: 'editable' },
  { kind: 'route', name: '/api/claude-head/unwrap', disposition: 'editable' },
];
