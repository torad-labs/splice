import { copyFile, lstat, mkdir, readFile, realpath } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { control, counter, type OwnedDesk } from './control';
import { hash, type Manifest } from './manifest';
import { numericProjection } from './telemetry';
import { readSourceCosts, sourceCostDelta } from './costs';
export interface RetainedFixture { directory: string; receipt_sha256: string; source_commit: string }
/** Copies only verified producer output into a new isolated desk before its process starts. */
export async function installHistory(fixture: RetainedFixture, state: string) {
  const root = await realpath(fixture.directory);
  if (!root.startsWith('/tmp/splice-instrumentation-') || !state.startsWith('/tmp/splice-instrumentation-')) throw new Error('history_task_directory_required');
  const bytes = await readFile(join(root, 'fixture-receipt.json'));
  if (bytes.length > 8 * 1024 ** 2 || hash(bytes) !== fixture.receipt_sha256) throw new Error('history_receipt_unverified');
  const receipt = JSON.parse(bytes.toString());
  if (receipt.schema_version !== 1 || receipt.fixture_kind !== 'production_writers_with_virtual_wall_and_elapsed_clocks' || receipt.identity?.build !== `source:${fixture.source_commit}` || !Array.isArray(receipt.files) || !receipt.files.length || receipt.files.length > 20000 || receipt.coverage_certified_until_ms !== receipt.until_ms || receipt.until_ms - receipt.from_ms !== 7 * 86400000) throw new Error('history_fixture_invalid');
  let total = 0; const seen = new Set<string>();
  for (const file of receipt.files) {
    if (typeof file.path !== 'string' || !/^state\/perf-(aggregates|evidence)\/[a-zA-Z0-9_.-]+$/.test(file.path) || seen.has(file.path) || !/^[a-f0-9]{64}$/.test(file.sha256) || counter(file.bytes) === null || file.bytes > 8 * 1024 ** 2) throw new Error('history_file_invalid');
    seen.add(file.path); total += file.bytes; if (total > 384 * 1024 ** 2) throw new Error('history_fixture_limit');
    const source = resolve(root, file.path); if (await realpath(source) !== source || !(await lstat(source)).isFile()) throw new Error('history_fixture_symlink');
    if (hash(await readFile(source)) !== file.sha256) throw new Error('history_source_changed');
    const target = join(state, file.path.slice('state/'.length)); await mkdir(dirname(target), { recursive: true, mode: 0o700 });
    await copyFile(source, target, 1);
    if ((await lstat(target)).size !== file.bytes || hash(await readFile(target)) !== file.sha256) throw new Error('history_copy_changed');
  }
  if (![...seen].some(p => p === 'state/perf-evidence/coverage.json')) throw new Error('history_coverage_receipt_missing');
  return { receipt_sha256: fixture.receipt_sha256, identity: receipt.identity, from_ms: receipt.from_ms as number, until_ms: receipt.until_ms as number, files: seen.size, bytes: total };
}
export async function queryHistory(desk: OwnedDesk, fixture: { from_ms: number; until_ms: number }, selection: NonNullable<Manifest['retained_history']>, repetition: number) {
  const rows: object[] = [];
  const window = selection.query_window_ms, source = selection.query_source, until = fixture.until_ms, from = until - window;
  if (![86400000, 604800000].includes(window) || !['history', 'evidence'].includes(source) || from < fixture.from_ms || !Number.isSafeInteger(until) || until > Date.now()) throw new Error('history_query_selection_invalid');
  for (const cache of ['first_query_in_process', 'repeat_query']) {
    const before = await readSourceCosts(desk);
    const endpoint = source === 'history' ? 'series' : 'summary';
    const started = performance.now();
    let result: { value: Record<string, any>; bytes: number | null; elapsed_ms: number }, errorClass: string | null = null;
    try {
      result = await control(desk, `/api/perf/${endpoint}?contract=1&head=bench&source=${source}&from=${from}&until=${until}`, { limit: 4 * 1024 ** 2, timeout_ms: 30000 });
    } catch (error) {
      const message = error instanceof Error ? error.message : '';
      errorClass = /^[a-z0-9_]{1,100}$/.test(message) ? message : 'history_query_failed';
      result = { value: {}, bytes: null, elapsed_ms: performance.now() - started };
    }
    const v = result.value && typeof result.value === 'object' && !Array.isArray(result.value) ? result.value : {};
    const work_cost = sourceCostDelta(before, await readSourceCosts(desk));
    const queryBoundsMatch = v.query?.from_ms === from && v.query?.until_ms === until && v.query?.source === source;
    if (!queryBoundsMatch) errorClass ??= 'history_query_bounds_changed';
    const histogramCount = source === 'history' && Array.isArray(v.series) ? v.series.reduce((n: number, row: any) => n + Object.values(row.metrics ?? {}).reduce<number>((s, value: any) => s + (counter(value.count) ?? 0), 0), 0) : null;
    const workload = v.comparison?.workload;
    const observedArrivals = source === 'evidence' ? counter(workload?.observed_arrivals) : null;
    const populationComplete = source === 'evidence' ? typeof workload?.population_complete === 'boolean' ? workload.population_complete : null : typeof v.coverage?.complete === 'boolean' ? v.coverage.complete : null;
    const actual = source === 'history' ? histogramCount : observedArrivals, expected = (source === 'history' ? 48 : 1) * window / 60000;
    const evidenceCoverage = v.population_coverage?.request_scope_resolved_at_terminal_or_latest_checkpoint;
    rows.push({ repetition, window_ms: window, source, cache, cache_scope: cache === 'first_query_in_process' ? 'fresh_process_and_query_cache' : 'same_process_same_query', prior_population_queries_in_process: cache === 'first_query_in_process' ? 0 : 1,
      binding: { process_id: desk.pid, start_ticks: desk.identity?.start_ticks ?? null, artifact_sha256: desk.identity?.artifact_sha256 ?? null },
      status: errorClass ? /^control_http_(409|429|503)$/.test(errorClass) ? 'busy' : 'error' : typeof v.status === 'string' && ['ok', 'partial', 'busy', 'error'].includes(v.status) ? v.status : 'unknown', error_class: errorClass,
      query_bounds_match: queryBoundsMatch, returned_from_ms: counter(v.query?.from_ms), returned_until_ms: counter(v.query?.until_ms), returned_source: ['history', 'evidence'].includes(v.query?.source) ? v.query.source : null,
      from_ms: from, until_ms: until, elapsed_ms: result.elapsed_ms, bytes: result.bytes,
      population: source === 'history' ? 'aggregate_histogram_observations' : 'request_arrivals', populated: actual === null ? null : actual > 0, histogram_count: histogramCount, expected_histogram_count: source === 'history' ? expected : null,
      observed_arrivals: observedArrivals, expected_arrivals: source === 'evidence' ? expected : null, population_complete: populationComplete, observed_count_matches_fixture: actual === null ? null : actual === expected, count_matches_fixture: populationComplete === true && actual === expected,
      coverage: numericProjection(v.coverage), population_coverage: numericProjection(v.population_coverage), missing_fields: Array.isArray(v.missing_fields) ? v.missing_fields.filter((code: unknown) => typeof code === 'string' && /^[a-z0-9_.-]{1,100}$/.test(code)).slice(0, 256) : null,
      as_of_ms: counter(v.as_of_ms), evidence_as_of_ms: counter(evidenceCoverage?.as_of_ms), coverage_basis: evidenceCoverage?.coverage_basis === 'committed_observations_as_of' ? 'committed_observations_as_of' : 'unknown', event_time_final: typeof evidenceCoverage?.event_time_final === 'boolean' ? evidenceCoverage.event_time_final : null,
      resolution_ms: counter(v.resolution_ms), settled_until_ms: counter(v.settled_until_ms), work_cost, identity_cohort_count: Array.isArray(v.identity_cohorts) ? v.identity_cohorts.length : null,
      limitations: ['filesystem cache is uncontrolled', 'fixture cohort identity differs from the live boot', 'partial evidence summaries are not complete exports; complete cursor traversal is a separate acceptance check'] });
  }
  return rows;
}
