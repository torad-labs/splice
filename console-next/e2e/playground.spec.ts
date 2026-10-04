// NEW: V4-444 — the Playground sends one prompt to several models through the isolated stack and lays the answers side by side. The
// persona walk found "This page is being rebuilt" here while a working one-command form sat in Settings › Health with no pointer to it.
import { expect, test } from '@playwright/test';
import type { HeadsPayload } from '../src/types/core';
import { assertHealthy, open, read } from './support';
import { STACK } from './stack';

const PROMPT = 'one synthetic prompt from the console e2e';
/** Not the solo head's pinned model, so an upstream body that names it can only have come from the lane. */
const NAMED = 'e2e-named-model';
const hash = (url: string): string => decodeURIComponent(new URL(url).hash);

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
  const model = lanes.nth(0).getByLabel('Model', { exact: true });
  await model.fill('gpt-test-model');
  await model.press('Enter');
  await expect.poll(() => hash(page.url())).toBe(`#/playground?try=${first}:gpt-test-model&try=${second}&try=${third}`);
  await page.reload();
  await expect(lanes).toHaveCount(3);
  await expect(lanes.nth(0)).toHaveAttribute('aria-label', 'gpt-test-model');
  await expect(lanes.nth(0).getByLabel('Model', { exact: true })).toHaveValue('gpt-test-model');
  await assertHealthy(page, faults);
});
