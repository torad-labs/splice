import { expect, test } from 'bun:test';
import { boundedIdentityCohortProjection, boundedIssueProjection, fixtureHistogramProfiles } from './history';

const metrics = ['arrival_to_terminal_ms', 'arrival_to_first_meaningful_content_ms', 'queue_wait_ms'] as const;
const bounds = [0, 1, 2, 5, 10, 20, 50, 100, 200, 250, 500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000];
const histogram = (count: number) => ({ count, sum: count * 20, min: 20, max: 20, bounds_ms: bounds, buckets: bounds.map((_, i) => i === 5 ? count : 0).concat(0) });

test('retained history projection folds only fixed histograms and preserves exact bounded bucket totals', () => {
  const result = fixtureHistogramProfiles({ series: [
    { scope: { model: 'private-scope-label' }, metrics: Object.fromEntries(metrics.map(key => [key, histogram(1)])) },
    { scope: { model: 'another-private-label' }, metrics: Object.fromEntries(metrics.map(key => [key, histogram(2)])) },
  ] });
  expect(Object.keys(result!.metrics).sort()).toEqual(metrics.slice().sort());
  expect(result).toMatchObject({ valid: true, truncated: false, unexpected_metric_count: 0 });
  for (const key of metrics) expect(result!.metrics[key]).toEqual({
    rows: 2, count: 3, sum: 60, min: 20, max: 20, bounds_ms: bounds,
    buckets: bounds.map((_, i) => i === 5 ? 3 : 0).concat(0), valid: true,
  });
  expect(JSON.stringify(result)).not.toContain('private-scope-label');
  const unexpected = fixtureHistogramProfiles({ series: [{ metrics: { ...Object.fromEntries(metrics.map(key => [key, histogram(1)])), unknown_metric: histogram(900) } }] });
  expect(unexpected).toMatchObject({ valid: false, unexpected_metric_count: 1, truncated: false });
  const malformedRow = fixtureHistogramProfiles({ series: [
    { metrics: Object.fromEntries(metrics.map(key => [key, histogram(1)])) },
    { scope: { model: 'unprojectable-private-row' } },
  ] });
  expect(malformedRow).toMatchObject({ valid: false, invalid_row_count: 1, truncated: false });
  expect(JSON.stringify(malformedRow)).not.toContain('unprojectable-private-row');
  const malformedKnownMetric = fixtureHistogramProfiles({ series: [
    { metrics: Object.fromEntries(metrics.map(key => [key, histogram(2)])) },
    { metrics: { arrival_to_terminal_ms: 'malformed-private-value' } },
    { metrics: { arrival_to_first_meaningful_content_ms: null } },
  ] });
  expect(malformedKnownMetric?.metrics.arrival_to_terminal_ms).toMatchObject({ rows: 2, count: 2, valid: false });
  expect(malformedKnownMetric?.metrics.arrival_to_first_meaningful_content_ms).toMatchObject({ rows: 2, count: 2, valid: false });
  expect(malformedKnownMetric?.valid).toBe(false);
  expect(JSON.stringify(malformedKnownMetric)).not.toContain('malformed-private-value');
});

test('malformed histogram wire shapes remain invalid and issue projection drops unsafe text', () => {
  const profile = fixtureHistogramProfiles({ series: [{ metrics: { arrival_to_terminal_ms: { ...histogram(1), bounds_ms: [0, 1], buckets: [1] } } }] });
  expect(profile?.metrics.arrival_to_terminal_ms.valid).toBe(false);
  expect(profile?.metrics.queue_wait_ms.valid).toBe(false);
  expect(profile?.valid).toBe(false);

  const issues = boundedIssueProjection([
    { code: 'boot_changed', source: 'aggregate_history', count: 1 },
    { code: 'credential=/secret', source: '/tmp/private', count: 2 },
  ]);
  expect(issues).toEqual({ issues: [
    { code: 'boot_changed', source: 'aggregate_history', count: 1 },
    { code: null, source: null, count: 2 },
  ], valid: false, truncated: false });
  expect(JSON.stringify(issues)).not.toContain('/tmp/private');
  expect(boundedIssueProjection(Array(70).fill({ code: 'coverage_gap', source: 'aggregate_history', count: 1 }))).toMatchObject({ valid: false, truncated: true, issues: { length: 64 } });
});

test('identity cohort projection keeps only validated boot/build/config fields within its fixed bound', () => {
  const cohort = { boot_id: '00000000-0000-4000-8000-000000000001', build: `compiled-artifact-inputs-sha256:${'a'.repeat(64)}`, boot_config_revision: 'b'.repeat(64), schema_version: 1, version: 'private-version-label', effective_config_revision: '/private/path' };
  expect(boundedIdentityCohortProjection([cohort])).toEqual({ cohorts: [{ boot_id: cohort.boot_id, build: cohort.build, boot_config_revision: cohort.boot_config_revision, schema_version: 1 }], valid: true, truncated: false });
  const seed = { boot_id: 'benchmark-retained-fixture', build: `source:${'c'.repeat(40)}`, boot_config_revision: 'fixture-config-v1', schema_version: 1 };
  expect(boundedIdentityCohortProjection([seed])).toEqual({ cohorts: [seed], valid: true, truncated: false });
  const malformed = boundedIdentityCohortProjection([{ ...cohort, boot_id: 'unsafe-private-boot', build: 'private-build-path', boot_config_revision: 'unknown' }]);
  expect(malformed).toEqual({ cohorts: [{ boot_id: null, build: null, boot_config_revision: null, schema_version: 1 }], valid: false, truncated: false });
  expect(JSON.stringify(malformed)).not.toContain('unsafe-private-boot');
  expect(boundedIdentityCohortProjection(Array(70).fill(cohort))).toMatchObject({ valid: false, truncated: true, cohorts: { length: 64 } });
  expect(boundedIdentityCohortProjection(null)).toEqual({ cohorts: [], valid: false, truncated: false });
});
