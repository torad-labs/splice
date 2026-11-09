import { boundedBody } from './control';
import { decodeOtlp, type OtlpSignal } from './otlp';
import { sleep } from './fixture';
export { startProfile } from './profile';
export type { ProfileReceipt } from './profile';

export interface CollectorFault { mode: 'healthy' | 'slow' | 'unavailable'; delay_ms: number; max_active: number }
/** This collector validates OTLP/protobuf signals; it never persists payloads or arbitrary strings. */
export function collector(enabled: boolean, maxBytes: number, fault: CollectorFault = { mode: 'healthy', delay_ms: 0, max_active: 8 }) {
  const counts = { requests: 0, failed: 0, bytes: 0, active: 0, max_active: 0, elapsed_ms: 0, wire_invalid: 0, refused: 0,
    evidence: [] as { received_epoch_ms: number; signal: OtlpSignal; records: number; points: number; metric_names: string[]; attribute_keys: string[]; first_epoch_ns: string | null; last_epoch_ns: string | null }[], evidence_overflow: 0,
    signals: { metrics: { requests: 0, records: 0, points: 0 }, traces: { requests: 0, records: 0, points: 0 }, logs: { requests: 0, records: 0, points: 0 } },
    fault, first_received_epoch_ms: null as number | null, last_received_epoch_ms: null as number | null };
  const server = enabled ? Bun.serve({ hostname: '127.0.0.1', port: 0, maxRequestBodySize: maxBytes, async fetch(req) {
    const path = new URL(req.url).pathname; const signal = path.slice('/v1/'.length) as OtlpSignal;
    if (!['/v1/metrics', '/v1/traces', '/v1/logs'].includes(path) || req.method !== 'POST') return new Response(null, { status: 404 });
    if (counts.active >= fault.max_active) { counts.failed++; counts.refused++; return new Response(null, { status: 503 }); }
    counts.requests++; counts.active++; counts.max_active = Math.max(counts.max_active, counts.active); const start = performance.now();
    try {
      if (!req.headers.get('content-type')?.startsWith('application/x-protobuf') || req.headers.get('content-encoding')) throw new Error('otlp_media_type_unsupported');
      const bytes = await boundedBody(new Response(req.body), maxBytes); counts.bytes += bytes.byteLength;
      const evidence = decodeOtlp(bytes, signal); const row = counts.signals[signal]; row.requests++; row.records += evidence.records; row.points += evidence.points;
      if (counts.evidence.length < 10000) counts.evidence.push({ received_epoch_ms: Date.now(), signal, records: evidence.records, points: evidence.points, metric_names: evidence.metric_names, attribute_keys: evidence.attribute_keys, first_epoch_ns: evidence.first_epoch_ns, last_epoch_ns: evidence.last_epoch_ns }); else counts.evidence_overflow++;
      counts.first_received_epoch_ms ??= Date.now(); counts.last_received_epoch_ms = Date.now();
      if (fault.mode === 'slow') await sleep(fault.delay_ms);
      if (fault.mode === 'unavailable') { counts.failed++; counts.refused++; return new Response(null, { status: 503 }); }
      return new Response(new Uint8Array(), { headers: { 'content-type': 'application/x-protobuf' } });
    } catch { counts.failed++; counts.wire_invalid++; return new Response(null, { status: 400 }); }
    finally { counts.active--; counts.elapsed_ms += performance.now() - start; }
  } }) : null;
  return { endpoint: server ? `http://127.0.0.1:${server.port}` : null, counts, stop: async () => { if (server) await server.stop(true); } };
}
