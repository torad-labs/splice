// Every word this widget prints (docs/design/DESIGN.md section 10): labels, three words or fewer,
// sentence case, and U for the words printed beside a figure. The one-line help lives in copy.ts.
import type { Provenance } from '@entities/config';

export const S = {
  save: 'Save',
  pending: 'Restart pending',
  changed: 'Changed',
  reset: 'Reset to default',
  ownValue: 'Own value',
  useGlobal: 'Use global value',
  on: 'On',
  off: 'Off',
  unset: 'Not set',
  /** A picker's "not set" option: the daemon fills it with each model's own default. */
  modelDefault: 'Model default',
  live: 'Applies live',
  /** A knob the daemon reads at start: a saved change takes effect when it restarts. Said as a
   *  fact about the knob, beside `Applies live`; `restart to apply` read as an order to restart
   *  now, on a page (mcp) that had changed nothing. */
  restart: 'Applies on restart',
  /** The list of knob groups the rack prints beside the knobs, to jump by. */
  groups: 'Knob groups',
  /** The layer chip's name: which layer the value came from. */
  source: 'Source',
  /** A knob only a head's own overrides set, printed read-only in the global view. */
  perHead: 'Per head',
  /** A knob the daemon forces, whatever is saved. */
  locked: 'Locked',
} as const;

/** The words beside a figure: what the scale measures the value against. */
export const U = {
  default: 'default',
  global: 'global',
} as const;

/** Where a value came from, in the operator's words. The entity's provenance names are the
 *  daemon's layers ("defaults table", "state file"); these say what the operator did to put the
 *  value there. A save in the console writes both the state file and the running layer, so both
 *  read Console. */
export const SOURCE_LABELS = {
  default: 'Default',
  'defaults table': 'TOML',
  'head override': 'Head override',
  'state file': 'Console',
  env: 'Environment',
  patch: 'Console',
} as const satisfies Record<Provenance, string>;

/** What each group of knobs is about, in the order the rack shows them. */
export const GROUP_LABELS = {
  limits: 'Load limits',
  retries: 'Retries and timeouts',
  reasoning: 'Reasoning',
  models: 'Models and tools',
  usage: 'Usage and budgets',
  mcp: 'Shared MCP servers',
  records: 'Recording and history',
  logins: 'ChatGPT and Grok',
  daemon: 'Daemon',
} as const;

/** Every knob's name as the operator reads it. The key stays visible beside it, small, because
 *  it is what splice.toml, the env and the CLI spell. The completeness test ties this table to
 *  Knob.kt in both directions. */
export const KNOB_LABELS = {
  activityRetentionDays: 'Message history',
  activityStoreHeads: 'Activity heads',
  authCacheMs: 'Login cache',
  budgetDefaultAction: 'Budget default',
  chatgptApiBase: 'ChatGPT API URL',
  codexAuthPath: 'ChatGPT login file',
  contextWindowOverride: 'Context window',
  controlPort: 'Console port',
  debug: 'Debug logging',
  effort: 'Reasoning effort',
  firstByteTimeoutMs: 'First output wait',
  foldMarkerText: 'Fold marker',
  foldMaxContinue: 'Fold rounds',
  foldMaxTier: 'Fold tier cap',
  foldReasoningModels: 'Fold models',
  grokAuthPath: 'Grok login file',
  grokModel: 'Grok model',
  grokPort: 'Grok head port',
  materializationPermits: 'Parallel conversions',
  maxInflight: 'Concurrent turns',
  maxQueued: 'Queued turns',
  maxRequestBytes: 'Max request size',
  mcpIdleTimeoutMs: 'Idle shutdown',
  mcpInitializeTimeoutMs: 'Startup time limit',
  mcpMaxServers: 'Max servers',
  mcpRequestTimeoutMs: 'Call time limit',
  mcpSlice: 'Systemd slice',
  mirrorReasoning: 'Mirror reasoning',
  perfArchiveRetentionDays: 'Turn stats history',
  pinnedModel: 'ChatGPT model',
  port: 'ChatGPT head port',
  progressLine: 'Progress line',
  quotaPoll: 'Plan usage polling',
  quotaPollIntervalMs: 'Usage poll interval',
  replayReasoning: 'Replay reasoning',
  requestReadTimeoutMs: 'Request read limit',
  retryBackoffBaseMs: 'First retry delay',
  retryBackoffCapMs: 'Longest retry delay',
  retryBackoffJitterPct: 'Retry jitter',
  showReasoning: 'Show reasoning',
  stallReanchorMs: 'Stall resume',
  statuslineGitRoots: 'Git roots',
  streamIdleMs: 'Silence check',
  summary: 'Reasoning summary',
  supervisorUnit: 'Systemd unit',
  toolSurface: 'Deferred tools',
  trace: 'Full trace',
  traceMaxBodyChars: 'Trace body cap',
  traceRetentionDays: 'Trace history',
  upstreamRetries: 'Retry attempts',
  upstreamTimeoutMs: 'Turn time limit',
  usageWarnPct: 'Usage warning',
  usageWarnTokens5h: '5h token warning',
  wireTap: 'Wire tap',
  xaiApiBase: 'Grok API URL',
} as const;
