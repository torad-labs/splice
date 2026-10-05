import { expect, test } from '@playwright/test';
import { open, assertHealthy, env } from './support';
import { STACK } from './stack';
import type { AccountWire, AccountsWire } from '../src/types/accounts';
import type { HeadStatus } from '../src/types/core';
import type { HeadCatalog } from '../src/types/models';

const budgetCatalog = (priced: boolean): HeadCatalog => ({
  head: STACK.keyHead, provider: 'synthetic', pinned_model: 'synthetic-budget-model', models: [{
    id: 'synthetic-budget-model', label: 'Synthetic budget model', description: '', slot: null, context_window: 1000, context_window_source: 'synthetic', pinned: true, resolved: true,
    ...(priced ? { rates: { input: 1, cache_read: 0, output: 2 } } : {}),
  }],
});

test('native and separate sign-in dialogs say what Start login does without inventing a label', async ({ page }) => {
  await page.route(url => url.pathname === '/api/accounts', route => route.fulfill({ json: { accounts: [] } }));
  const faults = await open(page, 'accounts');
  const native = page.locator('.account-card').filter({ hasText: 'The login stored for the plain claude command.' });
  await native.getByRole('button', { name: 'Sign in', exact: true }).click();
  let dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('This changes the native Claude Code login.');
  await expect(dialog).toContainText('The separate claude-splice login is unchanged.');
  await expect(dialog).toContainText('Start login opens provider sign-in.');
  await expect(dialog.getByRole('textbox')).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Start login', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  const separate = page.locator('.account-card').filter({ hasText: 'The login stored separately for the claude-splice command.' });
  await separate.getByRole('button', { name: 'Sign in', exact: true }).click();
  dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('This changes the separate claude-splice login.');
  await expect(dialog).toContainText('The native Claude Code login is unchanged.');
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await assertHealthy(page, faults);
});

test('command labels name Accounts while a renamed label never changes the budget API identity', async ({ page }) => {
  await page.route(url => url.pathname === '/api/models', route => route.fulfill({ json: { heads: [budgetCatalog(true)] } }));
  let command = 'claude-synthetic-wrapper';
  await page.route(url => url.pathname === '/api/status', async route => {
    const response = await route.fetch();
    const body = await response.json() as { registry: { key: string; label: string }[] };
    const head = body.registry.find(row => row.key === STACK.keyHead);
    if (head === undefined) throw new Error('the isolated fixture must report its API-key command');
    head.label = command;
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'accounts');
  const group = () => page.locator('.accounts-provider').filter({ has: page.getByRole('heading', { level: 2, name: command, exact: true }) });
  await expect(group().getByRole('heading', { level: 3, name: command, exact: true })).toBeVisible();
  await expect(group()).toContainText('This command uses an API key. There is no account order to change.');
  await expect(group()).not.toContainText('This command uses one login.');
  await expect(group().locator('.account-budget b')).toHaveText(command + ' daily budget');
  await expect(group()).not.toContainText(STACK.keyHead);
  command = 'claude-renamed-wrapper';
  await page.reload();
  await expect(group().getByRole('heading', { level: 3, name: command, exact: true })).toBeVisible();
  await expect(group()).toContainText('This command uses an API key. There is no account order to change.');
  await expect(group()).not.toContainText('This command uses one login.');
  await expect(group().locator('.account-budget b')).toHaveText(command + ' daily budget');
  try {
    await group().getByRole('button', { name: 'Set daily cap', exact: true }).click();
    await group().getByRole('spinbutton', { name: command + ' daily budget', exact: true }).fill('3.00');
    const writing = page.waitForRequest(request => request.method() === 'PUT' && new URL(request.url()).pathname === '/api/budgets');
    await group().getByRole('button', { name: 'Save', exact: true }).click();
    const body = (await writing).postDataJSON() as { budgets: { head: string; daily_usd: number | null }[] };
    expect(body.budgets.some(row => row.head === STACK.keyHead && row.daily_usd === 3)).toBe(true);
    expect(body.budgets.some(row => row.head === command)).toBe(false);
    await expect(group().locator('.account-budget')).toContainText('$3');
    await assertHealthy(page, faults);
  } finally {
    const restored = await page.request.put(env('CONSOLE_E2E_BASE') + '/api/budgets', {
      headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY') }, data: { budgets: [] },
    });
    expect(restored.ok()).toBe(true);
  }
});

for (const [priced, narrow] of [[false, false], [true, false], [false, true]] as const) {
  test(`Accounts and Usage budgets state the command's actual declared-price capability: priced=${priced}, narrow=${narrow}`, async ({ page }) => {
    if (narrow) await page.setViewportSize({ width: 390, height: 844 });
    await page.route(url => url.pathname === '/api/models', route => route.fulfill({ json: { heads: [budgetCatalog(priced)] } }));
    let budgets: { head: string; daily_usd: number | null; action: string }[] = [];
    const writes: unknown[] = [];
    await page.route(url => url.pathname === '/api/budgets', route => {
      if (route.request().method() === 'PUT') {
        const body = route.request().postDataJSON() as { budgets: typeof budgets };
        writes.push(body);
        budgets = body.budgets;
      }
      return route.fulfill({ json: { budgets } });
    });
    const faults = await open(page, 'accounts');
    const offer = page.locator('.account-budget').filter({ has: page.getByText(STACK.keyHead + ' daily budget', { exact: true }) });
    await expect(offer).not.toContainText('public prices');
    if (priced) {
      await expect(offer).toContainText('declared token prices');
      await offer.getByRole('button', { name: 'Set daily cap', exact: true }).click();
      await offer.getByRole('spinbutton').fill('3');
      await offer.getByRole('button', { name: 'Save', exact: true }).click();
      await expect.poll(() => writes.length).toBe(1);
      expect(budgets).toEqual([{ head: STACK.keyHead, daily_usd: 3, action: 'warn' }]);
      await expect(offer.getByRole('spinbutton')).toHaveCount(0);
    } else {
      await expect(offer).toContainText('budget cannot count spending yet');
      await expect(offer.getByRole('button', { name: 'Set daily cap', exact: true })).toBeDisabled();
      await offer.getByRole('link', { name: 'Prices for ' + STACK.keyHead, exact: true }).click();
      await expect.poll(() => decodeURIComponent(new URL(page.url()).hash)).toBe('#/usage?prices=' + STACK.keyHead);
      const card = page.locator('.usage-pricing-command').filter({ has: page.getByText('Prices for ' + STACK.keyHead, { exact: true }) });
      await expect(card).toHaveAttribute('open', '');
      await expect(card).toContainText('No price is declared for: synthetic-budget-model');
      await expect(card).toContainText('[heads.');
      expect(writes).toEqual([]);
    }
    budgets = [];
    await open(page, 'usage');
    await page.getByRole('button', { name: 'Add a budget', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('button', { name: 'Command', exact: true }).click();
    await page.getByRole('menuitemradio', { name: STACK.keyHead, exact: true }).click();
    await dialog.getByRole('spinbutton', { name: 'Dollars a day', exact: true }).fill('3');
    await expect(dialog).not.toContainText('public prices');
    if (priced) {
      await expect(dialog).toContainText('declared token prices');
      await expect(dialog.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
    } else {
      await expect(dialog).toContainText('budget cannot count spending yet');
      await expect(dialog.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
      await dialog.getByText('Prices for ' + STACK.keyHead, { exact: true }).click();
      await expect(dialog).toContainText('No price is declared for: synthetic-budget-model');
      expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
      await page.screenshot({ path: 'captures/console-walk-oct3/budget-contained-' + (narrow ? 'narrow' : 'wide') + '.png' });
    }
    await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await dialog.getByRole('button', { name: 'Discard changes', exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await assertHealthy(page, faults);
  });
}

test('a cap can still be removed on Accounts and Usage after its command loses prices', async ({ page }) => {
  await page.route(url => url.pathname === '/api/models', route => route.fulfill({ json: { heads: [budgetCatalog(false)] } }));
  let budgets: { head: string; daily_usd: number | null; action: string }[] = [{ head: STACK.keyHead, daily_usd: 3, action: 'warn' }];
  const writes: unknown[] = [];
  await page.route(url => url.pathname === '/api/budgets', route => {
    if (route.request().method() === 'PUT') {
      const body = route.request().postDataJSON() as { budgets: typeof budgets };
      writes.push(body);
      budgets = body.budgets;
    }
    return route.fulfill({ json: { budgets } });
  });
  const faults = await open(page, 'accounts');
  const offer = page.locator('.account-budget').filter({ has: page.getByText(STACK.keyHead + ' daily budget', { exact: true }) });
  await expect(offer).toContainText('budget cannot count spending yet');
  await offer.getByRole('button', { name: 'Edit cap', exact: true }).click();
  await offer.getByRole('spinbutton').fill('');
  await offer.getByRole('button', { name: 'Save', exact: true }).click();
  await expect.poll(() => writes.length).toBe(1);
  expect(budgets).toEqual([{ head: STACK.keyHead, daily_usd: null, action: 'warn' }]);
  await expect(offer).toContainText('No daily cap is set.');

  budgets = [{ head: STACK.keyHead, daily_usd: 3, action: 'warn' }];
  await open(page, 'usage');
  const row = page.locator('.usrow').filter({ has: page.getByText(STACK.keyHead, { exact: true }) });
  await row.getByRole('button', { name: 'Change', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
  await dialog.getByRole('button', { name: 'Remove the budget', exact: true }).click();
  await expect.poll(() => writes.length).toBe(2);
  expect(budgets).toEqual([]);
  await expect(dialog).toHaveCount(0);
  await assertHealthy(page, faults);
});

test('a daemon-declared local runtime has local guidance on Accounts, not a provider login alternative', async ({ page }) => {
  await page.route(url => url.pathname === '/api/status', async route => {
    const response = await route.fetch();
    const body = await response.json() as { registry: { key: string; family: string | null }[] };
    const command = body.registry.find(row => row.key === STACK.keyHead);
    if (command === undefined) throw new Error('isolated fixture must report the configured key command');
    command.family = 'local';
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'accounts');
  const card = page.locator('.account-card').filter({ has: page.getByRole('heading', { level: 3, name: STACK.keyHead, exact: true }) });
  await expect(card).toContainText('This command uses a runtime on this computer. There is no provider sign-in to change.');
  await expect(card.locator('.state')).toHaveText('Local runtime');
  await expect(card).not.toContainText('API key configured');
  await expect(card).not.toContainText('API key missing');
  const group = page.locator('.accounts-provider').filter({ has: card });
  await expect(group).toContainText('This command uses a runtime on this computer. There are no provider accounts to order.');
  await expect(group).not.toContainText('This command uses one login.');
  await expect(card).not.toContainText('not a browser login');
  await expect(card.getByRole('button', { name: 'Sign in', exact: true })).toHaveCount(0);
  await assertHealthy(page, faults);
});

test('an Accounts read failure stays visible while command kinds are still being read', async ({ page }) => {
  let release = (): void => {};
  const pending = new Promise<void>(resolve => { release = resolve; });
  await page.route(url => url.pathname === '/api/status', async route => {
    await pending;
    await route.continue();
  });
  await page.route(url => url.pathname === '/api/accounts', route => route.fulfill({
    status: 500, json: { error: 'Synthetic accounts read failed' },
  }));
  try {
    const faults = await open(page, 'accounts');
    await expect(page.getByRole('alert')).toContainText('Synthetic accounts read failed', { timeout: 20_000 });
    await expect(page.getByRole('main')).not.toContainText('Reading provider accounts');
    expect(faults.pageErrors).toEqual([]);
  } finally {
    release();
  }
});

for (const carrying of [true, false, null, undefined]) {
  test(`Accounts marks native request carrying from the roster only: ${String(carrying)}`, async ({ page }) => {
    await page.route(url => url.pathname === '/api/accounts', async route => {
      const response = await route.fetch();
      const body = await response.json() as AccountsWire;
      const base = body.accounts.find(row => row.heads.includes(STACK.oauthHead));
      if (base === undefined) throw new Error('synthetic login fixture is missing');
      const common = { ...base, provider: 'anthropic', kind: 'client', heads: ['claude-splice'], primary: false, selected: false, pinned: false, next_target: false, windows: [], credential_path: null, identity_verified: false };
      const rows: AccountWire[] = ['claude', 'claude-splice'].map((id, index) => ({
        ...common, label: id, display_name: index === 0 ? 'Personal login' : 'Separate login',
        login_place: { id: index === 0 ? 'claude' : 'claude-splice', command: id },
        ...(carrying === undefined ? {} : { carrying_request: carrying === null ? null : index === 0 ? carrying : !carrying }),
      }));
      await route.fulfill({ json: { accounts: rows } });
    });
    await page.route(url => url.pathname === '/api/auth/claude-splice/order', route => route.fulfill({ json: { unavailable: 'Synthetic selection unavailable' } }));
    const faults = await open(page, 'accounts');
    const personal = page.locator('.account-card').filter({ has: page.getByRole('heading', { name: 'Personal login', exact: true }) });
    const separate = page.locator('.account-card').filter({ has: page.getByRole('heading', { name: 'Separate login', exact: true }) });
    if (carrying === true || carrying === false) {
      const carried = carrying ? personal : separate;
      const other = carrying ? separate : personal;
      await expect(carried).toContainText('Carried the latest matched claude-splice request.');
      await expect(other).toContainText('The latest matched claude-splice request used another login.');
      await expect(other).not.toContainText('Carried the latest matched');
    } else if (carrying === null) {
      for (const card of [personal, separate]) await expect(card).toContainText('No claude-splice request has matched a login since the daemon started.');
    } else {
      for (const card of [personal, separate]) await expect(card).toContainText('The daemon has not reported which login carried this command’s requests.');
      await expect(page.getByText('No claude-splice request has matched a login since the daemon started.', { exact: true })).toHaveCount(0);
    }
    await expect(separate).not.toContainText('login used by claude-splice');
    await assertHealthy(page, faults);
  });
}

for (const path of ['accounts', 'models/claude-splice', 'settings/tools']) {
  test(path + ' edits colliding native and pool labels by explicit location, even for one subscription', async ({ page }) => {
    let logins: AccountWire[] = [];
    let loaded = false;
    const calls: { method: string; kind: string | null; id: string | null; pathId: string; name?: string }[] = [];
    await page.route(url => url.pathname === '/api/accounts', async route => {
      if (!loaded) {
        const response = await route.fetch();
        const body = await response.json() as AccountsWire;
        const base = body.accounts.find(row => row.heads.includes(STACK.oauthHead));
        if (base === undefined) throw new Error('the isolated stack must supply its synthetic login');
        const common: AccountWire = { ...base, provider: 'anthropic', heads: ['claude-splice'], kind: 'claude-account', label: 'claude', credential_path: null, primary: false, selected: false, pinned: false, next_target: false, can_remove: true, can_rename: true, account: { uuid: 'synthetic-shared-subscription', email: 'verified@example.invalid' }, identity_verified: true };
        logins = [
          { ...common, kind: 'client', display_name: 'Personal login', selected: true, login_place: { id: 'claude', command: 'claude' }, edit_target: { kind: 'native', id: 'claude' } },
          { ...common, display_name: 'Work login', identity_verified: false, account: { uuid: 'synthetic-shared-subscription', email: 'unverified@example.invalid' }, edit_target: { kind: 'pool', id: 'claude' } },
          { ...common, label: 'locked', display_name: 'Locked login', can_remove: false, can_rename: false, account: { uuid: 'synthetic-shared-subscription', email: null }, edit_target: { kind: 'pool', id: 'locked' } },
        ];
        loaded = true;
      }
      await route.fulfill({ json: { accounts: logins } });
    });
    await page.route(url => url.pathname === '/api/heads', async route => {
      const response = await route.fetch();
      const body = await response.json() as { heads: HeadStatus[] };
      const base = body.heads.find(row => row.key === STACK.oauthHead);
      if (base === undefined) throw new Error('synthetic command is missing');
      body.heads.push({ ...base, key: 'claude-splice', label: 'claude-splice', authKind: 'client' });
      await route.fulfill({ response, json: body });
    });
    await page.route(url => url.pathname === '/api/models', async route => {
      const response = await route.fetch();
      const body = await response.json() as { heads: HeadCatalog[] };
      const base = body.heads.find(row => row.head === STACK.oauthHead);
      if (base === undefined) throw new Error('synthetic model is missing');
      body.heads.push({ ...base, head: 'claude-splice', provider: 'anthropic' });
      await route.fulfill({ response, json: body });
    });
    await page.route(url => url.pathname === '/api/status', async route => {
      const response = await route.fetch();
      const body = await response.json() as { registry: { key: string; label: string; family: string | null }[] };
      const base = body.registry.find(row => row.key === STACK.oauthHead);
      if (base === undefined) throw new Error('synthetic registry row is missing');
      body.registry.push({ ...base, key: 'claude-splice', label: 'claude-splice', family: 'anthropic' });
      await route.fulfill({ response, json: body });
    });
    await page.route(url => url.pathname === '/api/auth/claude-splice/order', route => route.fulfill({ json: {
      head: 'claude-splice', order: [], effective_order: ['claude'], single_account: true,
    } }));
    await page.route(url => url.pathname === '/api/claude-head', route => route.fulfill({ json: {
      mode: 'separate', resolves_to: null, shim_path: '/synthetic/splice-launch', real_binary_path: null,
      claude_logins: { count: 1, selected: 'saved-copy', labels: ['saved-copy'], constraint: '' },
    } }));
    // Every edit is intercepted. No Remove can reach any real credential store.
    await page.route(url => url.pathname.startsWith('/api/auth/') && url.pathname.includes('/accounts/'), async route => {
      const request = route.request();
      const url = new URL(request.url());
      const kind = url.searchParams.get('target_kind');
      const id = url.searchParams.get('target_id');
      const pathId = decodeURIComponent(url.pathname.split('/').at(-1) ?? '');
      const target = logins?.find(row => row.edit_target?.kind === kind && row.edit_target.id === id);
      if (target === undefined || id !== pathId) throw new Error('edit did not identify its synthetic credential location');
      if (request.method() === 'PATCH') {
        const name = (request.postDataJSON() as { label: string }).label;
        calls.push({ method: 'PATCH', kind, id, pathId, name });
        target.display_name = name;
      } else if (request.method() === 'DELETE') {
        calls.push({ method: 'DELETE', kind, id, pathId });
        logins = logins?.filter(row => row !== target) ?? [];
      } else throw new Error('unexpected synthetic account edit method');
      await route.fulfill({ json: { ok: true } });
    });
    const faults = await open(page, path);
    const item = (name: string) => page.locator(path === 'accounts' ? 'li.account-card' : 'li.account').filter({ has: page.getByText(name, { exact: true }) });
    await expect(item('Personal login')).toBeVisible();
    await expect(item('Work login')).toBeVisible();
    await expect(item('Personal login')).toContainText('verified@example.invalid');
    await expect(page.getByRole('main')).not.toContainText('unverified@example.invalid');
    await expect(item('Locked login').getByRole('button', { name: 'Remove', exact: true })).toHaveCount(0);
    await expect(item('Locked login').getByRole('button', { name: 'Rename', exact: true })).toHaveCount(0);
    if (path === 'models/claude-splice') {
      // Same UUID collapses subscription facts, never physical management rows.
      await expect(page.getByRole('main')).not.toContainText('Pool · 2 accounts');
    }
    if (path === 'settings/tools') await expect(page.getByRole('main')).toContainText('Saved Claude login copies');
    for (const [kind, name] of [['native', 'Personal login'], ['pool', 'Work login']] as const) {
      const next = name + ' renamed';
      await item(name ?? '').getByRole('button', { name: 'Rename', exact: true }).click();
      let dialog = page.getByRole('dialog');
      await expect(dialog).toContainText(name ?? '');
      await dialog.getByRole('textbox', { name: 'New name', exact: true }).fill(next);
      await dialog.getByRole('button', { name: 'Save', exact: true }).click();
      await expect(dialog).toHaveCount(0);
      await expect(item(next)).toBeVisible();
      expect(logins?.find(row => row.edit_target?.kind === kind)?.label).toBe('claude');
      await item(next).getByRole('button', { name: 'Remove', exact: true }).click();
      dialog = page.getByRole('dialog');
      await expect(dialog).toContainText(next);
      await dialog.getByRole('button', { name: 'Remove', exact: true }).click();
      await expect(dialog).toHaveCount(0);
      await expect(item(next)).toHaveCount(0);
    }
    expect(calls).toEqual([
      { method: 'PATCH', kind: 'native', id: 'claude', pathId: 'claude', name: 'Personal login renamed' },
      { method: 'DELETE', kind: 'native', id: 'claude', pathId: 'claude' },
      { method: 'PATCH', kind: 'pool', id: 'claude', pathId: 'claude', name: 'Work login renamed' },
      { method: 'DELETE', kind: 'pool', id: 'claude', pathId: 'claude' },
    ]);
    await assertHealthy(page, faults);
  });
}

for (const path of ['accounts', 'settings', 'settings/health']) {
  test(path + ' remains a loading state while its first daemon reads are pending', async ({ page }) => {
    let release = (): void => {};
    const waiting = new Promise<void>(resolve => { release = resolve; });
    await page.route(url => ['/api/status', '/api/accounts', '/api/config', '/api/doctor'].includes(url.pathname), async route => {
      await waiting;
      await route.continue();
    });
    await open(page, path);
    await expect(page.getByRole('main')).toContainText(path === 'accounts' ? 'Reading provider accounts' : 'Reading the settings');
    await expect(page.locator('.foot')).toContainText('Checking daemon');
    await expect(page.getByRole('main')).not.toContainText('splice is not answering');
    await expect(page.locator('.foot')).not.toContainText('Not answering');
    release();
    await expect(page.locator('.foot')).toContainText('Daemon running');
  });
}
