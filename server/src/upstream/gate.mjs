// Inflight gate + live request map + upstream retry loop.
//
// No default inflight cap — the operator runs many concurrent Codex streams
// fine. maxInflight > 0 re-enables a FIFO gate. Live entries power /stats and
// /mgmt/status (phase connect|streaming distinguishes wedged connects from
// silent streams) and feed the stream idle watchdog.
import { getConfig } from '../config.mjs';
import { fetchOnce } from './fetch.mjs';
import { getCodexAuth, invalidateAuthCache, refreshCodexAuth } from '../auth/codex-oauth.mjs';

const gate = {
  inflight: 0,
  waiting: [],
  stats: { acquired: 0, released: 0, waited: 0, waitMsTotal: 0 },
  live: new Map(), // id -> { label, compact, phase, startedAt, lastByteAt }
};

let liveReqSeq = 0;

export function gateSnapshot() {
  const cfg = getConfig();
  const now = Date.now();
  const live = [...gate.live.values()].map((r) => ({
    label: r.label,
    compact: r.compact,
    phase: r.phase,
    age_ms: now - r.startedAt,
    idle_ms: now - r.lastByteAt,
  }));
  live.sort((a, b) => b.age_ms - a.age_ms);
  return {
    inflight: gate.inflight,
    queued: gate.waiting.length,
    max: cfg.maxInflight === 0 ? 'unlimited' : cfg.maxInflight,
    acquired: gate.stats.acquired,
    released: gate.stats.released,
    waited: gate.stats.waited,
    avg_wait_ms: gate.stats.waited ? Math.round(gate.stats.waitMsTotal / gate.stats.waited) : 0,
    live: live.slice(0, 20),
    stream_idle_ms: cfg.streamIdleMs,
  };
}

/**
 * Acquire an inflight slot (FIFO-queued when maxInflight > 0). Returns a handle:
 *   { id, release(), touch(), setPhase(phase), idleFor() }
 * release() is idempotent — client abort, write errors, and normal end all call it.
 */
export async function acquireSlot({ label, compact }) {
  const cfg = getConfig();
  const enter = () => {
    gate.inflight += 1;
    gate.stats.acquired += 1;
    const id = ++liveReqSeq;
    gate.live.set(id, { label, compact, phase: 'connect', startedAt: Date.now(), lastByteAt: Date.now() });
    let released = false;
    return {
      id,
      release() {
        if (released) return;
        released = true;
        gate.inflight = Math.max(0, gate.inflight - 1);
        gate.stats.released += 1;
        gate.live.delete(id);
        if (getConfig().maxInflight > 0) {
          const next = gate.waiting.shift();
          if (next) next();
        }
      },
      touch() {
        const r = gate.live.get(id);
        if (r) r.lastByteAt = Date.now();
      },
      setPhase(phase) {
        const r = gate.live.get(id);
        if (r) { r.phase = phase; r.lastByteAt = Date.now(); }
      },
      idleFor() {
        const r = gate.live.get(id);
        return r ? Date.now() - r.lastByteAt : 0;
      },
    };
  };

  if (cfg.maxInflight > 0 && gate.inflight >= cfg.maxInflight) {
    const waitStart = Date.now();
    gate.stats.waited += 1;
    await new Promise((resolve) => {
      gate.waiting.push(() => {
        gate.stats.waitMsTotal += Date.now() - waitStart;
        resolve();
      });
    });
  }
  return enter();
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

/**
 * Full-body retries only — same JSON every attempt; never shrink content or
 * swap model/effort. 401 → single-flight OAuth refresh → one extra attempt.
 * Returns { upstreamRes, upstreamAbort, lastErrText }.
 */
export async function fetchUpstreamWithRetries({ url, headers, bodyJson, slot, onRetryStderr }) {
  const cfg = getConfig();
  let upstreamRes = null;
  let upstreamAbort = null;
  let lastErrText = '';
  let refreshedOnce = false;

  for (let attempt = 0; attempt < cfg.upstreamRetries; attempt++) {
    try {
      const { res, abortController } = await fetchOnce(url, {
        headers,
        body: bodyJson,
        firstByteTimeoutMs: cfg.firstByteTimeoutMs,
      });
      upstreamAbort = abortController;
      upstreamRes = res;
      if (res.ok) {
        slot?.setPhase('streaming');
        break;
      }
      lastErrText = await res.text();
      if (res.status === 401) {
        invalidateAuthCache();
        if (!refreshedOnce) {
          refreshedOnce = true;
          const fresh = await refreshCodexAuth();
          if (fresh?.token) {
            headers.Authorization = `Bearer ${fresh.token}`;
            if (fresh.accountId) headers['ChatGPT-Account-ID'] = fresh.accountId;
            upstreamRes = null;
            attempt -= 1; // the refresh retry doesn't consume a normal attempt
            continue;
          }
        }
      }
      const retryable = res.status === 502 || res.status === 503 || res.status === 529 || res.status === 429;
      onRetryStderr?.(`ChatGPT ${res.status} attempt ${attempt + 1}/${cfg.upstreamRetries}: ${lastErrText.slice(0, 160)}`);
      if (!retryable || attempt === cfg.upstreamRetries - 1) break;
      await sleep(200 * (2 ** attempt));
      upstreamRes = null;
    } catch (err) {
      lastErrText = err.message || String(err);
      onRetryStderr?.(`fetch error attempt ${attempt + 1}: ${lastErrText}`);
      if (attempt === cfg.upstreamRetries - 1) break;
      await sleep(200 * (2 ** attempt));
    }
  }
  return { upstreamRes, upstreamAbort, lastErrText };
}

export { getCodexAuth };
