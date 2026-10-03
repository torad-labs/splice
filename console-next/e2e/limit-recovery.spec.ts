// NEW: V4-444 — history joins and quota-to-resume navigation retain the daemon's exact recipe.
import { expect, test } from '@playwright/test';
import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { AccountWire } from '../src/types/accounts';
import type { SessionHistoryPayload } from '../src/types/sessions';
import type { UsagePayload } from '../src/types/core';
import { env, open } from './support';
import { STACK } from './stack';

const SESSION = '3f2a9c1e-0000-4000-8000-000000000009';
const now = Date.now();
const reset = Math.floor(now / 1000) + 3600;
const account = (label: string, used: number, head: string): AccountWire => ({
  kind: 'chatgpt-oauth', label, single_login: false, credential_path: null, plan: 'plus',
  primary: true, selected: true, available: true, pinned: false, next_target: false,
  credential_present: true, auth_excluded_until_epoch_millis: null, auth_exclusion_reason: null,
  heads: [head], five_hour_used_percent: used, five_hour_reset_epoch_seconds: reset,
  five_hour_window_seconds: 18_000, five_hour_current: true,
  seven_day_used_percent: null, seven_day_reset_epoch_seconds: null,
  seven_day_window_seconds: null, seven_day_current: false, observed_at_epoch_seconds: Math.floor(now / 1000),
});
const history: SessionHistoryPayload = {
  sessions: [{
    pid: null, session_id: SESSION, name: 'Synthetic parser', kind: 'interactive', version: null,
    cwd: '/synthetic/parser', repo: { root: '/synthetic/parser' }, status: null, status_updated_at: null,
    started_at: now - 3600_000, updated_at: now, address: null, head: STACK.oauthHead,
    availability: 'gone', source: 'history+transcript', account: null,
  }],
  next: null, errors: [], skipped: {},
};

test('the daemon joins an isolated finished history row to its primary transcript', async ({ request }) => {
  const root = env('CONSOLE_E2E_TRANSCRIPT_ROOT');
  const id = '3f2a9c1e-0000-4000-8000-000000000010';
  const project = join(root, 'projects', 'synthetic-parser');
  mkdirSync(project, { recursive: true });
  writeFileSync(join(project, id + '.jsonl'), JSON.stringify({ type: 'custom-title', customTitle: 'Synthetic history join' }) + '\n');
  appendFileSync(join(root, 'history.jsonl'), JSON.stringify({
    sessionId: id, display: 'Synthetic finished work', project: '/synthetic/parser', timestamp: now,
  }) + '\n');
  await expect.poll(async () => {
    const response = await request.get(env('CONSOLE_E2E_BASE') + '/api/sessions/history?query=Synthetic%20history%20join', {
      headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY') },
    });
    expect(response.ok()).toBe(true);
    const body = await response.json() as SessionHistoryPayload;
    return body.sessions.find((row) => row.session_id === id) ?? null;
  }, { timeout: 20_000 }).toMatchObject({
    session_id: id, name: 'Synthetic history join', source: 'history+transcript', head: STACK.oauthHead,
  });
});

test('a near-limit notice leads through the spare plan to exact cross-plan resume argv on history cards and detail', async ({ page }) => {
  const targets: string[] = [];
  const command = STACK.soloHead + " -r " + SESSION + " --model 'synthetic model'";
  await page.route('**/api/accounts', (route) => route.fulfill({ json: { accounts: [
    account('synthetic-near', 99, STACK.oauthHead), account('synthetic-spare', 20, STACK.soloHead),
  ] } }));
  await page.route('**/api/usage', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as UsagePayload;
    for (const [head, pct] of [[STACK.oauthHead, 99], [STACK.soloHead, 20]] as const) {
      const row = body.heads.find((entry) => entry.key === head);
      if (row === undefined) throw new Error('the fixture is missing usage for ' + head);
      row.usage = {
        output_tokens_5h: 0, entries: 0, ratelimit: null,
        warn: { level: pct >= 90 ? 'critical' : 'ok', pct, source: 'quota_5h', reset: null },
        quota: { five_hour: { used_pct: pct, resets_at: reset } },
      };
    }
    await route.fulfill({ response, json: body });
  });
  await page.route('**/api/sessions', (route) => route.fulfill({ json: { note: 'Synthetic live registry', sessions: [] } }));
  await page.route('**/api/sessions/edges', (route) => route.fulfill({ json: { sessions: {} } }));
  await page.route((url) => url.pathname === '/api/sessions/history', (route) => route.fulfill({ json: history }));
  await page.route('**/api/sessions/' + SESSION + '/transcript*', (route) => route.fulfill({ json: {
    session_id: SESSION, path: '/synthetic/parser/session.jsonl', messages: [], next: null, skipped_records: {},
  } }));
  await page.route('**/api/sessions/' + SESSION + '/edges', (route) => route.fulfill({ json: { session_id: SESSION, edges: [] } }));
  await page.route((url) => url.pathname === '/api/sessions/' + SESSION + '/resume', (route) => {
    const head = new URL(route.request().url()).searchParams.get('head') ?? '';
    targets.push(head);
    return route.fulfill({ json: {
      session_id: SESSION, head, argv: [head, '-r', SESSION, '--model', 'synthetic model'],
      from: '/synthetic/parser/session.jsonl', to_tree: '/synthetic/spare', copies: true,
      model: STACK.soloModel, live: false,
    } });
  });
  await open(page, 'accounts');
  const notice = page.locator('li.account-card').filter({ has: page.getByRole('heading', { name: 'synthetic-near', exact: true }) });
  await expect(notice).toContainText(STACK.oauthHead);
  await expect(notice).toContainText('5 hours');
  await expect(notice).toContainText('99% used');
  await expect(notice).toContainText('Resets ');
  await expect(notice).not.toHaveClass(/attn/);
  await expect(notice.getByRole('link', { name: 'Switch account', exact: true })).toHaveCount(0);
  await page.getByRole('navigation', { name: 'Pages', exact: true }).getByRole('link', { name: 'Usage', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Usage', exact: true })).toBeVisible();
  const spareUsage = page.getByRole('listitem').filter({ hasText: STACK.soloHead });
  await expect(spareUsage).toContainText('20% of its limit');
  await expect(page.getByRole('main')).toContainText('99% of its limit');
  await page.getByRole('navigation', { name: 'Pages', exact: true }).getByRole('link', { name: 'Models', exact: true }).click();
  await page.getByRole('link', { name: STACK.soloHead, exact: true }).click();
  await expect(page.getByRole('main')).toContainText('synthetic-spare');
  await expect(page.getByRole('main')).toContainText('20%');
  await page.getByRole('navigation', { name: 'Pages', exact: true }).getByRole('link', { name: 'Sessions', exact: true }).click();
  await page.getByRole('searchbox', { name: 'Find a session', exact: true }).fill('Synthetic parser');
  const earlier = page.getByRole('region', { name: 'Earlier sessions', exact: true });
  const card = earlier.getByRole('listitem').filter({ hasText: 'Synthetic parser' });
  await expect(card).toContainText('parser');
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await card.getByRole('button', { name: 'Resume on another command', exact: true }).click();
  await page.getByRole('menuitem', { name: STACK.soloHead, exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(command);
  await card.getByRole('link', { name: 'Synthetic parser', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Synthetic parser', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Resume on another command', exact: true }).click();
  await page.getByRole('menuitem', { name: STACK.soloHead, exact: true }).click();
  await expect.poll(() => targets).toEqual([STACK.soloHead, STACK.soloHead]);
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(command);
  await page.unrouteAll({ behavior: 'wait' });
});
