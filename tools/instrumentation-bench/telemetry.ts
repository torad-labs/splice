import { lstat, readdir, realpath } from 'node:fs/promises';
import { join } from 'node:path';
import { control, counter, runtimeIdentity, type OwnedDesk, type RuntimeIdentity } from './control';
import { hash, type Manifest } from './manifest';
import { sleep } from './fixture';

export interface Sink { sink: string; observed: number; enqueued: number; written: number; failed: number; dropped: number; pending: number; oldestPendingMs: number | null; lastSuccessEpochMs: number | null }
export interface TelemetryPoint {
  repetition: number; phase: 'warmup' | 'steady' | 'drain'; sampled_from_ms: number; sampled_until_ms: number;
  identity: RuntimeIdentity | null; artifact_sha256: string; process_id: number | null; config_sha256: string;
  status: 'ok' | 'partial'; issues: string[]; as_of_ms: number | null; sinks: Sink[] | null;
  memory: { observation_reserved_bytes: number | null; observation_limit_bytes: number | null; work_leased_bytes: number | null; work_limit_bytes: number | null; owner_accounted_bytes: Record<string, number | null> };
  disk: Record<string, number | null>; runtime: unknown; exports: unknown; read_bytes: number; elapsed_ms: number;
}
const owners = ['aggregate', 'evidence', 'cursor', 'profile'] as const;
const codes = (value: unknown) => Array.isArray(value) ? value.filter(v => typeof v === 'string' && /^[a-z0-9_]{1,100}$/.test(v)).slice(0, 64) : [];
const capabilities = (value: unknown) => Array.isArray(value) ? value.slice(0, 256).map(v => ({ id: typeof v?.id === 'string' && /^runtime_[a-z0-9_]{1,100}$/.test(v.id) ? v.id : null, supported: typeof v?.supported === 'boolean' ? v.supported : null, reason: v?.supported === false ? 'source_reports_unavailable' : null })) : null;
export function sinks(value: unknown): Sink[] {
  if (!Array.isArray(value) || value.length === 0 || value.length > 32) throw new Error('sink_status_missing');
  const names = new Set<string>();
  return value.map(v => {
    if (!v || typeof v !== 'object' || typeof v.sink !== 'string' || !/^[a-zA-Z0-9_.:-]{1,80}$/.test(v.sink) || names.has(v.sink)) throw new Error('sink_status_invalid');
    names.add(v.sink);
    const result: Record<string, unknown> = { sink: v.sink };
    for (const key of ['observed', 'enqueued', 'written', 'failed', 'dropped', 'pending']) { const n = counter(v[key]); if (n === null) throw new Error('sink_counter_missing'); result[key] = n; }
    for (const key of ['oldestPendingMs', 'lastSuccessEpochMs']) { if (v[key] !== null && counter(v[key]) === null) throw new Error('sink_age_missing'); result[key] = v[key]; }
    return result as unknown as Sink;
  });
}
/** Only numeric measurements and fixed-shaped bounded metadata escape status responses. */
export function numericProjection(value: unknown, depth = 0): unknown {
  if (depth > 8) return null;
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'boolean' || value === null) return value;
  if (Array.isArray(value)) return value.slice(0, 256).map(v => numericProjection(v, depth + 1));
  if (!value || typeof value !== 'object') return null;
  return Object.fromEntries(Object.entries(value).filter(([key]) => /^[a-zA-Z0-9_.-]{1,100}$/.test(key)).slice(0, 256).map(([key, v]) => [key, numericProjection(v, depth + 1)]));
}
export async function diskSize(path: string, root: string): Promise<number> {
  let count = 0;
  const visit = async (at: string): Promise<number> => {
    if (++count > 20000) throw new Error('managed_disk_scan_limit');
    let info; try { info = await lstat(at); } catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return 0; throw error; }
    if (info.isSymbolicLink()) throw new Error('managed_disk_symlink');
    if (!info.isDirectory()) { if (!info.isFile()) throw new Error('managed_disk_not_regular'); return info.size; }
    if (!(await realpath(at)).startsWith(root + '/')) throw new Error('managed_disk_escape');
    let bytes = 0; for (const name of await readdir(at)) bytes += await visit(join(at, name)); return bytes;
  };
  return visit(path);
}
export function reconcileSinks(first: TelemetryPoint, last: TelemetryPoint) {
  const missing = (reason: string) => ({ status: 'unavailable', value: null, reason, sinks: [] as object[] });
  if (!first.identity || !last.identity || JSON.stringify(first.identity) !== JSON.stringify(last.identity) || first.process_id !== last.process_id || first.artifact_sha256 !== last.artifact_sha256 || first.config_sha256 !== last.config_sha256) return missing('sink_identity_changed');
  if (first.status !== 'ok' || last.status !== 'ok' || !first.sinks || !last.sinks) return missing('sink_boundary_partial');
  try { sinks(first.sinks); sinks(last.sinks); runtimeIdentity(first.identity, first.identity.schemaVersion); runtimeIdentity(last.identity, last.identity.schemaVersion); } catch { return missing('sink_boundary_malformed'); }
  if (first.as_of_ms === null || last.as_of_ms === null || last.as_of_ms <= first.as_of_ms) return missing('sink_time_not_advancing');
  if (JSON.stringify(first.sinks.map(s => s.sink).sort()) !== JSON.stringify(last.sinks.map(s => s.sink).sort())) return missing('sink_population_changed');
  const result: object[] = []; let loss = 0;
  for (const end of last.sinks) {
    const begin = first.sinks.find(s => s.sink === end.sink)!;
    const delta: Record<string, number> = {};
    for (const key of ['observed', 'enqueued', 'written', 'failed', 'dropped'] as const) { delta[key] = end[key] - begin[key]; if (delta[key]! < 0) return missing('sink_counter_reset'); }
    loss += delta.failed! + delta.dropped!;
    result.push({ sink: end.sink, delta, pending_start: begin.pending, pending_end: end.pending, pending_change: end.pending - begin.pending, oldest_pending_ms: end.oldestPendingMs, last_success_epoch_ms: end.lastSuccessEpochMs });
  }
  return { status: loss ? 'loss' : 'observed', value: loss, reason: null, sinks: result };
}
export async function startTelemetry(m: Manifest, desk: OwnedDesk, repetition: number) {
  const points: TelemetryPoint[] = []; let active = false, task: Promise<void> | undefined, phase: TelemetryPoint['phase'] = 'warmup';
  let expected: RuntimeIdentity | null = null;
  const sample = async () => {
    if (points.length >= 10000) throw new Error('telemetry_sample_limit');
    const start = performance.now();
    const point: TelemetryPoint = { repetition, phase, sampled_from_ms: Date.now(), sampled_until_ms: 0, identity: null, artifact_sha256: m.artifact_sha256, process_id: desk.pid, config_sha256: desk.identity?.raw_config_sha256 ?? 'unknown', status: 'partial', issues: [], as_of_ms: null, sinks: null, memory: { observation_reserved_bytes: null, observation_limit_bytes: null, work_leased_bytes: null, work_limit_bytes: null, owner_accounted_bytes: Object.fromEntries(owners.map(n => [n, null])) }, disk: {}, runtime: null, exports: null, read_bytes: 0, elapsed_ms: 0 };
    try {
      const result = await control(desk, '/api/perf/instrumentation/status'); point.read_bytes += result.bytes;
      const v = result.value; const identity = runtimeIdentity(v.identity, v.schema_version);
      if (identity.build !== desk.identity?.process_build) throw new Error('instrumentation_build_mismatch');
      point.identity = identity; point.as_of_ms = counter(v.as_of_ms);
      if (v.schema_version !== 1 || v.process_id !== desk.pid || v.collection_mode !== m.collection.mode || v.status !== 'ok') throw new Error('instrumentation_status_partial');
      if (expected && JSON.stringify(expected) !== JSON.stringify(point.identity)) throw new Error('instrumentation_identity_changed');
      expected ??= point.identity;
      if (point.as_of_ms === null || point.as_of_ms < point.sampled_from_ms - 15000 || point.as_of_ms > Date.now() + 1000) throw new Error('instrumentation_status_stale');
      point.sinks = sinks(v.sinks);
      point.memory = { observation_reserved_bytes: counter(v.memory?.observation?.reserved_bytes), observation_limit_bytes: counter(v.memory?.observation?.total_limit_bytes), work_leased_bytes: counter(v.memory?.work?.leased_bytes), work_limit_bytes: counter(v.memory?.work?.limit_bytes), owner_accounted_bytes: Object.fromEntries(owners.map(n => [n, counter(v.memory?.owners?.[n]?.accounted_bytes)])) };
      if (Object.entries(point.memory).some(([key, value]) => key !== 'owner_accounted_bytes' && value === null)) throw new Error('memory_pool_counters_missing');
      point.status = 'ok';
    } catch (error) { point.issues.push(error instanceof Error && /^[a-z0-9_]+$/.test(error.message) ? error.message : 'instrumentation_status_unavailable'); }
    try {
      const result = await control(desk, '/api/perf/runtime'); point.read_bytes += result.bytes;
      if (!point.identity || JSON.stringify(runtimeIdentity(result.value.identity, result.value.schema_version)) !== JSON.stringify(point.identity) || counter(result.value.as_of_ms) === null || Date.now() - result.value.as_of_ms > 15000) throw new Error('runtime_status_stale_or_mismatched');
      point.runtime = { as_of_ms: result.value.as_of_ms, age_ms: result.value.age_ms, capabilities: capabilities(result.value.capabilities), issues: codes(result.value.issues), measurements: numericProjection(result.value.measurements), owner_readings: numericProjection(result.value.owner_readings), owner_coverage: 'registered_owners_only_partial' };
    } catch { point.issues.push('runtime_status_unavailable'); }
    if (m.collection.exports) try { const result = await control(desk, '/api/perf/export/status'); point.read_bytes += result.bytes; point.exports = numericProjection(result.value); } catch { point.issues.push('export_status_unavailable'); }
    try {
      await desk.assertIdentity(); const root = await realpath(desk.state); if (!root.startsWith('/tmp/splice-instrumentation-')) throw new Error('managed_disk_root');
      for (const [owner, path] of Object.entries({ aggregate: 'perf-aggregates', evidence: 'perf-evidence', cursor: 'perf-evidence-cursors', profile: 'perf-profiles', configuration: 'perf-configuration.json', legacy_trace: 'bench-perf.jsonl', legacy_archive: 'perf-archive' })) point.disk[owner] = await diskSize(join(root, path), root);
      await desk.assertIdentity();
    } catch { point.issues.push('managed_disk_unavailable'); }
    point.sampled_until_ms = Date.now(); point.elapsed_ms = performance.now() - start; points.push(point); return point;
  };
  const start = () => { active = true; task = (async () => { while (active) { await sleep(1000); if (active) await sample(); } })(); };
  const pause = async () => { active = false; await task; };
  return { points, sample, setPhase: (p: TelemetryPoint['phase']) => { phase = p; }, start, pause,
    schema: async () => { const result = await control(desk, '/api/perf/schema'); return { sha256: hash(JSON.stringify(result.value)), bytes: result.bytes }; },
    summary: async (from: number, until: number) => {
      const result = await control(desk, `/api/perf/summary?contract=1&head=bench&source=evidence&from=${from}&until=${until}`, { limit: 4 * 1024 ** 2, timeout_ms: 30000 });
      const v = result.value;
      if (v.query?.from_ms !== from || v.query?.until_ms !== until) throw new Error('summary_interval_changed');
      const comparison = v.comparison;
      return { repetition, from_ms: from, until_ms: until, bytes: result.bytes, elapsed_ms: result.elapsed_ms, response_sha256: hash(JSON.stringify(v)),
        status: ['ok', 'partial', 'busy', 'error'].includes(v.status) ? v.status : 'unknown', settled_until_ms: counter(v.settled_until_ms), coverage: numericProjection(v.coverage), counts: numericProjection(v.counts), summary: numericProjection({ rates: v.rates, histograms: v.histograms, counters: v.counters, gauges: v.gauges, outcomes: v.outcomes, goodput: v.goodput }), configuration: numericProjection(v.configuration),
        comparison: { status: ['ready', 'insufficient_evidence'].includes(comparison?.status) ? comparison.status : 'unavailable', reasons: codes(comparison?.reasons), workload_status: ['complete', 'partial', 'empty', 'unavailable'].includes(comparison?.workload?.status) ? comparison.workload.status : 'unavailable', distribution_id: typeof comparison?.workload?.distribution_id === 'string' && /^[a-zA-Z0-9_:-]{1,100}$/.test(comparison.workload.distribution_id) ? comparison.workload.distribution_id : null, population_complete: comparison?.workload?.population_complete === true },
        binding: { identity: expected, artifact_sha256: m.artifact_sha256, process_id: desk.pid, config_sha256: desk.identity?.raw_config_sha256 ?? null } };
    } };
}
export function telemetrySummary(points: TelemetryPoint[], boundaries: { before: number; after: number; repetition: number; from_ms: number; until_ms: number }[]) {
  const intervals = boundaries.map(b => {
    const first = points[b.before], last = points[b.after];
    const bracketed = first && last && first.repetition === b.repetition && last.repetition === b.repetition && first.sampled_until_ms <= b.from_ms && last.sampled_from_ms >= b.until_ms && b.until_ms > b.from_ms;
    return { ...b, ...(bracketed ? reconcileSinks(first, last) : { status: 'unavailable', value: null, reason: 'sink_interval_not_bracketed', sinks: [] as object[] }) };
  });
  const valid = intervals.length > 0 && intervals.every(v => v.value !== null);
  const pools = points.filter(p => p.phase === 'steady').map(p => p.memory.observation_reserved_bytes === null || p.memory.work_leased_bytes === null ? null : p.memory.observation_reserved_bytes + p.memory.work_leased_bytes);
  const rollups = points.filter(p => p.phase === 'steady').map(p => p.disk.aggregate ?? null);
  return { intervals, loss: { value: valid ? intervals.reduce((sum, v) => sum + v.value!, 0) : null, reason: valid ? null : 'missing_or_partial_sink_intervals' },
    resources: { pool_reservation_peak_bytes: pools.length && pools.every(n => n !== null) ? Math.max(...pools as number[]) : null, rollup_disk_peak_bytes: rollups.length && rollups.every(n => n !== null) ? Math.max(...rollups as number[]) : null, retained_heap_bytes: null, sustained_growth: null, reason: 'pool reservation is accounted capacity, not actual retained heap; time series retained for growth study; steady samples alone do not prove zero sustained growth' } };
}
