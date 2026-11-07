// User-visible contract checks. The orchestrator runs this with integrated validation.
import { test, expect } from '@playwright/test';
import { response, summary, settings } from './fixtures.mjs';
async function open(page, state = 'partial') {
  await page.route('**/api/**', async (route) => {
    const data = response(route.request().url(), state);
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
  });
  await page.goto('/preview.html');
  await expect(page.locator('.pc-coverage')).toContainText('5 h selected');
}
test('five-hour project cohort is shared and retained by request links', async ({ page }) => {
  await open(page);
  const link = page.getByRole('link', { name: /Open requests/ });
  const url = new URL(await link.getAttribute('href'), 'http://fixture');
  expect(url.searchParams.get('project')).toBe('splice');
  expect(+url.searchParams.get('until') - +url.searchParams.get('from')).toBe(18000000);
  await page.getByRole('button', { name: 'All filters', exact: true }).click();
  await page.locator('[name="transport"]').fill('http');
  await page.locator('[name="streaming"]').selectOption('0');
  await page.locator('[name="head"]').fill('codex');
  await page.getByRole('button', { name: 'Apply cohort' }).click();
  await expect(link).toHaveAttribute('href', /transport=http/);
  await expect(link).toHaveAttribute('href', /streaming=0/);
  await expect(link).toHaveAttribute('href', /contract=1/);
});
test('partial goodput keeps known outcomes while exact metrics remain unknown', async ({ page }) => {
  await open(page);
  await expect(page.locator('.pc-content')).toContainText('119');
  await expect(page.locator('.pc-content')).toContainText('123');
  await expect(page.locator('.pc-content')).toContainText('known good rate is lower bound');
  await expect(page.locator('.pc-content')).toContainText('—');
  await page.getByRole('button', { name: 'Latency', exact: true }).click();
  await expect(page.locator('.pc-ranges')).toBeVisible();
  const distribution = page.locator('.pc-distributions > details').first();
  await distribution.locator('summary').click();
  await expect(distribution.getByRole('table', { name: 'Returned percentile bounds' })).toContainText('p99');
  await expect(distribution).toContainText('upper inclusive bucket bound');
  await expect(distribution).toContainText('Overflow');
});
test('resource scopes and nonadditive evidence stay explicit', async ({ page }) => {
  await open(page);
  await page.getByRole('button', { name: 'History', exact: true }).click();
  await expect(page.locator('.pc-content')).toContainText('never a process total');
  await expect(page.locator('.pc-content')).toContainText('Covered scope-ms');
  await page.locator('[name="historyMetric"]').selectOption('gate_queued');
  await expect(page.locator('.pc-history')).toHaveAttribute('aria-label', /gate queued/);
  await page.getByRole('button', { name: 'Requests & traces', exact: true }).click();
  await expect(page.locator('.pc-content')).toContainText('not additive');
  await expect(page.locator('.pc-content')).toContainText('arrival to first meaningful content ms: 3,000');
  await expect(page.locator('.pc-content')).toContainText('client write flush ms: 2');
  await page.getByRole('button', { name: 'Next records' }).click();
  const nextTurn = page.getByRole('link', { name: 'Open request 1', exact: true });
  await expect(nextTurn).toBeVisible();
  await expect.poll(async () => decodeURIComponent(new URL(await nextTurn.getAttribute('href'), 'http://fixture').hash)).toBe('#codex:fixture-turn-2');
});
test('snapshots are bounded, content-free and visibly insufficient for matched comparison', async ({ page }) => {
  await open(page);
  await page.getByRole('button', { name: 'Baselines', exact: true }).click();
  await page.locator('[name="baselineName"]').fill('<img src=x onerror=alert(1)>');
  await page.getByRole('button', { name: 'Save locally' }).click();
  await expect(page.locator('.pc-content')).toContainText('Insufficient evidence');
  await expect(page.locator('.pc-content')).toContainText('input size, output size, cache partition');
  await expect(page.locator('.pc-content')).toContainText('Saved and current observations');
  expect(await page.locator('.pc-content img').count()).toBe(0);
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Export JSON' }).click();
  expect((await download).suggestedFilename()).toBe('splice-performance-baseline.json');
  const baseline = { format: 'splice-performance-baseline', version: 1, name: 'Imported', summary: { ...summary('/?objective=interactive'), prompt: 'SECRET-FIXTURE', headers: { Authorization: 'SECRET-FIXTURE' } } };
  await page.locator('[data-pc-import]').setInputFiles({ name: 'baseline.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(baseline)) });
  await expect(page.locator('.pc-message')).toContainText('imported');
  const stored = await page.evaluate(() => localStorage.getItem('splice-performance-baselines-v1'));
  expect(stored).not.toContain('SECRET-FIXTURE');
  await page.getByRole('button', { name: 'Apply saved cohort' }).click();
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
});
test('profile start, stop, status and authenticated artifact controls show returned states', async ({ page }) => {
  await open(page);
  await page.getByRole('button', { name: 'Profiles', exact: true }).click();
  await expect(page.locator('.pc-content')).toContainText('jfr wall sampling unavailable');
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download artifact' }).click();
  await download;
  await page.route('**/api/perf/profiles', async (route) => {
    const req = route.request();
    if (req.method() === 'POST') expect(req.postDataJSON()).toEqual({ profiler: 'jfr', mode: 'cpu', durationSeconds: 60 });
    await route.fulfill({ json: { status: 'running', profile_id: 'fixture-profile', artifact_available: false } });
  });
  await page.getByRole('button', { name: 'Start capture' }).click();
  await expect(page.getByRole('button', { name: 'Stop capture' })).toBeVisible();
  await page.route('**/api/perf/profiles/fixture-profile/stop', (route) => route.fulfill({ json: { status: 'aborted', profile_id: 'fixture-profile', artifact_available: false, issues: ['explicit_stop'] } }));
  await page.getByRole('button', { name: 'Stop capture' }).click();
  await expect(page.locator('.pc-content')).toContainText('aborted');
  await page.getByRole('button', { name: 'Refresh status' }).click();
});
test('objective draft publishes whole settings, shows canonical revision, and alerts remain active on gaps', async ({ page }) => {
  await open(page);
  await page.getByRole('button', { name: 'Objectives', exact: true }).click();
  await expect(page.locator('[name="settings"]')).toBeHidden();
  await page.getByText('Add objective', { exact: true }).click();
  const form = page.locator('[data-pc-form="add-objective"]');
  await form.locator('[name="id"]').fill('new-objective');
  await form.locator('[name="first_content_ms"]').fill('3000');
  await form.getByRole('button', { name: 'Add to draft' }).click();
  await expect(page.locator('[name="settings"]')).toHaveValue(/new-objective/);
  await page.route('**/api/perf/objectives', async (route) => {
    if (route.request().method() === 'PUT') {
      expect(route.request().postDataJSON().rules).toEqual([]);
      await route.fulfill({ json: { status: 'ok', revision: 'canonical-new-revision' } });
    } else await route.fulfill({ json: { ...settings, revision: 'canonical-new-revision' } });
  });
  await page.getByRole('button', { name: 'Save objectives and rules' }).click();
  await expect(page.locator('.pc-message')).toContainText('Settings saved.');
  await page.getByText('Settings revision', { exact: true }).click();
  await expect(page.locator('.pc-content')).toContainText('canonical-new-revision');
  await page.getByText('Edit settings JSON', { exact: true }).click();
  await page.getByRole('button', { name: 'Discard edits and reload' }).click();
  await page.getByRole('button', { name: 'Alerts', exact: true }).click();
  await expect(page.locator('.pc-content')).toContainText('insufficient data · active');
  await expect(page.getByRole('link', { name: 'Inspect interval' })).toHaveAttribute('href', /from=/);
});
for (const state of ['empty', 'busy', 'unsupported', 'error', 'stale']) test(`distinct ${state} state`, async ({ page }) => {
  await open(page, state);
  await expect(page.locator('.pc-coverage')).toContainText(state === 'stale' ? 'Stale' : state[0].toUpperCase() + state.slice(1));
});
test('offline and loading never appear as zero traffic', async ({ page }) => {
  await page.route('**/api/perf/summary**', (route) => route.abort());
  await page.goto('/preview.html');
  await expect(page.locator('.pc-coverage')).toContainText('Offline');
  await expect(page.locator('.pc-content')).toContainText('Measurements have not been returned');
});

test('completed window is explicit, preserves exact duration and uses the certified boundary', async ({ page }) => {
  const from = 1793890000000, until = from + 18000000, reads = [];
  await page.route('**/api/**', async (route) => {
    const url = route.request().url();
    if (new URL(url).pathname === '/api/perf/summary') reads.push(new URL(url));
    await route.fulfill({ json: response(url) });
  });
  await page.goto(`/preview.html?from=${from}&until=${until}&transport=http&streaming=0`);
  await expect(page.locator('.pc-coverage')).toContainText('5 h selected');
  const link = page.getByRole('link', { name: /Open requests/ });
  expect(new URL(await link.getAttribute('href'), 'http://fixture').searchParams.get('until')).toBe(String(until));
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect.poll(() => reads.length).toBe(2);
  expect(reads[1].searchParams.get('until')).toBe(String(until));
  await page.getByRole('button', { name: 'Use completed window' }).click();
  await expect(link).toHaveAttribute('href', new RegExp(`until=${until - 60000}`));
  const chosen = new URL(await link.getAttribute('href'), 'http://fixture');
  expect(+chosen.searchParams.get('until') - +chosen.searchParams.get('from')).toBe(18000000);
  expect(chosen.searchParams.get('from')).toBe(String(from - 60000));
  expect(chosen.searchParams.get('transport')).toBe('http');
  expect(chosen.searchParams.get('streaming')).toBe('0');
  await page.getByRole('button', { name: 'All filters', exact: true }).click();
  await expect(page.locator('[name="from"]')).toHaveValue(String(from - 60000));
  await expect(page.locator('[name="until"]')).toHaveValue(String(until - 60000));
});

test('unknown recording boundary disables completed-window selection', async ({ page }) => {
  await open(page, 'busy');
  await expect(page.getByRole('button', { name: 'Use completed window' })).toBeDisabled();
  await page.getByText('Coverage and recording boundary', { exact: true }).click();
  await expect(page.getByText('No certified recording boundary available for this duration.')).toBeVisible();
});

test('measured workload match allows known build and settings changes but rejects a changed joint distribution', async ({ page }) => {
  await open(page, 'ok');
  await page.getByRole('button', { name: 'Baselines', exact: true }).click();
  await page.getByRole('button', { name: 'Save locally' }).click();
  await expect(page.getByText('Measured workload matches', { exact: true })).toBeVisible();
  const stored = JSON.parse(await page.evaluate(() => localStorage.getItem('splice-performance-baselines-v1')))[0];
  expect(stored.version).toBe(2);
  expect(stored.summary.comparison.workload.partitions[0].scope.outcome).toBe('ok');
  expect(stored.summary.comparison.workload.partitions[0].cache_write_1h_tokens).toBe(0);
  expect(stored.summary.configuration.comparison_scope).toBe('effective_knob_policy');
  let changedWorkload = false;
  await page.route('**/api/perf/summary?**', (route) => {
    const data = summary(route.request().url(), 'ok');
    data.identity_cohorts[0].build = 'fixture-build-b';
    data.comparison.provenance.builds = ['fixture-build-b'];
    data.configuration.intervals[0].revision = 'effective-config-b';
    if (changedWorkload) {
      // The returned rate and histogram means stay the same; the actual joint population differs.
      data.comparison.workload.distribution_id = 'b'.repeat(64);
      data.comparison.workload.partitions[0].scope.outcome = 'failed';
    }
    return route.fulfill({ json: data });
  });
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await page.getByText('Comparison conditions and exact values', { exact: true }).click();
  await expect(page.locator('.pc-content')).toContainText('Changed variables: build, effective settings');
  await expect(page.getByText('Measured workload matches', { exact: true })).toBeVisible();
  await expect(page.locator('.pc-content')).toContainText('Provider topology and remote conditions are unverified');
  changedWorkload = true;
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(page.getByText('Comparison conditions and exact values', { exact: true })).toBeVisible();
  await page.getByText('Comparison conditions and exact values', { exact: true }).click();
  await expect(page.getByText('Incompatible', { exact: true })).toBeVisible();
  await expect(page.locator('.pc-content')).toContainText('joint workload distribution');
  await expect(page.getByRole('table', { name: /Saved and current observations/ })).toBeVisible();
});

test('mixed provenance and old imports remain insufficient while v2 drops unapproved fields', async ({ page }) => {
  await open(page, 'ok');
  await page.getByRole('button', { name: 'Baselines', exact: true }).click();
  const data = summary('/?objective=interactive&project=splice&local=0', 'ok');
  data.comparison.workload.partitions[0].prompt = 'SECRET-FIXTURE';
  data.comparison.provenance.authorization = 'SECRET-FIXTURE';
  const upload = async (version) => page.locator('[data-pc-import]').setInputFiles({ name: 'baseline.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ format: 'splice-performance-baseline', version, name: 'Old or mixed', summary: data })) });
  await upload(1);
  await expect(page.getByText('Insufficient evidence', { exact: true })).toBeVisible();
  data.comparison.provenance.builds.push('fixture-build-b');
  await upload(2);
  await page.getByText('Comparison conditions and exact values', { exact: true }).click();
  await expect(page.locator('.pc-content')).toContainText('Unknown or mixed: build');
  const stored = await page.evaluate(() => localStorage.getItem('splice-performance-baselines-v1'));
  expect(stored).not.toContain('SECRET-FIXTURE');
  expect(JSON.parse(stored)[0].summary.comparison.workload.partitions[0].count).toBe(138);
  data.comparison.provenance.builds = ['fixture-build-a'];
  data.comparison.workload.partitions = Array(257).fill(data.comparison.workload.partitions[0]);
  await upload(2);
  await page.getByText('Comparison conditions and exact values', { exact: true }).click();
  await expect(page.locator('.pc-content')).toContainText('complete saved evidence');
  const bounded = JSON.parse(await page.evaluate(() => localStorage.getItem('splice-performance-baselines-v1')))[0];
  expect(bounded.summary.comparison.workload.partitions).toHaveLength(256);
  expect(bounded.summary.comparison.projection_truncated).toBe(true);
});

test('request links use real turn identity and preserve the selected query', async ({ page }) => {
  await open(page);
  await page.getByRole('button', { name: 'Requests & traces', exact: true }).click();
  const turn = page.getByRole('link', { name: 'Open recorded turn', exact: true });
  const url = new URL(await turn.getAttribute('href'), 'http://fixture');
  expect(decodeURIComponent(url.hash)).toBe('#codex:actual-turn-fixture');
  expect(url.searchParams.get('project')).toBe('splice');
  expect(url.searchParams.get('local')).toBe('0');
  expect(+url.searchParams.get('until') - +url.searchParams.get('from')).toBe(18000000);
  await page.route('**/api/perf/diagnose?**', (route) => {
    const data = response(route.request().url());
    const request = data.timeline.requests[0]; request.turn_id = null;
    request.events.forEach((e) => { e.turn_id = null; });
    return route.fulfill({ json: data });
  });
  await page.getByRole('button', { name: 'Refresh', exact: true }).click();
  await expect(turn).toHaveCount(0);
  expect(await page.locator('.pc-content a[href*="request-fixture"]').count()).toBe(0);
});

test('account alias gap explains cached names without polling accounts or auth', async ({ page }) => {
  const requested = [];
  await page.route('**/api/**', (route) => {
    const url = new URL(route.request().url()); requested.push(url.pathname);
    const data = response(url.href);
    if (url.pathname === '/api/perf/summary') data.coverage.issues.push({ code: 'account_alias_mapping_unavailable', source: 'accounts', count: 1 });
    return route.fulfill({ json: data });
  });
  await page.goto('/preview.html?account=fixture-name');
  await expect(page.getByText('Account names unavailable', { exact: true })).toBeVisible();
  await page.getByText('Coverage and recording boundary', { exact: true }).click();
  await expect(page.getByText(/Exact recorded account IDs still work/)).toBeVisible();
  await expect(page.getByText(/up to 60 seconds/)).toBeVisible();
  expect(requested.filter((p) => /\/api\/(accounts|auth|quota)/.test(p))).toEqual([]);
});
