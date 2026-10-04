import { expect, test } from '@playwright/test';
import { open, assertHealthy, env } from './support';
import { STACK } from './stack';

test('native and separate sign-in dialogs say what Start login does without inventing a label', async ({ page }) => {
  await page.route(url => url.pathname === '/api/accounts', route => route.fulfill({ json: { accounts: [] } }));
  const faults = await open(page, 'accounts');
  const native = page.locator('.account-card').filter({ hasText: 'The login used by Claude Code in ~/.claude.' });
  await native.getByRole('button', { name: 'Sign in', exact: true }).click();
  let dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('This changes the native Claude Code login.');
  await expect(dialog).toContainText('The separate claude-splice login is unchanged.');
  await expect(dialog).toContainText('Start login opens provider sign-in.');
  await expect(dialog.getByRole('textbox')).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Start login', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  const separate = page.locator('.account-card').filter({ hasText: 'The separate login used by claude-splice in its own configuration folder.' });
  await separate.getByRole('button', { name: 'Sign in', exact: true }).click();
  dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('This changes the separate claude-splice login.');
  await expect(dialog).toContainText('The native Claude Code login is unchanged.');
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await assertHealthy(page, faults);
});

test('command labels name Accounts while a renamed label never changes the budget API identity', async ({ page }) => {
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
  await expect(group().locator('.account-budget b')).toHaveText(command + ' daily budget');
  await expect(group()).not.toContainText(STACK.keyHead);
  command = 'claude-renamed-wrapper';
  await page.reload();
  await expect(group().getByRole('heading', { level: 3, name: command, exact: true })).toBeVisible();
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
  await expect(card).not.toContainText('not a browser login');
  await expect(card.getByRole('button', { name: 'Sign in', exact: true })).toHaveCount(0);
  await assertHealthy(page, faults);
});

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
