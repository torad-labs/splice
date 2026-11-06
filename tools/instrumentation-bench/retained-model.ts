import { budget, environment, hash } from './manifest';
import { interval } from './summary';
export const retainedProtocol = Object.freeze({ revision: 'retained-state-v1-before-measurement', fill_hours: 864, step_hours: 24, post_points: 5, repetitions: 5, composition_reserved_bytes: 11 * 1024 ** 2 / 2, java_flags: ['-Xmx512m'], owners: ['accumulator', 'aggregate', 'evidence', 'query_cache', 'cursor'], excluded_owners: ['exporter', 'profiles', 'alerts'], growth_budget_bytes_per_step: 0 });
export interface RetainedManifest { schema_version: 1; source_commit: string; artifact_sha256: string; artifact_path: string; fixture_classes_root: string; fixture_classes_sha256: string; protocol: typeof retainedProtocol }
export interface RetainedMeasurement { mode: 'local' | 'minimal'; virtual_ms: number; produced_hours: number; requests: number; observation_reserved_bytes: number; observation_limit_bytes: number; work_leased_bytes: number; work_limit_bytes: number; composition_reserved_bytes: number; query_count: number; query_refused: number; cursor_count: number; sinks: { sink: string; failed: number; dropped: number; pending: number }[] }
export interface RetainedPoint { pair: number; mode: 'local' | 'minimal'; point: number; pid: number; start_ticks: string; fixture: RetainedMeasurement; heap: { instances: number; bytes: number; classes: number }; histogram_sha256: string; histogram_path: string; probe_ms: number; disk: Record<string, number> }
export interface RetainedRun { schema_version: 1; status: 'complete' | 'partial'; error_class: string | null; manifest: RetainedManifest; environment: ReturnType<typeof environment>; jvm: string; java_executable: string; java_sha256: string; points: RetainedPoint[]; process_bindings: { pid: number; start_ticks: string; args: string[]; verification: 'spawned-child-start-ticks-exact-cmdline'; pair: number; mode: 'local' | 'minimal' }[]; artifact_receipt_sha256: string; fixture_classes: { path: string; sha256: string }[]; measured_at: string; histogram_command: string; harness_sha256: string }
export function parseHistogram(text: string) {
  const totals = [...text.matchAll(/^Total\s+(\d+)\s+(\d+)\s*$/gm)];
  if (totals.length !== 1) throw new Error('histogram_total_missing');
  let instances = 0, bytes = 0, classes = 0;
  for (const row of text.matchAll(/^\s*\d+:\s+(\d+)\s+(\d+)\s+\S.*$/gm)) { instances += Number(row[1]); bytes += Number(row[2]); classes++; }
  if (!classes || instances !== Number(totals[0]![1]) || bytes !== Number(totals[0]![2]) || !Number.isSafeInteger(instances) || !Number.isSafeInteger(bytes) || bytes <= 0) throw new Error('histogram_total_mismatch');
  return { instances, bytes, classes };
}
export function slope(values: number[]) {
  const middle = (values.length - 1) / 2; const mean = values.reduce((s, n) => s + n, 0) / values.length;
  return values.reduce((s, y, x) => s + (x - middle) * (y - mean), 0) / values.reduce((s, _, x) => s + (x - middle) ** 2, 0);
}
const decision = (estimate: ReturnType<typeof interval>, limit: number) => estimate.n < 5 || estimate.mean === null || estimate.high === null ? 'inconclusive' : estimate.mean > limit ? 'fail' : estimate.high > limit ? 'inconclusive' : 'pass';
export function compareRetained(run: RetainedRun) {
  const reasons: string[] = [];
  if (run.schema_version !== 1 || run.status !== 'complete' || run.error_class !== null || JSON.stringify(run.manifest.protocol) !== JSON.stringify(retainedProtocol)) reasons.push('retained_protocol_or_completion_invalid');
  if (run.points.length !== retainedProtocol.repetitions * retainedProtocol.post_points * 2) reasons.push('retained_point_count');
  if (new Set(run.points.map(p => `${p.pid}:${p.start_ticks}`)).size !== retainedProtocol.repetitions * 2) reasons.push('retained_fresh_controls_required');
  if (new Set(run.points.map(p => p.fixture.virtual_ms - p.fixture.produced_hours * 3600000)).size !== 1) reasons.push('retained_virtual_work_mismatch');
  const heapSlopes: number[] = [], extraSlopes: number[] = [], extraPeaks: number[] = [], diskSlopes: number[] = [], diskPeaks: number[] = [], poolPeaks: number[] = [];
  for (let pair = 0; pair < retainedProtocol.repetitions; pair++) {
    const select = (mode: 'local' | 'minimal') => run.points.filter(p => p.pair === pair && p.mode === mode).sort((a, b) => a.point - b.point);
    const local = select('local'), minimal = select('minimal');
    if ([local, minimal].some(ps => ps.length !== retainedProtocol.post_points || ps.some((p, i) => p.point !== i))) { reasons.push('retained_pair_missing'); continue; }
    for (const points of [local, minimal]) {
      if (new Set(points.map(p => `${p.pid}:${p.start_ticks}`)).size !== 1) reasons.push('retained_process_changed');
      for (const p of points) {
        const counters = ['virtual_ms', 'produced_hours', 'requests', 'observation_reserved_bytes', 'observation_limit_bytes', 'work_leased_bytes', 'work_limit_bytes', 'composition_reserved_bytes', 'query_count', 'query_refused', 'cursor_count'] as const;
        if (counters.some(key => !Number.isSafeInteger(p.fixture[key]) || p.fixture[key] < 0) || p.fixture.requests !== p.fixture.produced_hours * 60 || p.fixture.work_leased_bytes < p.fixture.composition_reserved_bytes) reasons.push('retained_counter_missing_or_invalid');
        if (['aggregate', 'evidence', 'cursor'].some(key => !Number.isSafeInteger(p.disk[key]) || p.disk[key]! < 0) || Object.keys(p.disk).length !== 3 || !Number.isFinite(p.probe_ms) || p.probe_ms <= 0) reasons.push('retained_resource_missing_or_invalid');
        if (p.fixture.mode !== p.mode || p.fixture.produced_hours !== retainedProtocol.fill_hours + (p.point + 1) * retainedProtocol.step_hours || p.fixture.composition_reserved_bytes !== retainedProtocol.composition_reserved_bytes || !Number.isSafeInteger(p.heap.bytes) || p.heap.bytes <= 0) reasons.push('retained_measurement_invalid');
        if (new Set(p.fixture.sinks.map(s => s.sink)).size !== p.fixture.sinks.length || p.fixture.sinks.some(s => typeof s.sink !== 'string' || ['failed', 'dropped', 'pending'].some(key => !Number.isSafeInteger(s[key as keyof typeof s]) || Number(s[key as keyof typeof s]) < 0))) reasons.push('retained_sink_missing_or_invalid');
        if (p.fixture.sinks.some(s => s.failed > 0 || s.dropped > 0 || s.pending > 0) || p.fixture.query_refused > 0) reasons.push('retained_loss_or_refused_work');
        if (p.mode === 'local' && (p.fixture.sinks.length < 4 || p.fixture.query_count < 2 || p.fixture.cursor_count < 1)) reasons.push('retained_owner_coverage_missing');
      }
    }
    const extra = local.map((p, i) => p.heap.bytes - minimal[i]!.heap.bytes);
    heapSlopes.push(slope(local.map(p => p.heap.bytes))); extraSlopes.push(slope(extra)); extraPeaks.push(Math.max(...extra));
    diskSlopes.push(slope(local.map(p => Object.values(p.disk).reduce((s, n) => s + n, 0)))); diskPeaks.push(Math.max(...local.map(p => p.disk.aggregate ?? Infinity)));
    poolPeaks.push(Math.max(...local.map(p => p.fixture.observation_reserved_bytes + p.fixture.work_leased_bytes)));
  }
  if (reasons.length) return { status: 'invalid', reasons: [...new Set(reasons)], checks: null };
  const heap = interval(heapSlopes), extra = interval(extraSlopes), cap = interval(extraPeaks), disk = interval(diskSlopes);
  const checks = { local_live_heap_growth: { estimate: heap, status: decision(heap, 0) }, extra_live_heap_growth: { estimate: extra, status: decision(extra, 0) }, managed_disk_growth: { estimate: disk, status: decision(disk, 0) },
    inferred_extra_live_heap: { estimate: cap, budget_bytes: budget.aggregate_cache_bytes, status: decision(cap, budget.aggregate_cache_bytes) }, pool_reservation: { maximum: Math.max(...poolPeaks), budget_bytes: budget.aggregate_cache_bytes, status: Math.max(...poolPeaks) > budget.aggregate_cache_bytes ? 'fail' : 'pass' }, rollup_disk: { maximum: Math.max(...diskPeaks), budget_bytes: budget.rollup_disk_bytes, status: Math.max(...diskPeaks) > budget.rollup_disk_bytes ? 'fail' : 'pass' } };
  const statuses = Object.values(checks).map(v => v.status);
  return { status: statuses.includes('fail') ? 'fail' : statuses.includes('inconclusive') ? 'inconclusive' : 'pass', reasons: [], checks, scope: retainedProtocol.owners, excluded_owners: retainedProtocol.excluded_owners, interpretation: 'Post-fill whole-JVM live shallow heap from explicit-GC histogram; local-minus-minimal is a controlled estimate, not exact owner attribution. Slopes are bytes per fixed equal-work step; t intervals use independent fresh pairs, not dependent points.' };
}
export const classDigest = (entries: { path: string; sha256: string }[]) => hash(JSON.stringify([...entries].sort((a, b) => a.path.localeCompare(b.path))));
