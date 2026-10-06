// NEW: V4-444 — the Playground sends one prompt to several models through the isolated stack and lays the answers side by side. The
// persona walk found "This page is being rebuilt" here while a working one-command form sat in Settings › Health with no pointer to it.
import { expect, test } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import type { HeadsPayload } from '../src/types/core';
import { assertHealthy, open, read } from './support';
import { STACK } from './stack';

const PROMPT = 'one synthetic prompt from the console e2e';
/** Not the solo head's pinned model, so an upstream body that names it can only have come from the lane. */
const NAMED = 'e2e-named-model';
const hash = (url: string): string => decodeURIComponent(new URL(url).hash);

for (const width of [1440, 390]) {
  test('Playground field tops align with custom model controls closed and open at ' + width, async ({ page }) => {
    await page.setViewportSize({ width, height: 1024 });
    const faults = await open(page, 'playground?try=' + STACK.oauthHead);
    const lane = page.getByRole('list', { name: 'Answers', exact: true }).getByRole('listitem');
    const command = lane.getByRole('button', { name: 'Command 1', exact: true });
    const model = lane.getByRole('button', { name: 'Model', exact: true });
    await expect(command).toBeVisible();
    await expect(model).toBeVisible();
    const measurements = [];
    for (const custom of [false, true]) {
      if (custom) await lane.getByRole('button', { name: 'Enter a model ID', exact: true }).click();
      const a = await command.boundingBox();
      const b = await model.boundingBox();
      if (a === null || b === null) throw new Error('a selector has no measured rectangle');
      const contains = await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth);
      measurements.push({ width, custom, command: a, model: b, topDelta: b.y - a.y, contains });
      await lane.screenshot({ path: test.info().outputPath('alignment-' + width + '-' + custom + '.png') });
    }
    const geometry = test.info().outputPath('field-geometry.json');
    writeFileSync(geometry, JSON.stringify(measurements, null, 2));
    await test.info().attach('field-geometry.json', { path: geometry, contentType: 'application/json' });
    for (const measurement of measurements) {
      expect(measurement.contains).toBe(true);
      if (width === 1440) expect(Math.abs(measurement.topDelta)).toBeLessThanOrEqual(1);
      else expect(measurement.command.y + measurement.command.height).toBeLessThanOrEqual(measurement.model.y);
    }
    await assertHealthy(page, faults);
    await page.unrouteAll({ behavior: 'wait' });
  });
}

test('catalog labels select their exact model ID while command defaults and custom models remain available', async ({ page }) => {
  await page.route(url => url.pathname === '/api/models', route => route.fulfill({ json: { heads: [{
    head: STACK.oauthHead, provider: 'codex', pinned_model: STACK.model,
    models: [{ id: 'synthetic-selected-model', label: 'Synthetic selected model', description: '', slot: null, context_window: 100000, context_window_source: 'synthetic', pinned: false, resolved: true }],
  }] } }));
  const faults = await open(page, `playground?try=${STACK.oauthHead}`);
  const lane = page.getByRole('list', { name: 'Answers', exact: true }).getByRole('listitem');
  const picker = lane.getByRole('button', { name: 'Model', exact: true });
  await expect(picker).toContainText('The pinned model, ' + STACK.model);
  await expect(lane.getByRole('textbox', { name: 'Custom model ID', exact: true })).toHaveCount(0);
  await picker.click();
  await page.getByRole('menuitemradio', { name: 'Synthetic selected model', exact: true }).click();
  await expect.poll(() => hash(page.url())).toBe(`#/playground?try=${STACK.oauthHead}:synthetic-selected-model`);
  await expect(picker).toContainText('Synthetic selected model');
  await page.reload();
  await expect(picker).toContainText('Synthetic selected model');
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 980 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const command = lane.getByRole('button', { name: 'Command 1', exact: true });
    const commandBounds = await command.boundingBox();
    const modelBounds = await picker.boundingBox();
    if (commandBounds === null || modelBounds === null) throw new Error('a model picker lost its layout');
    expect(commandBounds.x + commandBounds.width <= modelBounds.x || commandBounds.y + commandBounds.height <= modelBounds.y).toBe(true);
    await expect(lane).toHaveAttribute('aria-label', 'Synthetic selected model');
    await page.screenshot({ path: test.info().outputPath('playground-picker-' + width + '.png'), fullPage: true });
  }
  await picker.click();
  await page.getByRole('menuitemradio', { name: 'The pinned model, ' + STACK.model, exact: true }).click();
  await expect.poll(() => hash(page.url())).toBe(`#/playground?try=${STACK.oauthHead}`);
  await lane.getByRole('button', { name: 'Enter a model ID', exact: true }).click();
  await lane.getByRole('textbox', { name: 'Custom model ID', exact: true }).fill('synthetic/custom:model');
  await lane.getByRole('textbox', { name: 'Custom model ID', exact: true }).press('Enter');
  await expect.poll(() => hash(page.url())).toBe(`#/playground?try=${STACK.oauthHead}:synthetic/custom:model`);
  await expect(picker).toContainText('synthetic/custom:model');
  await assertHealthy(page, faults);
  await page.unrouteAll({ behavior: 'wait' });
});

test('one prompt reaches every lane, a named model is the one sent upstream, and a lane that cannot run fails alone', async ({ page }) => {
  const sent: unknown[] = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/playground') sent.push(request.postDataJSON());
  });
  const faults = await open(page, `playground?try=${STACK.oauthHead}&try=${STACK.soloHead}:${NAMED}&try=${STACK.keyHead}`);
  const lanes = page.getByRole('list', { name: 'Answers', exact: true }).getByRole('listitem');
  await expect(lanes).toHaveCount(3);
  const main = page.getByRole('main');
  await main.getByLabel('Prompt', { exact: true }).fill(PROMPT);
  await main.getByRole('button', { name: 'Send', exact: true }).click();
  for (const at of [0, 1]) {
    const lane = lanes.nth(at);
    await expect(lane).toContainText('console e2e answer', { timeout: 30_000 });
    await expect(lane).toContainText('Answered with 200');
    await expect(lane).toContainText('12 tokens in, 4 out');
    await lane.getByText('The request and the reply', { exact: true }).click();
    await expect(lane.getByRole('region', { name: 'What splice sent', exact: true })).toContainText('/responses');
    await expect(lane.getByRole('region', { name: 'What splice sent', exact: true })).toContainText(PROMPT);
  }
  await expect(lanes.nth(1).getByRole('region', { name: 'What splice sent', exact: true })).toContainText(`"model": "${NAMED}"`);
  await expect(lanes.nth(2).getByRole('alert')).toContainText('That did not run:', { timeout: 30_000 });
  expect(sent).toHaveLength(3);
  expect(sent).toEqual(expect.arrayContaining([
    { head: STACK.oauthHead, prompt: PROMPT },
    { head: STACK.soloHead, prompt: PROMPT, model: NAMED },
    { head: STACK.keyHead, prompt: PROMPT },
  ]));
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual(['502 /api/playground']);
});

test('Health opens the Playground, and an added lane and a named model live in the address through a reload', async ({ page }) => {
  const heads = await read<HeadsPayload>(page, '/api/heads');
  const [first, second, third] = heads.heads.filter((head) => head.authKind !== 'client').map((head) => head.key);
  if (first === undefined || second === undefined || third === undefined) throw new Error('the isolated stack needs three commands the Playground can try');
  const faults = await open(page, 'settings/health');
  await page.getByRole('link', { name: 'Open the Playground', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Playground', exact: true })).toBeVisible();
  const lanes = page.getByRole('list', { name: 'Answers', exact: true }).getByRole('listitem');
  await expect(lanes).toHaveCount(2);
  await page.getByRole('button', { name: 'Add a model', exact: true }).click();
  await expect(lanes).toHaveCount(3);
  await lanes.nth(0).getByRole('button', { name: 'Enter a model ID', exact: true }).click();
  const model = lanes.nth(0).getByRole('textbox', { name: 'Custom model ID', exact: true });
  await model.fill('gpt-test-model');
  await model.press('Enter');
  await expect.poll(() => hash(page.url())).toBe(`#/playground?try=${first}:gpt-test-model&try=${second}&try=${third}`);
  await page.reload();
  await expect(lanes).toHaveCount(3);
  await expect(lanes.nth(0)).toHaveAttribute('aria-label', 'gpt-test-model');
  await expect(lanes.nth(0).getByRole('button', { name: 'Model', exact: true })).toContainText('gpt-test-model');
  await assertHealthy(page, faults);
});
