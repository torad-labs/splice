// Sample data for the design capture, under the fixture rule (CONTRACTS.md section 4): it loads
// only when `import.meta.env.DEV` is true and the address carries `?fixture=<name>`, the shipped
// dist carries none of it, and the page prints a grey `sample data` holder edge while it is shown.
//
// Both payloads are shaped from the real contracts — the config payload is the daemon's layered
// answer (GET /api/config) and the topology is a plausible splice.toml — so the capture shows the
// page the daemon will actually produce and not a flattering rearrangement of it.
import type { ConfigPayload } from '@shared/api';

export const FIXTURE_NAME = 'settings';

/** The fixture this build was opened with, or null. Never true in a shipped dist. */
export function fixtureName(search: string, dev: boolean): string | null {
  if (!dev) return null;
  const asked = new URLSearchParams(search).get('fixture');
  return asked === FIXTURE_NAME ? asked : null;
}

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
  // The three hot knobs of FEATURES 2.2, and nothing else.
  restart_required_keys: Object.keys(EFFECTIVE).filter(
    (key) => !['maxInflight', 'maxQueued', 'statuslineGitRoots'].includes(key),
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
