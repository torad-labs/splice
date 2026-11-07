import { expect, test } from 'bun:test';
import { compareRetained, parseHistogram, retainedProtocol, type RetainedRun } from './retained-model';
import { defaults, environment } from './manifest';
import { sourceCostDelta, type SourceCosts } from './costs';
function fixture(): RetainedRun {
  const points: RetainedRun['points'] = [];
  for (let pair = 0; pair < 5; pair++) {
    for (const mode of ['minimal', 'local'] as const) {
      for (let point = 0; point < 5; point++) {
        points.push({
          pair, mode, point,
          pid: pair * 2 + (mode === 'local' ? 2 : 1), start_ticks: '1234',
          heap: { instances: 1000, bytes: mode === 'local' ? 2 * 1024 ** 2 : 1024 ** 2, classes: 100 },
          histogram_sha256: 'e'.repeat(64), histogram_path: '', probe_ms: 20,
          disk: { aggregate: mode === 'local' ? 2000000 : 0, evidence: mode === 'local' ? 1000000 : 0, cursor: mode === 'local' ? 1000 : 0 },
          fixture: {
            mode, virtual_ms: 1000000 + point * 86400000, produced_hours: 864 + (point + 1) * 24,
            requests: (864 + (point + 1) * 24) * 60,
            observation_reserved_bytes: mode === 'local' ? 2000000 : 0, observation_limit_bytes: 16 * 1024 ** 2,
            work_leased_bytes: retainedProtocol.composition_reserved_bytes, work_limit_bytes: 16 * 1024 ** 2,
            composition_reserved_bytes: retainedProtocol.composition_reserved_bytes,
            query_count: mode === 'local' ? 2 * (point + 1) : 0, query_refused: 0,
            expected_retention_incomplete: 0, cursor_count: mode === 'local' ? point + 1 : 0,
            read_diagnostics: mode === 'local'
              ? { history_series_sizes: [3, 3], cursor_status: 'complete', cursor_issue_codes: [], cursor_returned_observations: 1, cursor_known_matching_events: 1, cursor_total_matching_events: 1, cursor_malformed_rows: 0, cursor_has_next: false, cursor_coverage_status: 'retained_snapshot', cursor_historical_completeness: 'unknown_before_retained_segments' }
              : { history_series_sizes: [], cursor_status: null, cursor_issue_codes: [], cursor_returned_observations: null, cursor_known_matching_events: null, cursor_total_matching_events: null, cursor_malformed_rows: null, cursor_has_next: null, cursor_coverage_status: null, cursor_historical_completeness: null },
            sinks: mode === 'local' ? ['accumulator', 'aggregate', 'evidence', 'coverage'].map(sink => ({ sink, failed: 0, dropped: 0, pending: 0 })) : [],
          },
        });
      }
    }
  }
  return { schema_version: 1, status: 'complete', error_class: null, manifest: { schema_version: 1, source_commit: 'a'.repeat(40), artifact_sha256: 'b'.repeat(64), artifact_path: '/tmp/splice-instrumentation-test/candidate.jar', fixture_classes_root: '/tmp/splice-instrumentation-test/classes', fixture_classes_sha256: 'c'.repeat(64), protocol: retainedProtocol }, environment: environment(), jvm: 'fixture java version', java_executable: '/fixture/java', java_sha256: 'f'.repeat(64), process_bindings: [], artifact_receipt_sha256: 'd'.repeat(64), fixture_classes: [], measured_at: '', histogram_command: '', harness_sha256: '', points };
}
test('live histogram totals must agree with every reported class, not a plausible Total line alone', () => {
  expect(parseHistogram('123:\n num #instances #bytes class\n 1: 10 160 java.lang.Object\n 2: 2 64 [B\nTotal 12 224\n')).toEqual({ instances: 12, bytes: 224, classes: 2 });
  expect(() => parseHistogram('Total 100 1000\n')).toThrow('histogram_total_mismatch');
  expect(() => parseHistogram(' 1: 10 160 java.lang.Object\nTotal 10 161\n')).toThrow('histogram_total_mismatch');
  expect(() => parseHistogram(' 1: 10 160 java.lang.Object\n')).toThrow('histogram_total_missing');
});
test('retained decisions require actual stable post-fill data and retain the zero-growth budget', () => {
  const steady = fixture(); expect(compareRetained(steady).status).toBe('pass');
  expect(compareRetained(steady).checks?.inferred_extra_live_heap.budget_bytes).toBe(defaults().budgets.aggregate_cache_bytes);
  const growth = fixture(); growth.points.filter(p => p.mode === 'local').forEach(p => { p.heap.bytes += p.point * 1000; });
  expect(compareRetained(growth).status).toBe('fail');
  const unstable = fixture(); unstable.points.filter(p => p.mode === 'local').forEach(p => { p.heap.bytes += (p.pair === 0 ? 400 : -100) * p.point; });
  expect(compareRetained(unstable).status).toBe('inconclusive');
  const missing = fixture(); missing.points.pop(); expect(compareRetained(missing).status).toBe('invalid');
  const dropped = fixture(); dropped.points.find(p => p.mode === 'local')!.fixture.sinks[0]!.dropped = 1;
  expect(compareRetained(dropped).reasons).toContain('retained_loss_or_refused_work');
  const beforeFill = fixture(); beforeFill.points[0]!.fixture.produced_hours = 2;
  expect(compareRetained(beforeFill).reasons).toContain('retained_measurement_invalid');
  const overCap = fixture(); overCap.points.filter(p => p.mode === 'local').forEach(p => { p.fixture.work_leased_bytes = 40 * 1024 ** 2; });
  expect(compareRetained(overCap).status).toBe('fail');
  const noDisk = fixture(); delete noDisk.points[0]!.disk.evidence;
  expect(compareRetained(noDisk).status).toBe('invalid');
  const reused = fixture(); reused.points.filter(p => p.mode === 'local').forEach(p => { p.pid = 999; });
  expect(compareRetained(reused).reasons).toContain('retained_fresh_controls_required');
  const badLoss = fixture(); badLoss.points.find(p => p.mode === 'local')!.fixture.sinks[0]!.dropped = -1;
  expect(compareRetained(badLoss).reasons).toContain('retained_sink_missing_or_invalid');
});
test('expected retention-limited pages remain visible without becoming operational refusals', () => {
  const retained = fixture();
  for (const point of retained.points.filter(p => p.mode === 'local')) {
    point.fixture.expected_retention_incomplete = point.point + 1;
    point.fixture.read_diagnostics = { history_series_sizes: [3, 3], cursor_status: 'incomplete', cursor_issue_codes: ['evidence_prior_loss_unknown', 'evidence_retention_expired'], cursor_returned_observations: 32, cursor_known_matching_events: 120, cursor_total_matching_events: null, cursor_malformed_rows: 0, cursor_has_next: true, cursor_coverage_status: 'incomplete', cursor_historical_completeness: 'unknown_before_retained_segments' };
  }
  const result = compareRetained(retained);
  expect(result.status).toBe('pass');
  expect(result.read_summary).toMatchObject({
    expected_retention_incomplete_final_per_local_process: Array.from({ length: 5 }, (_, pair) => ({ pair, count: 5 })),
    cursor_status_counts: { incomplete: 25 },
    cursor_coverage_status_counts: { incomplete: 25 },
    cursor_issue_codes: ['evidence_prior_loss_unknown', 'evidence_retention_expired'],
    cursor_historical_completeness: ['unknown_before_retained_segments'],
  });

  const unexpected = fixture();
  const unexpectedLocal = unexpected.points.find(p => p.mode === 'local')!;
  unexpectedLocal.fixture.read_diagnostics = { history_series_sizes: [3, 3], cursor_status: 'incomplete', cursor_issue_codes: ['cursor_busy'], cursor_returned_observations: 32, cursor_known_matching_events: 120, cursor_total_matching_events: null, cursor_malformed_rows: 0, cursor_has_next: true, cursor_coverage_status: 'incomplete', cursor_historical_completeness: 'unknown_before_retained_segments' };
  expect(compareRetained(unexpected).reasons).toContain('retained_loss_or_refused_work');

  const emptyHistory = fixture();
  emptyHistory.points.find(p => p.mode === 'local')!.fixture.read_diagnostics.history_series_sizes = [0, 3];
  expect(compareRetained(emptyHistory).reasons).toContain('retained_loss_or_refused_work');

  const mixed = fixture();
  for (const point of mixed.points.filter(p => p.mode === 'local')) {
    if (point.point < 2) continue;
    point.fixture.expected_retention_incomplete = point.point - 1;
    point.fixture.read_diagnostics = { history_series_sizes: [3, 3], cursor_status: 'incomplete', cursor_issue_codes: ['evidence_prior_loss_unknown'], cursor_returned_observations: 32, cursor_known_matching_events: 120, cursor_total_matching_events: null, cursor_malformed_rows: 0, cursor_has_next: true, cursor_coverage_status: 'incomplete', cursor_historical_completeness: 'unknown_before_retained_segments' };
  }
  expect(compareRetained(mixed).status).toBe('pass');
  expect(compareRetained(mixed).read_summary).toMatchObject({
    expected_retention_incomplete_final_per_local_process: Array.from({ length: 5 }, (_, pair) => ({ pair, count: 3 })),
    cursor_status_counts: { complete: 10, incomplete: 15 },
    cursor_coverage_status_counts: { retained_snapshot: 10, incomplete: 15 },
  });

  const undercounted = fixture();
  for (const point of undercounted.points.filter(p => p.mode === 'local')) {
    point.fixture.expected_retention_incomplete = 1;
    point.fixture.read_diagnostics = { history_series_sizes: [3, 3], cursor_status: 'incomplete', cursor_issue_codes: ['evidence_prior_loss_unknown', 'evidence_retention_expired'], cursor_returned_observations: 32, cursor_known_matching_events: 120, cursor_total_matching_events: null, cursor_malformed_rows: 0, cursor_has_next: true, cursor_coverage_status: 'incomplete', cursor_historical_completeness: 'unknown_before_retained_segments' };
  }
  expect(compareRetained(undercounted).reasons).toContain('retained_expected_coverage_counter_mismatch');

  const regressed = fixture();
  for (const point of regressed.points.filter(p => p.mode === 'local')) {
    point.fixture.expected_retention_incomplete = point.point + 1;
    point.fixture.read_diagnostics = { history_series_sizes: [3, 3], cursor_status: 'incomplete', cursor_issue_codes: ['evidence_prior_loss_unknown', 'evidence_retention_expired'], cursor_returned_observations: 32, cursor_known_matching_events: 120, cursor_total_matching_events: null, cursor_malformed_rows: 0, cursor_has_next: true, cursor_coverage_status: 'incomplete', cursor_historical_completeness: 'unknown_before_retained_segments' };
  }
  regressed.points.find(p => p.mode === 'local' && p.pair === 0 && p.point === 3)!.fixture.expected_retention_incomplete = 2;
  expect(compareRetained(regressed).reasons).toContain('retained_expected_coverage_counter_mismatch');

  const cursorCountRegression = fixture();
  cursorCountRegression.points.find(p => p.mode === 'local' && p.pair === 0 && p.point === 3)!.fixture.cursor_count = 3;
  expect(compareRetained(cursorCountRegression).reasons).toContain('retained_cursor_count_progression_invalid');

  const inconsistent = fixture();
  const local = inconsistent.points.find(p => p.mode === 'local')!;
  local.fixture.expected_retention_incomplete = 2;
  expect(compareRetained(inconsistent).reasons).toContain('retained_counter_missing_or_invalid');
});
test('source work cost brackets expose cache hits and shared leases without fabricating reset deltas', () => {
  const owner = { activeQueries: 0, queries: 2, cacheMisses: 1, rowsScanned: 30, bytesScanned: 400, queryMs: 10, busy: 0, warmProjections: 1, coldProjections: 1, historyBytes: 5000, retainedBytes: 6000 };
  const before: SourceCosts = { process_id: 123, as_of_ms: 100, identity: { bootId: 'b', build: 'c', version: '1', configRevision: 'd', schemaVersion: 1 }, sources: { aggregate: { ...owner }, evidence: { ...owner } }, retained_bytes_scope: 'process_shared_work_pool' };
  const after = structuredClone(before); after.as_of_ms++; after.sources.aggregate.warmProjections++;
  expect(sourceCostDelta(before, after)).toMatchObject({ status: 'observed', retained_bytes_scope: 'process_shared_work_pool', sources: { aggregate: { delta: { queries: 0, warmProjections: 1 } } } });
  after.sources.evidence.bytesScanned = 0;
  expect(sourceCostDelta(before, after)).toMatchObject({ status: 'unavailable', sources: null, reason: 'source_cost_counter_reset' });
  expect(sourceCostDelta(null, after).sources).toBeNull();
});
