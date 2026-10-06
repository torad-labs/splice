// NEW: V4-444 — opening a limits surface probes without a turn and keeps the retained observation visible.
import { expect, test } from '@playwright/test';
import type { UsagePayload } from '../src/types/core';
import { open, read } from './support';
import { STACK } from './stack';

test.use({ timezoneId: 'America/Chicago' });

function observedText(observed: number): string {
  return 'Observed ' + new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
    timeZoneName: 'short', timeZone: 'America/Chicago',
  }).format(new Date(observed * 1000));
}

test('each quota surface calls probe-now on open and Usage prints the retained observation', async ({ page }) => {
  const observed = Math.floor(Date.now() / 1000) - 60;
  const payload = await read<UsagePayload>(page, '/api/usage');
  const head = payload.heads.find((entry) => entry.key === STACK.oauthHead);
  if (head?.usage == null) throw new Error('isolated quota fixture has no subscription head');
  head.usage.quota = {
    five_hour: { used_pct: 25, resets_at: observed + 3_600, observed_at: observed },
    seven_day: { used_pct: 50, resets_at: observed + 86_400, observed_at: observed },
    plan: 'synthetic-plan',
  };
  let probes = 0;
  await page.route('**/api/usage/probe', (route) => {
    expect(route.request().method()).toBe('POST');
    probes++;
    return route.fulfill({ json: payload });
  });
  const paths = ['accounts', 'models', 'models/' + STACK.oauthHead, 'usage'];
  for (const [index, path] of paths.entries()) {
    await open(page, path);
    await expect.poll(() => probes, { message: path + ' must probe quota on open' }).toBe(index + 1);
  }
  await expect(page.getByText('Short window · 25% · resets ' + observedText(observed + 3_600).slice('Observed '.length) + ' · ' + observedText(observed), { exact: true })).toBeVisible();
  await expect(page.getByText('Longer window · 50% · resets ' + observedText(observed + 86_400).slice('Observed '.length) + ' · ' + observedText(observed), { exact: true })).toBeVisible();
});

test('retained quota readings distinguish an expired deadline from a stale future reset on every display', async ({ page }) => {
  const now = Math.floor(Date.now() / 1000);
  const payload = await read<UsagePayload>(page, '/api/usage');
  const head = payload.heads.find(entry => entry.key === STACK.oauthHead);
  if (head?.usage == null) throw new Error('isolated quota fixture has no subscription head');
  const expired = now - 3600;
  const future = now + 86400;
  const observed = now - 7200;
  head.usage.quota = {
    five_hour: { used_pct: 25, resets_at: expired, observed_at: observed, current: false },
    seven_day: { used_pct: 50, resets_at: future, observed_at: observed, current: false },
    plan: 'synthetic-plan',
  };
  await page.route('**/api/usage', route => route.fulfill({ json: payload }));
  await page.route('**/api/usage/probe', route => route.fulfill({ json: payload }));
  const past = observedText(expired).slice('Observed '.length);
  const ahead = observedText(future).slice('Observed '.length);
  for (const path of ['models', 'models/' + STACK.oauthHead, 'usage']) {
    const faults = await open(page, path);
    const separator = path === 'models' ? ', ' : ' · ';
    const short = path === 'usage' ? 'Short window' : '5 hours';
    const long = path === 'usage' ? 'Longer window' : 'Week';
    await expect(page.getByRole('main')).toContainText(short + separator + 'Last reading 25%' + separator + 'reset ' + past + separator + 'Not current');
    await expect(page.getByRole('main')).not.toContainText(short + separator + 'Last reading 25%' + separator + 'resets ' + past);
    await expect(page.getByRole('main')).toContainText(long + separator + 'Last reading 50%' + separator + 'resets ' + ahead + separator + 'Not current');
    await expect(page.getByRole('main')).toContainText(observedText(observed));
    expect(faults.pageErrors).toEqual([]);
    expect(faults.failedReads).toEqual([]);
  }
  await page.unrouteAll({ behavior: 'wait' });
});

test('a late pre-probe usage read cannot replace the refreshed observation', async ({ page }) => {
  const observed = Math.floor(Date.now() / 1000) - 60;
  const payload = await read<UsagePayload>(page, '/api/usage');
  const head = payload.heads.find(entry => entry.key === STACK.oauthHead);
  if (head?.usage == null) throw new Error('isolated quota fixture has no subscription head');
  head.usage.quota = { five_hour: { used_pct: 25, resets_at: observed + 3600, observed_at: observed } };
  const old = structuredClone(payload);
  const oldHead = old.heads.find(entry => entry.key === STACK.oauthHead);
  if (oldHead?.usage?.quota?.five_hour === undefined) throw new Error('missing synthetic window');
  oldHead.usage.quota.five_hour.observed_at = observed - 3600;
  let beginRead!: () => void;
  const began = new Promise<void>(resolve => { beginRead = resolve; });
  let releaseRead!: () => void;
  const released = new Promise<void>(resolve => { releaseRead = resolve; });
  await page.route('**/api/usage', async route => {
    beginRead();
    await released;
    await route.fulfill({ json: old });
  });
  await page.route('**/api/usage/probe', async route => {
    await began;
    await route.fulfill({ json: payload });
  });
  await open(page, 'models');
  await expect(page.getByText(observedText(observed), { exact: true }).first()).toBeVisible();
  const lateRead = page.waitForResponse(response => new URL(response.url()).pathname === '/api/usage');
  releaseRead();
  await lateRead;
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
  await expect(page.getByText(observedText(observed), { exact: true }).first()).toBeVisible();
  await expect(page.getByText(observedText(observed - 3600), { exact: true })).toHaveCount(0);
});
