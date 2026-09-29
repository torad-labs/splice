// V4-410: a pooled account whose credential splice refuses (a symlinked <label>.json, V4-405) reads as refused
// in a real browser, in the daemon's words, and offers no sign-in. V4-357's spec covers the account whose
// credential is simply gone, which still renews.
import { expect, test } from '@playwright/test';
import { STACK } from '../stack';

const reason = 'is a symbolic link, and splice does not load a linked credential';
const refusal = `'${STACK.poolLabel}' ${reason}; remove the link and sign in again, or sign in under a different label`;

test('Needs you and Accounts say a refused account is refused and offer no sign-in', async ({ page }) => {
  const base = process.env.CONSOLE_E2E_BASE;
  const key = process.env.CONSOLE_E2E_KEY;
  if (!base || !key) throw new Error('the isolated console stack did not start');
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', key]);
  await page.route('**/api/accounts', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as { accounts: { label: string | null; credential_present: boolean; refusal?: string | null }[] };
    const linked = body.accounts.find((account) => account.label === STACK.poolLabel);
    if (linked === undefined) throw new Error('the isolated stack did not create its pooled account');
    linked.credential_present = false;
    linked.refusal = refusal;
    await route.fulfill({ response, json: body });
  });

  await page.goto(`${base}/#/needs-you`);
  const item = page.getByRole('row').filter({ hasText: reason });
  await expect(item).toContainText(STACK.poolLabel, { timeout: 15_000 });
  await expect(item.getByRole('button', { name: 'Sign in again' })).toHaveCount(0);
  await expect(page.getByRole('row').filter({ hasText: 'Its login is gone' })).toHaveCount(0);

  await page.goto(`${base}/#/accounts`);
  const row = page.getByRole('row').filter({ hasText: STACK.poolLabel });
  await expect(row.getByText('Refused', { exact: true })).toBeVisible({ timeout: 15_000 });
  await expect(row.getByText('Signed out', { exact: true })).toHaveCount(0);
});
