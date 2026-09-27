// The complete limit-to-resume journey in a normal browser against the isolated console stack.
// Provider readings and historical metadata are injected at its API boundary; the browser still
// navigates the actual pages and copies the one command the resume route returns.
import { expect, test } from '@playwright/test';
import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { AccountWire } from '../src/entities/account';
import { STACK } from './stack';

const SESSION = '3f2a9c1e-0000-4000-8000-000000000009';
const NOW = Date.now() + 60 * 60 * 1000;
const account = (label: string, used: number): AccountWire => ({
  kind: 'chatgpt-oauth', label, single_login: false, credential_path: null, plan: 'plus',
  primary: label === 'near', selected: label === 'near', available: true,
  pinned: false, next_target: label === 'spare', credential_present: true,
  auth_excluded_until_epoch_millis: null, auth_exclusion_reason: null,
  heads: [label === 'spare' ? STACK.soloHead : STACK.oauthHead],
  five_hour_used_percent: used,
  five_hour_reset_epoch_seconds: Math.floor(NOW / 1000),
  five_hour_window_seconds: 18_000,
  seven_day_used_percent: null,
  seven_day_reset_epoch_seconds: null,
  seven_day_window_seconds: null,
  observed_at_epoch_seconds: Math.floor(Date.now() / 1000),
});

const history = {
  sessions: [{
    pid: null, session_id: SESSION, name: 'Atlas parser', kind: 'interactive', version: null,
    cwd: '/work/atlas', repo: { root: '/work/atlas' }, status: null, status_updated_at: null,
    started_at: NOW - 3_600_000, updated_at: NOW, address: null, head: STACK.oauthHead,
    availability: 'gone', source: 'history+transcript', account: null,
  }],
  next: null, errors: [], skipped: {},
};

test('the live daemon joins an isolated finished history row to its primary transcript', async ({ request }) => {
  const base = process.env.CONSOLE_E2E_BASE;
  const key = process.env.CONSOLE_E2E_KEY;
  const root = process.env.CONSOLE_E2E_TRANSCRIPT_ROOT;
  if (!base || !key || !root) throw new Error('the isolated console stack did not start');
  const id = '3f2a9c1e-0000-4000-8000-000000000010';
  const project = join(root, 'projects', '-work-atlas');
  mkdirSync(project, { recursive: true });
  writeFileSync(join(project, `${id}.jsonl`), `${JSON.stringify({ type: 'custom-title', customTitle: 'Atlas history test' })}\n`);
  appendFileSync(join(root, 'history.jsonl'), `${JSON.stringify({ sessionId: id, display: 'Finished work', project: '/work/atlas', timestamp: NOW })}\n`);
  await expect.poll(async () => {
    const response = await request.get(`${base}/api/sessions/history?query=Atlas%20history%20test`, {
      headers: { Authorization: `Bearer ${key}` },
    });
    if (!response.ok()) return null;
    const body = await response.json() as { sessions?: Array<{ session_id: string; name: string; source: string; head: string }> };
    return body.sessions?.find((row) => row.session_id === id) ?? null;
  }, { timeout: 20_000, intervals: [1_000] }).toMatchObject({
    session_id: id, name: 'Atlas history test', source: 'history+transcript', head: STACK.oauthHead,
  });
});

test('a limit notice leads through a plan with room to a copyable resume command', async ({ page }) => {
  const base = process.env.CONSOLE_E2E_BASE;
  const key = process.env.CONSOLE_E2E_KEY;
  if (!base || !key) throw new Error('the isolated console stack did not start');
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', key]);
  let accountReads = 0;
  await page.route('**/api/accounts*', (route) => {
    accountReads++;
    return route.fulfill({ json: { accounts: [account('near', 99), account('spare', 20)] } });
  });
  await page.route('**/api/sessions', (route) => route.fulfill({ json: { note: 'Live registry', sessions: [] } }));
  await page.route('**/api/sessions/edges', (route) => route.fulfill({ json: { sessions: {} } }));
  await page.route((url) => url.pathname === '/api/sessions/history', (route) => route.fulfill({ json: history }));
  await page.route((url) => url.pathname === `/api/sessions/${SESSION}/transcript`, (route) => route.fulfill({
    json: { session_id: SESSION, path: '/work/atlas/session.jsonl', messages: [], next: null, skipped_records: {} },
  }));
  await page.route((url) => url.pathname === `/api/sessions/${SESSION}/resume`, (route) => {
    const target = new URL(route.request().url()).searchParams.get('head') ?? STACK.soloHead;
    return route.fulfill({
      json: {
        session_id: SESSION, head: target, argv: [target, '-r', SESSION],
        from: '/work/atlas/session.jsonl', to_tree: '/work/atlas', copies: true,
        model: STACK.soloModel, live: false,
      },
    });
  });

  await page.goto(`${base}/#/needs-you`);
  await expect(page.getByRole('heading', { name: 'Needs you' })).toBeVisible();
  await expect.poll(() => accountReads).toBeGreaterThan(0);
  await expect(page.locator('main')).toContainText('99%');
  await expect(page.getByRole('row').filter({ hasText: '99%' })).toContainText('Open accounts');
  await page.getByRole('link', { name: 'Open accounts' }).click();
  await expect(page.getByRole('table', { name: 'Accounts' })).toContainText('spare');
  await expect(page.getByRole('table', { name: 'Accounts' })).toContainText('20%');
  await page.getByRole('button', { name: 'Open account chatgpt-oauth spare' }).click();
  await page.getByRole('link', { name: `Continue on ${STACK.soloHead}` }).click();
  await expect(page.locator('main')).toContainText(`Resume on ${STACK.soloHead}`);
  await page.getByRole('textbox', { name: 'Find session' }).fill('Atlas');
  await page.getByRole('tab', { name: 'By project' }).click();
  await expect(page.getByRole('table', { name: 'Sessions' })).toContainText('Atlas parser');
  await expect(page.getByRole('table', { name: 'Sessions' })).toContainText('atlas');
  await page.getByRole('button', { name: 'Sessions Atlas parser' }).click();
  const panel = page.getByRole('complementary', { name: 'Session detail' });
  const command = `${STACK.soloHead} -r ${SESSION}`;
  await expect(panel).toContainText(command);
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await panel.getByRole('button', { name: 'Copy' }).last().click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(command);
});
