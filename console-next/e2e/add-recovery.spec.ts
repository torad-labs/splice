// Add opening refusals leave a usable recovery path, including profiles that originally ask nothing.
import { expect, test } from '@playwright/test';
import type { AddView } from '../src/types/add';
import { open } from './support';

const opened: AddView = {
  id: 'synthetic-add-recovery', profile: 'claude', key: 'another', command: 'claude-another',
  auth_kind: 'claude-oauth', base_url: null, models: [], sign_in_by: 'none', key_env: null,
  credential: { present: false, detail: '' }, sign_in: null, checks: null, saved: null,
};

test('taken names and commands reveal editable fields and keep the draft across refusals', async ({ page }) => {
  const writes: unknown[] = [];
  await page.route('**/api/add', (route) => {
    writes.push(route.request().postDataJSON());
    if (writes.length === 1) return route.fulfill({ status: 409, json: { error: 'Pick another name.', field: 'name' } });
    if (writes.length === 2) return route.fulfill({ status: 409, json: { error: 'Pick another command.', field: 'command' } });
    return route.fulfill({ json: opened });
  });
  await page.route('**/api/add/' + opened.id, (route) => route.fulfill({ json: opened }));
  const faults = await open(page, 'fleet');
  await page.getByRole('button', { name: 'Add a command', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: /^claude\s/i }).click();
  const name = dialog.getByRole('textbox', { name: 'Command name', exact: true });
  await expect(name).toBeVisible();
  await expect(dialog.getByText('Opening…', { exact: true })).toHaveCount(0);
  const next = dialog.getByRole('button', { name: 'Continue', exact: true });
  await expect(next).toBeDisabled();
  await name.fill('another');
  await next.click();
  const command = dialog.getByRole('textbox', { name: 'Terminal command', exact: true });
  await expect(command).toBeVisible();
  await expect(name).toHaveValue('another');
  await expect(next).toBeDisabled();
  await command.fill('claude-another');
  await next.click();
  await expect(dialog).toContainText('another · claude-another');
  expect(writes).toEqual([
    { profile: 'claude' },
    { profile: 'claude', name: 'another' },
    { profile: 'claude', name: 'another', command: 'claude-another' },
  ]);
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

test('a failed no-ask opening offers retry and back instead of staying on Opening', async ({ page }) => {
  let attempts = 0;
  await page.route('**/api/add', (route) => {
    attempts += 1;
    return attempts === 1
      ? route.fulfill({ status: 503, json: { error: 'Synthetic opening unavailable.' } })
      : route.fulfill({ json: opened });
  });
  await page.route('**/api/add/' + opened.id, (route) => route.fulfill({ json: opened }));
  const faults = await open(page, 'fleet');
  await page.getByRole('button', { name: 'Add a command', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('button', { name: /^claude\s/i }).click();
  await expect(dialog.getByRole('alert')).toContainText('Synthetic opening unavailable.');
  await expect(dialog.getByText('Opening…', { exact: true })).toHaveCount(0);
  await expect(dialog.getByRole('button', { name: 'Back', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: 'Try again', exact: true }).click();
  await expect(dialog).toContainText('another · claude-another');
  expect(attempts).toBe(2);
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

test('the additional catalogue choices use human labels without replacing the six initial choices', async ({ page }) => {
  const faults = await open(page, 'fleet');
  await page.getByRole('button', { name: 'Add a command', exact: true }).click();
  const dialog = page.getByRole('dialog');
  for (const label of ['DeepSeek', 'Claude', 'API key']) {
    await expect(dialog.getByRole('button', { name: new RegExp('^' + label + '\\s') })).toBeVisible();
  }
  expect(faults.pageErrors).toEqual([]);
});
