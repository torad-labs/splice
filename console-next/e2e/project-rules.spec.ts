// NEW: V4-444 — source-derived project facts and effective rule lengths/applicability.
import { expect, test } from '@playwright/test';
import { basename, dirname, join } from 'node:path';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
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
  await expect(main).toContainText('No turn here was priced today');
  const rules = page.getByRole('region', { name: 'Compaction rules', exact: true });
  expect(row.compaction).toEqual([{ scope: 'project', source: 'project:' + repo, chars: STACK.compactProject.length }]);
  await expect(rules).toContainText(basename(repo));
  await expect(rules).toContainText(STACK.compactProject.length + ' characters');
  await expect(rules).not.toContainText('Everywhere');
  await expect(rules).not.toContainText(STACK.model);
  const roots = page.getByRole('region', { name: 'Where each command looks for a branch', exact: true });
  const root = roots.getByRole('listitem').filter({ has: page.getByText(STACK.oauthHead) }).first();
  await expect(root).toContainText(dirname(repo));
  await expect(root).toContainText('Home folder');
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});

test('a turn on the same command in another repository is attributed only to that repository', async ({ page }) => {
  const wait = utcDayWait(120_000);
  test.setTimeout(120_000 + wait);
  await new Promise((resolve) => setTimeout(resolve, wait));
  const repo = env('CONSOLE_E2E_REPO');
  const other = mkdtempSync(join(dirname(repo), 'synthetic-other-repo-'));
  const registry = join(dirname(dirname(dirname(env('CONSOLE_E2E_CONFIG')))), '.claude', 'sessions');
  const file = join(registry, 'synthetic-other-repo.json');
  const sessionId = randomUUID();
  try {
    execFileSync('git', ['init', '--quiet', other]);
    const sender = JSON.parse(readFileSync(join(registry, STACK.sender.name + '.json'), 'utf8')) as Record<string, unknown>;
    writeFileSync(file, JSON.stringify({ ...sender, sessionId, name: 'synthetic-other-repo', cwd: other, pid: process.pid, updatedAt: Date.now() }));
    const faults = await open(page, 'sessions');
    const before = await read<ProjectRow>(page, '/api/projects/' + encodeURIComponent(repo));
    await driveOneTurn(Number(env('CONSOLE_E2E_OAUTH_PORT')), env('CONSOLE_E2E_KEY'), sessionId);
    await expect.poll(async () => (await read<ProjectRow>(page, '/api/projects/' + encodeURIComponent(other))).turns_today).toBe(1);
    const after = await read<ProjectRow>(page, '/api/projects/' + encodeURIComponent(repo));
    expect(after.turns_today).toBe(before.turns_today);
    expect(after.cost_today_usd).toBe(before.cost_today_usd);
    expect(faults.pageErrors).toEqual([]);
  } finally {
    rmSync(file, { force: true });
    rmSync(other, { recursive: true, force: true });
  }
});

test('Settings lists each actual effective compaction rule with its length and applicable plans', async ({ page }) => {
  const faults = await open(page, 'settings/conversation');
  const block = page.getByRole('region', { name: 'Compaction', exact: true });
  const rule = (source: string) => block.getByRole('listitem').filter({ hasText: source });
  const repoName = basename(env('CONSOLE_E2E_REPO'));
  // This is the panel's first instruction read; later value assertions retain Playwright's default bound.
  await expect(block).toContainText(STACK.compactGlobal.length + ' characters', { timeout: FIRST_READ_MS });
  await expect(rule(STACK.model)).toContainText(STACK.compactModel.length + ' characters');
  await expect(rule(STACK.model)).toContainText(STACK.oauthHead);
  await expect(rule(STACK.model)).not.toContainText(STACK.keyHead);
  await expect(rule(repoName)).toContainText(STACK.compactProject.length + ' characters');
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});
