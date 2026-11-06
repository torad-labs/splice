import { counter } from './control';
import type { Manifest } from './manifest';

/** Recompute the requested study's validity from returned receipts, not success flags alone. */
export function historyStudy(m: Manifest, rows: unknown[]) {
  const selection = m.retained_history;
  if (!selection) return { requested: false, status: 'not_requested', reasons: [] as string[], expected_queries: 0, recorded_queries: rows.length, completed_queries: 0, queries: rows };
  const reasons = new Set<string>(), seen = new Set<string>(), owners = new Map<number, string>();
  const expected = (selection.query_source === 'history' ? 48 : 1) * selection.query_window_ms / 60000;
  let complete = 0, bounds: string | null = null;
  if (rows.length !== m.repetitions * 2) reasons.add('history_query_receipts_missing_or_extra');
  if (m.cache_state !== 'cold') reasons.add('history_process_not_cold');
  for (const raw of rows) {
    const issues = new Set<string>();
    const row = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, any> : {};
    const repetition = counter(row.repetition), first = row.cache === 'first_query_in_process';
    if (repetition === null || repetition >= m.repetitions || !['first_query_in_process', 'repeat_query'].includes(row.cache)) issues.add('history_query_receipt_invalid');
    const key = `${repetition}:${row.cache}`;
    if (seen.has(key)) issues.add('history_query_receipt_duplicate');
    seen.add(key);
    if (row.cache_scope !== (first ? 'fresh_process_and_query_cache' : 'same_process_same_query') || row.prior_population_queries_in_process !== (first ? 0 : 1)) issues.add('history_cache_scope_unverified');
    if (row.source !== selection.query_source || row.window_ms !== selection.query_window_ms || row.query_bounds_match !== true || counter(row.from_ms) === null || counter(row.until_ms) === null || row.until_ms - row.from_ms !== selection.query_window_ms) issues.add('history_query_bounds_changed');
    const rowBounds = `${row.from_ms}:${row.until_ms}`;
    bounds ??= rowBounds;
    if (rowBounds !== bounds) issues.add('history_fixture_window_changed');
    if (row.status !== 'ok' || row.error_class !== null) issues.add('history_query_partial_or_failed');
    if (row.population_complete !== true || row.coverage?.complete !== true || row.coverage?.observed_ms !== selection.query_window_ms) issues.add('history_coverage_incomplete');
    const observed = counter(selection.query_source === 'history' ? row.histogram_count : row.observed_arrivals);
    const declared = counter(selection.query_source === 'history' ? row.expected_histogram_count : row.expected_arrivals);
    const population = selection.query_source === 'history' ? 'aggregate_histogram_observations' : 'request_arrivals';
    if (row.population !== population || observed !== expected || declared !== expected || row.count_matches_fixture !== true) issues.add('history_population_mismatch');
    if (counter(row.as_of_ms) === null || row.as_of_ms < row.until_ms) issues.add('history_as_of_unknown');
    if (selection.query_source === 'evidence' && (counter(row.evidence_as_of_ms) === null || row.evidence_as_of_ms < row.until_ms || counter(row.settled_until_ms) === null || row.settled_until_ms < row.until_ms || row.coverage_basis !== 'committed_observations_as_of' || row.event_time_final !== false)) issues.add('history_evidence_proof_incomplete');
    if (row.work_cost?.status !== 'observed') issues.add('history_work_cost_unavailable');
    if (counter(row.binding?.process_id) === null || row.binding.process_id < 1 || typeof row.binding?.start_ticks !== 'string' || !/^\d+$/.test(row.binding.start_ticks) || row.binding?.artifact_sha256 !== m.artifact_sha256) issues.add('history_process_binding_unverified');
    else if (repetition !== null) {
      const owner = `${row.binding.process_id}:${row.binding.start_ticks}`;
      if (owners.has(repetition) && owners.get(repetition) !== owner) issues.add('history_repeat_process_changed');
      owners.set(repetition, owner);
    }
    if (issues.size === 0) complete++;
    for (const issue of issues) reasons.add(issue);
  }
  if (owners.size !== m.repetitions || new Set(owners.values()).size !== m.repetitions) reasons.add('history_fresh_processes_missing_or_reused');
  return { requested: true, status: reasons.size ? 'partial' : 'complete', reasons: [...reasons], source: selection.query_source, window_ms: selection.query_window_ms,
    expected_queries: m.repetitions * 2, recorded_queries: rows.length, completed_queries: complete, expected_population_per_query: expected, queries: rows };
}
