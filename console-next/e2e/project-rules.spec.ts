// NEW: V4-444 — source-derived project facts and effective rule lengths/applicability.
import { expect, test } from '@playwright/test';
import { dirname } from 'node:path';
import type { ProjectRow } from '../src/types/projects';
import { driveOneTurn, STACK, utcDayWait } from './stack';
import { env, FIRST_READ_MS, open, read } from './support';

test('a repository page retains daemon session/turn facts, selected rule length and trusted roots', async ({ page }) => {
  const wait = utcDayWait(60_000);
  test.setTimeout(60_000 + wait);
  await new Promise((resolve) => setTimeout(resolve, wait));
  await driveOneTurn(Number(env('CONSOLE_E2E_OAUTH_PORT')), env('CONSOLE_E2E_KEY'), STACK.sender.id);
  const repo = env('CONSOLE_E2E_REPO');
  const faults = await open(page, 'sessions?group=repo');
  const row = await read<ProjectRow>(page, '/api/projects/' + encodeURIComponent(repo));
  expect(row.turns_today).toBeGreaterThanOrEqual(1);
  expect(row.live_sessions).toBeGreaterThanOrEqual(2);
  await page.getByRole('link', { name: 'Open the project', exact: true }).click();
  const main = page.getByRole('main');
  await expect(main).toContainText(repo);
  await expect(main).toContainText(row.live_sessions + ' sessions are running');
  await expect(main).toContainText(new RegExp(row.turns_today + ' turns? today'));
  await expect(main).toContainText(repo + '/CLAUDE.md');
  expect(row.cost_today_usd).toBeNull();
  await expect(main).toContainText('API cost today');
  await expect(main).toContainText('–');
  const rules = page.getByRole('region', { name: 'Compaction rules', exact: true });
  expect(row.compaction).toEqual([{ scope: 'project', source: 'project:' + repo, chars: STACK.compactProject.length }]);
  await expect(rules).toContainText(repo);
  await expect(rules).toContainText(STACK.compactProject.length + ' characters');
  await expect(rules).not.toContainText('Everywhere');
  await expect(rules).not.toContainText(STACK.model);
  const roots = page.getByRole('region', { name: 'Where each plan looks for a branch', exact: true });
  const root = roots.getByRole('listitem').filter({ has: page.getByText(STACK.oauthHead) }).first();
  await expect(root).toContainText(dirname(repo));
  await expect(root).toContainText('Home folder');
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});

test('Settings lists each actual effective compaction rule with its length and applicable plans', async ({ page }) => {
  const faults = await open(page, 'settings/storage');
  const block = page.getByRole('region', { name: 'Compaction', exact: true });
  const rule = (source: string) => block.getByRole('listitem').filter({ hasText: source });
  // This is the panel's first instruction read; later value assertions retain Playwright's default bound.
  await expect(block).toContainText(STACK.compactGlobal.length + ' characters', { timeout: FIRST_READ_MS });
  await expect(rule(STACK.model)).toContainText(STACK.compactModel.length + ' characters');
  await expect(rule(STACK.model)).toContainText(STACK.oauthHead);
  await expect(rule(STACK.model)).not.toContainText(STACK.keyHead);
  await expect(rule(env('CONSOLE_E2E_REPO'))).toContainText(STACK.compactProject.length + ' characters');
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});
