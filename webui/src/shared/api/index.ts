// The control-plane HTTP client — the ONLY module in the app that talks to the
// network. Importable solely from entity api segments (lint-enforced boundary;
// an ast-grep wall additionally forbids fetch() anywhere else in webui/src).
// Served same-origin by the control server (spliced), which also hosts this
// dashboard at /; the bearer key comes from ~/.claude-codex/state/mgmt-key,
// pasted once by the operator.

const KEY_STORAGE = 'myx-mgmt-key';

export class MgmtError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export function getStoredKey(): string {
  try {
    return localStorage.getItem(KEY_STORAGE) ?? '';
  } catch {
    return '';
  }
}

export function storeKey(key: string): void {
  try {
    localStorage.setItem(KEY_STORAGE, key.trim());
  } catch { /* private mode: key lives for the session via module state below */ }
  sessionKey = key.trim();
  locked = false; // re-arm the pollers; the next tick retries with the new key
}

let sessionKey = getStoredKey();

// While locked (last response was 401) requests short-circuit without touching
// the network — no poller 401 spam behind the key gate. storeKey() re-arms.
let locked = false;

type UnauthorizedListener = () => void;
let onUnauthorized: UnauthorizedListener | null = null;
export function bindUnauthorized(fn: UnauthorizedListener): void {
  onUnauthorized = fn;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  if (locked) throw new MgmtError(401, 'management key required');
  const res = await fetch(path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${sessionKey}`,
      ...(init?.headers ?? {}),
    },
  });
  if (res.status === 401) {
    locked = true;
    onUnauthorized?.();
    throw new MgmtError(401, 'management key required');
  }
  const body = (await res.json().catch(() => null)) as T | { error?: { message?: string } } | null;
  if (!res.ok) {
    const message = (body as { error?: { message?: string } } | null)?.error?.message ?? `HTTP ${res.status}`;
    throw new MgmtError(res.status, message);
  }
  return body as T;
}

// ── payload types ────────────────────────────────────────────────────────────

export interface RegistryEntry {
  key: string;
  label: string;
  authKind: string;
}

export interface ControlStatusPayload {
  server: string;
  version: string;
  heads: string[];
  registry: RegistryEntry[];
}

export interface GateLive {
  label: string;
  compact: boolean;
  phase: 'connect' | 'streaming' | string;
  age_ms: number;
  idle_ms: number;
}

export interface GateSnapshot {
  inflight: number;
  queued: number;
  max: number | 'unlimited';
  acquired: number;
  released: number;
  waited: number;
  avg_wait_ms: number;
  live: GateLive[];
  stream_idle_ms: number;
}

export interface HeadStatus {
  key: string;
  label: string;
  name: string;
  port: number;
  authKind: string;
  wantVersion: string;
  running: boolean;
  healthy: boolean;
  version: string | null;
  versionMatch: boolean | null;
  mode: string | null;
  gate: GateSnapshot | null;
  maxInflight: number | null;
  pids: number[];
}

/** The lifecycle endpoints return the fresh head status plus a transient note. */
export interface HeadActionResult extends HeadStatus {
  started?: boolean;
  stopped?: boolean;
  note?: string;
  logPath?: string;
}

export interface HeadsPayload {
  heads: HeadStatus[];
}

export type ConfigValue = string | number | boolean | null;
export type EffectiveConfig = Record<string, ConfigValue>;

export interface ConfigPayload {
  effective: EffectiveConfig;
  layers: {
    defaults: EffectiveConfig;
    file: EffectiveConfig;
    env: EffectiveConfig;
    runtime: EffectiveConfig;
  };
  restart_required_keys: string[];
  source: string;
}

export interface ConfigPatchTarget {
  key: string;
  ok: boolean;
}

export interface PatchResult {
  applied: EffectiveConfig;
  rejected: Record<string, string>;
  restart_required: string[];
  targets: ConfigPatchTarget[];
  persisted: string;
}

export interface RatelimitState {
  limit_tokens: number;
  remaining_tokens: number | null;
  reset_tokens: string | null;
}

export type WarnLevel = 'ok' | 'warn' | 'critical';

export interface UsageWarn {
  level: WarnLevel;
  pct: number;
  source: string;
  reset: string | null;
}

export interface HeadUsage {
  output_tokens_5h: number;
  entries: number;
  ratelimit: RatelimitState | null;
  warn: UsageWarn;
}

export interface HeadUsageEntry {
  key: string;
  label: string;
  usage: HeadUsage | null;
}

export interface UsagePayload {
  window_hours: number;
  warn_pct: number;
  warn_tokens_5h: number;
  heads: HeadUsageEntry[];
}

export interface CompactRow {
  ts: number;
  outcome?: string;
  chars?: number;
  ms?: number;
  status?: number;
  error?: string;
}

export interface CompactPayload {
  stats: { total: number; by_outcome: Record<string, number>; tail: CompactRow[] };
}

export interface CodexAuth {
  kind: 'codex';
  login: string;
  present: boolean;
  account_id_masked: string | null;
  last_refresh: string | null;
  auth_path?: string;
  cached?: boolean;
  cache_age_ms?: number | null;
}

export interface ClaudeAuth {
  kind: 'claude';
  login: string;
  present: boolean;
  expires_at: number | null;
  cred_path?: string;
  cached?: boolean;
}

export interface AuthPayload {
  codex: CodexAuth;
  claude: ClaudeAuth;
}

/** POST /api/auth/:head/refresh|login — a transient outcome, not the full card
 * (callers re-fetch /api/auth for the authoritative card state). */
export interface AuthActionResult {
  head?: string;
  refreshed?: boolean;
  started?: boolean;
  note?: string;
}

export interface LogsPayload {
  key: string;
  path: string;
  lines: string[];
  note?: string;
}

// ── endpoints ────────────────────────────────────────────────────────────────

export const control = {
  status: () => request<ControlStatusPayload>('/api/status'),
  heads: () => request<HeadsPayload>('/api/heads'),
  startHead: (head: string) => request<HeadActionResult>(`/api/heads/${head}/start`, { method: 'POST' }),
  stopHead: (head: string) => request<HeadActionResult>(`/api/heads/${head}/stop`, { method: 'POST' }),
  restartHead: (head: string) => request<HeadActionResult>(`/api/heads/${head}/restart`, { method: 'POST' }),
  config: () => request<ConfigPayload>('/api/config'),
  patchConfig: (patch: Record<string, ConfigValue>) =>
    request<PatchResult>('/api/config', { method: 'PATCH', body: JSON.stringify(patch) }),
  usage: () => request<UsagePayload>('/api/usage'),
  auth: () => request<AuthPayload>('/api/auth'),
  refreshAuth: (head: string) => request<AuthActionResult>(`/api/auth/${head}/refresh`, { method: 'POST' }),
  loginAuth: (head: string) => request<AuthActionResult>(`/api/auth/${head}/login`, { method: 'POST' }),
  compact: () => request<CompactPayload>('/api/compact'),
  logs: (head: string, tail: number) => request<LogsPayload>(`/api/logs/${head}?tail=${tail}`),
};
