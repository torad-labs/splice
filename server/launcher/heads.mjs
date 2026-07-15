// Shared head-process lifecycle — the ONE source of truth for health / spawn /
// kill, used by BOTH the CLI launcher (ensure-proxy.mjs) and the control server
// (control/api.mjs). No forked process logic: the launcher orchestrates a
// version handshake + exec handoff ON TOP of these primitives; the control
// server orchestrates start/stop/restart ON TOP of the SAME primitives.
import { execFileSync, spawn } from 'node:child_process';
import { mkdirSync, openSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CONFIG_ENV_NAMES, getConfig, logsDir } from '../src/config.mjs';
import { CODEX_PROXY_VERSION } from '../src/versions.mjs';
import { proxyLogName } from '../src/mgmt/api.mjs';

const SRC_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ── registry ─────────────────────────────────────────────────────────────────
// The head catalog the control server iterates. grok joins here when its proxy
// conductor lands (portKey: 'grokPort', authKind: 'grok', label: 'claude-grok').
export const HEAD_REGISTRY = Object.freeze({
  codex: { key: 'codex', name: 'codex-proxy', scriptName: 'codex-proxy.mjs', portKey: 'port', version: CODEX_PROXY_VERSION, authKind: 'codex', label: 'claudex' },
});

export function listHeads() {
  return Object.keys(HEAD_REGISTRY);
}

export function resolveHead(key) {
  const entry = HEAD_REGISTRY[key];
  if (!entry) throw new Error(`unknown head: ${key}`);
  return entry;
}

// ── health + handshake ───────────────────────────────────────────────────────
export async function proxyHealth(port) {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(1000) });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

async function waitForHealth(port, { tries = 40, intervalMs = 100 } = {}) {
  for (let i = 0; i < tries; i++) {
    await sleep(intervalMs);
    const h = await proxyHealth(port);
    if (h) return h;
  }
  return null;
}

/** Pure handshake decision — unit-tested (re-exported by ensure-proxy). */
export function decideAction({ health, wantVersion, wantMode = null }) {
  if (!health || health.ok !== true) return 'start';
  if (String(health.version) !== String(wantVersion)) return 'restart';
  if (wantMode && health.mode !== wantMode) return 'patch-mode';
  return 'ok';
}

// ── process discovery + kill ─────────────────────────────────────────────────
/** Exclude our own process tree from a kill candidate list — unit-tested. */
export function filterSelf(pids, self = { pid: process.pid, ppid: process.ppid }) {
  return pids.filter((pid) => Number.isFinite(pid) && pid > 1 && pid !== self.pid && pid !== self.ppid);
}

/**
 * pgrep pattern for OUR instance on OUR port only (unit-tested). Spawns are
 * tagged with an inert --instance=<port> argv so a side-port test head can never
 * name-match the production proxy — nor the old v29 fork, whose same-named script
 * carries no tag. Anything else dies only via the PORT it actually holds.
 */
export function stalePattern(scriptName, port) {
  return `${scriptName}.*--instance=${port}(\\s|$)`;
}

function pgrep(pattern) {
  try {
    const out = execFileSync('pgrep', ['-f', pattern], { encoding: 'utf8' });
    return out.split('\n').filter(Boolean).map((s) => parseInt(s, 10));
  } catch {
    return []; // no matches
  }
}

function pidsOnPort(port) {
  try {
    const out = execFileSync('lsof', ['-ti', `tcp:${port}`, '-sTCP:LISTEN'], { encoding: 'utf8' });
    return out.split('\n').filter(Boolean).map((s) => parseInt(s, 10));
  } catch {
    return [];
  }
}

/** Live pids that ARE our tagged instance on this port (name-match ∪ port-hold). */
export function headPids(scriptName, port) {
  return filterSelf([...new Set([...pgrep(stalePattern(scriptName, port)), ...pidsOnPort(port)])]);
}

export async function killStale({ scriptName, port }) {
  const namePattern = stalePattern(scriptName, port);
  const candidates = filterSelf([...new Set([...pgrep(namePattern), ...pidsOnPort(port)])]);
  if (!candidates.length) return;
  process.stderr.write(`launcher: killing stale proxy pid(s) ${candidates.join(', ')}\n`);
  for (const pid of candidates) {
    try { process.kill(pid, 'SIGTERM'); } catch { /* already gone */ }
  }
  for (let i = 0; i < 20; i++) {
    await sleep(100);
    if (!filterSelf([...new Set([...pgrep(namePattern), ...pidsOnPort(port)])]).length) return;
  }
  // escalation: whoever still HOLDS the port is the blocker
  for (const pid of filterSelf(pidsOnPort(port))) {
    try { process.kill(pid, 'SIGKILL'); } catch { /* already gone */ }
  }
  await sleep(200);
}

// ── spawn ────────────────────────────────────────────────────────────────────
/**
 * Spawn a detached proxy tagged with --instance=<port> for stalePattern.
 * stripConfigEnv (the control-server path) removes inherited config env so
 * config.json — the dashboard's source of truth — wins; the CLI launcher leaves
 * it false because there env IS the boot authority (assembleClaudexEnv pins it).
 */
export function spawnProxy({ scriptName, port, proxyEnv = {}, logName, stripConfigEnv = false }) {
  mkdirSync(logsDir(), { recursive: true });
  const logPath = join(logsDir(), logName);
  const fd = openSync(logPath, 'a');
  const env = { ...process.env };
  if (stripConfigEnv) for (const name of CONFIG_ENV_NAMES) delete env[name];
  Object.assign(env, proxyEnv);
  // The proxy process must never inherit the caller's Anthropic routing.
  delete env.ANTHROPIC_BASE_URL;
  delete env.ANTHROPIC_API_KEY;
  delete env.ANTHROPIC_AUTH_TOKEN;
  const child = spawn(process.execPath, [join(SRC_DIR, scriptName), `--instance=${port}`], {
    detached: true,
    stdio: ['ignore', fd, fd],
    env,
  });
  child.unref();
  return logPath;
}

// ── control-server lifecycle (start / stop / restart / status) ───────────────
/** Live status for one head — /health for liveness+version, pgrep/lsof for pids.
 * All file-based (config, port) truth resolves from the shared config. */
export async function headStatus(key) {
  const entry = resolveHead(key);
  const port = getConfig()[entry.portKey];
  const health = await proxyHealth(port);
  const pids = headPids(entry.scriptName, port);
  return {
    key: entry.key,
    label: entry.label,
    name: entry.name,
    port,
    authKind: entry.authKind,
    wantVersion: entry.version,
    running: Boolean(health) || pids.length > 0,
    healthy: Boolean(health?.ok),
    version: health?.version ?? null,
    versionMatch: health ? String(health.version) === String(entry.version) : null,
    mode: health?.mode ?? null,
    gate: health?.gate ?? null,
    maxInflight: health?.max_inflight ?? null,
    pids,
  };
}

export async function statusAll() {
  return Promise.all(listHeads().map((k) => headStatus(k)));
}

/** Start a head if it isn't already up at the wanted version. Spawns with a
 * clean config env (config.json authoritative), then waits for /health. */
export async function startHead(key) {
  const entry = resolveHead(key);
  const port = getConfig()[entry.portKey];
  const existing = await proxyHealth(port);
  if (existing && String(existing.version) === String(entry.version)) {
    return { ...(await headStatus(key)), started: false, note: 'already running' };
  }
  if (existing) await killStale({ scriptName: entry.scriptName, port }); // wrong version — clear the port first
  const logPath = spawnProxy({
    scriptName: entry.scriptName,
    port,
    proxyEnv: {},
    logName: proxyLogName(entry.name, port),
    stripConfigEnv: true,
  });
  const health = await waitForHealth(port);
  return { ...(await headStatus(key)), started: Boolean(health), logPath };
}

export async function stopHead(key) {
  const entry = resolveHead(key);
  const port = getConfig()[entry.portKey];
  await killStale({ scriptName: entry.scriptName, port });
  return { ...(await headStatus(key)), stopped: true };
}

export async function restartHead(key) {
  await stopHead(key);
  return startHead(key);
}
