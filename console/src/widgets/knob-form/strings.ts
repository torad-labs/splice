// Every label this widget prints. Lowercase, three words or fewer, no em-dash (CONTRACTS.md
// section 4, enforced by the label wall).
import type { Provenance } from '@entities/config';

export const S = {
  save: 'save',
  pending: 'pending restart',
  unchanged: 'unchanged',
  changed: 'changed',
  reset: 'reset to default',
  ownValue: 'own value',
  useGlobal: 'use global value',
  on: 'on',
  off: 'off',
  unset: 'not set',
  live: 'applies live',
  restart: 'restart to apply',
} as const;

/** Where a value came from, in the operator's words. The entity's provenance names are the
 *  daemon's layers ("defaults table", "state file"); these say what the operator did to put the
 *  value there. */
export const SOURCE_LABELS = {
  default: 'default',
  'defaults table': 'splice.toml',
  'head override': 'head override',
  'state file': 'set in console',
  env: 'environment',
  patch: 'set in console',
} as const satisfies Record<Provenance, string>;

/** What each group of knobs is about, in the order the rack shows them. */
export const GROUP_LABELS = {
  limits: 'load limits',
  retries: 'retries and timeouts',
  reasoning: 'reasoning',
  models: 'models and tools',
  usage: 'usage and budgets',
  mcp: 'shared mcp servers',
  records: 'recording and history',
  logins: 'chatgpt and grok',
  daemon: 'daemon',
} as const;

/** Every knob's name as the operator reads it. The key stays visible beside it, small, because
 *  it is what splice.toml, the env and the CLI spell. The completeness test ties this table to
 *  Knob.kt in both directions. */
export const KNOB_LABELS = {
  activityRetentionDays: 'activity history',
  activityStoreHeads: 'activity heads',
  authCacheMs: 'login cache',
  budgetDefaultAction: 'budget default',
  chatgptApiBase: 'chatgpt api url',
  codexAuthPath: 'chatgpt login file',
  contextWindowOverride: 'context window',
  controlPort: 'console port',
  debug: 'debug logging',
  effort: 'reasoning effort',
  firstByteTimeoutMs: 'first output wait',
  foldMarkerText: 'fold marker',
  foldMaxContinue: 'fold rounds',
  foldMaxTier: 'fold tier cap',
  foldReasoningModels: 'fold models',
  grokAuthPath: 'grok login file',
  grokModel: 'grok model',
  grokPort: 'grok head port',
  materializationPermits: 'parallel conversions',
  maxInflight: 'concurrent turns',
  maxQueued: 'queued turns',
  maxRequestBytes: 'max request size',
  mcpIdleTimeoutMs: 'idle shutdown',
  mcpInitializeTimeoutMs: 'startup time limit',
  mcpMaxServers: 'max servers',
  mcpRequestTimeoutMs: 'call time limit',
  mcpSlice: 'systemd slice',
  mirrorReasoning: 'mirror reasoning',
  perfArchiveRetentionDays: 'turn stats history',
  pinnedModel: 'chatgpt model',
  port: 'chatgpt head port',
  progressLine: 'progress line',
  quotaPoll: 'plan usage polling',
  quotaPollIntervalMs: 'usage poll interval',
  replayReasoning: 'replay reasoning',
  requestReadTimeoutMs: 'request read limit',
  retryBackoffBaseMs: 'first retry delay',
  retryBackoffCapMs: 'longest retry delay',
  retryBackoffJitterPct: 'retry jitter',
  showReasoning: 'show reasoning',
  stallReanchorMs: 'stall resume',
  statuslineGitRoots: 'git roots',
  streamIdleMs: 'silence check',
  summary: 'reasoning summary',
  supervisorUnit: 'systemd unit',
  toolSurface: 'deferred tools',
  trace: 'full trace',
  traceMaxBodyChars: 'trace body cap',
  traceRetentionDays: 'trace history',
  upstreamRetries: 'retry attempts',
  upstreamTimeoutMs: 'turn time limit',
  usageWarnPct: 'usage warning',
  usageWarnTokens5h: '5h token warning',
  wireTap: 'wire tap',
  xaiApiBase: 'xai api url',
} as const;
