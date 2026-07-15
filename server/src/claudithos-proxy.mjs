#!/usr/bin/env node
/**
 * Claudithos Proxy — Anthropic Messages API → Anthropic Messages API, with the
 * mythos memory-architecture transform. The experiment: does the configuration
 * (per-turn reasoning amnesia + conclusions mirrored into the transcript) that
 * emerged in claudex/Sol reproduce on Claude (Fable)?
 *
 * Three arms, all through the SAME proxy so gateway-mode quirks are constant:
 *   native   — pure passthrough (control; within-turn thinking replay intact)
 *   amnesia  — A: drop thinking + textualize tool exchanges in request history
 *   mirror   — A+B: amnesia + past thinking persists as [reasoning summary] text
 *
 * Auth: Claude Code runs in gateway mode (dummy token); this proxy swaps in the
 * real OAuth token from ~/.claude/.credentials.json and adds the oauth beta.
 *
 * EXPERIMENT TOOL — scratch sessions and disposable tasks only (locked).
 */
import { Agent, fetch as undiciFetch } from 'undici';
import { getConfig } from './config.mjs';
import { createProxyServer, listenLoopback, readBodyBuffer, sendJson } from './http/server.mjs';
import { transformMessages } from './mythos/transform.mjs';
import { getOauthToken, invalidateCreds, credCached } from './auth/claude-oauth.mjs';
import { handleMgmt } from './mgmt/api.mjs';

import { CLAUDITHOS_PROXY_VERSION as PROXY_VERSION } from './versions.mjs';

export { PROXY_VERSION };

const startedAt = Date.now();

const upstreamAgent = new Agent({
  connections: 32,
  pipelining: 1,
  keepAliveTimeout: 60_000,
  keepAliveMaxTimeout: 300_000,
  headersTimeout: 120_000,      // headers should be fast; generation lives in the body
  bodyTimeout: 3_600_000,       // long thinking streams
  connectTimeout: 10_000,
});

export function currentMode() {
  return getConfig().claudithosMode;
}

export function createServer() {
  return createProxyServer(async (req, res) => {
    const cfg = getConfig();

    if (req.method === 'GET' && req.url?.startsWith('/health')) {
      sendJson(res, 200, {
        ok: true,
        mode: cfg.claudithosMode,
        version: PROXY_VERSION,
        port: cfg.claudithosPort,
        cred_cached: credCached(),
      });
      return;
    }

    if (await handleMgmt(req, res, {
      proxy: 'claudithos-proxy',
      version: PROXY_VERSION,
      startedAt,
      status: () => ({ mode: cfg.claudithosMode, upstream: cfg.anthropicUpstream }),
    })) return;

    const token = getOauthToken();
    if (!token) {
      sendJson(res, 401, {
        error: { type: 'authentication_error', message: `claudithos: no OAuth token at ${cfg.claudeCredentialsPath} — log in with plain \`claude\` first` },
      });
      return;
    }

    // Read body (needed for the transform; passthrough paths reuse it verbatim)
    let rawBody = null;
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      try {
        rawBody = await readBodyBuffer(req);
      } catch {
        res.writeHead(400);
        res.end('Bad Request');
        return;
      }
    }

    const mode = cfg.claudithosMode;
    const isMessagesPost = req.method === 'POST' && /\/messages/.test(req.url ?? '');
    let outBody = rawBody;

    // Request transform (amnesia + mirror arms). Applies to count_tokens too,
    // so context estimates reflect what will actually be sent.
    if (isMessagesPost && mode !== 'native' && rawBody?.length) {
      try {
        const body = JSON.parse(rawBody.toString('utf8'));
        if (Array.isArray(body.messages)) {
          body.messages = transformMessages(body.messages, { mirrorThinking: mode === 'mirror' });
          outBody = Buffer.from(JSON.stringify(body), 'utf8');
        }
      } catch { /* not JSON — pass through untouched */ }
    }

    // Forward headers: keep everything Claude Code sent, swap auth for real
    // OAuth, ensure the oauth beta rides along (gateway mode omits it).
    const headers = {};
    for (const [k, v] of Object.entries(req.headers)) {
      const lk = k.toLowerCase();
      if (lk === 'host' || lk === 'content-length' || lk === 'authorization' || lk === 'x-api-key' || lk === 'connection') continue;
      headers[k] = v;
    }
    headers.authorization = `Bearer ${token}`;
    const beta = String(headers['anthropic-beta'] ?? '');
    if (!/oauth-2025-04-20/.test(beta)) {
      headers['anthropic-beta'] = beta ? `${beta},oauth-2025-04-20` : 'oauth-2025-04-20';
    }

    const ac = new AbortController();
    res.on('close', () => { try { ac.abort(); } catch { /* ignore */ } });

    let upstreamRes;
    try {
      upstreamRes = await undiciFetch(`${cfg.anthropicUpstream}${req.url}`, {
        method: req.method,
        headers,
        body: outBody ?? undefined,
        dispatcher: upstreamAgent,
        signal: ac.signal,
      });
    } catch (err) {
      if (!res.writableEnded) {
        sendJson(res, 502, { error: { type: 'api_error', message: `claudithos upstream error: ${err?.message || err}` } });
      }
      return;
    }

    if (upstreamRes.status === 401) invalidateCreds();

    const respHeaders = {};
    for (const [k, v] of upstreamRes.headers.entries()) {
      const lk = k.toLowerCase();
      if (lk === 'content-length' || lk === 'transfer-encoding' || lk === 'content-encoding' || lk === 'connection') continue;
      respHeaders[k] = v;
    }

    // The response is ALWAYS a byte-faithful passthrough in every arm. All
    // three arms differ ONLY in the request transform above. B lives in the
    // request transform — injecting a block into a live tool_use turn
    // fragmented the agentic loop (v1 bug, caught in the first live session).
    try {
      res.writeHead(upstreamRes.status, respHeaders);
      if (upstreamRes.body) {
        for await (const chunk of upstreamRes.body) {
          if (res.writableEnded || res.destroyed) break;
          res.write(chunk);
        }
      }
      res.end();
    } catch (err) {
      process.stderr.write(`[claudithos] stream error: ${err?.message || err}\n`);
      try { if (!res.writableEnded) res.end(); } catch { /* ignore */ }
    }
  });
}

export function startServer() {
  const cfg = getConfig();
  const server = createServer();
  return listenLoopback(server, cfg.claudithosPort, 'claudithos', () => {
    process.stderr.write(`[claudithos] listening on http://127.0.0.1:${cfg.claudithosPort} v${PROXY_VERSION} mode=${cfg.claudithosMode}\n`);
  });
}

export { transformMessages } from './mythos/transform.mjs';

if (process.env.CLAUDITHOS_TEST !== '1') {
  startServer();
}
