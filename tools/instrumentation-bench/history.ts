import { copyFile, lstat, mkdir, readFile, realpath } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { control, counter, type OwnedDesk } from './control';
import { hash } from './manifest';
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
export async function queryHistory(desk: OwnedDesk, until: number, repetition: number) {
  const rows: object[] = [];
  for (const window of [24 * 3600000, 7 * 86400000]) for (const cache of ['first_read', 'repeat_read']) {
    const from = until - window;
    const before = await readSourceCosts(desk);
    const result = await control(desk, `/api/perf/series?contract=1&head=bench&source=history&from=${from}&until=${until}`, { limit: 4 * 1024 ** 2, timeout_ms: 30000 });
    const v = result.value;
    const work_cost = sourceCostDelta(before, await readSourceCosts(desk));
    if (v.query?.from_ms !== from || v.query?.until_ms !== until || v.query?.source !== 'history') throw new Error('history_query_bounds_changed');
    const series = Array.isArray(v.series) ? v.series : [];
    const histogramCount = series.reduce((n: number, row: any) => n + Object.values(row.metrics ?? {}).reduce<number>((s, value: any) => s + (counter(value.count) ?? 0), 0), 0);
    rows.push({ repetition, window_ms: window, cache, status: typeof v.status === 'string' && ['ok', 'partial', 'busy', 'error'].includes(v.status) ? v.status : 'unknown', from_ms: from, until_ms: until, elapsed_ms: result.elapsed_ms, bytes: result.bytes, populated: histogramCount > 0, histogram_count: histogramCount, expected_histogram_count: 48 * window / 60000, count_matches_fixture: histogramCount === 48 * window / 60000, coverage: numericProjection(v.coverage), resolution_ms: counter(v.resolution_ms), settled_until_ms: counter(v.settled_until_ms), work_cost, identity_cohort_count: Array.isArray(v.identity_cohorts) ? v.identity_cohorts.length : null, limitations: ['first read is process/query-cache cold only on a fresh desk; filesystem cache is uncontrolled', 'fixture cohort identity differs from the live boot and must remain disclosed'] });
  }
  return rows;
}
