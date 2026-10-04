// NEW: V4-444 — retained settings writes, instruction previews and loopback-only playground behavior.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { STACK } from './stack';
import { env, open, read } from './support';

async function pick(page: Page, scope: Locator, label: string, value: string) {
  await scope.getByRole('button', { name: label, exact: true }).click();
  await page.getByRole('menuitemradio', { name: value, exact: true }).click();
}

async function topologyWrites(page: Page) {
  const writes: Record<string, unknown>[] = [];
  await page.route('**/api/topology', async (route) => {
    if (route.request().method() === 'PUT') {
      writes.push((route.request().postDataJSON() as { topology: Record<string, unknown> }).topology);
      return route.fulfill({ json: { ok: true, restart_required: true, findings: [] } });
    }
    const response = await route.fetch();
    const body = await response.json() as { topology: Record<string, unknown> };
    body.topology = writes.at(-1) ?? { ...body.topology, compaction: {
      instructions: 'Original synthetic compaction instructions.',
      model: [{ model: 'synthetic-model', instructions: 'Keep this model concise.' }],
    } };
    return route.fulfill({ response, json: body });
  });
  return writes;
}

test('the configuration editor writes compaction instructions without discarding model rules', async ({ page }) => {
  const writes = await topologyWrites(page);
  await open(page, 'settings/advanced');
  await page.getByRole('button', { name: 'Open the file', exact: true }).click();
  const group = page.locator('details.cf-group').filter({ has: page.locator('summary').filter({ hasText: /^Compaction/ }) });
  await group.locator('summary').click();
  const field = group.getByRole('textbox', { name: 'instructions', exact: true }).first();
  await field.fill('Prefer concise answers.');
  await field.press('Tab');
  await page.getByRole('button', { name: 'Write the file', exact: true }).click();
  await expect.poll(() => writes.length).toBe(1);
  expect(writes[0]?.compaction).toEqual({
    instructions: 'Prefer concise answers.',
    model: [{ model: 'synthetic-model', instructions: 'Keep this model concise.' }],
  });
  await expect(page.getByRole('status').filter({ hasText: 'Written.' })).toBeVisible();
});

test('a written configuration file says so without waiting for the page to re-read its other data', async ({ page }) => {
  await topologyWrites(page);
  let slow = false;
  await page.route('**/api/mcp', async (route) => {
    if (slow) await new Promise((resolve) => setTimeout(resolve, 8_000));
    await route.continue().catch(() => undefined);
  });
  await open(page, 'settings/advanced');
  await page.getByRole('button', { name: 'Open the file', exact: true }).click();
  const group = page.locator('details.cf-group').filter({ has: page.locator('summary').filter({ hasText: /^Compaction/ }) });
  await group.locator('summary').click();
  const field = group.getByRole('textbox', { name: 'instructions', exact: true }).first();
  await field.fill('Prefer concise answers.');
  await field.press('Tab');
  slow = true;
  await page.getByRole('button', { name: 'Write the file', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Written.' })).toBeVisible({ timeout: 5_000 });
  await page.unrouteAll({ behavior: 'ignoreErrors' });
});

test('plan instruction modes preview isolated writes and preserve an unrelated unsaved file draft', async ({ page }) => {
  const writes = await topologyWrites(page);
  await open(page, 'settings/conversation');
  const conversation = page.getByRole('region', { name: 'Conversation', exact: true });
  await pick(page, conversation, 'Command', STACK.oauthHead);
  await page.getByRole('navigation', { name: 'Settings sections', exact: true }).getByRole('link', { name: 'Advanced', exact: true }).click();
  await page.getByRole('button', { name: 'Open the file', exact: true }).click();
  const group = page.locator('details.cf-group').filter({ has: page.locator('summary').filter({ hasText: /^Compaction/ }) });
  await group.locator('summary').click();
  const pending = group.getByRole('textbox', { name: 'instructions', exact: true }).first();
  await pending.fill('Pending only in the file editor.');
  await pending.press('Tab');
  await page.getByRole('navigation', { name: 'Settings sections', exact: true }).getByRole('link', { name: 'Conversation', exact: true }).click();
  await conversation.getByRole('button', { name: 'Add instructions', exact: true }).click();
  const preview = conversation.getByRole('region', { name: 'What splice will use', exact: true });
  const save = conversation.getByRole('button', { name: 'Save', exact: true });
  await expect(save).toBeDisabled();
  await conversation.getByRole('textbox', { name: 'Instructions', exact: true }).fill('Keep answers brief.');
  await expect(preview).toContainText('splice adds this after Claude Code’s own instructions.');
  await expect(preview).toContainText('Keep answers brief.');
  await save.click();
  await expect(conversation.getByRole('status')).toHaveText('Saved. It applies after splice restarts.');
  await expect.poll(() => writes.length).toBe(1);
  const savedHead = () => (writes.at(-1)?.heads as Record<string, Record<string, unknown>>)[STACK.oauthHead];
  expect(savedHead()).toMatchObject({ system_prompt: 'Keep answers brief.', system_prompt_mode: 'append' });
  await conversation.getByRole('button', { name: 'Replace', exact: true }).click();
  await expect(preview).toContainText('This replaces Claude Code’s own instructions.');
  await expect(preview).toContainText('That takes away Claude Code’s operating instructions');
  await save.click();
  await expect(conversation.getByRole('status')).toHaveText('Saved. It applies after splice restarts.');
  await expect.poll(() => writes.length).toBe(2);
  expect(savedHead()).toMatchObject({ system_prompt: 'Keep answers brief.', system_prompt_mode: 'replace' });
  await conversation.getByRole('button', { name: 'Take out', exact: true }).click();
  await conversation.getByRole('textbox', { name: 'Lines to take out', exact: true }).fill('^Private note');
  await expect(preview).toContainText('Paragraphs that match these lines are taken out');
  await save.click();
  await expect(conversation.getByRole('status')).toHaveText('Saved. It applies after splice restarts.');
  await expect.poll(() => writes.length).toBe(3);
  expect(savedHead()).toMatchObject({ system_prompt: '^Private note', system_prompt_mode: 'strip' });
  await conversation.getByRole('button', { name: 'In a file', exact: true }).click();
  await conversation.getByRole('textbox', { name: 'File path', exact: true }).fill('synthetic-missing-rules.txt');
  await expect(preview).toContainText('instruction file does not exist');
  await expect(save).toBeDisabled();
  expect(writes).toHaveLength(3);
  await conversation.getByRole('button', { name: 'Remove instructions', exact: true }).click();
  await save.click();
  await expect(conversation.getByRole('status')).toHaveText('Saved. It applies after splice restarts.');
  await expect.poll(() => writes.length).toBe(4);
  expect(savedHead()).not.toHaveProperty('system_prompt');
  expect(savedHead()).not.toHaveProperty('system_prompt_mode');
  await page.getByRole('navigation', { name: 'Settings sections', exact: true }).getByRole('link', { name: 'Advanced', exact: true }).click();
  await expect(pending).toHaveValue('Pending only in the file editor.');
  expect(writes.every((value) => (value.compaction as { instructions: string }).instructions === 'Original synthetic compaction instructions.')).toBe(true);
});

test('a finished instruction save preserves a newer mode edit made while the write was pending', async ({ page }) => {
  const writes = await topologyWrites(page);
  let release: (() => void) | undefined;
  const held = new Promise<void>((resolve) => { release = resolve; });
  await page.route('**/api/topology', async (route) => {
    if (route.request().method() === 'PUT') await held;
    await route.fallback();
  });
  try {
    const faults = await open(page, 'settings/conversation');
    const conversation = page.getByRole('region', { name: 'Conversation', exact: true });
    await pick(page, conversation, 'Command', STACK.oauthHead);
    await conversation.getByRole('button', { name: 'Add instructions', exact: true }).click();
    await conversation.getByRole('textbox', { name: 'Instructions', exact: true }).fill('Synthetic pending instructions.');
    const saving = page.waitForRequest((request) => new URL(request.url()).pathname === '/api/topology' && request.method() === 'PUT');
    await conversation.getByRole('button', { name: 'Save', exact: true }).click();
    await saving;
    await conversation.getByRole('button', { name: 'Replace', exact: true }).click();
    release?.();
    await expect.poll(() => writes.length).toBe(1);
    await expect(conversation.getByRole('button', { name: 'Replace', exact: true })).toHaveAttribute('aria-pressed', 'true');
    const save = conversation.getByRole('button', { name: 'Save', exact: true });
    await expect(save).toBeEnabled();
    await expect(conversation.getByRole('region', { name: 'What splice will use', exact: true })).toContainText('This replaces Claude Code’s own instructions.');
    await save.click();
    await expect(conversation.getByRole('status')).toHaveText('Saved. It applies after splice restarts.');
    await expect.poll(() => writes.length).toBe(2);
    expect((writes[1]?.heads as Record<string, unknown>)[STACK.oauthHead]).toMatchObject({ system_prompt: 'Synthetic pending instructions.', system_prompt_mode: 'replace' });
    expect(faults.pageErrors).toEqual([]);
  } finally {
    release?.();
    await page.unrouteAll({ behavior: 'wait' });
  }
});

test('a file-backed replacement previews bytes and length before Save without writing the draft', async ({ page }) => {
  const content = 'Synthetic instructions for this plan.\n' + 'Keep the answer precise.\n'.repeat(160);
  const dir = join(dirname(env('CONSOLE_E2E_CONFIG')), 'prompts');
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'preview.md'), content);
  const previews: unknown[] = [];
  let puts = 0;
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    if (path === '/api/topology/preview') previews.push(request.postDataJSON());
    if (path === '/api/topology' && request.method() === 'PUT') puts += 1;
  });
  await open(page, 'settings/conversation');
  const conversation = page.getByRole('region', { name: 'Conversation', exact: true });
  await pick(page, conversation, 'Command', STACK.oauthHead);
  await conversation.getByRole('button', { name: 'Add instructions', exact: true }).click();
  await conversation.getByRole('button', { name: 'Replace', exact: true }).click();
  await conversation.getByRole('button', { name: 'In a file', exact: true }).click();
  await conversation.getByRole('textbox', { name: 'File path', exact: true }).fill('prompts/preview.md');
  const preview = conversation.getByRole('region', { name: 'What splice will use', exact: true });
  await expect(preview).toContainText('Synthetic instructions for this plan.');
  await expect(preview).toContainText(content.length.toLocaleString('en-US') + ' characters');
  await expect(preview).toContainText('That takes away Claude Code’s operating instructions');
  await expect(conversation.getByRole('button', { name: 'Save', exact: true })).toBeEnabled();
  expect(previews).toContainEqual({ head: STACK.oauthHead, file: 'prompts/preview.md', mode: 'replace' });
  expect(puts).toBe(0);
  expect(readFileSync(env('CONSOLE_E2E_CONFIG'), 'utf8')).not.toContain('prompts/preview.md');
});

test('a refused instruction file disables Save and the daemon refuses a direct write byte-identically', async ({ page }) => {
  const config = env('CONSOLE_E2E_CONFIG');
  let puts = 0;
  page.on('request', (request) => {
    if (request.method() === 'PUT' && new URL(request.url()).pathname === '/api/topology') puts += 1;
  });
  await open(page, 'settings/conversation');
  const conversation = page.getByRole('region', { name: 'Conversation', exact: true });
  await pick(page, conversation, 'Command', STACK.oauthHead);
  await conversation.getByRole('button', { name: 'Add instructions', exact: true }).click();
  await conversation.getByRole('button', { name: 'In a file', exact: true }).click();
  await conversation.getByRole('textbox', { name: 'File path', exact: true }).fill('synthetic-missing-instructions.md');
  await expect(conversation.getByRole('region', { name: 'What splice will use' })).toContainText('instruction file does not exist');
  const save = conversation.getByRole('button', { name: 'Save', exact: true });
  await expect(save).toBeDisabled();
  expect(puts).toBe(0);
  const before = readFileSync(config, 'utf8');
  const { topology } = await read<{ topology: { heads: Record<string, Record<string, unknown>> } }>(page, '/api/topology');
  const edited = { ...topology.heads[STACK.oauthHead], system_prompt_file: 'synthetic-missing-instructions.md' } as Record<string, unknown>;
  delete edited.system_prompt;
  topology.heads[STACK.oauthHead] = edited;
  const response = await page.request.put(env('CONSOLE_E2E_BASE') + '/api/topology', {
    headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY') }, data: { topology },
  });
  const answer = await response.json() as { ok: boolean; findings: unknown[] };
  expect(answer.ok).toBe(false);
  expect(answer.findings).toEqual([{ path: 'heads.' + STACK.oauthHead + '.system_prompt_file', message: 'instruction file does not exist' }]);
  expect(readFileSync(config, 'utf8')).toBe(before);
  await conversation.getByRole('textbox', { name: 'File path', exact: true }).fill('');
  await expect(save).toBeDisabled();
});

test('Settings › Health points to the Playground, where trying a command now lives, and no longer holds the form', async ({ page }) => {
  const faults = await open(page, 'settings/health');
  const section = page.getByRole('region', { name: 'Health', exact: true });
  await expect(section.getByRole('link', { name: 'Open the Playground', exact: true })).toHaveAttribute('href', '#/playground');
  await expect(section.getByRole('textbox', { name: 'Prompt', exact: true })).toHaveCount(0);
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});
