// NEW: V4-444 — retained settings writes, instruction previews and loopback-only playground behavior.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { STACK } from './stack';
import { assertHealthy, env, open, read } from './support';

// Writes start background refetches. Keep their fetched bodies alive until every handler settles.
test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: 'wait' });
});

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

test('attention dots are amber in both themes and a healthy Wrapped command is green', async ({ page }, testInfo) => {
  await page.route(url => url.pathname === '/api/doctor', async route => {
    const response = await route.fetch();
    const body = await response.json();
    body.checks = [{ id: 'runtime/synthetic-attention', status: 'warn', detail: 'Synthetic check needs attention.' }];
    await route.fulfill({ response, json: body });
  });
  await page.route(url => url.pathname === '/api/claude-head', async route => {
    const response = await route.fetch();
    const body = await response.json();
    await route.fulfill({ response, json: { ...body, mode: 'wrapped' } });
  });
  for (const path of ['settings/health', 'settings/tools']) {
    const faults = await open(page, path);
    const sheet = page.locator('.settings-sheet:not([hidden])');
    const words = path === 'settings/health' ? ['Mostly good', 'Worth a look'] : ['Wrapped'];
    for (const width of [1536, 393]) {
      await page.setViewportSize({ width, height: 1024 });
      for (const theme of ['Day', 'Night']) {
        await page.getByRole('button', { name: theme, exact: true }).click();
        const color = path === 'settings/health'
          ? theme === 'Day' ? 'rgb(125, 84, 25)' : 'rgb(231, 192, 105)'
          : theme === 'Day' ? 'rgb(44, 101, 85)' : 'rgb(97, 190, 163)';
        for (const word of words) {
          const state = sheet.locator('.state').filter({ hasText: new RegExp('^' + word + '$') });
          await expect(state).toHaveCount(1);
          await expect(state.locator('i')).toHaveCSS('background-color', color);
        }
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await sheet.screenshot({ path: testInfo.outputPath(path.split('/')[1] + '-attention-' + width + '-' + theme.toLowerCase() + '.png') });
      }
    }
    await assertHealthy(page, faults);
  }
});

test('Compaction report labels stand above complete single-line recent records', async ({ page }, testInfo) => {
  const at = Date.now();
  await page.route(url => url.pathname === '/api/compact', route => route.fulfill({ json: { stats: {
    total: 3, by_outcome: { model_text: 1, model_thinking: 1, stream_error: 1 },
    tail: [
      { head: STACK.oauthHead, ts: at, outcome: 'model_thinking', ms: 33_456 },
      { head: STACK.soloHead, ts: at - 1000, outcome: 'stream_error', ms: 111_111 },
      { head: STACK.keyHead, ts: at - 2000, outcome: 'model_text', ms: 10_000 },
    ],
  } } }));
  const faults = await open(page, 'settings/conversation');
  const block = page.getByRole('region', { name: 'Compaction', exact: true });
  await expect(block).toContainText('Summary from reasoning');
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 1024 });
    // Check the data geometry, not an implementation class: the rejected list puts durations
    // on a second line and its Recent label beside the middle of the records.
    const record = block.locator('tr, li').filter({ hasText: STACK.oauthHead }).filter({ hasText: 'Summary from reasoning' });
    await expect(record).toHaveCount(1);
    const geometry = await record.evaluate(element => {
      const values = [...element.children].map(child => child.getBoundingClientRect());
      const centres = values.map(value => value.y + value.height / 2);
      const texts = [...element.children].map(child => {
        const walker = document.createTreeWalker(child, NodeFilter.SHOW_TEXT);
        const tops: number[] = [];
        for (let text = walker.nextNode(); text !== null; text = walker.nextNode()) {
          if (text.textContent?.trim() === '') continue;
          const range = document.createRange();
          range.selectNodeContents(text);
          tops.push(...[...range.getClientRects()].map(rect => rect.y));
        }
        return tops;
      });
      return { count: values.length, spread: Math.max(...centres) - Math.min(...centres), lines: texts.map(tops => Math.max(...tops) - Math.min(...tops)), top: element.getBoundingClientRect().top };
    });
    expect(geometry.count).toBe(4);
    expect(geometry.spread).toBeLessThan(2);
    for (const spread of geometry.lines) expect(spread).toBeLessThan(2);
    const recentLabel = block.getByText('Recent', { exact: true });
    const label = await recentLabel.boundingBox();
    expect((label?.y ?? 0) + (label?.height ?? 0)).toBeLessThanOrEqual(geometry.top);
    const ended = block.getByRole('heading', { name: 'How they ended', exact: true });
    const labelBottom = await ended.evaluate(element => element.getBoundingClientRect().bottom);
    const valuesTop = await block.getByText('Summary written', { exact: true }).first().evaluate(element => element.getBoundingClientRect().top);
    expect(labelBottom).toBeLessThan(valuesTop);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await block.screenshot({ path: testInfo.outputPath('compaction-report-' + width + '.png') });
  }
  const recent = block.getByRole('region', { name: 'Recent compactions', exact: true });
  await recent.focus();
  await expect(recent).toBeFocused();
  const before = await recent.evaluate(element => element.scrollLeft);
  await page.keyboard.press('ArrowRight');
  await expect.poll(() => recent.evaluate(element => element.scrollLeft)).toBeGreaterThan(before);
  await expect(block.getByRole('table', { name: 'Recent', exact: true })).toBeVisible();
  await assertHealthy(page, faults);
});

test('saved login copies remain listed when their last selection is not recorded', async ({ page }, testInfo) => {
  let selected: string | null = null;
  await page.route(url => url.pathname === '/api/claude-head', async route => {
    const response = await route.fetch();
    const body = await response.json();
    await route.fulfill({ response, json: { ...body, claude_logins: {
      count: 2, selected, labels: ['synthetic-saved', 'second-synthetic'], constraint: '',
    } } });
  });
  const faults = await open(page, 'settings/tools');
  const copies = page.locator('.row').filter({ has: page.getByRole('heading', { name: 'Saved Claude login copies', exact: true }) });
  await expect(copies).toContainText('synthetic-saved, second-synthetic');
  const choice = page.locator('.row').filter({ has: page.getByRole('heading', { name: 'Last saved copy selection', exact: true }) });
  await expect(choice).toContainText('No selection recorded');
  await expect(page.getByRole('main')).not.toContainText('No label recorded');
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 1024 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('saved-copy-selection-' + width + '.png'), fullPage: true });
  }
  selected = 'second-synthetic';
  await page.reload();
  await expect(choice).toContainText('second-synthetic');
  await expect(choice).not.toContainText('No selection recorded');
  await expect(copies).toContainText('synthetic-saved, second-synthetic');
  await assertHealthy(page, faults);
});

test('settings teardown lets an active route finish reading its fetched body', async ({ page }) => {
  let fetched = false;
  await page.route('**/api/topology', async route => {
    const response = await route.fetch();
    fetched = true;
    // End the journey while this handler still owns a fetched response, as a save refetch can.
    await new Promise(resolve => setTimeout(resolve, 500));
    const body = await response.json();
    await route.fulfill({ response, json: body });
  });
  await open(page, 'settings/advanced');
  await expect.poll(() => fetched).toBe(true);
});

test('the warning slider makes its chosen percentage visibly selected', async ({ page }) => {
  await page.route(url => url.pathname === '/api/config', async route => {
    const response = await route.fetch();
    const body = await response.json();
    await route.fulfill({ response, json: { ...body, effective: { ...body.effective, usageWarnPct: 80 } } });
  });
  const faults = await open(page, 'settings/general');
  const slider = page.getByRole('slider', { name: 'Warn me when a command is this full', exact: true });
  await expect(slider).toHaveValue('80');
  await expect(page.locator('.slider b')).toHaveText('Selected: 80%');
  await expect(page.locator('.slider small')).toContainText('50%');
  await expect(page.locator('.slider small')).toContainText('100%');
  await page.screenshot({ path: 'captures/console-walk-oct3/login-coherence-settings-wide.png' });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator('.slider b')).toHaveText('Selected: 80%');
  expect(await page.locator('.slider').evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
  await page.screenshot({ path: 'captures/console-walk-oct3/login-coherence-settings-narrow.png', fullPage: true });
  await assertHealthy(page, faults);
});

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
  await expect(conversation.getByRole('heading', { name: 'System prompt for a command', exact: true })).toBeVisible();
  await expect(conversation.getByRole('heading', { name: 'Compaction instructions in effect', exact: true })).toBeVisible();
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 980 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await conversation.screenshot({ path: test.info().outputPath('instruction-purposes-' + width + '.png') });
  }
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
