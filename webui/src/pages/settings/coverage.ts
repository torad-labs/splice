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
  // V4-173: the per-head upstream wire tap (0 = off). An ordinary restart-required knob a head
  // reads at assembly; the console edits it like any other, and `splice doctor` warns while it is on.
  'WIRE_TAP',
  // V4-174: the per-head full request/response trace (off), its retention and body cap. Ordinary
  // restart-required knobs a head reads at assembly; `splice doctor` warns while the trace is on.
  'TRACE', 'TRACE_RETENTION_DAYS', 'TRACE_MAX_BODY_CHARS',
  // V4-133 (console daemon table stakes): the budget default action fills a bare PUT /api/budgets
  // row and takes effect on the next PUT (no restart); the perf-archive retention days is ordinary
  // restart-required, read only once a head names an archiveDir. Both PATCH-able like any knob.
  'BUDGET_DEFAULT_ACTION', 'PERF_ARCHIVE_RETENTION_DAYS',
] as const;

/** FEATURES 4.11: "The four host knobs, read-only with the reason." They shape one McpHost read
 *  once at ControlPlane.start, and a console that offered an editor would be offering a no-op. */
const MCP_KNOBS = ['MCP_IDLE_TIMEOUT_MS', 'MCP_MAX_SERVERS', 'MCP_REQUEST_TIMEOUT_MS', 'MCP_INITIALIZE_TIMEOUT_MS'] as const;

/** FEATURES 2.2: "Some are legacy single-head knobs (grokPort, grokModel, xaiApiBase,
 *  chatgptApiBase)". They are superseded by the provider and head tables, so the console prints
 *  them with their provenance and sends the operator to the topology editor rather than letting a
 *  second, older source of truth look live. */
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
  'code_mode_timeout_ms', 'code_mode_heap', 'zstd_request_body', 'reasoning_effort',
  // Arrived with V4-163 (token reporting on local heads), the same way the two activity knobs
  // above did: a daemon key is a red console until someone says what the console does with it.
  // An ordinary boolean quirk the topology forms write, so it is editable like its neighbours.
  'stream_usage',
  // V4-165 (slot affinity on a llama-server head): an ordinary boolean quirk, editable like stream_usage.
  'slot_affinity',
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
  ...entries('knob', 'read-only', MCP_KNOBS, 'host limits, read once at start'),
  ...entries('knob', 'read-only', LEGACY_KNOBS, 'legacy single-head, edit the topology'),

  ...entries('topology', 'editable', EDITABLE_TOPOLOGY),
  ...entries('topology', 'read-only', DIALECT_VALUES, 'a dialect value, chosen per provider'),
  ...entries('topology', 'read-only', MODE_VALUES, 'a system prompt mode value'),
  ...entries('topology', 'read-only', RETIRED_TOPOLOGY, 'retired, the daemon refuses it'),

  // The routes this page reads and writes. `/api/config` exists (GET and PATCH). `/api/topology`
  // does not: it is served by V4-128, and the surface that needs it renders the honest empty
  // naming that row.
  //
  // V4-175: the three Claude head routes moved off `pending`. V4-129 shipped them and these
  // entries stayed as they were — the same staleness class as an allowlist entry for a fixture
  // that was edited: a disposition that says "not built yet" about a route the page is calling is
  // a manifest describing an earlier tree, and nothing fails while it does.
  { kind: 'route', name: '/api/config', disposition: 'editable' },
  { kind: 'route', name: '/api/topology', disposition: 'pending', where: 'V4-128' },
  { kind: 'route', name: '/api/claude-head', disposition: 'read-only', reason: 'a status read; the mode changes through the two action routes beside it' },
  { kind: 'route', name: '/api/claude-head/wrap', disposition: 'editable' },
  { kind: 'route', name: '/api/claude-head/unwrap', disposition: 'editable' },
];
