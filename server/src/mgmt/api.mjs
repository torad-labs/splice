// Management API — bearer-guarded /mgmt/* namespace, shared by both proxies.
// Loopback-only by construction (the server binds 127.0.0.1); the bearer key is
// generated once into ~/.claude-codex/state/mgmt-key and read by the WebUI.
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { readBody, sendJson } from '../http/server.mjs';
import { configLayers, getConfig, logsDir, patchConfig, RESTART_REQUIRED_KEYS, stateDir, statePaths } from '../config.mjs';
import { readUsageState } from '../usage/hud.mjs';
import { readCompactStats, shadowTail } from '../codex/compact.mjs';
import { describeCodexAuth, refreshCodexAuth } from '../auth/codex-oauth.mjs';
import { CODEX_MODEL_OPTIONS, CODEX_MODEL_CONTEXT_WINDOWS, discoveryModels } from '../models/codex-models.mjs';

export function ensureMgmtKey() {
  const path = statePaths.mgmtKey();
  try {
    if (existsSync(path)) {
      const key = readFileSync(path, 'utf8').trim();
      if (key) return key;
    }
  } catch { /* regenerate below */ }
  const key = randomBytes(32).toString('hex');
  mkdirSync(stateDir(), { recursive: true });
  writeFileSync(path, key + '\n', { mode: 0o600 });
  return key;
}

// NO REGEX, deliberately (CodeQL js/polynomial-redos, 2026-07-29). `\s+` and `(.+)` both match a
// space, so the engine must try every split; with a trailing newline `$` is unreachable (`.` does not
// cross \n) and V8 backtracks the whole span. MEASURED on this exact pattern, not assumed:
// 50k spaces => 1.0s, 100k => 3.1s, 200k => 11.1s of blocked event loop — quadratic, on an
// UNAUTHENTICATED path, before any key comparison happens. Node's HTTP parser rejects bare newlines
// in header values, so this is defence in depth rather than a live exploit; the scan below is linear
// and removes the primitive entirely, which is cheaper than continuing to reason about reachability.
function authorized(req) {
  const header = String(req.headers.authorization ?? '');
  const SCHEME = 'Bearer';
  if (header.slice(0, SCHEME.length) !== SCHEME) return false;
  const rest = header.slice(SCHEME.length);
  // at least one space must separate scheme from token — `Bearerabc` is not a bearer header
  if (!rest || !/^\s/.test(rest)) return false;
  const presented = Buffer.from(rest.trim());
  const expected = Buffer.from(ensureMgmtKey());
  return presented.length === expected.length && timingSafeEqual(presented, expected);
}

/** Same bearer check, exported for the control server (one auth story, one key). */
export function checkMgmtBearer(req) {
  return authorized(req);
}

/** Port-scoped log name — the launcher writes it, /mgmt/logs reads it; both
 * derive from this so a side-port test head never reads production's log. */
export function proxyLogName(proxy, port) {
  return `${proxy}-${port}.log`;
}

function readLogsTail(proxy, tailN) {
  const cfg = getConfig();
  const port = cfg.port;
  const path = join(logsDir(), proxyLogName(proxy, port));
  if (!existsSync(path)) return { path, lines: [], note: 'no log file yet' };
  try {
    const lines = readFileSync(path, 'utf8').split('\n').filter(Boolean);
    return { path, lines: lines.slice(-tailN) };
  } catch (err) {
    // Detail to stderr, generic to the client (CodeQL js/stack-trace-exposure, alert 1) — the
    // mgmt twin of the control-plane site above; both were missed when dashboard.mjs was fixed.
    process.stderr.write(`[mgmt] log tail failed for ${path}: ${err?.stack || err?.message || err}\n`);
    return { path, lines: [], note: 'log unavailable — see the daemon log' };
  }
}

/**
 * Handle a /mgmt/* request. Returns true when the request was consumed.
 * ctx: { proxy: 'codex-proxy', version, startedAt, status() }
 */
export async function handleMgmt(req, res, ctx) {
  const url = new URL(req.url, 'http://127.0.0.1');
  if (!url.pathname.startsWith('/mgmt/')) return false;

  if (!authorized(req)) {
    sendJson(res, 401, { error: { type: 'authentication_error', message: `mgmt: bearer key required (${statePaths.mgmtKey()})` } });
    return true;
  }

  const route = `${req.method} ${url.pathname}`;

  if (route === 'GET /mgmt/status') {
    sendJson(res, 200, {
      proxy: ctx.proxy,
      version: ctx.version,
      uptime_s: Math.round((Date.now() - ctx.startedAt) / 1000),
      ...ctx.status(),
    });
    return true;
  }

  if (route === 'GET /mgmt/config') {
    sendJson(res, 200, {
      effective: getConfig(),
      layers: configLayers(),
      restart_required_keys: RESTART_REQUIRED_KEYS,
    });
    return true;
  }

  if (route === 'PATCH /mgmt/config') {
    let partial;
    try {
      partial = JSON.parse(await readBody(req));
    } catch {
      sendJson(res, 400, { error: { type: 'invalid_request_error', message: 'invalid JSON' } });
      return true;
    }
    const { applied, rejected, restartRequired, effective } = patchConfig(partial);
    sendJson(res, Object.keys(rejected).length && !Object.keys(applied).length ? 400 : 200, {
      applied,
      rejected,
      restart_required: restartRequired,
      effective,
    });
    return true;
  }

  if (route === 'GET /mgmt/usage') {
    sendJson(res, 200, readUsageState());
    return true;
  }

  if (route === 'GET /mgmt/compact') {
    sendJson(res, 200, {
      stats: readCompactStats(50),
      shadow: shadowTail(100),
    });
    return true;
  }

  if (route === 'GET /mgmt/auth') {
    sendJson(res, 200, describeCodexAuth());
    return true;
  }

  if (route === 'POST /mgmt/auth/refresh') {
    const fresh = await refreshCodexAuth();
    sendJson(res, 200, { refreshed: Boolean(fresh?.token), ...describeCodexAuth() });
    return true;
  }

  if (route === 'GET /mgmt/logs') {
    const tailN = Math.min(2000, Math.max(1, parseInt(url.searchParams.get('tail') ?? '200', 10) || 200));
    sendJson(res, 200, readLogsTail(ctx.proxy, tailN));
    return true;
  }

  if (route === 'GET /mgmt/models') {
    const cfg = getConfig();
    sendJson(res, 200, {
      catalog: CODEX_MODEL_OPTIONS,
      context_windows: CODEX_MODEL_CONTEXT_WINDOWS,
      pinned: cfg.pinnedModel,
      discovery: discoveryModels().map((m) => m.id),
    });
    return true;
  }

  sendJson(res, 404, { error: { type: 'invalid_request_error', message: `unknown mgmt route: ${route}` } });
  return true;
}
