#!/usr/bin/env node
/**
 * Codex Proxy (claudex head) — Anthropic Messages API → ChatGPT Codex Responses API.
 *
 * v30: the splice decomposition of the 1783-line v29 single file. This entry is
 * the conductor only — translation, streaming, compaction, auth, gating, and
 * the management plane live in their modules; every wire frame goes through
 * anthropic/sse.mjs (L3) and both response paths mirror via reasoning/mirror.mjs (L2).
 *
 * Requirements: Codex CLI OAuth login in ~/.codex/auth.json.
 */
import { mkdirSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { getConfig } from './config.mjs';
import { createProxyServer, endResponse, listenLoopback, readBody, sendJson } from './http/server.mjs';
import { createSseEmitter } from './anthropic/sse.mjs';
import { classifyCompact, recordShadow, recordCompactStat } from './codex/compact.mjs';
import { buildRequest } from './codex/translate-request.mjs';
import { translateResponse } from './codex/translate-response.mjs';
import { runStreamTurn, collectTerminal } from './codex/stream.mjs';
import { anthropicErrorBody, mapOutStatus } from './codex/errors.mjs';
import { extractThinking, mirrorInto } from './reasoning/mirror.mjs';
import { acquireSlot, fetchUpstreamWithRetries, gateSnapshot } from './upstream/gate.mjs';
import { getCodexAuth, describeCodexAuth } from './auth/codex-oauth.mjs';
import { appendCodexUsage, buildUsagePayload, logTurnCache, makeOutputClamp, persistCodexRateLimit } from './usage/hud.mjs';
import { discoveryModels, unwrapCodexModel } from './models/codex-models.mjs';
import { handleMgmt } from './mgmt/api.mjs';

import { CODEX_PROXY_VERSION as PROXY_VERSION } from './versions.mjs';

export { PROXY_VERSION };

const startedAt = Date.now();

function logDebug(...args) {
  if (!getConfig().debug) return;
  process.stderr.write(`[codex-proxy:debug] ${args.map((a) => (typeof a === 'string' ? a : JSON.stringify(a))).join(' ')}\n`);
}

/** Env-gated dump of the raw Anthropic request body for capture→replay A/Bs.
 * Off unless SPLICE_CAPTURE_DIR is set on the proxy process. Sequential
 * turn-NNN.json so a multi-turn live session can be replayed byte-identical. */
function maybeCaptureRequest(rawBody) {
  const dir = process.env.SPLICE_CAPTURE_DIR;
  if (!dir) return;
  try {
    mkdirSync(dir, { recursive: true });
    const n = readdirSync(dir).filter((f) => /^turn-\d+\.json$/.test(f)).length + 1;
    const name = `turn-${String(n).padStart(3, '0')}.json`;
    writeFileSync(join(dir, name), rawBody);
    process.stderr.write(`[codex-proxy] capture ${name} (${rawBody.length}b) → ${dir}\n`);
  } catch (err) {
    process.stderr.write(`[codex-proxy] capture dump failed: ${err?.message || err}\n`);
  }
}

async function handleMessages(req, res) {
  const cfg = getConfig();
  let rawBody;
  try {
    rawBody = await readBody(req);
  } catch {
    res.writeHead(400);
    res.end('Bad Request');
    return;
  }

  let body;
  try {
    body = JSON.parse(rawBody);
  } catch {
    sendJson(res, 400, { error: { type: 'invalid_request_error', message: 'Invalid JSON' } });
    return;
  }

  maybeCaptureRequest(rawBody);

  // Discovery-wrapped ids (claude-codex--gpt-5.6-luna) unwrap for routing; the
  // id Claude Code sent is echoed back in every response.
  const originalModel = body.model;
  const routedModel = unwrapCodexModel(originalModel);

  // The v29 claude-* passthrough was dead in practice (claudex children carry a
  // dummy token that upstream 401s) — it is now an honest error (P2).
  if (/^claude-|^(opus|sonnet|haiku)(-|$)/i.test(routedModel ?? '')) {
    sendJson(res, 400, {
      error: {
        type: 'invalid_request_error',
        message: 'claudex has no Anthropic credentials; Claude models are unavailable via the codex proxy — use plain `claude`',
      },
    });
    return;
  }

  const auth = getCodexAuth();
  if (!auth) {
    sendJson(res, 401, {
      error: { type: 'authentication_error', message: 'No Codex auth: login via `codex` CLI' },
    });
    return;
  }

  // Compaction detection (tools-agnostic positive marker) + the shadow
  // classifier on EVERY request — ground truth for marker drift.
  const compactMode = classifyCompact(body);
  recordShadow(body, compactMode);

  const t0 = Date.now();
  const { req: responsesReq, meta } = buildRequest(body, { compact: compactMode, config: cfg, originalModel });
  const primaryBodyJson = JSON.stringify(responsesReq);
  logDebug('req', {
    model: responsesReq.model,
    compact: compactMode,
    tools: responsesReq.tools?.length ?? 0,
    input_items: responsesReq.input?.length ?? 0,
    stream: meta.stream,
    showReasoning: meta.showReasoning,
    effort: meta.effort,
    summary: meta.summary,
    budget: meta.budgetTokens,
    body_bytes: primaryBodyJson.length,
    pre_ms: Date.now() - t0,
  });
  if (compactMode) {
    process.stderr.write(`[codex-proxy] compact request model=${responsesReq.model} input_items=${responsesReq.input?.length ?? 0}\n`);
  }

  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${auth.token}`,
    'Accept': 'text/event-stream',
  };
  if (auth.accountId) headers['ChatGPT-Account-ID'] = auth.accountId;

  const slot = await acquireSlot({ label: compactMode ? 'compact' : (responsesReq.model || 'req'), compact: compactMode });
  if (res.destroyed || res.writableEnded) {
    slot.release(); // client gone while queued — don't burn an upstream call
    return;
  }

  const { upstreamRes, upstreamAbort, lastErrText } = await fetchUpstreamWithRetries({
    url: `${cfg.chatgptApiBase}/responses`,
    headers,
    bodyJson: primaryBodyJson,
    slot,
    onRetryStderr: (line) => process.stderr.write(`[codex-proxy] ${line}\n`),
  });

  if (!upstreamRes || !upstreamRes.ok) {
    slot.release();
    const status = upstreamRes?.status ?? 502;
    const errText = lastErrText || 'upstream failed';
    process.stderr.write(`[codex-proxy] ChatGPT backend ${status}: ${errText.slice(0, 400)}\n`);
    // Fidelity: surface real errors — never invent a local summary as success
    if (compactMode) {
      recordCompactStat({ outcome: 'upstream_error', status, ms: Date.now() - t0 });
    }
    sendJson(res, mapOutStatus(status), anthropicErrorBody(status, errText));
    return;
  }

  persistCodexRateLimit(upstreamRes.headers);
  logDebug('upstream_ok', { ms: Date.now() - t0, compact: compactMode, gate: gateSnapshot() });

  // Idempotent teardown — client abort, write errors, and normal end all land
  // here exactly once: free the slot AND abort upstream (v25: abort(), never
  // body.cancel() — cancel() on a locked stream rejects unhandled).
  let torndown = false;
  const teardown = () => {
    if (torndown) return;
    torndown = true;
    try { slot.release(); } catch { /* ignore */ }
    try { upstreamAbort?.abort(); } catch { /* ignore */ }
  };
  res.on('close', teardown);
  res.on('error', teardown);

  try {
    if (meta.stream) {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive',
        'X-Accel-Buffering': 'no',
      });
      // Flush headers immediately so Claude Code can open the stream
      if (typeof res.flushHeaders === 'function') res.flushHeaders();

      const emitter = createSseEmitter(res, {
        model: meta.originalModel,
        onFirstByte: () => logDebug('ttfb_client', { ms: Date.now() - t0, compact: compactMode }),
        onWriteError: teardown,
      });
      const outcome = await runStreamTurn({ upstreamRes, upstreamAbort, res, emitter, meta, slot, t0 });
      logDebug('stream_done', { ...outcome, compact: compactMode, ms: Date.now() - t0, gate: gateSnapshot() });
    } else {
      // Non-stream (v25): collect the terminal Responses object and translate it
      // wholesale via translateResponse — one translator for both paths.
      const { finalResp, failure } = await collectTerminal(upstreamRes, slot);
      if (finalResp && !failure) {
        logTurnCache({ model: meta.upstreamModel, usage: finalResp.usage ?? {}, compact: compactMode });
        const anthropicResp = translateResponse(finalResp, meta.originalModel, { replay: !compactMode && cfg.replayReasoning });
        const clampOutput = makeOutputClamp(meta.clientMaxTokens, compactMode);
        if (anthropicResp.usage) anthropicResp.usage.output_tokens = clampOutput(anthropicResp.usage.output_tokens ?? 0);
        // Reasoning mirror parity for non-stream clients — the SAME mirrorInto
        // as the stream path (L2), sinking into the content array.
        mirrorInto(
          { addTextBlock: (text) => anthropicResp.content.push({ type: 'text', text }) },
          extractThinking(anthropicResp.content),
          { showReasoning: meta.showReasoning, compact: compactMode },
        );
        const hasAny = anthropicResp.content.some((c) =>
          (c.type === 'text' && String(c.text || '').trim())
          || (c.type === 'thinking' && String(c.thinking || '').trim())
          || c.type === 'tool_use');
        if (!hasAny) {
          // Honesty: empty completed response → error, not a blank 200
          if (compactMode) recordCompactStat({ outcome: 'empty_model', ms: Date.now() - t0 });
          sendJson(res, 502, {
            error: { type: 'api_error', message: 'claudex: model returned no content (empty response) — retry' },
          });
        } else {
          appendCodexUsage(anthropicResp.usage?.output_tokens ?? 0);
          if (compactMode) recordCompactStat({ outcome: 'model', ms: Date.now() - t0 });
          sendJson(res, 200, anthropicResp);
        }
      } else {
        if (compactMode) recordCompactStat({ outcome: failure ? 'stream_error' : 'empty_model', ms: Date.now() - t0 });
        sendJson(res, 502, {
          error: {
            type: 'api_error',
            message: failure || 'claudex: no terminal response received from ChatGPT backend',
          },
        });
      }
    }
  } catch (err) {
    process.stderr.write(`[codex-proxy] stream/handler error: ${err?.message || err}\n`);
    try {
      if (!res.headersSent) {
        sendJson(res, 502, { error: { message: String(err?.message || err) } });
      } else if (!res.writableEnded) {
        endResponse(res);
      }
    } catch { /* ignore */ }
  } finally {
    teardown();
  }
}

export function createServer() {
  return createProxyServer(async (req, res) => {
    const cfg = getConfig();
    if (req.method === 'GET' && (req.url === '/health' || req.url?.startsWith('/health?'))) {
      sendJson(res, 200, {
        ok: true,
        mode: 'codex-proxy',
        port: cfg.port,
        version: PROXY_VERSION,
        gate: gateSnapshot(),
        max_inflight: cfg.maxInflight,
        upstream_timeout_ms: cfg.upstreamTimeoutMs,
      });
      return;
    }

    if (req.method === 'GET' && req.url === '/stats') {
      const auth = describeCodexAuth();
      sendJson(res, 200, {
        version: PROXY_VERSION,
        gate: gateSnapshot(),
        auth_cached: auth.cached,
        auth_age_ms: auth.cache_age_ms,
      });
      return;
    }

    // Gateway model discovery — the durable /model picker source. Claude Code
    // GETs {base}/v1/models?limit=1000 when gateway discovery is enabled,
    // re-fetched every startup. Ids MUST be claude/anthropic-prefixed.
    if (req.method === 'GET' && (req.url === '/v1/models' || req.url?.startsWith('/v1/models?'))) {
      sendJson(res, 200, { object: 'list', has_more: false, data: discoveryModels() });
      return;
    }

    if (await handleMgmt(req, res, {
      proxy: 'codex-proxy',
      version: PROXY_VERSION,
      startedAt,
      status: () => ({
        gate: gateSnapshot(),
        pinned_model: cfg.pinnedModel,
        show_reasoning: cfg.showReasoning,
        effort_fallback: cfg.effort,
        summary_fallback: cfg.summary,
      }),
    })) return;

    if (!req.url?.includes('/messages')) {
      res.writeHead(404);
      res.end('Not Found');
      return;
    }
    if (req.method !== 'POST') {
      res.writeHead(405);
      res.end('Method Not Allowed');
      return;
    }
    await handleMessages(req, res);
  });
}

export function startServer() {
  const cfg = getConfig();
  const server = createServer();
  return listenLoopback(server, cfg.port, 'codex-proxy', () => {
    process.stderr.write(`[codex-proxy] listening on http://127.0.0.1:${cfg.port} v${PROXY_VERSION}\n`);
    process.stderr.write(`[codex-proxy] chatgpt=${cfg.chatgptApiBase} max_inflight=${cfg.maxInflight} timeout_ms=${cfg.upstreamTimeoutMs}\n`);
  });
}

// Re-exports for tests and tooling (kept name-compatible where sensible).
export { buildRequest } from './codex/translate-request.mjs';
export { translateResponse } from './codex/translate-response.mjs';
export { classifyCompact } from './codex/compact.mjs';
export { classifyUpstreamFailure } from './codex/errors.mjs';
export { getContextWindowForModel } from './models/codex-models.mjs';

if (process.env.CODEX_PROXY_TEST !== '1') {
  startServer();
}
