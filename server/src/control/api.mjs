// Aggregated control-plane API — the dashboard's single backend. Bearer-guarded
// /api/* spanning every head, served by control-server.mjs (loopback only).
//
// Split by data type, on purpose:
//   • config  — fanned out as PATCH /mgmt/config to each RUNNING head, so the
//               change lands in that head's runtime layer (which beats the env
//               layer the CLI launcher pins). Writing config.json alone would be
//               shadowed by env on a CLI-launched proxy.
//   • auth/usage/compact/logs — read straight from the shared state files via the
//               shared modules, so the dashboard still shows a head that is DOWN.
//   • status/lifecycle — heads.mjs (health ping + spawn/kill), one source shared
//               with the CLI launcher.
import { openSync, readFileSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readBody, sendJson } from '../http/server.mjs';
import { checkMgmtBearer, ensureMgmtKey, proxyLogName } from '../mgmt/api.mjs';
import { configLayers, getConfig, logsDir, patchConfig, RESTART_REQUIRED_KEYS, statePaths } from '../config.mjs';
import { computeUsageWarn } from '../usage/warn.mjs';
import { readCompactStats } from '../codex/compact.mjs';
import { describeCodexAuth, refreshCodexAuth } from '../auth/codex-oauth.mjs';
import { CONTROL_SERVER_VERSION } from '../versions.mjs';
import {
  HEAD_REGISTRY, headStatus, listHeads, proxyHealth, resolveHead,
  restartHead, startHead, statusAll, stopHead,
} from '../../launcher/heads.mjs';

const AUTH_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'auth');
const FIVE_HOURS_MS = 5 * 60 * 60 * 1000;

// Heads whose usage the proxy tracks (statePaths pair). grok joins when it lands.
const HEAD_USAGE_PATHS = {
  codex: { usage: statePaths.usage, ratelimit: statePaths.ratelimit },
  grok: { usage: statePaths.grokUsage, ratelimit: statePaths.grokRatelimit },
};

function readJsonFile(path) {
  try { return JSON.parse(readFileSync(path, 'utf8')); } catch { return null; }
}

// ── config: read effective (with a running head's runtime layer) ─────────────
async function mgmtFetch(port, path, { method = 'GET', body } = {}) {
  try {
    const res = await fetch(`http://127.0.0.1:${port}${path}`, {
      method,
      headers: { Authorization: `Bearer ${ensureMgmtKey()}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(3000),
    });
    return { ok: res.ok, status: res.status, json: await res.json().catch(() => null) };
  } catch (err) {
    return { ok: false, status: 0, json: null, error: String(err?.message || err) };
  }
}

async function runningTargets() {
  const cfg = getConfig();
  const targets = [];
  for (const key of listHeads()) {
    const entry = resolveHead(key);
    const port = cfg[entry.portKey];
    if (await proxyHealth(port)) targets.push({ key, port });
  }
  return targets;
}

async function readEffectiveConfig() {
  for (const { key, port } of await runningTargets()) {
    const r = await mgmtFetch(port, '/mgmt/config');
    if (r.ok && r.json) return { ...r.json, source: key };
  }
  return { effective: getConfig(), layers: configLayers(), restart_required_keys: RESTART_REQUIRED_KEYS, source: 'control' };
}

/** Apply a config patch to every running head (runtime layer wins over env). If
 * none run, persist to the file so a control-started head inherits it. */
async function patchConfigFanout(partial) {
  const targets = await runningTargets();
  if (!targets.length) {
    const r = patchConfig(partial);
    return { applied: r.applied, rejected: r.rejected, restart_required: r.restartRequired, targets: [], persisted: 'file' };
  }
  const results = [];
  for (const t of targets) {
    const r = await mgmtFetch(t.port, '/mgmt/config', { method: 'PATCH', body: partial });
    results.push({ key: t.key, ok: r.ok, applied: r.json?.applied, rejected: r.json?.rejected, restart_required: r.json?.restart_required });
  }
  // config is shared, so applied/rejected are identical across heads — surface one.
  const first = results.find((r) => r.ok) ?? results[0];
  return {
    applied: first?.applied ?? {},
    rejected: first?.rejected ?? {},
    restart_required: first?.restart_required ?? [],
    targets: results.map((r) => ({ key: r.key, ok: r.ok })),
    persisted: 'runtime+file',
  };
}

// ── usage aggregation + soft-warn ────────────────────────────────────────────
function readHeadUsage(key, cfg) {
  const paths = HEAD_USAGE_PATHS[key];
  if (!paths) return null;
  const usage = readJsonFile(paths.usage());
  const ratelimit = readJsonFile(paths.ratelimit());
  const cutoff = Date.now() - FIVE_HOURS_MS;
  const entries = Array.isArray(usage) ? usage.filter((e) => e && e.timestamp > cutoff) : [];
  const outputTokens5h = entries.reduce((s, e) => s + (e.output_tokens || 0), 0);
  return {
    output_tokens_5h: outputTokens5h,
    entries: entries.length,
    ratelimit,
    warn: computeUsageWarn({ outputTokens5h, ratelimit, warnPct: cfg.usageWarnPct, warnTokens5h: cfg.usageWarnTokens5h }),
  };
}

function usagePayload() {
  const cfg = getConfig();
  return {
    window_hours: 5,
    warn_pct: cfg.usageWarnPct,
    warn_tokens_5h: cfg.usageWarnTokens5h,
    heads: listHeads().map((key) => ({ key, label: resolveHead(key).label, usage: readHeadUsage(key, cfg) })),
  };
}

// ── auth ─────────────────────────────────────────────────────────────────────
function describeAllAuth() {
  return {
    codex: { kind: 'codex', login: 'automated', ...describeCodexAuth() },
  };
}

async function refreshAuth(head) {
  if (head === 'codex') {
    const fresh = await refreshCodexAuth();
    return { head, refreshed: Boolean(fresh?.token), ...describeCodexAuth() };
  }
  return null;
}

/** Kick off the browser OAuth flow (codex). Spawns the same login the CLI uses,
 * detached — it opens the browser + binds :1455 and writes auth.json; the
 * dashboard polls /api/auth to see it flip to present. */
function startLogin(head) {
  if (head !== 'codex') {
    return { started: false, note: 'browser login is only wired for the codex head' };
  }
  const script = join(AUTH_DIR, 'codex-login.mjs');
  const fd = openSync(join(logsDir(), 'codex-login.log'), 'a');
  const child = spawn(process.execPath, [script], { detached: true, stdio: ['ignore', fd, fd] });
  child.unref();
  return { started: true, note: 'Sign-in opened in your browser — complete it there; this page updates when done.' };
}

// ── logs ─────────────────────────────────────────────────────────────────────
function tailLog(key, tailN) {
  const entry = resolveHead(key);
  const port = getConfig()[entry.portKey];
  const path = join(logsDir(), proxyLogName(entry.name, port));
  try {
    const lines = readFileSync(path, 'utf8').split('\n').filter(Boolean);
    return { key, path, lines: lines.slice(-tailN) };
  } catch (err) {
    return { key, path, lines: [], note: String(err?.message || err) };
  }
}

// ── router ───────────────────────────────────────────────────────────────────
/** Handle an /api/* request. Returns true when consumed. */
export async function handleControlApi(req, res) {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (!url.pathname.startsWith('/api/')) return false;

  if (!checkMgmtBearer(req)) {
    sendJson(res, 401, { error: { type: 'authentication_error', message: `control: bearer key required (${statePaths.mgmtKey()})` } });
    return true;
  }

  const seg = url.pathname.split('/').filter(Boolean); // ['api', <resource>, ...]
  const resource = seg[1];
  const method = req.method;

  try {
    // ── server meta ──
    if (resource === 'status' && method === 'GET') {
      sendJson(res, 200, { server: 'control', version: CONTROL_SERVER_VERSION, heads: listHeads(), registry: Object.values(HEAD_REGISTRY).map((h) => ({ key: h.key, label: h.label, authKind: h.authKind })) });
      return true;
    }

    // ── heads: status + lifecycle ──
    if (resource === 'heads') {
      const head = seg[2];
      const action = seg[3];
      if (!head && method === 'GET') { sendJson(res, 200, { heads: await statusAll() }); return true; }
      if (head && !action && method === 'GET') { sendJson(res, 200, await headStatus(head)); return true; }
      if (head && action && method === 'POST') {
        if (!(head in HEAD_REGISTRY)) { sendJson(res, 404, { error: { type: 'invalid_request_error', message: `unknown head: ${head}` } }); return true; }
        if (action === 'start') { sendJson(res, 200, await startHead(head)); return true; }
        if (action === 'stop') { sendJson(res, 200, await stopHead(head)); return true; }
        if (action === 'restart') { sendJson(res, 200, await restartHead(head)); return true; }
      }
    }

    // ── config ──
    if (resource === 'config' && method === 'GET') { sendJson(res, 200, await readEffectiveConfig()); return true; }
    if (resource === 'config' && method === 'PATCH') {
      let partial;
      try { partial = JSON.parse(await readBody(req)); } catch { sendJson(res, 400, { error: { type: 'invalid_request_error', message: 'invalid JSON' } }); return true; }
      const result = await patchConfigFanout(partial);
      const failed = Object.keys(result.rejected).length && !Object.keys(result.applied).length;
      sendJson(res, failed ? 400 : 200, result);
      return true;
    }

    // ── usage ──
    if (resource === 'usage' && method === 'GET') { sendJson(res, 200, usagePayload()); return true; }

    // ── auth ──
    if (resource === 'auth') {
      const head = seg[2];
      const action = seg[3];
      if (!head && method === 'GET') { sendJson(res, 200, describeAllAuth()); return true; }
      if (head && action === 'refresh' && method === 'POST') {
        const out = await refreshAuth(head);
        if (!out) { sendJson(res, 404, { error: { type: 'invalid_request_error', message: `unknown auth head: ${head}` } }); return true; }
        sendJson(res, 200, out);
        return true;
      }
      if (head && action === 'login' && method === 'POST') { sendJson(res, 200, startLogin(head)); return true; }
    }

    // ── compact ──
    if (resource === 'compact' && method === 'GET') { sendJson(res, 200, { stats: readCompactStats(50) }); return true; }

    // ── logs ──
    if (resource === 'logs' && method === 'GET') {
      const head = seg[2];
      if (!head || !(head in HEAD_REGISTRY)) { sendJson(res, 404, { error: { type: 'invalid_request_error', message: `unknown head: ${head}` } }); return true; }
      const tailN = Math.min(2000, Math.max(1, parseInt(url.searchParams.get('tail') ?? '200', 10) || 200));
      sendJson(res, 200, tailLog(head, tailN));
      return true;
    }

    sendJson(res, 404, { error: { type: 'invalid_request_error', message: `unknown control route: ${method} ${url.pathname}` } });
    return true;
  } catch (err) {
    sendJson(res, 500, { error: { type: 'api_error', message: String(err?.message || err) } });
    return true;
  }
}
