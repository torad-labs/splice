// V4-357: a retained-usage account renews under its old label, or hears why it cannot.
import { expect, test } from '@playwright/test';
import type { LoginStatusPayload } from '../../src/entities/auth';
import { STACK } from '../stack';

const refusal = 'Linked credential cannot use work; sign in as work-2 instead.';
const status = (id: string, state: LoginStatusPayload['state'], over: Partial<LoginStatusPayload> = {}): LoginStatusPayload => ({
  id, head: STACK.oauthHead, state, user_code: null, verification_uri: null, browser_url: null,
  failure_reason: null, label: null, usage_set_aside: null, ...over,
});

test('Needs you renews an orphaned account under its label and names a refusal', async ({ page }) => {
  const base = process.env.CONSOLE_E2E_BASE;
  const key = process.env.CONSOLE_E2E_KEY;
  if (!base || !key) throw new Error('the isolated console stack did not start');
  await page.addInitScript(([storage, value]) => localStorage.setItem(storage, value), ['myx-mgmt-key', key]);
  await page.route('**/api/accounts', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as { accounts: { label: string | null; credential_present: boolean }[] };
    const orphan = body.accounts.find((account) => account.label === STACK.poolLabel);
    if (orphan === undefined) throw new Error('the isolated stack did not create its pooled account');
    orphan.credential_present = false;
    await route.fulfill({ response, json: body });
  });
  const requested: string[] = [];
  await page.route((url) => url.pathname === `/api/auth/${STACK.oauthHead}/login`, (route) => {
    const body = route.request().postDataJSON() as { label: string };
    requested.push(body.label);
    return route.fulfill({ json: requested.length === 1 ? status('renew-1', 'starting')
      : status('renew-2', 'failed', { failure_reason: refusal }) });
  });
  await page.route((url) => url.pathname === `/api/auth/${STACK.oauthHead}/login/renew-1`, (route) => route.fulfill({
    json: status('renew-1', 'live_after_restart', {
      label: STACK.poolLabel, usage_set_aside: '/synthetic/work-quota.json.orphaned-20260928-110000',
    }),
  }));

  await page.goto(`${base}/#/needs-you`);
  const item = page.getByRole('row').filter({ hasText: 'Its login is gone' });
  await expect(item).toContainText(STACK.poolLabel, { timeout: 15_000 });
  await item.getByRole('button', { name: 'Sign in again' }).click();
  await expect(item.getByRole('textbox', { name: 'Label' })).toHaveValue(STACK.poolLabel);
  await item.getByRole('button', { name: 'Start login' }).click();
  await expect(item.getByRole('status')).toContainText('old usage set aside', { timeout: 10_000 });
  await expect(item.getByRole('status')).toContainText('next request');
  expect(requested).toEqual([STACK.poolLabel]);

  await item.getByRole('button', { name: 'Cancel' }).click();
  await expect(item.getByRole('textbox', { name: 'Label' })).toHaveValue(STACK.poolLabel);
  await item.getByRole('button', { name: 'Start login' }).click();
  await expect(item.getByRole('status')).toContainText('work-2');
  await expect(item.getByRole('status')).not.toContainText('old usage set aside');
  expect(requested).toEqual([STACK.poolLabel, STACK.poolLabel]);
});
