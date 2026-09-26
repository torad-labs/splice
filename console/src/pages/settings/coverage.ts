// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads.
//
// Every one of them was `pending: M2-05` in the baseline; this file is the row honouring that.
// The disposition says what the CONSOLE does with the name, not what the daemon does with it.
//
// Two vocabularies meet here and they are not the same one. The denominator parses KNOBS by their
// enum entry name (`PORT`) and TOPOLOGY fields by their dotted path in the file
// (`daemon.control_port`, V4-312), because those are the identifiers the sources of record declare. The console's form is keyed by the
// config KEY (`port`), which is what the daemon's payload carries and what the operator types.
// Both are listed below, each in its own group, because the wall counts the first and the page
// renders the second.
import type { Disposition, PageJob } from '@shared/coverage';

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

/** Every field of splice.toml, by its path in the manifest the wall reads (V4-312,
 *  core/src/test/resources/topology-fields.tsv). The topology editor writes each one the file carries,
 *  in whatever table it sits (model.ts topologyTables), and every edit is boot-only, which is what the
 *  page's backup-and-restart note is about. A field the daemon grows is red here until it is listed. */
const EDITABLE_TOPOLOGY = [
  // [claude]: the directories every head's Claude Code shares by default.
  'claude.share',
  // [compaction]: the global rule, then per model and per project ([[compaction.project]]); V4-313
  // gives the project rows an editor on the Projects page.
  'compaction.file', 'compaction.instructions', 'compaction.model[].file', 'compaction.model[].instructions',
  'compaction.model[].model', 'compaction.project[].file', 'compaction.project[].instructions',
  'compaction.project[].model', 'compaction.project[].path',
  // [daemon]: `summary` and `effort` included, which the @SerialName parse never counted.
  'daemon.control_port', 'daemon.effort', 'daemon.fold_marker_text', 'daemon.fold_max_continue',
  'daemon.fold_max_tier', 'daemon.fold_reasoning_models', 'daemon.mcp_hosting', 'daemon.mcp_hosting_exclude',
  'daemon.mirror_reasoning', 'daemon.replay_reasoning', 'daemon.show_reasoning', 'daemon.state_dir',
  'daemon.summary',
  // [defaults]: any runtime knob by its config key.
  'defaults.*',
  // [heads.KEY]: the head, its tiers, its own [claude], its overrides and its rate cards.
  'heads.*.claude.command', 'heads.*.claude.config_dir', 'heads.*.claude.isolate', 'heads.*.context_window',
  'heads.*.discovery_prefix', 'heads.*.models[].id', 'heads.*.models[].slot', 'heads.*.overrides.*',
  'heads.*.pinned_model', 'heads.*.port', 'heads.*.provider', 'heads.*.rates.*.cache_read',
  'heads.*.rates.*.cache_write', 'heads.*.rates.*.input', 'heads.*.rates.*.long_context_cache_read',
  'heads.*.rates.*.long_context_cache_write', 'heads.*.rates.*.long_context_input',
  'heads.*.rates.*.long_context_output', 'heads.*.rates.*.long_context_over_input_tokens',
  'heads.*.rates.*.output', 'heads.*.system_prompt', 'heads.*.system_prompt_file',
  'heads.*.system_prompt_mode',
  // [projects."<root>"] (V4-124): a repo's standing prompt, globally and per head.
  'projects.*.heads.*.system_prompt', 'projects.*.heads.*.system_prompt_file',
  'projects.*.heads.*.system_prompt_mode', 'projects.*.system_prompt', 'projects.*.system_prompt_file',
  'projects.*.system_prompt_mode',
  // [providers.NAME]: dialect, auth, models and their cards (V4-240 long-context tier), discovery and
  // the quirks (stream_usage V4-163, slot_affinity V4-165); models_url is where `splice models` asks.
  'providers.*.auth.env', 'providers.*.auth.file', 'providers.*.auth.kind', 'providers.*.base_url',
  'providers.*.default_context_window', 'providers.*.dialect', 'providers.*.discovery.exclude',
  'providers.*.discovery.include', 'providers.*.extra_headers.*',
  'providers.*.extra_windows[].context_window', 'providers.*.extra_windows[].id', 'providers.*.local',
  'providers.*.models[].context_window', 'providers.*.models[].description', 'providers.*.models[].id',
  'providers.*.models[].label', 'providers.*.models[].rates.cache_read',
  'providers.*.models[].rates.cache_write', 'providers.*.models[].rates.input',
  'providers.*.models[].rates.long_context_cache_read',
  'providers.*.models[].rates.long_context_cache_write', 'providers.*.models[].rates.long_context_input',
  'providers.*.models[].rates.long_context_output',
  'providers.*.models[].rates.long_context_over_input_tokens', 'providers.*.models[].rates.output',
  'providers.*.models_url', 'providers.*.quirks.account_id_header', 'providers.*.quirks.block_allowlist',
  'providers.*.quirks.cache_key', 'providers.*.quirks.code_mode', 'providers.*.quirks.code_mode_heap',
  'providers.*.quirks.code_mode_models', 'providers.*.quirks.code_mode_timeout_ms',
  'providers.*.quirks.code_mode_workers', 'providers.*.quirks.effort_ceiling',
  'providers.*.quirks.map_thinking_adaptive', 'providers.*.quirks.mfjs',
  'providers.*.quirks.parallel_tool_calls', 'providers.*.quirks.reanchor_prefill',
  'providers.*.quirks.reasoning_cache', 'providers.*.quirks.reasoning_effort',
  'providers.*.quirks.slot_affinity', 'providers.*.quirks.store', 'providers.*.quirks.stream_usage',
  'providers.*.quirks.strip_cache_control', 'providers.*.quirks.strip_sampling_params',
  'providers.*.quirks.summary_field', 'providers.*.quirks.synthesize_signatures',
  'providers.*.quirks.tool_choice', 'providers.*.quirks.tool_name_cap',
  'providers.*.quirks.tool_surface.defer', 'providers.*.quirks.tool_surface.defer_prefixes',
  'providers.*.quirks.tool_surface.eager', 'providers.*.quirks.tool_surface.enabled',
  'providers.*.quirks.tool_surface.min_deferred', 'providers.*.quirks.tool_surface.search_limit',
  'providers.*.quirks.tool_surface.search_rounds', 'providers.*.quirks.websocket',
  'providers.*.quirks.zstd_request_body', 'providers.*.window_rules[].context_window',
  'providers.*.window_rules[].prefix',
] as const;

/** Retired in code (2026-09-05) but still PARSED, so a config carrying it fails loudly at load.
 *  The daemon's `QuirksConfig.init` refuses it by name; the console must not offer it. */
const RETIRED_TOPOLOGY = ['providers.*.quirks.compact_effort'] as const;

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
  // The CLI verbs this page answers (V4-219: every CLI capability has a console answer; CommandParser.kt).
  { kind: 'verb', name: 'init', disposition: 'excluded', reason: 'writes the first splice.toml and state before any daemon runs; the console exists only on a running one' },
  { kind: 'verb', name: 'setup', disposition: 'excluded', reason: 'the first-run wizard that installs and starts the daemon this console runs on' },
  { kind: 'verb', name: 'dashboard', disposition: 'excluded', reason: 'opens this console' },
  { kind: 'verb', name: 'shim-version', disposition: 'excluded', reason: 'the client shim\'s build stamp for the installer\'s own check; doctor shows the versions an operator reads' },
  // splice-lead, 2026-09-25: install is Doctor's (its Fix runs install --all); uninstall stays CLI-only.
  // Its reason, read from UninstallCommand.kt: it deletes wrapper symlinks, not the daemon.
  { kind: 'verb', name: 'uninstall', disposition: 'excluded', reason: 'deletes wrapper commands from the operator\'s bin directory, splice itself with --all; destructive, CLI only' },
];

/** What this page is for (V4-219, rendered into docs/design/JOBS.md). */
export const job: PageJob = {
  question: 'How is the daemon set up, and what does a change do?',
  leaves: 'Every runtime knob, each head\'s splice.toml table and the Claude head\'s mode, and which changes need a restart.',
  actions: [
    { name: 'Edit a knob' },
    { name: 'Edit a head\'s topology' },
    { name: 'Wrap or unwrap the Claude head' },
  ],
};
