// NEW: V4-444 — a retained account renews under its original label and preserves a refused retry.
import { expect, test } from '@playwright/test';
import type { AccountsWire } from '../src/types/accounts';
import type { LoginStatusPayload } from '../src/types/login';
import { open } from './support';
import { STACK } from './stack';

const refusal = 'Synthetic linked credential cannot use work; sign in as synthetic-work-2 instead.';
const status = (id: string, state: LoginStatusPayload['state'], over: Partial<LoginStatusPayload> = {}): LoginStatusPayload => ({
  id, head: STACK.oauthHead, state, user_code: null, verification_uri: null, browser_url: null,
  failure_reason: null, label: null, usage_set_aside: null, ...over,
});

test('Accounts renews a missing pooled login under its retained label and prints a whole refused retry', async ({ page }) => {
  await page.route('**/api/accounts', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as AccountsWire;
    const orphan = body.accounts.find((account) => account.label === STACK.poolLabel);
    if (orphan === undefined) throw new Error('synthetic pool is missing its retained account');
    orphan.credential_present = false;
    orphan.display_name = 'Synthetic work login';
    await route.fulfill({ response, json: body });
  });
  const labels: string[] = [];
  await page.route((url) => url.pathname === '/api/auth/' + STACK.oauthHead + '/login', (route) => {
    labels.push((route.request().postDataJSON() as { label: string }).label);
    return route.fulfill({ json: labels.length === 1 ? status('synthetic-renew-1', 'starting')
      : status('synthetic-renew-2', 'failed', { failure_reason: refusal }) });
  });
  await page.route('**/api/auth/' + STACK.oauthHead + '/login/synthetic-renew-1', (route) => route.fulfill({ json:
    status('synthetic-renew-1', 'live_after_restart', {
      label: STACK.poolLabel, usage_set_aside: '/synthetic/quota.json.orphaned',
    }),
  }));
  const faults = await open(page, 'accounts');
  const item = page.locator('li.account-card').filter({ has: page.getByRole('heading', { name: 'Synthetic work login', exact: true }) });
  await expect(item).toContainText('Synthetic work login');
  await item.getByRole('button', { name: 'Sign in again', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('textbox', { name: 'Label', exact: true })).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Start login', exact: true }).click();
  await expect(dialog.getByRole('status')).toContainText('Synthetic work login renewed; old usage set aside, read again next request.');
  expect(labels).toEqual([STACK.poolLabel]);
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await item.getByRole('button', { name: 'Sign in again', exact: true }).click();
  await dialog.getByRole('button', { name: 'Start login', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText(refusal);
  await expect(dialog).not.toContainText('old usage set aside');
  expect(labels).toEqual([STACK.poolLabel, STACK.poolLabel]);
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});
