// NEW: V4-444 — runtime/quota/refused-account signals over the actual replacement cards and plan pages.
import { expect, test } from '@playwright/test';
import type { HeadsPayload } from '../src/types/core';
import type { AccountsWire } from '../src/types/accounts';
import { STACK } from './stack';
import { env, open, read } from './support';

test('a silent runtime is off on its card and detail while unmarked plans remain ready', async ({ page }) => {
  await page.route((url) => url.pathname === '/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    const marked = body.heads.find((head) => head.key === STACK.oauthHead);
    if (marked === undefined) throw new Error('isolated stack has no OAuth head');
    expect(marked.running).toBe(true);
    marked.runtimeNotAnswering = ':8099';
    await route.fulfill({ response, json: body });
  });
  await open(page, 'fleet');
  const card = page.locator('li.card').filter({ has: page.getByRole('link', { name: STACK.oauthHead, exact: true }) });
  await expect(card.getByText('Runtime off', { exact: true })).toBeVisible();
  await expect(card).toContainText('The runtime is not answering on :8099.');
  await expect(page.locator('li.card').filter({ hasNot: page.getByRole('link', { name: STACK.oauthHead, exact: true }) }).getByText('Runtime off', { exact: true })).toHaveCount(0);
  await expect(page.locator('main .lede')).toContainText('one switched off');
  await card.getByRole('link', { name: STACK.oauthHead, exact: true }).click();
  await expect(page.getByRole('main')).toContainText('Runtime off');
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.getByRole('button', { name: 'Copy start command', exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe('rig up ' + STACK.oauthHead);
});

test('quota refusal moves one card out of ready and keeps its local reset identical on the plan page', async ({ page }) => {
  const reset = Math.floor(Date.now() / 1000) + 3 * 86_400;
  let marking = false;
  await page.route((url) => url.pathname === '/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    const marked = body.heads.find((head) => head.key === STACK.oauthHead);
    if (marked === undefined) throw new Error('isolated stack has no OAuth head');
    expect(marked.running).toBe(true);
    if (marking) marked.quotaResetAtEpochSeconds = reset;
    await route.fulfill({ response, json: body });
  });
  await open(page, 'fleet');
  const ready = page.locator('li.card').getByText('Ready', { exact: true });
  await expect(ready.first()).toBeVisible();
  const before = await ready.count();
  marking = true;
  await page.reload();
  const card = page.locator('li.card').filter({ has: page.getByRole('link', { name: STACK.oauthHead, exact: true }) });
  const state = card.getByText(/^Out of quota until /);
  await expect(state).toBeVisible();
  const sentence = await state.innerText();
  const local = await page.evaluate((seconds) => new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  }).format(new Date(seconds * 1000)), reset);
  expect(sentence).toBe('Out of quota until ' + local);
  await expect(ready).toHaveCount(before - 1);
  await expect(page.locator('li.card').filter({ hasNot: page.getByRole('link', { name: STACK.oauthHead, exact: true }) }).getByText(/^Out of quota/)).toHaveCount(0);
  await expect(page.locator('main .lede')).toContainText('one out of quota');
  await card.getByRole('link', { name: STACK.oauthHead, exact: true }).click();
  await expect(page.getByRole('main').getByText(sentence, { exact: true })).toBeVisible();
});

test('refused credentials keep the daemon sentence and allow neither switching nor renewal', async ({ page }) => {
  const refusal = "'" + STACK.poolLabel + "' is a symbolic link, and splice does not load a linked credential; remove the link and sign in again, or sign in under a different label";
  await page.route('**/api/accounts', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as AccountsWire;
    const linked = body.accounts.find((account) => account.label === STACK.poolLabel);
    if (linked === undefined) throw new Error('isolated stack has no pooled account');
    linked.credential_present = false;
    linked.refusal = refusal;
    await route.fulfill({ response, json: body });
  });
  await open(page, 'needs-you');
  const need = page.getByRole('listitem').filter({ hasText: refusal });
  await expect(need).toBeVisible();
  await expect(need).toContainText(STACK.poolLabel);
  await expect(need.getByRole('button', { name: 'Sign in again', exact: true })).toHaveCount(0);
  await expect(need).not.toContainText('Its login is gone');
  await page.goto(env('CONSOLE_E2E_BASE') + '/#/fleet/' + STACK.oauthHead);
  const account = page.locator('li.account').filter({ has: page.getByText(STACK.poolLabel, { exact: true }) });
  await expect(account.getByText('refused', { exact: true })).toBeVisible();
  await expect(account).toContainText(refusal);
  await expect(account.getByText('Signed out', { exact: true })).toHaveCount(0);
  await expect(account.getByRole('button', { name: 'Switch to this one', exact: true })).toHaveCount(0);
  await expect(account.getByRole('button', { name: 'Sign in again', exact: true })).toHaveCount(0);
  await expect(account.getByRole('button', { name: 'Rename', exact: true })).toBeVisible();
  await expect(account.getByRole('button', { name: 'Remove', exact: true })).toBeVisible();
  const serving = page.locator('li.account').filter({ hasNot: page.getByText(STACK.poolLabel, { exact: true }) }).first();
  await expect(serving).toBeVisible();
  await expect(serving.getByText('refused', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Refresh sign-in', exact: true })).toBeVisible();
  await page.unrouteAll({ behavior: 'wait' });
});

test('the real account pool keeps provider windows, its exact next target and serving actions separate from a single login', async ({ page }) => {
  const faults = await open(page, 'fleet/' + STACK.oauthHead);
  const wire = await read<AccountsWire>(page, '/api/accounts');
  const pool = wire.accounts.filter((account) => account.heads.includes(STACK.oauthHead));
  expect(pool).toHaveLength(2);
  const accounts = page.locator('li.account');
  await expect(accounts).toHaveCount(pool.length);
  for (const account of pool) {
    const row = accounts.filter({ has: page.locator('b').getByText(account.label ?? '', { exact: true }) });
    await expect(row).toBeVisible();
    if (account.five_hour_used_percent !== null) {
      if (account.five_hour_window_seconds === null) throw new Error('reported short usage has no window');
      await expect(row).toContainText(account.five_hour_window_seconds / 3600 + 'h ' + account.five_hour_used_percent + '%');
    }
    if (account.seven_day_used_percent !== null) {
      if (account.seven_day_window_seconds === null) throw new Error('reported long usage has no window');
      await expect(row).toContainText(account.seven_day_window_seconds / 86400 + 'd ' + account.seven_day_used_percent + '%');
    }
    await expect(row.getByText('Next', { exact: true })).toHaveCount(account.next_target === true ? 1 : 0);
    if (account.next_target === true && account.primary) await expect(row).toContainText('Next because it is the primary account.');
    if (account.label === STACK.poolLabel) {
      await expect(row.getByRole('button', { name: 'Switch to this one', exact: true })).toBeVisible();
      await expect(row.getByRole('button', { name: 'Rename', exact: true })).toBeVisible();
      await expect(row.getByRole('button', { name: 'Remove', exact: true })).toBeVisible();
    }
  }
  await expect(page.getByRole('main')).toContainText('The next account is taken in this order: pinned, then primary, then last used, then most weekly room.');
  await expect(page.getByRole('button', { name: 'Refresh sign-in', exact: true })).toBeVisible();
  await expect(accounts.filter({ hasText: STACK.soloHead })).toHaveCount(0);
  await page.goto(env('CONSOLE_E2E_BASE') + '/#/fleet/' + STACK.keyHead);
  await expect(page.locator('li.account')).toHaveCount(0);
  await expect(page.getByRole('main')).toContainText('This head has no account pool: it uses one login or a key.');
  expect(faults.pageErrors).toEqual([]);
});

test('an excluded account prints its whole provider reason and cannot be switched to while a serving peer can', async ({ page }) => {
  const reason = 'Synthetic subscription is excluded until its provider accepts this login again.';
  await page.route('**/api/accounts', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as AccountsWire;
    const account = body.accounts.find((row) => row.label === STACK.poolLabel);
    if (account === undefined) throw new Error('isolated stack has no pooled account');
    account.available = false;
    account.auth_excluded_until_epoch_millis = Date.now() + 3600_000;
    account.auth_exclusion_reason = reason;
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'fleet/' + STACK.oauthHead);
  const excluded = page.locator('li.account').filter({ has: page.getByText(STACK.poolLabel, { exact: true }) });
  await expect(excluded.getByText('excluded', { exact: true })).toBeVisible();
  await expect(excluded).toContainText(reason);
  await expect(excluded.getByRole('button', { name: 'Switch to this one', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Refresh sign-in', exact: true })).toBeVisible();
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});
