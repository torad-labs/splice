// V4-423: the Accounts panel of an account splice cannot serve (a refused link, or no credential file) offers no
// Switch and no Refresh, and a serving account keeps both. Marlin's walk of V4-410 (ef86845f3): the refused row
// still offered both, a switch answered ok and pinned it, and a refresh logged five NoSuchFileException lines per
// click. The row keeps Relabel and Remove, and the account table's own Reason row; the daemon's refusal of the
// switch itself is pinned in features/accounts (v4423).
import { expect, test } from '@playwright/test';
import { STACK } from '../stack';

const refusal = `'${STACK.poolLabel}' is a symbolic link, and splice does not load a linked credential; ` +
  'remove the link and sign in again, or sign in under a different label';

test('a refused account offers no Switch or Refresh, and a serving one keeps both', async ({ page }) => {
  const base = process.env.CONSOLE_E2E_BASE;
  const key = process.env.CONSOLE_E2E_KEY;
  if (!base || !key) throw new Error('the isolated console stack did not start');
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', key]);
  let serving: string | null = null;
  await page.route('**/api/accounts', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as { accounts: { kind: string; label: string | null; credential_present: boolean; refusal?: string | null }[] };
    const linked = body.accounts.find((account) => account.label === STACK.poolLabel);
    if (linked === undefined) throw new Error('the isolated stack did not create its pooled account');
    linked.credential_present = false;
    linked.refusal = refusal;
    serving = body.accounts.find((account) => account.kind === 'chatgpt-oauth' && account.label !== null && account.label !== STACK.poolLabel)?.label ?? null;
    await route.fulfill({ response, json: body });
  });

  await page.goto(`${base}/#/accounts`);
  await page.getByRole('button', { name: `Open account chatgpt-oauth ${STACK.poolLabel}` }).click({ timeout: 15_000 });
  await expect(page.getByRole('button', { name: 'Remove', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Relabel', exact: true }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: `Switch ${STACK.oauthHead}` })).toHaveCount(0);
  await expect(page.getByRole('button', { name: `Refresh ${STACK.oauthHead}` })).toHaveCount(0);

  if (serving === null) throw new Error('the isolated stack has no serving pooled account to compare with');
  await page.getByRole('button', { name: `Open account chatgpt-oauth ${serving}` }).click();
  await expect(page.getByRole('button', { name: `Switch ${STACK.oauthHead}` })).toHaveCount(1);
  await expect(page.getByRole('button', { name: `Refresh ${STACK.oauthHead}` })).toHaveCount(1);
});
