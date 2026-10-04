import { expect, test } from '@playwright/test';
import { open, assertHealthy } from './support';

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
