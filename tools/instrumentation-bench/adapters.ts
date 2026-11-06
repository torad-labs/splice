import { hash, type Manifest } from './manifest';
import { sleep } from './fixture';
/** Task-owned OTLP/HTTP collector: payloads are discarded after bounded accounting. */
export function collector(enabled: boolean, maxBytes: number) {
  const counts = { requests: 0, failed: 0, bytes: 0, active: 0, max_active: 0, elapsed_ms: 0 };
  const server = enabled ? Bun.serve({ hostname: '127.0.0.1', port: 0, maxRequestBodySize: maxBytes, async fetch(req) {
    if (!['/v1/metrics', '/v1/traces', '/v1/logs'].includes(new URL(req.url).pathname) || req.method !== 'POST') return new Response(null, { status: 404 });
    if (counts.active >= 8) { counts.failed++; return new Response(null, { status: 503 }); }
    counts.requests++; counts.active++; counts.max_active = Math.max(counts.max_active, counts.active); const start = performance.now();
    try {
      const reader = req.body?.getReader(); let bytes = 0;
      if (reader) while (true) { const item = await reader.read(); if (item.done) break; bytes += item.value.byteLength; if (bytes > maxBytes) { await reader.cancel(); counts.failed++; return new Response(null, { status: 413 }); } }
      counts.bytes += bytes;
      return new Response(new Uint8Array(), { headers: { 'content-type': 'application/x-protobuf' } });
    } finally { counts.active--; counts.elapsed_ms += performance.now() - start; }
  } }) : null;
  return { endpoint: server ? `http://127.0.0.1:${server.port}` : null, counts, stop: async () => { if (server) await server.stop(true); } };
}
export interface ProfileReceipt { repetition: number; id_sha256: string; started: boolean; stopped: boolean; status: string; error_class: string | null }
async function profileRequest(base: string, key: string, path: string, method: 'GET' | 'POST', body?: object) {
  const result = await fetch(base + path, { method, headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json' },
    body: method === 'POST' ? JSON.stringify(body ?? {}) : undefined, signal: AbortSignal.timeout(5000), redirect: 'error' });
  if (!result.ok) { await result.body?.cancel(); throw new Error(`profile_http_${result.status}`); }
  const reader = result.body?.getReader(); const chunks: Uint8Array[] = []; let size = 0;
  if (reader) while (true) { const item = await reader.read(); if (item.done) break; size += item.value.byteLength; if (size > 16384) { await reader.cancel(); throw new Error('profile_response_limit'); } chunks.push(item.value); }
  const text = Buffer.concat(chunks).toString('utf8'); return text ? JSON.parse(text) : {};
}
export async function startProfile(m: Manifest, controlBase: string, key: string, repetition: number) {
  if (!m.collection.profiles) return null;
  const durationSeconds = Math.min(300, Math.max(1, Math.ceil((m.duration_ms + m.generator_limits.timeout_ms) / 1000)));
  const body = await profileRequest(controlBase, key, '/api/perf/profiles', 'POST', { profiler: 'jfr', mode: 'cpu', durationSeconds });
  if (typeof body.id !== 'string' || !/^[a-zA-Z0-9_-]{1,100}$/.test(body.id)) throw new Error('profile_id_unavailable');
  const receipt: ProfileReceipt = { repetition, id_sha256: hash(body.id), started: true, stopped: false, status: 'running', error_class: null };
  const state = (result: any) => result.status ?? result.state;
  const complete = (result: any) => ['completed', 'complete'].includes(state(result));
  return { receipt, stop: async () => {
    try {
      let result = await profileRequest(controlBase, key, `/api/perf/profiles/${body.id}/stop`, 'POST');
      const deadline = performance.now() + 5000;
      while (!complete(result) && performance.now() < deadline) {
        await sleep(50);
        result = await profileRequest(controlBase, key, `/api/perf/profiles/${body.id}`, 'GET');
      }
      receipt.stopped = complete(result); receipt.status = receipt.stopped ? 'completed' : 'partial';
      if (!receipt.stopped) receipt.error_class = 'profile_completion_unverified';
    } catch { receipt.error_class = 'profile_stop_failed'; receipt.status = 'partial'; }
  } };
}
