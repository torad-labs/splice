// Layered runtime config — defaults ← state config.json ← env ← runtime PATCH.
//
// getConfig() is called PER REQUEST and merges layers fresh each time; there are
// no frozen module consts (v29 froze every knob at import, so nothing was
// hot-tunable and tests had to set env before import). The env layer is read
// live so launcher-provided env stays authoritative at boot, and /mgmt/config
// PATCH (the runtime layer) sits above it for hot changes.
//
// External contract (byte-identical paths — the out-of-repo HUD reads them):
//   ~/.claude-codex/state/codex-usage.json
//   ~/.claude-codex/state/codex-ratelimit.json
//   ~/.claude-codex/claudex-compact-stats.jsonl
// New state introduced by mythos lives in the same dirs:
//   ~/.claude-codex/state/config.json   (file layer + PATCH persistence)
//   ~/.claude-codex/state/mgmt-key      (management API bearer key)
//   ~/.claude-codex/logs/               (proxy logs; moved out of /tmp)
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

// CLAUDEX_STATE_DIR exists for the hermetic test suites only; production always
// uses the contract path.
export function stateDir() {
  return process.env.CLAUDEX_STATE_DIR || join(homedir(), '.claude-codex', 'state');
}
export function logsDir() {
  return join(dirname(stateDir()), 'logs');
}
export const statePaths = {
  usage: () => join(stateDir(), 'codex-usage.json'),
  ratelimit: () => join(stateDir(), 'codex-ratelimit.json'),
  config: () => join(stateDir(), 'config.json'),
  mgmtKey: () => join(stateDir(), 'mgmt-key'),
  compactStats: () => join(dirname(stateDir()), 'claudex-compact-stats.jsonl'),
};

export const DEFAULTS = Object.freeze({
  // codex head
  port: 3099,
  chatgptApiBase: 'https://chatgpt.com/backend-api/codex',
  codexAuthPath: join(homedir(), '.codex', 'auth.json'),
  pinnedModel: 'gpt-5.6-sol',
  effort: null,            // env/config fallback for turns with no thinking budget (v27 chain)
  summary: null,           // reasoning summary fallback
  showReasoning: 'text',   // off | thinking | text — install default is text (load-bearing mirror)
  maxInflight: 0,          // 0 = unlimited
  upstreamRetries: 2,
  upstreamTimeoutMs: 900_000,
  firstByteTimeoutMs: 300_000, // v29: large-context prefill needs minutes before first byte
  streamIdleMs: 180_000,
  authCacheMs: 60_000,
  debug: false,
  contextWindowOverride: null, // number | null — CODEX_MODEL_CONTEXT_WINDOW
  // claudithos head
  claudithosPort: 3098,
  claudithosMode: 'mirror', // native | amnesia | mirror
  anthropicUpstream: 'https://api.anthropic.com',
  claudeCredentialsPath: join(homedir(), '.claude', '.credentials.json'),
});

// Everything else hot-applies on the next request.
export const RESTART_REQUIRED_KEYS = Object.freeze(['port', 'claudithosPort', 'upstreamTimeoutMs']);

const ENV_MAP = {
  port: ['CODEX_PROXY_PORT'],
  chatgptApiBase: ['CHATGPT_API_BASE'],
  codexAuthPath: ['CODEX_AUTH_PATH'],
  pinnedModel: ['CLAUDEX_PINNED_MODEL', 'CLAUDEX_MODEL'],
  effort: ['CLAUDEX_REASONING_EFFORT', 'CODEX_REASONING_EFFORT'],
  summary: ['CLAUDEX_REASONING_SUMMARY', 'CODEX_REASONING_SUMMARY'],
  showReasoning: ['CLAUDEX_SHOW_REASONING', 'CODEX_SHOW_REASONING'],
  maxInflight: ['CLAUDEX_MAX_INFLIGHT'],
  upstreamRetries: ['CLAUDEX_UPSTREAM_RETRIES'],
  upstreamTimeoutMs: ['CLAUDEX_UPSTREAM_TIMEOUT_MS'],
  firstByteTimeoutMs: ['CLAUDEX_FIRST_BYTE_TIMEOUT_MS'],
  streamIdleMs: ['CLAUDEX_STREAM_IDLE_MS'],
  authCacheMs: ['CLAUDEX_AUTH_CACHE_MS'],
  debug: ['CLAUDEX_DEBUG', 'CODEX_PROXY_DEBUG'],
  contextWindowOverride: ['CODEX_MODEL_CONTEXT_WINDOW'],
  claudithosPort: ['CLAUDITHOS_PORT'],
  claudithosMode: ['CLAUDITHOS_MODE'],
  anthropicUpstream: ['ANTHROPIC_UPSTREAM'],
  claudeCredentialsPath: ['CLAUDE_CREDENTIALS_PATH'],
};

const NUMBER_KEYS = new Set([
  'port', 'maxInflight', 'upstreamRetries', 'upstreamTimeoutMs', 'firstByteTimeoutMs',
  'streamIdleMs', 'authCacheMs', 'contextWindowOverride', 'claudithosPort',
]);
const BOOL_KEYS = new Set(['debug']);

function coerce(key, raw) {
  if (raw == null) return undefined;
  if (BOOL_KEYS.has(key)) {
    if (typeof raw === 'boolean') return raw;
    return /^(1|true|yes|on)$/i.test(String(raw).trim());
  }
  if (NUMBER_KEYS.has(key)) {
    if (key === 'maxInflight') {
      const s = String(raw).trim().toLowerCase();
      if (s === '' || s === 'unlimited' || s === 'off' || s === 'none') return 0;
    }
    const n = parseInt(String(raw), 10);
    return Number.isFinite(n) ? n : undefined;
  }
  const s = String(raw).trim();
  return s === '' ? undefined : s;
}

function normalize(cfg) {
  const out = { ...cfg };
  out.chatgptApiBase = String(out.chatgptApiBase).replace(/\/$/, '');
  out.anthropicUpstream = String(out.anthropicUpstream).replace(/\/$/, '');
  out.maxInflight = Math.max(0, out.maxInflight || 0);
  out.upstreamRetries = Math.max(1, out.upstreamRetries || DEFAULTS.upstreamRetries);
  out.upstreamTimeoutMs = Math.max(30_000, out.upstreamTimeoutMs || DEFAULTS.upstreamTimeoutMs);
  out.firstByteTimeoutMs = Math.max(10_000, out.firstByteTimeoutMs || DEFAULTS.firstByteTimeoutMs);
  const idleFloor = process.env.CODEX_PROXY_TEST === '1' ? 250 : 30_000;
  out.streamIdleMs = Math.max(idleFloor, out.streamIdleMs || DEFAULTS.streamIdleMs);
  out.authCacheMs = Math.max(5_000, out.authCacheMs || DEFAULTS.authCacheMs);
  if (!['native', 'amnesia', 'mirror'].includes(String(out.claudithosMode).toLowerCase())) {
    out.claudithosMode = DEFAULTS.claudithosMode;
  } else {
    out.claudithosMode = String(out.claudithosMode).toLowerCase();
  }
  out.showReasoning = normalizeShowReasoning(out.showReasoning);
  if (out.contextWindowOverride != null && !(out.contextWindowOverride > 0)) out.contextWindowOverride = null;
  return out;
}

export function normalizeShowReasoning(raw) {
  const v = String(raw ?? '').trim().toLowerCase();
  if (!v || v === '0' || v === 'false' || v === 'off' || v === 'none') return 'off';
  if (v === 'text' || v === 'mirror' || v === 'full' || v === 'both' || v === '2') return 'text';
  return 'thinking';
}

// ── layers ────────────────────────────────────────────────────────────────────
let fileCache = { path: null, mtimeMs: -1, data: {} };
const runtimeLayer = {};

function fileLayer() {
  const path = statePaths.config();
  try {
    if (!existsSync(path)) return {};
    const st = statSync(path);
    if (fileCache.path === path && fileCache.mtimeMs === st.mtimeMs) return fileCache.data;
    const parsed = JSON.parse(readFileSync(path, 'utf8'));
    const data = {};
    for (const key of Object.keys(DEFAULTS)) {
      const v = coerce(key, parsed[key]);
      if (v !== undefined) data[key] = v;
    }
    fileCache = { path, mtimeMs: st.mtimeMs, data };
    return data;
  } catch {
    return {};
  }
}

function envLayer() {
  const data = {};
  for (const [key, envNames] of Object.entries(ENV_MAP)) {
    for (const name of envNames) {
      if (process.env[name] != null && process.env[name] !== '') {
        const v = coerce(key, process.env[name]);
        if (v !== undefined) data[key] = v;
        break;
      }
    }
  }
  return data;
}

export function getConfig() {
  return normalize({ ...DEFAULTS, ...fileLayer(), ...envLayer(), ...runtimeLayer });
}

export function configLayers() {
  return { defaults: { ...DEFAULTS }, file: fileLayer(), env: envLayer(), runtime: { ...runtimeLayer } };
}

/** Hot-apply a partial config (mgmt PATCH). Persists to the file layer so the
 * change survives restart (env still wins at next boot — the launcher is the
 * boot authority). Returns which keys need a restart to take effect. */
export function patchConfig(partial) {
  const applied = {};
  const rejected = {};
  for (const [key, raw] of Object.entries(partial ?? {})) {
    if (!(key in DEFAULTS)) {
      rejected[key] = 'unknown key';
      continue;
    }
    const v = raw === null ? null : coerce(key, raw);
    if (v === undefined) {
      rejected[key] = 'invalid value';
      continue;
    }
    applied[key] = v;
    if (v === null) delete runtimeLayer[key];
    else runtimeLayer[key] = v;
  }
  if (Object.keys(applied).length) {
    try {
      mkdirSync(stateDir(), { recursive: true });
      const path = statePaths.config();
      let onDisk = {};
      try { onDisk = existsSync(path) ? JSON.parse(readFileSync(path, 'utf8')) : {}; } catch { onDisk = {}; }
      for (const [k, v] of Object.entries(applied)) {
        if (v === null) delete onDisk[k];
        else onDisk[k] = v;
      }
      writeFileSync(path, JSON.stringify(onDisk, null, 2) + '\n');
      fileCache = { path: null, mtimeMs: -1, data: {} };
    } catch { /* persistence is best-effort; the runtime layer already applied */ }
  }
  const restartRequired = Object.keys(applied).filter((k) => RESTART_REQUIRED_KEYS.includes(k));
  return { applied, rejected, restartRequired, effective: getConfig() };
}

export function resetRuntimeConfigForTests() {
  for (const k of Object.keys(runtimeLayer)) delete runtimeLayer[k];
  fileCache = { path: null, mtimeMs: -1, data: {} };
}
