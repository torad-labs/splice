import { expect, test } from '@playwright/test';
import type { ConfigPayload } from '../src/types/core';
import { open, assertHealthy } from './support';
import { STACK } from './stack';

test('Advanced names the key button that reveals a setting on request', async ({ page }) => {
  const faults = await open(page, 'settings/advanced');
  const advanced = page.getByRole('region', { name: 'Advanced', exact: true });
  await expect(advanced).toContainText('use the key button on a row');
  await expect(advanced).not.toContainText('<>');
  await page.getByRole('button', { name: 'Open the full list', exact: true }).click();
  const row = advanced.locator('.row').filter({ has: page.getByRole('heading', { name: 'Max request size', exact: true }) });
  await expect(row.locator('.key code')).toHaveCount(0);
  await row.locator('button.key').click();
  await expect(row.locator('.key code')).toHaveText('maxRequestBytes');
  await assertHealthy(page, faults);
});

test('thinking defaults write to their actual scope and keep saved defaults separate from the running value', async ({ page }) => {
  let globalEffort = 'high';
  const patches: unknown[] = [];
  const writes: Record<string, unknown>[] = [];
  await page.route(url => url.pathname === '/api/config', async route => {
    if (route.request().method() === 'PATCH') {
      const patch = route.request().postDataJSON() as { effort: string };
      patches.push(patch);
      globalEffort = patch.effort;
      return route.fulfill({ json: { applied: patch, rejected: {}, restart_required: [], targets: [], persisted: '/synthetic/state/config.json' } });
    }
    const response = await route.fetch();
    const body = await response.json() as ConfigPayload;
    const head = new URL(route.request().url()).searchParams.get('head');
    body.effective.effort = head === STACK.oauthHead ? 'medium' : globalEffort;
    await route.fulfill({ response, json: body });
  });
  await page.route(url => url.pathname === '/api/topology', async route => {
    if (route.request().method() === 'PUT') {
      writes.push((route.request().postDataJSON() as { topology: Record<string, unknown> }).topology);
      return route.fulfill({ json: { ok: true, restart_required: true, findings: [] } });
    }
    const response = await route.fetch();
    const body = await response.json() as { topology: Record<string, unknown> };
    const heads = body.topology.heads as Record<string, Record<string, unknown>>;
    body.topology = writes.at(-1) ?? { ...body.topology, heads: { ...heads, [STACK.oauthHead]: { ...heads[STACK.oauthHead], overrides: { effort: 'low', maxInflight: '3' } }, [STACK.soloHead]: { ...heads[STACK.soloHead], overrides: { effort: 'medium' } } } };
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'settings/conversation');
  const conversation = page.getByRole('region', { name: 'Conversation', exact: true });
  const scope = conversation.getByRole('button', { name: 'Thinking default for', exact: true });
  const thinking = conversation.getByRole('group', { name: 'How hard the model thinks', exact: true });
  await expect(thinking.getByRole('button', { name: 'High', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(conversation).toContainText('Currently running: High');
  await scope.click();
  await page.getByRole('menuitemradio', { name: STACK.oauthHead, exact: true }).click();
  await expect(thinking.getByRole('button', { name: 'Low', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(conversation).toContainText('Currently running: Medium');
  await thinking.getByRole('button', { name: 'High', exact: true }).click();
  await expect.poll(() => writes.length).toBe(1);
  expect(patches).toHaveLength(0);
  const savedHeads = writes[0]?.heads as Record<string, Record<string, unknown>>;
  expect(savedHeads[STACK.oauthHead]?.overrides).toEqual({ effort: 'high', maxInflight: '3' });
  expect(savedHeads[STACK.soloHead]?.overrides).toEqual({ effort: 'medium' });
  await expect(thinking.getByRole('button', { name: 'High', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(conversation).toContainText('Saved default: High');
  await expect(conversation).toContainText('Currently running: Medium');
  await expect(conversation).toContainText('Applies after a restart');
  await thinking.getByRole('button', { name: 'Use all commands', exact: true }).click();
  await expect.poll(() => writes.length).toBe(2);
  const resetHeads = writes[1]?.heads as Record<string, Record<string, unknown>>;
  expect(resetHeads[STACK.oauthHead]?.overrides).toEqual({ maxInflight: '3' });
  await scope.focus();
  await scope.press('Enter');
  await page.getByRole('menuitemradio', { name: 'All commands', exact: true }).press('Enter');
  await thinking.getByRole('button', { name: 'Low', exact: true }).focus();
  await thinking.getByRole('button', { name: 'Low', exact: true }).press('Enter');
  await expect.poll(() => patches.length).toBe(1);
  expect(patches).toEqual([{ effort: 'low' }]);
  expect(writes).toHaveLength(2);
  await expect(thinking.getByRole('button', { name: 'Low', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await assertHealthy(page, faults);
});

test('a section opens alone and switching sections preserves an unsaved file draft', async ({ page }) => {
  const faults = await open(page, 'settings/advanced');
  await expect(page.locator('.settings-sheet:visible')).toHaveCount(1);
  await page.getByRole('button', { name: 'Open the file', exact: true }).click();
  const group = page.locator('details.cf-group').filter({ has: page.locator('summary').filter({ hasText: /^Compaction/ }) });
  await group.locator('summary').click();
  const draft = group.getByRole('textbox', { name: 'instructions', exact: true }).first();
  await draft.fill('Synthetic draft kept while changing sections.');
  await draft.press('Tab');
  const nav = page.getByRole('navigation', { name: 'Settings sections', exact: true });
  await nav.getByRole('link', { name: 'Conversation', exact: true }).click();
  await expect(page.locator('.settings-sheet:visible')).toHaveCount(1);
  await expect(page.getByRole('region', { name: 'Conversation', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Write the file', exact: true })).toBeHidden();
  await nav.getByRole('link', { name: 'Advanced', exact: true }).focus();
  await nav.getByRole('link', { name: 'Advanced', exact: true }).press('Enter');
  await expect(draft).toHaveValue('Synthetic draft kept while changing sections.');
  await expect(page.locator('.settings-sheet:visible')).toHaveCount(1);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await assertHealthy(page, faults);
});

test('an unavailable command topology disables its write without inventing a global default', async ({ page }) => {
  let puts = 0;
  let patches = 0;
  page.on('request', request => {
    if (request.method() === 'PUT' && new URL(request.url()).pathname === '/api/topology') puts++;
    if (request.method() === 'PATCH' && new URL(request.url()).pathname === '/api/config') patches++;
  });
  await page.route(url => url.pathname === '/api/topology', route => route.fulfill({ json: { pending: 'synthetic-unavailable' } }));
  await open(page, 'settings/conversation');
  const conversation = page.getByRole('region', { name: 'Conversation', exact: true });
  await conversation.getByRole('button', { name: 'Thinking default for', exact: true }).click();
  await page.getByRole('menuitemradio', { name: STACK.oauthHead, exact: true }).click();
  await expect(conversation).toContainText('a command’s own value cannot be written from here');
  await expect(conversation.getByRole('group', { name: 'How hard the model thinks', exact: true })).toHaveCount(0);
  expect(puts).toBe(0);
  expect(patches).toBe(0);
});
