// User-visible contract checks. The orchestrator runs this with integrated validation.
import { test, expect } from '@playwright/test';
import { response, summary, settings } from './fixtures.mjs';
async function open(page, state = 'partial') {
  await page.route('**/api/**', async (route) => {
    const data = response(route.request().url(), state);
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
  });
  await page.goto('/preview.html');
  await expect(page.locator('.pc-coverage')).toContainText('18,000,000');
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
  await expect(page.getByRole('table', { name: 'Latency distributions', exact: true })).toContainText('p99');
  await page.getByText('Distribution', { exact: true }).first().click();
  await expect(page.locator('.pc-content')).toContainText('upper inclusive bucket bound');
  await expect(page.locator('.pc-content')).toContainText('Overflow');
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
  await expect(page.getByRole('link', { name: 'fixture-turn-2' })).toBeVisible();
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
    } else await route.fulfill({ json: settings });
  });
  await page.getByRole('button', { name: 'Save objectives and rules' }).click();
  await expect(page.locator('.pc-message')).toContainText('canonical-new-revision');
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
