// NEW: V4-444 — runtime/quota/refused-account signals over the actual replacement cards and plan pages.
import { expect, test, type Page } from '@playwright/test';
import type { ControlStatusPayload, HeadsPayload, UsagePayload } from '../src/types/core';
import type { KeysPayload } from '../src/types/login';
import type { AccountsWire } from '../src/types/accounts';
import { STACK } from './stack';
import { assertHealthy, env, open, read } from './support';

/** Every card's words on one line each, so a failed count says which head stood in which state. */
async function cardStates(page: Page): Promise<string> {
  const cards = await page.locator('li.card').all();
  return (await Promise.all(cards.map(async (card) => (await card.innerText()).replace(/\s+/g, ' ').trim().slice(0, 160)))).join('\n');
}

test('a key-auth head with a stored key reads as local when the daemon names its family', async ({ page }) => {
  await page.route((url) => url.pathname === '/api/status', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as ControlStatusPayload;
    const local = body.registry.find((row) => row.key === STACK.keyHead);
    if (local === undefined) throw new Error('isolated stack has no API-key head');
    local.family = 'local';
    await route.fulfill({ response, json: body });
  });
  await page.route((url) => url.pathname === '/api/keys', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as KeysPayload;
    body.keys.push({ name: 'E2E_LOCAL_KEY', stored: true, heads: [{ head: STACK.keyHead, source: 'store' }] });
    await route.fulfill({ response, json: body });
  });
  await open(page, 'models');
  const card = page.locator('li.card').filter({ has: page.getByRole('link', { name: STACK.keyHead, exact: true }) });
  await expect(card.locator('.quiet-meta').first()).toContainText('this computer');
  await expect(card.locator('.quiet-meta').first()).not.toContainText('api key');
  await card.getByRole('link', { name: STACK.keyHead, exact: true }).click();
  await expect(page.locator('header.top .quiet-meta')).toContainText('this computer');
  await expect(page.locator('header.top .quiet-meta')).not.toContainText('api key');
  await expect(page.getByRole('main')).not.toContainText('Pays per token; no window');
  await page.unrouteAll({ behavior: 'ignoreErrors' });
});

for (const authKind of ['bearer', 'local']) {
  test(`a remote family stays remote on Fleet and detail with auth kind ${authKind}`, async ({ page }) => {
    await page.route((url) => url.pathname === '/api/heads', async (route) => {
      const response = await route.fetch();
      const body = await response.json() as HeadsPayload;
      const head = body.heads.find((row) => row.key === STACK.keyHead);
      if (head === undefined) throw new Error('isolated stack has no key head');
      head.authKind = authKind;
      await route.fulfill({ response, json: body });
    });
    await page.route((url) => url.pathname === '/api/status', async (route) => {
      const response = await route.fetch();
      const body = await response.json() as ControlStatusPayload;
      const head = body.registry.find((row) => row.key === STACK.keyHead);
      if (head === undefined) throw new Error('isolated stack has no key head');
      head.family = 'openrouter';
      await route.fulfill({ response, json: body });
    });
    const faults = await open(page, 'models');
    const card = page.locator('li.card').filter({ has: page.getByRole('link', { name: STACK.keyHead, exact: true }) });
    await expect(card.locator('.quiet-meta').first()).toContainText('api key');
    await expect(card.locator('.quiet-meta').first()).not.toContainText('this computer');
    await expect(card.getByText('Key missing', { exact: true })).toBeVisible();
    await expect(card.getByRole('button', { name: 'Copy the key command', exact: true })).toBeVisible();
    await expect(card).not.toContainText('until you sign in');
    await card.getByRole('link', { name: STACK.keyHead, exact: true }).click();
    await expect(page.locator('header.top .quiet-meta')).toContainText('api key');
    await expect(page.locator('header.top .quiet-meta')).not.toContainText('this computer');
    await expect(page.getByRole('main')).toContainText('Pays per token; no window');
    await expect(page.getByRole('button', { name: 'Store key', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Copy the key command', exact: true })).toBeVisible();
    await assertHealthy(page, faults);
    await page.unrouteAll({ behavior: 'ignoreErrors' });
  });
}

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
  await open(page, 'models');
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

test('a full reading stays Ready in command colour, reads as usage, and still serves without a required act', async ({ page }, testInfo) => {
  await page.route('**/api/status', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as ControlStatusPayload;
    const head = body.registry.find((row) => row.key === STACK.oauthHead);
    if (head === undefined) throw new Error('isolated stack has no OAuth registry head');
    head.family = 'openai';
    await route.fulfill({ response, json: body });
  });
  const reset = Math.floor(Date.now() / 1000) + 3600;
  let pct = 100;
  await page.route((url) => url.pathname === '/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    const marked = body.heads.find((head) => head.key === STACK.oauthHead);
    if (marked === undefined) throw new Error('isolated stack has no OAuth head');
    delete marked.quotaResetAtEpochSeconds;
    await route.fulfill({ response, json: body });
  });
  await page.route((url) => url.pathname === '/api/usage', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as UsagePayload;
    const marked = body.heads.find((head) => head.key === STACK.oauthHead);
    if (marked === undefined || marked.usage === null) throw new Error('isolated stack has no OAuth usage');
    marked.usage.quota = { five_hour: { used_pct: pct, resets_at: reset, observed_at: Math.floor(Date.now() / 1000) } };
    await route.fulfill({ response, json: body });
  });
  // No switch target: usage remains the source for the head with no account row.
  await page.route((url) => url.pathname === '/api/accounts', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as AccountsWire;
    body.accounts = body.accounts.filter((account) => !account.heads.includes(STACK.oauthHead));
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'models');
  const card = page.locator('li.card').filter({ has: page.getByRole('link', { name: STACK.oauthHead, exact: true }) });
  await expect(card.getByText('Ready', { exact: true })).toBeVisible();
  await expect(card.locator('.track')).not.toHaveClass(/full/);
  await expect(card.locator('.track i')).toHaveCSS('width', await card.locator('.track').evaluate((node) => getComputedStyle(node).width));
  const local = await page.evaluate((seconds) => new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
  }).format(new Date(seconds * 1000)), reset);
  await expect(card.locator('.gl small')).toHaveText('near its limit · 100%, resets ' + local);
  pct = 85;
  await page.reload();
  await expect(card.getByText('Ready', { exact: true })).toBeVisible();
  await expect(card.locator('.gl small')).toHaveText('near its limit · 85%, resets ' + local);
  await expect(page.locator('main .lede')).not.toContainText('near its limit');
  pct = 100;
  await page.reload();
  await expect(card.locator('.gl small')).toHaveText('near its limit · 100%, resets ' + local);
  expect((await card.locator('.gl').innerText()).match(/100%/g)).toHaveLength(1);
  for (const theme of ['Day', 'Night']) {
    await page.getByRole('button', { name: theme, exact: true }).click();
    const colours = await card.locator('.track i').evaluate((node) => {
      const style = getComputedStyle(node);
      const probe = document.createElement('i');
      node.append(probe);
      probe.style.color = 'var(--gpt)';
      const command = getComputedStyle(probe).color;
      probe.style.color = 'var(--stuck)';
      const refusal = getComputedStyle(probe).color;
      probe.remove();
      return { fill: style.backgroundColor, image: style.backgroundImage, command, refusal };
    });
    expect(colours.fill).toBe(colours.command);
    expect(colours.fill).not.toBe(colours.refusal);
    expect(colours.image).toBe('none');
    await card.screenshot({ path: testInfo.outputPath('full-reading-' + theme.toLowerCase() + '.png') });
  }
  await page.getByRole('link', { name: 'Usage', exact: true }).click();
  await expect(page.locator('main .lede')).toContainText(STACK.oauthHead + ' is at 100% of its limit.');
  const plan = page.locator('li.uplan').filter({ hasText: STACK.oauthHead }).first();
  await expect(plan.locator('.track')).not.toHaveClass(/full/);
  // The removed global observations feed has no nav badge; serving and quota facts stay on Models and Usage.
  await assertHealthy(page, faults);
  await page.unrouteAll({ behavior: 'wait' });
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
  await open(page, 'models');
  await expect(page.locator('li.card').getByText('Ready', { exact: true }).first()).toBeVisible();
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
  // The marked card alone must leave Ready and be the only one out of quota. No count of the other cards is taken: the
  // stack's heads are still moving between states while the page first loads, which made a before/after count flake.
  try {
    await expect(card.getByText('Ready', { exact: true })).toHaveCount(0);
    await expect(page.locator('li.card').getByText(/^Out of quota/)).toHaveCount(1);
  } catch (error) {
    throw new Error(`the quota refusal must mark only ${STACK.oauthHead}; every card:\n${await cardStates(page)}`, { cause: error });
  }
  await expect(page.locator('main .lede')).toContainText('one out of quota');
  await expect(card.locator('.track')).toHaveClass(/full/);
  const refusal = await card.locator('.track i').evaluate((node) => {
    const probe = document.createElement('i');
    node.append(probe);
    probe.style.color = 'var(--stuck)';
    const colour = getComputedStyle(probe).color;
    probe.remove();
    return { colour, image: getComputedStyle(node).backgroundImage };
  });
  expect(refusal.image).toContain(refusal.colour);
  await card.getByRole('link', { name: STACK.oauthHead, exact: true }).click();
  await expect(page.getByRole('main').getByText(sentence, { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Usage', exact: true }).click();
  await expect(page.locator('main .lede')).toContainText(STACK.oauthHead + ' is out of quota.');
  const plan = page.locator('li.uplan').filter({ hasText: STACK.oauthHead }).first();
  await expect(plan.locator('.track')).toHaveClass(/full/);
  const usageRefusal = await plan.locator('.track i').evaluate((node) => {
    const probe = document.createElement('i');
    node.append(probe);
    probe.style.color = 'var(--stuck)';
    const colour = getComputedStyle(probe).color;
    probe.remove();
    return { colour, image: getComputedStyle(node).backgroundImage };
  });
  expect(usageRefusal.image).toContain(usageRefusal.colour);
  await page.unrouteAll({ behavior: 'wait' });
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
  await open(page, 'accounts');
  const need = page.locator('li.account-card').filter({ hasText: refusal });
  await expect(need).toBeVisible();
  await expect(need).toContainText(STACK.poolLabel);
  await expect(need.getByText('Credential refused', { exact: true })).toBeVisible();
  await expect(need.getByRole('button', { name: 'Sign in again', exact: true })).toBeDisabled();
  await expect(need).not.toContainText('Its login is gone');
  await page.goto(env('CONSOLE_E2E_BASE') + '/#/models/' + STACK.oauthHead);
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
  const faults = await open(page, 'models/' + STACK.oauthHead);
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
    if (account.next_target === true && account.primary) await expect(row).not.toContainText('Next because it is the primary account.');
    if (account.label === STACK.poolLabel) {
      await expect(row.getByRole('button', { name: 'Switch to this one', exact: true })).toBeVisible();
      await expect(row.getByRole('button', { name: 'Rename', exact: true })).toBeVisible();
      await expect(row.getByRole('button', { name: 'Remove', exact: true })).toBeVisible();
    }
  }
  await expect(page.getByRole('main')).toContainText('The next account is taken in this order: pinned, then saved order, then primary, then last used, then most weekly room.');
  await expect(page.getByRole('button', { name: 'Refresh sign-in', exact: true })).toBeVisible();
  await expect(accounts.filter({ hasText: STACK.soloHead })).toHaveCount(0);
  await page.goto(env('CONSOLE_E2E_BASE') + '/#/models/' + STACK.keyHead);
  await expect(page.locator('li.account')).toHaveCount(0);
  await expect(page.getByRole('main')).toContainText('This command has no account pool: it uses one login or a key.');
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
  const faults = await open(page, 'models/' + STACK.oauthHead);
  const excluded = page.locator('li.account').filter({ has: page.getByText(STACK.poolLabel, { exact: true }) });
  await expect(excluded.getByText('excluded', { exact: true })).toBeVisible();
  await expect(excluded).toContainText(reason);
  await expect(excluded.getByRole('button', { name: 'Switch to this one', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Refresh sign-in', exact: true })).toBeVisible();
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});
