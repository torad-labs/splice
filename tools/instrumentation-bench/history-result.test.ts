import { expect, test } from 'bun:test';
import { initial } from './observe';
import { finalSchedule, materialize, materializeHistory } from './schedule';
import { summarize, type Extras } from './summary';

const artifact = { path: '/tmp/splice-instrumentation-history/candidate.jar', source_commit: 'a'.repeat(40), sha256: 'b'.repeat(64) };
const seed = { directory: '/tmp/splice-instrumentation-history/seed', receipt_sha256: 'c'.repeat(64), source_commit: artifact.source_commit };
const points = finalSchedule().points.filter(point => point.cohort === 'candidate-history-study');
function fixture(point = points[0]!) {
  const m = materializeHistory(point, artifact, seed), selection = m.retained_history!;
  const window = selection.query_window_ms, evidence = selection.query_source === 'evidence', expected = (evidence ? 1 : 48) * window / 60000;
  const until = 20 * 86400000, history: Record<string, any>[] = [];
  const extras: Extras = { repetitions: [], fixture: {}, polling: { count: 0, failed: 0, bytes: 0, elapsed_ms: 0 }, partial_reason: null, history };
  const samples = Array.from({ length: m.repetitions }, (_, repetition) => {
    extras.repetitions.push({ repetition, observed_duration_ms: m.duration_ms, drain_ms: 0, cpu_ms: 10, rss_start_bytes: 1, rss_end_bytes: 1, disk_start_bytes: 1, disk_end_bytes: 1 });
    for (const first of [true, false]) history.push({ repetition, source: selection.query_source, window_ms: window, cache: first ? 'first_query_in_process' : 'repeat_query',
      cache_scope: first ? 'fresh_process_and_query_cache' : 'same_process_same_query', prior_population_queries_in_process: first ? 0 : 1,
      from_ms: until - window, until_ms: until, query_bounds_match: true, status: 'ok', error_class: null,
      population: evidence ? 'request_arrivals' : 'aggregate_histogram_observations', population_complete: true, count_matches_fixture: true,
      histogram_count: evidence ? null : expected, expected_histogram_count: evidence ? null : expected, observed_arrivals: evidence ? expected : null, expected_arrivals: evidence ? expected : null,
      coverage: { complete: true, observed_ms: window }, as_of_ms: until + 1, evidence_as_of_ms: until + 1, settled_until_ms: until,
      coverage_basis: 'committed_observations_as_of', event_time_final: false, work_cost: { status: 'observed' }, binding: { process_id: 100 + repetition, start_ticks: `${1000 + repetition}`, artifact_sha256: artifact.sha256 } });
    return { ...initial(repetition, repetition, 'steady', 0), started_at_ms: 0, first_content_at_ms: 1, terminal_at_ms: 2, accepted: true, outcome: 'completed' as const, terminal_correct: true, generator_delay_ms: 0 };
  });
  return { m, samples, extras, history };
}
test('all four history preparations bind exact selectors and preserve independent complete population results', () => {
  expect(points).toHaveLength(4);
  for (const point of points) {
    const { m, samples, extras } = fixture(point);
    expect(m).toMatchObject({ repetitions: 5, cache_state: 'cold', role: 'candidate', retained_history: { ...seed, query_source: point.history_query!.source, query_window_ms: point.history_query!.window_ms } });
    const result = summarize(m, samples, extras);
    expect(result).toMatchObject({ status: 'complete', partial_reason: null, history: { requested: true, status: 'complete', recorded_queries: 10, completed_queries: 10, reasons: [] } });
  }
});
test('partial, refused, unknown and mismatched history receipts remain visible and make the overall result partial', () => {
  const changes = [
    { status: 'partial' }, { status: 'busy', error_class: 'control_http_503' }, { status: 'error', error_class: 'history_query_failed' },
    { coverage: null }, { coverage: { complete: false, observed_ms: 0 } }, { population_complete: null },
    { observed_arrivals: 1 }, { as_of_ms: null }, { evidence_as_of_ms: null }, { settled_until_ms: null },
    { query_bounds_match: false }, { work_cost: { status: 'unavailable' } },
  ];
  for (const change of changes) {
    const { m, samples, extras, history } = fixture(points[2]!);
    Object.assign(history[0]!, change);
    const result = summarize(m, samples, extras);
    expect(result).toMatchObject({ status: 'partial', partial_reason: 'history_study_partial', history: { status: 'partial', recorded_queries: 10 } });
    expect(result.history.queries).toBe(history);
    expect(result.history.reasons.length).toBeGreaterThan(0);
  }
  const aggregate = fixture(); aggregate.history[0]!.histogram_count = 1;
  expect(summarize(aggregate.m, aggregate.samples, aggregate.extras).history.reasons).toContain('history_population_mismatch');
});
test('missing receipts and reused processes cannot complete a requested cold history study', () => {
  const absent = fixture(); absent.history.pop();
  expect(summarize(absent.m, absent.samples, absent.extras).status).toBe('partial');
  const reused = fixture(); for (const row of reused.history) row.binding = reused.history[0]!.binding;
  expect(summarize(reused.m, reused.samples, reused.extras).history.reasons).toContain('history_fresh_processes_missing_or_reused');
});
test('history materialization rejects unrelated prerequisites and a seed from another source', () => {
  expect(() => materialize(points[0]!, 'candidate', artifact)).toThrow('fixture_prerequisite_required');
  expect(() => materializeHistory(points[0]!, artifact, { ...seed, source_commit: 'd'.repeat(40) })).toThrow('invalid retained history fixture');
  for (const id of ['retained-state', 'native-mcp']) expect(() => materializeHistory(finalSchedule().points.find(point => point.id === id)!, artifact, seed)).toThrow('history_point_required');
});
