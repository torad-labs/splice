// Upstream HTTP: shared undici Agent + the first-byte (headers-phase) watchdog.
import { Agent, fetch as undiciFetch } from 'undici';
import { getConfig } from '../config.mjs';

// The Agent is built once (its timeouts are restart-required config). The
// headers-phase backstop is generous — the per-request first-byte timer in
// fetchOnce() enforces the hot-configurable value.
const bootCfg = getConfig();
const upstreamAgent = new Agent({
  // High connection pool — multi-session parallel to chatgpt.com (no artificial queue)
  connections: Math.max(bootCfg.maxInflight > 0 ? bootCfg.maxInflight + 8 : 64, 16),
  pipelining: 1,
  keepAliveTimeout: 60_000,
  keepAliveMaxTimeout: 300_000,
  headersTimeout: Math.max(600_000, bootCfg.firstByteTimeoutMs),
  bodyTimeout: bootCfg.upstreamTimeoutMs,
  connectTimeout: 10_000,
  // Prefer snappy TCP to ChatGPT
  allowH2: false,
});

export function upstreamFetch(url, init = {}) {
  return undiciFetch(url, { ...init, dispatcher: upstreamAgent });
}

/**
 * One upstream POST with the headers-phase timeout (v29: 300s default — a
 * near-window prompt or a compaction re-sending the whole transcript
 * legitimately prefills for minutes before the first byte; 90s aborted real
 * work). The body phase is governed by the stream idle watchdog.
 * Returns { res, abortController } — abort() is the ONLY lock-safe way to kill
 * the body stream (v25: body.cancel() on a locked stream rejects unhandled).
 */
export async function fetchOnce(url, { headers, body, firstByteTimeoutMs }) {
  const ac = new AbortController();
  const timer = setTimeout(() => ac.abort(), firstByteTimeoutMs);
  try {
    const res = await upstreamFetch(url, { method: 'POST', headers, body, signal: ac.signal });
    return { res, abortController: ac };
  } finally {
    clearTimeout(timer);
  }
}
