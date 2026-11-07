import { copyFile, lstat, mkdir, readFile, realpath } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { control, counter, type OwnedDesk } from './control';
import { hash, type Manifest } from './manifest';
import { numericProjection } from './telemetry';
import { readSourceCosts, sourceCostDelta } from './costs';
export interface RetainedFixture { directory: string; receipt_sha256: string; source_commit: string }
const FIXTURE_HISTOGRAM_METRICS = ['arrival_to_terminal_ms', 'arrival_to_first_meaningful_content_ms', 'queue_wait_ms'] as const;
const FIXTURE_DURATION_BOUNDS_MS = [0, 1, 2, 5, 10, 20, 50, 100, 200, 250, 500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000];
// Exact identity literals emitted by app/src/test/.../BenchmarkFixtureSeed.kt; no arbitrary fixture labels pass.
const RETAINED_FIXTURE_BOOT_ID = 'benchmark-retained-fixture';
const RETAINED_FIXTURE_CONFIG_REVISION = 'fixture-config-v1';
export function boundedIssueProjection(value: unknown) {
  if (!Array.isArray(value)) return { issues: [], valid: false, truncated: false };
  const truncated = value.length > 64;
  const issues = value.slice(0, 64).map(issue => ({
    code: typeof issue?.code === 'string' && /^[a-z0-9_]{1,100}$/.test(issue.code) ? issue.code : null,
    source: typeof issue?.source === 'string' && /^[a-z0-9_:-]{1,80}$/.test(issue.source) ? issue.source : null,
    count: counter(issue?.count),
  }));
  const valid = !truncated && issues.every(issue => issue.code !== null && issue.source !== null && issue.count !== null);
  return { issues, valid, truncated };
}
/** Retain only stable identity fields needed to bind a stored cohort to a verified process. */
export function boundedIdentityCohortProjection(value: unknown) {
  if (!Array.isArray(value)) return { cohorts: [], valid: false, truncated: false };
  const truncated = value.length > 64;
  const cohorts = value.slice(0, 64).map(identity => {
    const record = identity && typeof identity === 'object' && !Array.isArray(identity) ? identity as Record<string, unknown> : {};
    const bootId = typeof record.boot_id === 'string' && (/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(record.boot_id) || record.boot_id === RETAINED_FIXTURE_BOOT_ID) ? record.boot_id : null;
    const build = typeof record.build === 'string' && /^(?:compiled-artifact-inputs-sha256:[a-f0-9]{64}|source:[a-f0-9]{40})$/.test(record.build) ? record.build : null;
    const configRevision = typeof record.boot_config_revision === 'string' && (/^[a-f0-9]{64}$/.test(record.boot_config_revision) || record.boot_config_revision === RETAINED_FIXTURE_CONFIG_REVISION) ? record.boot_config_revision : null;
    const schemaVersion = record.schema_version === 1 ? 1 : null;
    return { boot_id: bootId, build, boot_config_revision: configRevision, schema_version: schemaVersion };
  });
  const valid = !truncated && cohorts.every(identity => identity.boot_id !== null && identity.build !== null && identity.boot_config_revision !== null && identity.schema_version === 1);
  return { cohorts, valid, truncated };
}
/** Aggregate only the fixed synthetic histogram vocabulary; never retain scopes or arbitrary response fields. */
export function fixtureHistogramProfiles(value: Record<string, any>) {
  if (!Array.isArray(value?.series)) return null;
  let unexpectedMetricCount = 0, invalidRowCount = 0;
  const profiles = Object.fromEntries(FIXTURE_HISTOGRAM_METRICS.map(metric => [metric, {
    rows: 0, count: 0, sum: 0, min: null as number | null, max: null as number | null,
    bounds_ms: FIXTURE_DURATION_BOUNDS_MS, buckets: Array(FIXTURE_DURATION_BOUNDS_MS.length + 1).fill(0), valid: true,
  }])) as Record<typeof FIXTURE_HISTOGRAM_METRICS[number], { rows: number; count: number; sum: number; min: number | null; max: number | null; bounds_ms: number[]; buckets: number[]; valid: boolean }>;
  for (const row of value.series) {
    const rowValid = row && typeof row === 'object' && !Array.isArray(row)
      && row.metrics && typeof row.metrics === 'object' && !Array.isArray(row.metrics);
    if (!rowValid) invalidRowCount++;
    const metrics = rowValid ? row.metrics : {};
    unexpectedMetricCount += Object.keys(metrics).filter(metric => !FIXTURE_HISTOGRAM_METRICS.includes(metric as typeof FIXTURE_HISTOGRAM_METRICS[number])).length;
    for (const metric of FIXTURE_HISTOGRAM_METRICS) {
      if (!Object.hasOwn(metrics, metric)) continue;
      const target = profiles[metric]; target.rows++;
      const sample = metrics[metric];
      if (!sample || typeof sample !== 'object' || Array.isArray(sample)) { target.valid = false; continue; }
      const count = counter(sample.count), sum = typeof sample.sum === 'number' && Number.isFinite(sample.sum) ? sample.sum : null;
      const min = typeof sample.min === 'number' && Number.isFinite(sample.min) ? sample.min : null;
      const max = typeof sample.max === 'number' && Number.isFinite(sample.max) ? sample.max : null;
      const bounds = Array.isArray(sample.bounds_ms) ? sample.bounds_ms : null;
      const buckets = Array.isArray(sample.buckets) ? sample.buckets.map(counter) : null;
      if (count === null || sum === null || min === null || max === null || !bounds || JSON.stringify(bounds) !== JSON.stringify(FIXTURE_DURATION_BOUNDS_MS)
        || !buckets || buckets.length !== target.buckets.length || buckets.some((n: number | null) => n === null)) { target.valid = false; continue; }
      const nextCount = target.count + count, nextSum = target.sum + sum;
      const nextBuckets = target.buckets.map((n, index) => n + (buckets[index] ?? 0));
      if (!Number.isSafeInteger(nextCount) || !Number.isFinite(nextSum) || nextBuckets.some(n => !Number.isSafeInteger(n))) { target.valid = false; continue; }
      target.count = nextCount; target.sum = nextSum;
      target.min = target.min === null ? min : Math.min(target.min, min); target.max = target.max === null ? max : Math.max(target.max, max);
      target.buckets = nextBuckets;
    }
  }
  for (const metric of FIXTURE_HISTOGRAM_METRICS) if (profiles[metric].rows === 0) profiles[metric].valid = false;
  return { metrics: profiles, unexpected_metric_count: unexpectedMetricCount, invalid_row_count: invalidRowCount, truncated: false,
    valid: unexpectedMetricCount === 0 && invalidRowCount === 0 && FIXTURE_HISTOGRAM_METRICS.every(metric => profiles[metric].valid) };
}
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
    const histogramProfiles = source === 'history' ? fixtureHistogramProfiles(v) : null;
    const identityCohorts = boundedIdentityCohortProjection(v.identity_cohorts);
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
      identity_cohorts: identityCohorts.cohorts, identity_cohorts_valid: identityCohorts.valid, identity_cohorts_truncated: identityCohorts.truncated,
      histogram_profiles: histogramProfiles, coverage_issues: boundedIssueProjection(v.coverage?.issues),
      limitations: ['filesystem cache is uncontrolled', 'fixture cohort identity differs from the live boot', 'partial evidence summaries are not complete exports; complete cursor traversal is a separate acceptance check'] });
  }
  return rows;
}
