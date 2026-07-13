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
import { describeClaudeAuth, getOauthToken, invalidateCreds } from '../auth/claude-oauth.mjs';
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

function authorized(req) {
  const header = String(req.headers.authorization ?? '');
  const m = /^Bearer\s+(.+)$/.exec(header);
  if (!m) return false;
  const presented = Buffer.from(m[1].trim());
  const expected = Buffer.from(ensureMgmtKey());
  return presented.length === expected.length && timingSafeEqual(presented, expected);
}

function readLogsTail(proxy, tailN) {
  const path = join(logsDir(), `${proxy}.log`);
  if (!existsSync(path)) return { path, lines: [], note: 'no log file yet' };
  try {
    const lines = readFileSync(path, 'utf8').split('\n').filter(Boolean);
    return { path, lines: lines.slice(-tailN) };
  } catch (err) {
    return { path, lines: [], note: String(err?.message || err) };
  }
}

/**
 * Handle a /mgmt/* request. Returns true when the request was consumed.
 * ctx: { proxy: 'codex-proxy'|'claudithos-proxy', version, startedAt, status() }
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
    sendJson(res, 200, ctx.proxy === 'claudithos-proxy' ? describeClaudeAuth() : describeCodexAuth());
    return true;
  }

  if (route === 'POST /mgmt/auth/refresh') {
    if (ctx.proxy === 'claudithos-proxy') {
      invalidateCreds();
      const token = getOauthToken();
      sendJson(res, 200, { refreshed: Boolean(token), ...describeClaudeAuth() });
    } else {
      const fresh = await refreshCodexAuth();
      sendJson(res, 200, { refreshed: Boolean(fresh?.token), ...describeCodexAuth() });
    }
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
      discovery: discoveryModels(cfg.pinnedModel).map((m) => m.id),
    });
    return true;
  }

  sendJson(res, 404, { error: { type: 'invalid_request_error', message: `unknown mgmt route: ${route}` } });
  return true;
}
