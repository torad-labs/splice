import { expect, test } from 'bun:test';
import { mkdtemp, rm } from 'node:fs/promises';
import { decodeOtlp } from './otlp';
import { reconcileSinks, sinks, startTelemetry, telemetrySummary, type TelemetryPoint } from './telemetry';
import { finalSchedule, materialize } from './schedule';
import { defaults, validate } from './manifest';
import { sameRuntimeOwner } from './profile';
import type { OwnedDesk } from './control';

const cat = (...bytes: Uint8Array[]) => Buffer.concat(bytes);
const varint = (value: number) => { const bytes: number[] = []; do { const rest = value % 128; value = Math.floor(value / 128); bytes.push(rest | (value ? 128 : 0)); } while (value); return new Uint8Array(bytes); };
const field = (id: number, bytes: Uint8Array) => cat(varint(id * 8 + 2), varint(bytes.length), bytes);
const string = (id: number, value: string) => field(id, new TextEncoder().encode(value));
const fixed = (id: number, n: bigint) => { const bytes = Buffer.alloc(8); bytes.writeBigUInt64LE(n); return cat(varint(id * 8 + 1), bytes); };
const attribute = (key: string, value: string) => cat(string(1, key), field(2, string(1, value)));
const envelope = (item: Uint8Array, service = 'splice') => field(1, cat(field(1, field(1, attribute('service.name', service))), field(2, cat(field(1, string(1, 'splice.operator')), field(2, item)))));

test('OTLP validates concrete metric, span and event records and preserves event intervals', () => {
  const metric = envelope(cat(string(1, 'splice_requests_arrived'), field(7, field(1, cat(fixed(3, 1000000n), fixed(6, 7n))))));
  expect(decodeOtlp(metric, 'metrics')).toMatchObject({ records: 1, points: 1, metric_names: ['splice_requests_arrived'], first_epoch_ns: '1000000' });
  const span = envelope(cat(field(1, new Uint8Array(16).fill(1)), field(2, new Uint8Array(8).fill(2)), string(5, 'splice.request'), fixed(7, 100n), fixed(8, 200n)));
  expect(decodeOtlp(span, 'traces')).toMatchObject({ records: 1, first_epoch_ns: '100', last_epoch_ns: '200' });
  const log = envelope(cat(fixed(1, 100n), field(5, string(1, 'REQUEST_FINISHED'))));
  expect(decodeOtlp(log, 'logs')).toMatchObject({ records: 1, first_epoch_ns: '100' });
  expect(() => decodeOtlp(new Uint8Array(), 'metrics')).toThrow('otlp_records_missing');
  const missingTimestamp = envelope(cat(string(1, 'splice_requests_arrived'), field(7, field(1, fixed(6, 1n)))));
  expect(() => decodeOtlp(missingTimestamp, 'metrics')).toThrow('otlp_metric_timestamp_invalid');
  expect(() => decodeOtlp(envelope(cat(fixed(1, 100n), field(5, string(1, 'private payload')))), 'logs')).toThrow('otlp_log_body_not_lifecycle');
  expect(() => decodeOtlp(envelope(cat(fixed(1, 100n), field(5, string(1, 'REQUEST_FINISHED'))), 'other'), 'logs')).toThrow('otlp_service_identity_missing');
  expect(() => decodeOtlp(span.subarray(0, span.length - 1), 'traces')).toThrow();
});

test('histogram export rejects mismatched bucket totals instead of counting accepted bytes as evidence', () => {
  const buckets = Buffer.alloc(8); buckets.writeBigUInt64LE(2n);
  const malformed = envelope(cat(string(1, 'splice_latency_seconds'), field(9, field(1, cat(fixed(3, 100n), fixed(4, 3n), field(6, buckets))))));
  expect(() => decodeOtlp(malformed, 'metrics')).toThrow('otlp_histogram_count_mismatch');
});
function point(at: number, value: number): TelemetryPoint {
  return { repetition: 0, phase: 'steady', sampled_from_ms: at, sampled_until_ms: at + 1, identity: { bootId: 'a', build: 'b', version: '1', configRevision: 'c', schemaVersion: 1 }, artifact_sha256: 'a'.repeat(64), process_id: 123, config_sha256: 'b'.repeat(64), status: 'ok', issues: [], as_of_ms: at,
    sinks: [{ sink: 'request_evidence:bench', observed: value, enqueued: value, written: value, failed: 0, dropped: 0, pending: 0, oldestPendingMs: null, lastSuccessEpochMs: at }], memory: { observation_reserved_bytes: 10, observation_limit_bytes: 16, work_leased_bytes: 4, work_limit_bytes: 16, owner_accounted_bytes: {} }, disk: { aggregate: 100 }, runtime: null, exports: null, read_bytes: 100, elapsed_ms: 1 };
}
test('sink evidence reports actual interval losses and refuses resets, changed ownership and missing counters', () => {
  const first = point(1000, 5), last = point(2000, 10);
  const sink = first.sinks![0]!;
  expect(sinks(first.sinks)).toEqual(first.sinks!);
  expect(sinks([{ ...sink, sink: 'a'.repeat(80) }])).toHaveLength(1);
  for (const name of ['', 'a'.repeat(81), 'request_evidence:/bench', 'request_evidence:bench name', 'request_evidence:bench\n', 'request_evidence:bench\u0000', 'request_evidence:bé nch']) {
    expect(() => sinks([{ ...sink, sink: name }])).toThrow('sink_status_invalid');
  }
  expect(() => sinks([sink, sink])).toThrow('sink_status_invalid');
  for (const key of ['observed', 'enqueued', 'written', 'failed', 'dropped', 'pending']) {
    for (const value of [undefined, null, -1, 0.5, Infinity, Number.MAX_SAFE_INTEGER + 1, '1']) {
      expect(() => sinks([{ ...sink, [key]: value }])).toThrow('sink_counter_missing');
    }
  }
  for (const key of ['oldestPendingMs', 'lastSuccessEpochMs']) {
    expect(() => sinks([{ ...sink, [key]: undefined }])).toThrow('sink_age_missing');
    expect(() => sinks([{ ...sink, [key]: -1 }])).toThrow('sink_age_missing');
  }
  expect(reconcileSinks(first, last)).toMatchObject({ status: 'observed', value: 0 });
  last.sinks![0]!.dropped = 2;
  expect(reconcileSinks(first, last)).toMatchObject({ status: 'loss', value: 2 });
  expect(reconcileSinks(first, point(2000, 1))).toMatchObject({ value: null, reason: 'sink_counter_reset' });
  expect(reconcileSinks(first, { ...last, process_id: 124 })).toMatchObject({ value: null, reason: 'sink_identity_changed' });
  expect(reconcileSinks(first, { ...last, sinks: null })).toMatchObject({ value: null, reason: 'sink_boundary_partial' });
  expect(reconcileSinks(first, { ...last, status: 'partial' })).toMatchObject({ value: null, reason: 'sink_boundary_partial' });
  const summary = telemetrySummary([first, last], [{ repetition: 0, before: 0, after: 1, from_ms: 1001, until_ms: 1999 }]);
  expect(summary.resources).toMatchObject({ pool_reservation_peak_bytes: 14, retained_heap_bytes: null, sustained_growth: null });
});
test('live telemetry requires the artifact-bound build before accepting its first or subsequent sample', async () => {
  const state = await mkdtemp('/tmp/splice-instrumentation-live-identity-test-');
  const processBuild = `compiled-artifact-inputs-sha256:${'a'.repeat(64)}`;
  const identity = { bootId: 'test-boot', build: `source:${'1'.repeat(40)}`, version: '1', configRevision: 'test-config', schemaVersion: 1 };
  const server = Bun.serve({ hostname: '127.0.0.1', port: 0, fetch(request) {
    const as_of_ms = Date.now();
    if (new URL(request.url).pathname === '/api/perf/runtime') return Response.json({ identity, as_of_ms, age_ms: 0, capabilities: [], issues: [], measurements: {} });
    return Response.json({ identity, as_of_ms, schema_version: 1, process_id: 123, collection_mode: 'local', status: 'ok', sinks: point(as_of_ms, 1).sinks,
      memory: { observation: { reserved_bytes: 10, total_limit_bytes: 16 }, work: { leased_bytes: 4, limit_bytes: 16 }, owners: {} } });
  } });
  try {
    const desk: OwnedDesk = { pid: 123, state, mgmtKey: 'test-key', controlBase: server.url.origin, assertIdentity: async () => {},
      identity: { artifact_path: `${state}/synthetic.jar`, source_commit: '1'.repeat(40), artifact_sha256: '2'.repeat(64), artifact_receipt_sha256: '3'.repeat(64), process_build: processBuild,
        config_revision: '4'.repeat(64), base_config_sha256: '5'.repeat(64), raw_config_sha256: '6'.repeat(64), pid: 123, start_ticks: '1', listeners: [], verification: 'linux-proc-listen-inodes', verified_at: new Date().toISOString() } };
    const manifest = defaults(); manifest.role = 'candidate'; manifest.collection.mode = 'local';
    const telemetry = await startTelemetry(manifest, desk, 0);
    const first = await telemetry.sample();
    expect(first.status).toBe('partial'); expect(first.issues).toContain('instrumentation_build_mismatch'); expect(first.identity).toBeNull(); expect(first.sinks).toBeNull();
    identity.build = processBuild;
    const valid = await telemetry.sample();
    expect(valid.status).toBe('ok'); expect(valid.issues).toEqual([]); expect(valid.identity?.build).toBe(processBuild); expect(valid.sinks).toHaveLength(1);
    identity.build = `compiled-artifact-inputs-sha256:${'b'.repeat(64)}`;
    const changed = await telemetry.sample();
    expect(changed.status).toBe('partial'); expect(changed.issues).toContain('instrumentation_build_mismatch'); expect(changed.identity).toBeNull(); expect(changed.sinks).toBeNull();
  } finally { await server.stop(true); await rm(state, { recursive: true, force: true }); }
});
test('prepared schedule has five repeated required closed points, bounded open sweep and distinct optional studies', () => {
  const plan = finalSchedule(); expect(plan.measured).toBe(false);
  expect(plan.points.filter(p => p.id.startsWith('closed-')).map(p => p.patch.load?.value)).toEqual([1, 10, 50, 70, 100]);
  expect(plan.points.filter(p => p.id.startsWith('arrival-')).map(p => p.patch.load?.value)).toEqual([100, 250, 400, 500]);
  for (const p of plan.points.filter(p => p.cohort === 'matched')) { const manifest = materialize(p, 'direct'); expect(manifest.repetitions).toBe(5); expect(manifest.duration_ms).toBeGreaterThanOrEqual(30000); }
  const optional = plan.points.find(p => p.id === 'profile-c10')!;
  expect(() => materialize(optional, 'baseline')).toThrow('study_requires_candidate_role');
  expect(() => materialize(plan.points.find(p => p.id === 'native-mcp')!, 'candidate')).toThrow('fixture_prerequisite_required');
  const old = defaults(); old.role = 'baseline'; old.collection.mode = 'minimal';
  expect(() => validate(old)).toThrow('legacy baseline has no minimal collection toggle');
  expect(plan.points.filter(p => p.cohort === 'candidate-history-study').map(p => p.history_query)).toEqual([
    { source: 'history', window_ms: 86400000 }, { source: 'history', window_ms: 604800000 },
    { source: 'evidence', window_ms: 86400000 }, { source: 'evidence', window_ms: 604800000 },
  ]);
});

test('profile owner must match the measured boot and build while configuration scope remains explicit', () => {
  const runtime = { bootId: 'boot', build: 'loaded-build', version: '1', schemaVersion: 1, configRevision: 'applied-policy' };
  const profile = { ...runtime, configRevision: 'unknown' };
  expect(sameRuntimeOwner(profile, runtime)).toBe(true);
  for (const changed of [{ bootId: 'other-boot' }, { build: 'other-build' }, { schemaVersion: 2 }, { version: '2' }, { build: 'unknown' }]) {
    expect(sameRuntimeOwner({ ...profile, ...changed }, runtime)).toBe(false);
  }
});
