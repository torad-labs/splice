import { expect, test } from '@playwright/test';
import type { ConfigPayload } from '../src/types/core';
import { open, assertHealthy } from './support';
import { STACK } from './stack';

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: 'wait' });
});

test('Advanced model pickers save exact IDs, preserve unlisted folds and target the selected command', async ({ page }) => {
  const patches: Record<string, unknown>[] = [];
  const writes: Record<string, unknown>[] = [];
  let holdNextPatch = false;
  let releasePatch = (): void => { throw new Error('no synthetic patch is held'); };
  let holdNextConfigRead = false;
  let configReadHeld = false;
  let releaseConfigRead = (): void => { throw new Error('no synthetic config read is held'); };
  let holdNextTopologyRead = false;
  let topologyReadHeld = false;
  let releaseTopologyRead = (): void => { throw new Error('no synthetic topology read is held'); };
  const effective = { pinnedModel: 'synthetic-unlisted-current', grokModel: 'synthetic-grok-a', foldReasoningModels: 'synthetic-codex-a,synthetic-unlisted-fold' };
  const holdPatch = async (): Promise<void> => {
    if (!holdNextPatch) return;
    holdNextPatch = false;
    await new Promise<void>(resolve => { releasePatch = resolve; });
  };
  await page.route(url => url.pathname === '/api/models', route => route.fulfill({ json: { heads: [
    { head: STACK.oauthHead, provider: 'synthetic-codex-provider', pinned_model: 'synthetic-codex-a', models: [{ id: 'synthetic-codex-a', label: 'Synthetic ChatGPT A' }, { id: 'synthetic-codex-b', label: 'Synthetic ChatGPT B' }] },
    { head: STACK.soloHead, provider: 'grok', pinned_model: 'synthetic-grok-a', models: [{ id: 'synthetic-grok-a', label: 'Synthetic Grok A' }, { id: 'synthetic-grok-b', label: 'Synthetic Grok B' }] },
  ] } }));
  await page.route(url => url.pathname === '/api/config', async route => {
    if (route.request().method() === 'PATCH') {
      const patch = route.request().postDataJSON() as Record<string, unknown>;
      patches.push(patch);
      await holdPatch();
      Object.assign(effective, patch);
      return route.fulfill({ json: { applied: patch, rejected: {}, restart_required: [], targets: [], persisted: '/synthetic/config.json' } });
    }
    if (holdNextConfigRead) {
      holdNextConfigRead = false;
      configReadHeld = true;
      await new Promise<void>(resolve => { releaseConfigRead = resolve; });
    }
    const response = await route.fetch();
    const body = await response.json() as ConfigPayload;
    Object.assign(body.effective, effective);
    Object.assign(body.layers.runtime, effective);
    await route.fulfill({ response, json: body });
  });
  await page.route(url => url.pathname === '/api/topology', async route => {
    if (route.request().method() === 'PUT') {
      writes.push((route.request().postDataJSON() as { topology: Record<string, unknown> }).topology);
      return route.fulfill({ json: { ok: true, restart_required: true, findings: [] } });
    }
    if (holdNextTopologyRead) {
      holdNextTopologyRead = false;
      topologyReadHeld = true;
      await new Promise<void>(resolve => { releaseTopologyRead = resolve; });
    }
    const response = await route.fetch();
    const body = await response.json() as { topology: Record<string, unknown> };
    const heads = body.topology.heads as Record<string, Record<string, unknown>>;
    body.topology = writes.at(-1) ?? { ...body.topology, heads: { ...heads, [STACK.soloHead]: { ...heads[STACK.soloHead], overrides: { effort: 'low' } } } };
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'settings/advanced');
  await page.getByRole('button', { name: 'Open the full list', exact: true }).click();
  const chatgpt = page.getByRole('button', { name: 'ChatGPT model', exact: true });
  const grok = page.getByRole('button', { name: 'Grok model', exact: true });
  await expect(chatgpt).toContainText('synthetic-unlisted-current');
  await expect(grok).toContainText('Synthetic Grok A');
  const folds = page.locator('.row').filter({ has: page.getByRole('heading', { name: 'Fold models', exact: true }) });
  await expect(folds).toContainText('synthetic-unlisted-fold');
  await expect(folds.getByRole('textbox')).toHaveCount(0);
  await chatgpt.click();
  await page.getByRole('menuitemradio', { name: 'Synthetic ChatGPT B', exact: true }).click();
  await expect.poll(() => patches.at(-1)).toEqual({ pinnedModel: 'synthetic-codex-b' });
  await folds.getByRole('button', { name: 'Add a fold model', exact: true }).click();
  await page.getByRole('menuitemradio', { name: 'Synthetic ChatGPT B', exact: true }).click();
  await expect.poll(() => patches.at(-1)).toEqual({ foldReasoningModels: 'synthetic-codex-a,synthetic-unlisted-fold,synthetic-codex-b' });
  await folds.getByRole('button', { name: 'Remove synthetic-unlisted-fold from fold models', exact: true }).click();
  await expect.poll(() => patches.at(-1)).toEqual({ foldReasoningModels: 'synthetic-codex-a,synthetic-codex-b' });
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 980 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await folds.scrollIntoViewIfNeeded();
    await page.screenshot({ path: test.info().outputPath('advanced-models-' + width + '.png') });
  }
  holdNextPatch = true;
  holdNextConfigRead = true;
  await folds.getByRole('button', { name: 'Remove Synthetic ChatGPT A from fold models', exact: true }).click();
  await expect.poll(() => patches.at(-1)).toEqual({ foldReasoningModels: 'synthetic-codex-b' });
  const lastRemove = folds.getByRole('button', { name: 'Remove Synthetic ChatGPT B from fold models', exact: true });
  try {
    await expect(lastRemove).toBeDisabled();
  } finally {
    releasePatch();
  }
  try {
    await expect.poll(() => configReadHeld).toBe(true);
    await expect(lastRemove).toBeDisabled();
  } finally {
    releaseConfigRead();
  }
  await expect(lastRemove).toBeEnabled();
  await folds.getByRole('button', { name: 'Remove Synthetic ChatGPT B from fold models', exact: true }).click();
  await expect.poll(() => patches.at(-1)).toEqual({ foldReasoningModels: ',' });
  await expect(folds).toContainText('No models are folded.');
  const patchCount = patches.length;
  await page.getByRole('button', { name: 'Which command', exact: true }).click();
  await page.getByRole('menuitemradio', { name: STACK.soloHead, exact: true }).click();
  holdNextTopologyRead = true;
  await grok.click();
  await page.getByRole('menuitemradio', { name: 'Synthetic Grok B', exact: true }).click();
  await expect.poll(() => writes.length).toBe(1);
  const saved = writes[0]?.heads as Record<string, Record<string, unknown>>;
  expect(saved[STACK.soloHead]?.overrides).toEqual({ effort: 'low', grokModel: 'synthetic-grok-b' });
  try {
    await expect.poll(() => topologyReadHeld).toBe(true);
    await expect(grok).toBeDisabled();
    await expect(folds.getByRole('button', { name: 'Add a fold model', exact: true })).toBeDisabled();
  } finally {
    releaseTopologyRead();
  }
  await expect(grok).toBeEnabled();
  await expect(grok).toContainText('Synthetic Grok B');
  const grokRow = page.locator('.row').filter({ has: page.getByRole('heading', { name: 'Grok model', exact: true }) });
  await expect(grokRow).toContainText('Current configuration: Synthetic Grok A.');
  await expect(grokRow).toContainText('The all-command setting takes precedence');
  await folds.getByRole('button', { name: 'Add a fold model', exact: true }).click();
  await page.getByRole('menuitemradio', { name: 'Synthetic ChatGPT B', exact: true }).click();
  await expect.poll(() => writes.length).toBe(2);
  await expect(folds.getByRole('button', { name: 'Remove Synthetic ChatGPT B from fold models', exact: true })).toBeEnabled();
  const withFold = writes[1]?.heads as Record<string, Record<string, unknown>>;
  expect(withFold[STACK.soloHead]?.overrides).toEqual({ effort: 'low', grokModel: 'synthetic-grok-b', foldReasoningModels: 'synthetic-codex-b' });
  await grokRow.getByRole('button', { name: 'Enter a model ID', exact: true }).click();
  const custom = grokRow.getByRole('textbox', { name: 'Custom model ID', exact: true });
  await custom.fill('synthetic/private:model');
  await custom.press('Enter');
  await expect.poll(() => writes.length).toBe(3);
  await expect(grok).toBeEnabled();
  await expect(grok).toContainText('synthetic/private:model');
  await grokRow.getByRole('button', { name: 'Use the value for all commands', exact: true }).click();
  await expect.poll(() => writes.length).toBe(4);
  await expect(grok).toBeEnabled();
  await expect(grok).toContainText('Synthetic Grok A');
  const reset = writes[3]?.heads as Record<string, Record<string, unknown>>;
  expect(reset[STACK.soloHead]?.overrides).toEqual({ effort: 'low', foldReasoningModels: 'synthetic-codex-b' });
  expect(patches).toHaveLength(patchCount);
  await assertHealthy(page, faults);
  await page.unrouteAll({ behavior: 'wait' });
});
