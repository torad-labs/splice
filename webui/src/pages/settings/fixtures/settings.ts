// Sample data for the design capture, under the fixture rule (CONTRACTS.md section 4): it loads
// only when `import.meta.env.DEV` is true and the address carries `?fixture=<name>`, the shipped
// dist carries none of it, and the page prints a grey `sample data` holder edge while it is shown.
//
// Both payloads are shaped from the real contracts — the config payload is the daemon's layered
// answer (GET /api/config) and the topology is a plausible splice.toml — so the capture shows the
// page the daemon will actually produce and not a flattering rearrangement of it.
import type { ConfigPayload } from '@shared/api';


/** Every runtime knob, with the provenance spread the real daemon produces. */
const EFFECTIVE = {
  port: 3099, chatgptApiBase: 'https://chatgpt.com/backend-api/codex',
  codexAuthPath: '~/.local/share/splice/auth/chatgpt.json', pinnedModel: 'gpt-5.6-sol',
  effort: 'high', summary: 'detailed', showReasoning: 'text', replayReasoning: false,
  mirrorReasoning: false, progressLine: true, foldReasoningModels: 'gpt-5.6-luna,gpt-5.6-terra',
  foldMaxContinue: 3, foldMarkerText: 'Continue thinking...', foldMaxTier: 6,
  toolSurface: 'auto', quotaPoll: 'auto', quotaPollIntervalMs: 300000, maxInflight: 24,
  maxQueued: 512, upstreamRetries: 4, retryBackoffBaseMs: 200, retryBackoffCapMs: 10000,
  retryBackoffJitterPct: 10, upstreamTimeoutMs: 900000, firstByteTimeoutMs: 300000,
  streamIdleMs: 300000, stallReanchorMs: 20000, authCacheMs: 60000, debug: true,
  contextWindowOverride: null, grokPort: 3100, grokModel: 'grok-4.6',
  xaiApiBase: 'https://api.x.ai/v1', grokAuthPath: '~/.local/share/splice/auth/grok.json',
  controlPort: 3096, usageWarnPct: 75, usageWarnTokens5h: 0, mcpIdleTimeoutMs: 1800000,
  mcpMaxServers: 32, mcpRequestTimeoutMs: 1800000, mcpInitializeTimeoutMs: 60000,
  maxRequestBytes: 8388608, requestReadTimeoutMs: 30000, materializationPermits: 16,
  statuslineGitRoots: '',
  // Arrived with the per-head activity stores (f9e19d00). The values are Knob.kt's own defaults —
  // 90 days, every head — and both are restart-required, which the derived list below gets right
  // without an edit here.
  activityRetentionDays: 90, activityStoreHeads: '*',
  // V4-173: Knob.kt's default — off.
  wireTap: 0,
  // V4-174: Knob.kt's defaults — off, a week, 4 MiB of characters.
  trace: false, traceRetentionDays: 7, traceMaxBodyChars: 4194304,
  // V4-133: Knob.kt's defaults. budgetDefaultAction is hot (read live per PUT /api/budgets);
  // perfArchiveRetentionDays is restart-required like activityRetentionDays.
  budgetDefaultAction: 'warn', perfArchiveRetentionDays: 90,
  // V4-176: splice's supervision contract, as two names the operator supplies — the systemd user
  // unit it restarts into and the slice hosted MCP servers are spawned into. Both restart-required.
  // They are here because THIS FILE is compared to Knob.kt, not to a count: a fixture that lags the
  // daemon fails the arm above rather than quietly proving a claim over a stale sample.
  supervisorUnit: 'splice.service', mcpSlice: 'app-mcp.slice',
};

export const fixtureConfig: ConfigPayload = {
  effective: EFFECTIVE,
  head: 'claudex',
  layers: {
    defaults: { pinnedModel: 'gpt-5.6-sol', effort: 'high', summary: 'detailed' },
    toml: { port: 3099, progressLine: true },
    perHead: { claudex: { maxInflight: 24, effort: 'xhigh' } },
    file: { usageWarnPct: 75 },
    env: { debug: true },
    runtime: { quotaPoll: 'auto' },
  },
  // The four hot knobs (FEATURES 2.2's three, plus V4-133's live-read budget default), and
  // nothing else.
  restart_required_keys: Object.keys(EFFECTIVE).filter(
    (key) => !['maxInflight', 'maxQueued', 'statuslineGitRoots', 'budgetDefaultAction'].includes(key),
  ),
  source: 'fixture',
};

export const fixtureTopology: Record<string, unknown> = {
  daemon: { control_port: 3096, mcp_hosting: true, show_reasoning: 'text' },
  claude: { share: ['settings', 'mcps'], config_dir: '~/.config/splice/claude-splice' },
  compaction: { instructions: 'keep the ledger and the fences', model: [{ model: 'gpt-5.6-sol' }] },
  defaults: { effort: 'high' },
  providers: {
    codex: {
      dialect: 'openai-responses',
      base_url: 'https://chatgpt.com/backend-api/codex',
      auth: { kind: 'chatgpt-oauth' },
      quirks: { store: false, code_mode: true },
    },
  },
  heads: {
    claudex: {
      provider: 'codex', port: 3099, discovery_prefix: 'claude', pinned_model: 'gpt-5.6-sol',
      context_window: 400000, overrides: { effort: 'high' },
    },
  },
};
