// NEW: V4-444 — diagnostic refusals and upgrade-run continuity without executing an upgrade.
import { expect, test } from '@playwright/test';
import type { DoctorPayload, UpgradeRun } from '../src/types/doctor';
import type { LogsPayload } from '../src/types/logs';
import { open, read } from './support';
import { STACK } from './stack';

test('Settings reads the isolated whole doctor report and offers the API-key remedy', async ({ page }) => {
  const faults = await open(page, 'settings/health');
  const report = await read<DoctorPayload>(page, '/api/doctor');
  expect(report.schema_version).toBe(1);
  const health = page.getByRole('region', { name: 'Health', exact: true });
  for (const disclosure of await health.locator('details.members').all()) {
    await disclosure.locator('summary').click();
  }
  for (const check of report.checks.filter((row) => row.status === 'warn' || row.status === 'fail')) {
    const detail = check.detail.trim();
    if (detail !== '') await expect(health).toContainText(detail);
  }
  await expect(health.getByRole('button', { name: 'Check again', exact: true })).toBeVisible();
  await expect(health).toContainText('CONSOLE_E2E_NO_SUCH_KEY is not set');
  const keyCheck = health.locator('.row').filter({ hasText: 'CONSOLE_E2E_NO_SUCH_KEY is not set' });
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await keyCheck.getByRole('button', { name: 'Copy the command', exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe('splice key set CONSOLE_E2E_NO_SUCH_KEY');
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});

test('Needs you opens the intended plan and the full Health report from its doctor item', async ({ page }) => {
  const faults = await open(page, 'needs-you');
  const wrapper = page.getByRole('listitem').filter({ hasText: 'installation/wrapper' }).first();
  await expect(wrapper).toContainText('splice install --all');
  await wrapper.getByRole('link', { name: 'Open', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Health', exact: true })).toBeVisible();
  await expect(page.locator('.row').filter({ hasText: 'installation/wrapper' })).toBeVisible();
  await page.goBack();
  const key = page.getByRole('listitem').filter({ hasText: 'CONSOLE_E2E_NO_SUCH_KEY' }).first();
  await key.getByRole('link', { name: 'Open', exact: true }).click();
  await expect(page).toHaveURL(new RegExp('#/fleet/' + STACK.keyHead + '$'));
  await expect(page.getByRole('heading', { name: STACK.keyHead, level: 1, exact: true })).toBeVisible();
  expect(faults.pageErrors).toEqual([]);
});

test('the Log tab renders the isolated daemon tail and its actual source path', async ({ page }) => {
  const answer = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/logs/' + STACK.oauthHead);
  const faults = await open(page, 'fleet/' + STACK.oauthHead + '?tab=log');
  const response = await answer;
  expect(response.ok()).toBe(true);
  const tail = await response.json() as LogsPayload;
  expect(tail.key).toBe(STACK.oauthHead);
  await expect(page.getByRole('main')).toContainText('Read from ' + tail.path);
  const log = page.getByRole('log', { name: 'Filter the log', exact: true });
  if (tail.lines.length === 0) await expect(log).toContainText('This head has written nothing yet.');
  else for (const line of tail.lines) await expect(log).toContainText(line);
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});

test('a refused doctor fix prints its sentence and reruns the remaining check', async ({ page }) => {
  const refusal = 'Synthetic file was not changed because its owner refused the write.';
  let reports = 0;
  let fixes = 0;
  let report: DoctorPayload;
  await page.route('**/api/doctor', async (route) => {
    reports += 1;
    const response = await route.fetch();
    const body = await response.json() as DoctorPayload;
    body.checks = [{
      id: 'synthetic/file', status: 'fail', detail: 'Synthetic file still needs repair.',
      fix: 'splice doctor --fix', fix_id: 'synthetic-file', fix_kind: 'command',
    }];
    report = body;
    await route.fulfill({ response, json: body });
  });
  await page.route('**/api/doctor/fix/synthetic-file', (route) => {
    fixes += 1;
    return route.fulfill({ status: 409, json: { fix: 'synthetic-file', error: refusal, report } });
  });
  await open(page, 'settings/health');
  const health = page.getByRole('region', { name: 'Health', exact: true });
  const before = reports;
  await health.getByRole('button', { name: 'Fix it', exact: true }).click();
  await expect(health.getByRole('alert')).toContainText(refusal);
  await health.getByRole('button', { name: 'Check again', exact: true }).click();
  await expect.poll(() => reports).toBeGreaterThan(before);
  await expect(health).toContainText('Synthetic file still needs repair.');
  expect(fixes).toBe(1);
  await page.unrouteAll({ behavior: 'wait' });
});

test('the real wrapper fix refuses inside the isolated home and returns the doctor rerun', async ({ page }) => {
  const faults = await open(page, 'settings/health');
  const report = await read<DoctorPayload>(page, '/api/doctor');
  const wrapper = report.checks.find((check) => check.fix_id === 'install_all');
  expect(wrapper).toBeDefined();
  const row = page.locator('.row').filter({ hasText: 'installation/wrapper' });
  const answer = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/doctor/fix/install_all' && response.request().method() === 'POST');
  await row.getByRole('button', { name: 'Fix it', exact: true }).click();
  const response = await answer;
  expect(response.status()).toBe(409);
  const body = await response.json() as { error: string; report: DoctorPayload };
  expect(body.error).toContain('launch shim not found');
  expect(body.report.checks.length).toBeGreaterThan(0);
  await expect(row.getByRole('alert')).toContainText(body.error);
  expect(faults.pageErrors).toEqual([]);
});

test('masked remedies remain noncopyable in Health and Needs you, while own-page links update their target', async ({ page }) => {
  await page.route('**/api/doctor', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as DoctorPayload;
    body.checks.push({ id: 'synthetic/masked', status: 'warn', detail: 'Synthetic masked remedy needs a terminal.', fix: 'export SYNTHETIC_KEY=<redacted>' });
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'settings/health');
  const row = page.locator('.row').filter({ hasText: 'synthetic/masked' });
  await expect(row).toContainText('Run splice doctor in a terminal to see it.');
  await expect(row.getByRole('button', { name: 'Copy the command', exact: true })).toHaveCount(0);
  await page.getByRole('navigation', { name: 'Pages', exact: true }).getByRole('link', { name: 'Needs you', exact: true }).click();
  const item = page.getByRole('listitem', { name: 'Doctor: synthetic/masked', exact: true });
  await expect(item).toContainText('Run splice doctor in a terminal to see it.');
  await expect(item.getByRole('button', { name: 'Copy the command', exact: true })).toHaveCount(0);
  await page.evaluate((head) => { location.hash = '#/fleet/' + head; }, STACK.keyHead);
  await expect(page.getByRole('heading', { name: STACK.keyHead, level: 1, exact: true })).toBeVisible();
  await page.evaluate((head) => { location.hash = '#/fleet/' + head; }, STACK.oauthHead);
  await expect(page.getByRole('heading', { name: STACK.oauthHead, level: 1, exact: true })).toBeVisible();
  await page.getByRole('main').getByRole('link', { name: 'Fleet', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Fleet', level: 1, exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: STACK.oauthHead, level: 1, exact: true })).toHaveCount(0);
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

test('an unstarted upgrade has no run or run alert and sends no upgrade command', async ({ page }) => {
  let starts = 0;
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/upgrade' && request.method() === 'POST') starts += 1;
  });
  await open(page, 'settings/health');
  const health = page.getByRole('region', { name: 'Health', exact: true });
  await expect(health.getByRole('button', { name: 'Upgrade', exact: true })).toBeVisible();
  await expect(health.getByRole('region', { name: 'The upgrade', exact: true })).toHaveCount(0);
  expect(starts).toBe(0);
});

test('an upgrade asks for the selected release and retains its run through an unavailable read to terminal exit', async ({ page }) => {
  const run: UpgradeRun = {
    id: 'synthetic-upgrade-run', args: ['upgrade', '--to', 'v0.4.1'], state: 'running',
    started_at_epoch_millis: Date.now(), exit_code: null, output: ['Synthetic upgrade began'],
  };
  const starts: unknown[] = [];
  let reads = 0;
  await page.route('**/api/upgrade', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    starts.push(route.request().postDataJSON());
    return route.fulfill({ status: 202, json: { run } });
  });
  await page.route('**/api/upgrade/run', (route) => {
    if (starts.length === 0) return route.fulfill({ json: { run: null } });
    reads += 1;
    if (reads === 1) return route.fulfill({ json: { run: { ...run, output: [...run.output, 'fetching synthetic.jar'] } } });
    if (reads === 2) return route.abort('connectionrefused');
    return route.fulfill({ json: { run: { ...run, state: 'succeeded', exit_code: 0, output: [...run.output, 'fetching synthetic.jar', 'restarted'] } } });
  });
  await open(page, 'settings/health');
  const health = page.getByRole('region', { name: 'Health', exact: true });
  await health.getByLabel('Release', { exact: true }).fill('v0.4.1');
  await health.getByRole('button', { name: 'Upgrade', exact: true }).click();
  expect(starts).toEqual([]);
  const confirmation = page.getByRole('dialog');
  await confirmation.getByRole('button', { name: 'Upgrade', exact: true }).click();
  await expect.poll(() => starts).toEqual([{ to: 'v0.4.1' }]);
  const shown = health.getByRole('region', { name: 'The upgrade', exact: true });
  await expect(shown).toContainText('splice upgrade --to v0.4.1');
  await expect(shown.getByRole('log', { name: 'What it printed', exact: true })).toContainText('fetching synthetic.jar');
  await expect(shown.getByRole('status')).toContainText('restart', { timeout: 10_000 });
  await expect(shown).toContainText('Finished', { timeout: 10_000 });
  await expect(shown).toContainText('Exit 0');
  await expect(shown.getByRole('status')).toHaveCount(0);
  await expect(shown.getByRole('log', { name: 'What it printed', exact: true })).toContainText('restarted');
});
