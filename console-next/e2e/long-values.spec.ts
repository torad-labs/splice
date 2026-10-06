// NEW: V4-444 — synthetic long-value reachability across the source-derived console page census.
import { expect, test, type Locator, type Page } from '@playwright/test';
import type { ConfigPayload, HeadsPayload } from '../src/types/core';
import { KNOB_META } from '../src/lib/knobs';
import { headOptions } from '../src/lib/config';
import { CURATED_KNOBS, SECTIONS, controlOf } from '../src/lib/settings';
import { STACK } from './stack';
import { KNOB_LABELS } from '../src/lib/words-knobs';
import { ROUTES, assertHealthy, expectWholeValue, open, read, routePath } from './support';

test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: 'wait' });
});

const WIDTHS = [1440, 390] as const;
const valueOf = (prefix: string, suffix = ''): string => prefix + 'x'.repeat(400 - prefix.length - suffix.length) + suffix;
const LONG = {
  path: valueOf('/synthetic-long-path/'),
  url: valueOf('https://synthetic.example.invalid/synthetic-long-url/'),
  model: valueOf('synthetic-long-model-'),
  login: valueOf('synthetic-long-login-', '@example.invalid'),
  token: valueOf('synthetic-long-token-'),
};

// Written dispositions for native single-line inputs, whose exact value is reachable by caret:
// SearchField and LogTab filters; AccountEdits and SignIn names; AddPlan name/command/address/model;
// TeamDialog name/folder/role; Prompt folder; Upgrade release; PlanInstructions file; Alerts webhook;
// generic TextInput knobs and ConfigFile scalars/lists. Passwords remain intentionally masked.
const NATIVE_INPUT = 'native single-line input, value reachable by caret';

/** Strict wrapping controls retain the exact parent-red geometry; native controls prove caret reachability. */
async function controlReachable(field: Locator, nativeDisposition?: string): Promise<void> {
  await field.scrollIntoViewIfNeeded();
  expect(await field.evaluate(element => {
    const box = element.getBoundingClientRect();
    for (let parent = element.parentElement; parent !== null; parent = parent.parentElement) {
      const style = getComputedStyle(parent);
      const clip = parent.getBoundingClientRect();
      if (['hidden', 'clip', 'auto', 'scroll'].includes(style.overflowX) && (box.left < clip.left - 1 || box.right > clip.right + 1)) return `${parent.tagName}.${parent.className}: control ${box.left}..${box.right}, clip ${clip.left}..${clip.right}`;
      if (['hidden', 'clip'].includes(style.overflowY) && (box.top < clip.top - 1 || box.bottom > clip.bottom + 1)) return `${parent.tagName}.${parent.className}: vertical control ${box.top}..${box.bottom}, clip ${clip.top}..${clip.bottom}`;
    }
    return box.left >= -1 && box.right <= innerWidth + 1 ? null : `viewport: control ${box.left}..${box.right}, width ${innerWidth}`;
  }), 'a control must not be cut by an ancestor').toBeNull();
  const tag = await field.evaluate(element => element.tagName);
  const textarea = tag === 'TEXTAREA';
  if (nativeDisposition !== undefined && (tag === 'INPUT' || textarea)) {
    const label = await field.getAttribute('aria-label') ?? await field.locator('xpath=..').innerText();
    test.info().annotations.push({ type: 'disposition', description: `${label}: ${textarea ? 'native textarea, wrapped value reachable by caret' : nativeDisposition}` });
    await field.focus();
    // Filling can leave Blink's cached caret at the end without revealing it. Move the caret, not just its unchanged index.
    await field.press(textarea ? 'Control+Home' : 'Home');
    await field.press(textarea ? 'Control+End' : 'End');
    await expect.poll(() => field.evaluate(async element => {
      await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())));
      const input = element as HTMLInputElement | HTMLTextAreaElement;
      const style = getComputedStyle(input);
      const multiline = input instanceof HTMLTextAreaElement;
      return {
        caretAtEnd: input.selectionStart === input.value.length && input.selectionEnd === input.value.length,
        scrolledToEnd: input.scrollWidth - input.clientWidth - input.scrollLeft <= parseFloat(style.paddingRight) + 2 &&
          (!multiline || input.scrollHeight - input.clientHeight - input.scrollTop <= parseFloat(style.paddingBottom) + 2),
      };
    }), { message: label + ': the end of the native value must be reachable' }).toEqual({ caretAtEnd: true, scrolledToEnd: true });
    await field.press(textarea ? 'Control+Home' : 'Home');
    await expect.poll(() => field.evaluate(element => {
      const input = element as HTMLInputElement | HTMLTextAreaElement;
      const style = getComputedStyle(input);
      return {
        caretAtStart: input.selectionStart === 0 && input.selectionEnd === 0,
        scrolledToStart: input.scrollLeft <= parseFloat(style.paddingLeft) + 1 && input.scrollTop <= parseFloat(style.paddingTop) + 1,
      };
    }), { message: label + ': the start of the native value must be reachable' }).toEqual({ caretAtStart: true, scrolledToStart: true });
    return;
  }
  await expectWholeValue(field);
  // Preserve the original geometry, and additionally use the browser's actual width including letter spacing.
  expect(await field.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
}

/** Every glyph must fit its clip ancestors, unless a visibly scrollable container makes the full value reachable. */
async function textReachable(field: Locator): Promise<void> {
  const result = await field.evaluate(element => {
    const clips = (node: Element): boolean => {
      const style = getComputedStyle(node);
      return ['hidden', 'clip', 'auto', 'scroll'].includes(style.overflowX) || ['hidden', 'clip', 'auto', 'scroll'].includes(style.overflowY);
    };
    const range = document.createRange();
    range.selectNodeContents(element);
    let boxes = [...range.getClientRects()].filter(box => box.width > 0 && box.height > 0).map(({ left, right, top, bottom }) => ({ left, right, top, bottom }));
    for (let ancestor: Element | null = element; ancestor !== null; ancestor = ancestor.parentElement) {
      if (!clips(ancestor)) continue;
      const style = getComputedStyle(ancestor);
      const box = ancestor.getBoundingClientRect();
      const horizontalScroll = ['auto', 'scroll'].includes(style.overflowX) && ancestor.scrollWidth > ancestor.clientWidth + 1;
      const verticalScroll = ['auto', 'scroll'].includes(style.overflowY) && ancestor.scrollHeight > ancestor.clientHeight + 1;
      // A non-hidden native scrollbar is the affordance; do not accept a suppressed scroll track.
      const track = getComputedStyle(ancestor, '::-webkit-scrollbar');
      const trackVisible = style.scrollbarWidth !== 'none' && track.display !== 'none';
      const horizontalTrack = trackVisible && track.height !== '0px';
      const verticalTrack = trackVisible && track.width !== '0px';
      const outsideX = boxes.some(glyph => glyph.left < box.left - 1 || glyph.right > box.right + 1);
      const outsideY = boxes.some(glyph => glyph.top < box.top - 1 || glyph.bottom > box.bottom + 1);
      if (outsideX && !(horizontalScroll && horizontalTrack)) return false;
      if (outsideY && !(verticalScroll && verticalTrack)) return false;
      // A scrollport reveals its content, but its viewport must still fit every OUTER clip and the page.
      if (outsideX || outsideY) boxes = boxes.map(glyph => ({
        left: outsideX ? box.left : glyph.left, right: outsideX ? box.right : glyph.right,
        top: outsideY ? box.top : glyph.top, bottom: outsideY ? box.bottom : glyph.bottom,
      }));
    }
    return boxes.length > 0 && boxes.every(box => box.left >= -1 && box.right <= innerWidth + 1);
  });
  const name = await field.evaluate(element => `${element.tagName.toLowerCase()}.${element.className} ${(element.textContent ?? '').slice(-64)}`);
  expect(result, name + ': the complete synthetic value must wrap or have a visible scroll affordance').toBe(true);
}

async function pageContained(page: Page): Promise<void> {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'long values must not widen the page').toBe(true);
}

async function renderedValues(page: Page, scope = page.locator('body')): Promise<number> {
  let seen = 0;
  const controls = scope.locator('input:not([type="hidden"]):not([type="number"]):not([type="range"]):not([type="password"]), textarea');
  for (const field of await controls.all()) {
    if (!await field.isVisible() || (await field.inputValue()).length < 400) continue;
    await controlReachable(field, NATIVE_INPUT);
    seen++;
  }
  // Own text nodes find the field, including derived basenames that dropped the fixture prefix.
  const displays = scope.locator('xpath=.//*[not(self::script) and not(self::style) and not(self::textarea) and text()[contains(., "synthetic-long-") or string-length(.) >= 300]]');
  for (const field of await displays.all()) {
    if (!await field.isVisible()) continue;
    // The seat preview is deliberately clamped. Its full saved value is asserted in Edit team below.
    if (await field.evaluate(element => element.matches('.seatrow .note'))) {
      test.info().annotations.push({ type: 'disposition', description: 'Team seat instructions: clamped preview, complete saved value in Edit team' });
      continue;
    }
    if (await field.evaluate(element => element.matches('.tool .arg'))) {
      expect(await field.evaluate(element => {
        const tool = element.closest('details');
        return tool?.open === true && [...tool.children].filter(child => child.tagName !== 'SUMMARY').some(child => child.textContent?.includes(element.textContent ?? ''));
      }), 'the ellipsized tool target must be fully revealed in the open body').toBe(true);
      test.info().annotations.push({ type: 'disposition', description: 'Tool target: summary ellipsis, exact value revealed in the opened body' });
      continue;
    }
    await textReachable(field);
    seen++;
  }
  await pageContained(page);
  return seen;
}

// Display seams from the page implementations. Protocol discriminators stay intact; long head identities are consistently aliased.
const TEXT_KEYS = new Set(['name', 'label', 'title', 'goal', 'detail', 'details', 'description', 'reason', 'unread_reason', 'missing_reason', 'note', 'packet_note', 'text', 'content', 'instructions', 'question', 'failure_sentence', 'cause', 'message', 'constraint', 'refusal', 'auth_exclusion_reason', 'version', 'installed', 'latest', 'rollback_target', 'fix', 'summary']);
const TEXT_LISTS = new Set(['features', 'options', 'lines', 'looked_in', 'labels', 'args', 'output']);
const PATH_KEYS = new Set(['path', 'cwd', 'repo', 'root', 'file', 'credential_path', 'resolves_to', 'shim_path', 'real_binary_path', 'logPath', 'text_source', 'file_path', 'notebook_path', 'worktree', 'to_tree', 'address']);
const MODEL_KEYS = new Set(['model', 'model_id', 'pinned_model']);
const LOGIN_KEYS = new Set(['display_name', 'email', 'account']);
const URL_KEYS = new Set(['url', 'base_url', 'webhook_url', 'remote']);

/** Optional display seams must be populated, not silently absent in a passing route census. */
function populateDisplays(body: unknown, path: string): void {
  if (body === null || typeof body !== 'object') return;
  const data = body as Record<string, unknown>;
  if (path === '/api/config') (data.effective as Record<string, unknown>).statuslineGitRoots = LONG.path;
  if (path === '/api/alerts') data.webhook_url = LONG.url;
  if (path === '/api/upgrade') Object.assign(data, {
    installed: LONG.token, latest: LONG.model, latest_basis: 'measured', rollback_target: LONG.path, rollback_basis: 'measured',
  });
  if (path === '/api/upgrade/run') data.run = {
    id: 'synthetic-layout-run', args: ['upgrade', '--to', LONG.token], state: 'failed',
    started_at_epoch_millis: Date.now(), exit_code: 1, output: [LONG.token],
  };
  if (path === '/api/doctor' && Array.isArray(data.checks)) for (const check of data.checks as Record<string, unknown>[]) {
    check.details = LONG.token;
    check.fix = LONG.path;
    check.fix_kind = 'advice';
    check.fix_id = null;
  }
  if (path === '/api/teams' && Array.isArray(data.teams)) for (const team of data.teams as Record<string, unknown>[]) {
    team.features = [LONG.token];
    for (const slot of team.slots as Record<string, unknown>[]) {
      slot.role = LONG.token;
      slot.instructions = LONG.token;
    }
  }
  if (path === '/api/sessions' && Array.isArray(data.sessions)) {
    const row = (data.sessions as Record<string, unknown>[])[0];
    if (row !== undefined) Object.assign(row, {
      status: 'waiting', waiting_for: 'input needed', entrypoint: LONG.token,
      last: { role: 'assistant', tool: 'AskUserQuestion', text: LONG.token, ts: Date.now(), asks: [{ question: LONG.token, options: [LONG.login, LONG.model], multi: false }] },
    });
  }
  if (Array.isArray(data.messages) && path.startsWith('/api/sessions/')) {
    const messages = data.messages as Record<string, unknown>[];
    const index = Math.max(0, ...messages.map(message => Number(message.index) + 1));
    messages.push(
      { index, role: 'assistant', tool: 'Read', tool_use_id: 'synthetic-long-read', text: JSON.stringify({ file_path: LONG.path }) },
      { index: index + 1, role: 'tool', tool: 'Read', tool_use_id: 'synthetic-long-read', result: true, text: LONG.token },
      { index: index + 2, role: 'assistant', tool: 'Bash', tool_use_id: 'synthetic-long-command', text: JSON.stringify({ command: LONG.token, description: LONG.token }) },
      { index: index + 3, role: 'tool', tool: 'Bash', tool_use_id: 'synthetic-long-command', result: true, text: LONG.token },
    );
  }
  if (path.startsWith('/api/projects/') && 'root' in data) data.remote = LONG.url;
  if (path === '/api/models' && Array.isArray(data.heads)) for (const head of data.heads as Record<string, unknown>[]) {
    for (const model of head.models as Record<string, unknown>[]) model.description = LONG.token;
  }
}

function fixtureText(value: unknown, path: string[] = [], aliases: ReadonlyMap<string, string> = new Map()): unknown {
  if (Array.isArray(value)) return value.map((item, index) => fixtureText(item, [...path, String(index)], aliases));
  if (value !== null && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    return Object.fromEntries(Object.entries(record).map(([key, item]) => {
      // Transcript tool inputs cross the wire as JSON text. Keep the parseable payload and its discriminator.
      if (key === 'text' && typeof record.tool === 'string' && record.result !== true && typeof item === 'string') {
        try { return [key, JSON.stringify(fixtureText(JSON.parse(item) as unknown, [...path, 'input'], aliases))]; } catch { /* A cut input stays plain text. */ }
      }
      return [aliases.get(key) ?? key, fixtureText(item, [...path, key], aliases)];
    }));
  }
  if (typeof value !== 'string' || value === '') return value;
  const identity = aliases.get(value);
  if (identity !== undefined) return identity;
  const key = path.at(-1) ?? '';
  const modelId = key === 'id' && path.includes('models') || key === 'key' && path.includes('usage') && path.includes('models');
  const loginId = key === 'key' && path.includes('usage') && path.includes('accounts');
  const textList = TEXT_LISTS.has(path.at(-2) ?? '');
  const kind = PATH_KEYS.has(key) ? 'path' : URL_KEYS.has(key) ? 'url' : MODEL_KEYS.has(key) || modelId ? 'model' : LOGIN_KEYS.has(key) || loginId ? 'login' : TEXT_KEYS.has(key) || textList ? 'token' : null;
  if (kind === null) return value;
  const suffix = '-end-' + path.join('_').replace(/[^a-zA-Z0-9_]/g, '').slice(-60);
  return LONG[kind].slice(0, 400 - suffix.length) + suffix;
}

async function syntheticDisplays(page: Page): Promise<(path: string) => string> {
  const heads = await read<HeadsPayload>(page, '/api/heads');
  const aliases = new Map(heads.heads.map((head, index) => [head.key, valueOf(`synthetic-long-head-${index}-`)]));
  const replace = (path: string, backwards = false): string => {
    const entries = [...aliases].map(([short, long]) => backwards ? [long, short] as const : [short, long] as const).sort(([left], [right]) => right.length - left.length);
    for (const [from, to] of entries) path = path.replaceAll(encodeURIComponent(from), encodeURIComponent(to));
    return path;
  };
  await page.route(url => url.pathname.startsWith('/api/') && url.pathname !== '/api/events', async route => {
    if (route.request().method() !== 'GET') return route.fallback();
    if (new URL(route.request().url()).pathname.endsWith('/wire')) {
      const selected = /requests\/[^/]+\/(\d+)/.exec(new URL(page.url()).hash)?.[1];
      return route.fulfill({ json: { key: STACK.oauthHead, keep: 20, records: [{ ts: Number(selected ?? Date.now()), model: LONG.model, compact: false, body: JSON.stringify({ synthetic: LONG.token }) }] } });
    }
    // The oversized search is a layout fixture, not a change to the real history query-length boundary.
    const url = new URL(replace(route.request().url(), true));
    if (url.pathname === '/api/sessions/history') url.searchParams.delete('query');
    const response = await route.fetch({ url: url.href });
    if (!response.ok() || !response.headers()['content-type']?.includes('json')) return route.fulfill({ response });
    const body: unknown = await response.json();
    populateDisplays(body, url.pathname);
    await route.fulfill({ response, json: fixtureText(body, [url.pathname], aliases) });
  });
  return replace;
}

async function sectionValues(page: Page, section: string): Promise<void> {
  const seen = await renderedValues(page);
  if (section !== 'general') {
    expect(seen, section + ': a source section must consume long fixture values before geometry can pass').toBeGreaterThan(0);
    return;
  }
  // Sections.tsx General has a closed theme, bounded warning slider and debug boolean, not daemon free text.
  test.info().annotations.push({ type: 'disposition', description: 'General: closed theme, warning 50–100 and debug boolean; browser host is the required loopback capture origin, not a daemon-controlled string' });
  await expect(page.getByRole('textbox')).toHaveCount(0);
  await expect(page.getByRole('searchbox')).toHaveCount(0);
  await expect(page.getByRole('slider')).toHaveAttribute('min', '50');
  await expect(page.getByRole('slider')).toHaveAttribute('max', '100');
  const host = page.locator('.folder.code');
  await expect(host).toHaveText(await page.evaluate(() => location.host));
  await textReachable(host);
}

async function openDisclosures(page: Page): Promise<void> {
  // Details are native reveals, not truncated replicas. Open outer disclosures before their descendants.
  for (let round = 0; round < 4; round++) {
    const closed = page.locator('main details:not([open]) > summary');
    let opened = 0;
    for (const summary of await closed.all()) {
      if (!await summary.isVisible()) continue;
      await summary.click();
      opened++;
    }
    if (opened === 0) break;
  }
  for (const button of await page.locator('main .fold.shut > button.more').all()) if (await button.isVisible()) await button.click();
}

const TABS: Record<string, string[]> = {
  'models/:head': ['', '?tab=models', '?tab=log'],
  'requests/:head/:ts': ['', '?tab=request', '?tab=sent'],
  sessions: ['', '?group=repo', '?group=team'],
};

async function menusReachable(page: Page, scope = page.getByRole('main')): Promise<void> {
  for (const choice of await scope.locator('button.select').all()) {
    if (!await choice.isVisible() || !await choice.isEnabled()) continue;
    await choice.click();
    await renderedValues(page, page.getByRole('menu'));
    await page.keyboard.press('Escape');
  }
}

for (const route of ROUTES.filter(route => route !== 'settings/:section?')) {
  test('synthetic long-value page census: ' + route, async ({ page }) => {
    const original = await routePath(page, route);
    const pathOf = await syntheticDisplays(page);
    const path = pathOf(original);
    const faults = await open(page, path);
    for (const tab of TABS[route] ?? ['']) {
      if (tab !== '') await page.goto(page.url().split('#')[0] + '#/' + path + tab);
      // Every source route must consume a long fixture value before geometry can pass.
      await expect.poll(async () => (await page.getByRole('main').innerText()).includes('synthetic-long-'), { timeout: 20_000 }).toBe(true);
      for (const width of WIDTHS) {
        await page.setViewportSize({ width, height: 1024 });
        await page.evaluate(() => document.fonts.ready);
        await openDisclosures(page);
        expect(await renderedValues(page), route + tab + ' must render a long value').toBeGreaterThan(0);
        await menusReachable(page);
        for (const field of await page.getByRole('searchbox').all()) {
          await field.fill(LONG.token);
          await expect(field).toHaveValue(LONG.token);
          await controlReachable(field, NATIVE_INPUT);
          await pageContained(page);
          await field.fill('');
        }
      }
    }
    if (route === 'teams/:id') {
      const notes = await page.locator('.seatrow .note').allTextContents();
      expect(notes.length, 'saved seat instructions must be populated').toBeGreaterThan(0);
      await page.getByRole('button', { name: 'Edit the team', exact: true }).click();
      const dialog = page.getByRole('dialog');
      const instructions = dialog.locator('.seatform textarea');
      await expect(instructions).toHaveCount(notes.length);
      for (const [index, value] of notes.entries()) await expect(instructions.nth(index)).toHaveValue(value);
      await dialog.locator('details > summary').click();
      await draftFields(dialog, page);
      await renderedValues(page, dialog);
      await menusReachable(page, dialog);
      await page.keyboard.press('Escape');
    }
    for (const scope of await page.locator('.composer, .standing, .pg-ask').all()) await draftFields(scope, page);
    if (route === 'usage') {
      await draftFields(page.locator('.usrow').filter({ has: page.getByRole('textbox', { name: 'Webhook', exact: true }) }), page);
      const breakdown = page.locator('.usage-breakdown');
      for (const dimension of ['Model', 'Account', 'Day']) {
        await breakdown.getByRole('button', { name: dimension, exact: true }).click();
        for (const width of WIDTHS) {
          await page.setViewportSize({ width, height: 1024 });
          await renderedValues(page);
          await breakdown.getByRole('button', { name: 'Show the values as a table', exact: true }).click();
          await renderedValues(page);
          await breakdown.getByRole('button', { name: 'Show spend bars', exact: true }).click();
        }
      }
    }
    if (route === 'playground') {
      await page.getByRole('button', { name: 'Enter a model ID', exact: true }).first().click();
      await draftFields(page.locator('.model-choice').first(), page);
    }
    await assertHealthy(page, faults);
    await page.unrouteAll({ behavior: 'wait' });
  });
}

function knobValue(key: string): string {
  if (/path|file|dir/i.test(key)) return LONG.path;
  if (/url|base|endpoint|proxy/i.test(key)) return LONG.url;
  if (/model/i.test(key)) return LONG.model;
  if (/login|account|name/i.test(key)) return LONG.login;
  return LONG.token;
}

async function draftFields(scope: Locator, page: Page): Promise<void> {
  const fields = scope.locator('input:not([type="password"]):not([type="number"]):not([type="range"]):not([type="hidden"]), textarea');
  expect(await fields.count(), 'the disclosed draft must have actual text fields').toBeGreaterThan(0);
  const failures: string[] = [];
  for (const width of WIDTHS) {
    await page.setViewportSize({ width, height: 1024 });
    for (const [index, field] of (await fields.all()).entries()) {
      if (!await field.isVisible() || !await field.isEnabled()) continue;
      const label = await field.getAttribute('aria-label') ?? await field.locator('xpath=..').innerText();
      const value = /file|folder|path/i.test(label) ? LONG.path : /url/i.test(label) ? LONG.url : /model/i.test(label) ? LONG.model : /name|label/i.test(label) ? LONG.login : LONG.token;
      await field.fill(value);
      await expect(field).toHaveValue(value);
      try { await controlReachable(field, NATIVE_INPUT); } catch { failures.push(`${label || index} / ${width}px`); }
    }
    try { await pageContained(page); } catch { failures.push(`page overflow / ${width}px`); }
  }
  expect(failures, 'every disclosed text draft must keep its whole value reachable').toEqual([]);
}

for (const section of SECTIONS) {
  test('synthetic long-value Settings section: ' + section, async ({ page }) => {
    await syntheticDisplays(page);
    await page.route(url => url.pathname === '/api/topology/preview', route => route.fulfill({ json: { text: LONG.token, chars: 400, truncated: false } }));
    if (section === 'tools') {
      await page.route(url => url.pathname === '/api/mcp', route => route.fulfill({ json: { hosting: true, servers: { [LONG.token]: { eligible: false, reason: LONG.token } } } }));
      await page.route(url => url.pathname === '/api/claude-head', route => route.fulfill({ json: {
        mode: 'wrapped', resolves_to: LONG.path, shim_path: LONG.path, real_binary_path: LONG.path,
        claude_logins: { count: 1, selected: LONG.login, labels: [LONG.login], constraint: LONG.token },
      } }));
    }
    const faults = await open(page, 'settings/' + section);
    await expect(page.getByRole('main')).not.toBeEmpty();
    if (section === 'advanced') {
      await page.getByRole('button', { name: 'Open the file', exact: true }).click();
      await openDisclosures(page);
      await draftFields(page.locator('.cf'), page);
      for (const width of WIDTHS) {
        await page.setViewportSize({ width, height: 1024 });
        await sectionValues(page, section);
      }
    } else {
      await openDisclosures(page);
      for (const width of WIDTHS) {
        await page.setViewportSize({ width, height: 1024 });
        await sectionValues(page, section);
      }
    }
    if (section === 'health') await draftFields(page.locator('.row').filter({ has: page.getByRole('textbox', { name: 'Release', exact: true }) }), page);
    if (section === 'storage') {
      const root = page.locator('.folder');
      await expect(root).toHaveAttribute('title', /synthetic-long-path/);
      expect((await root.getAttribute('title'))?.length, 'the shortened folder label retains the complete source path').toBe(400);
      await page.getByRole('button', { name: 'Add a folder', exact: true }).click();
      await draftFields(page.getByRole('dialog'), page);
      await page.keyboard.press('Escape');
    }
    if (section === 'conversation') {
      const heads = await read<HeadsPayload>(page, '/api/heads');
      for (const head of heads.heads) {
        const plans = page.getByRole('button', { name: 'Command', exact: true });
        await plans.click();
        const index = heads.heads.findIndex(candidate => candidate.key === head.key);
        await page.getByRole('menuitemradio').nth(index).click();
        const add = page.getByRole('button', { name: 'Add instructions', exact: true });
        if (await add.count() > 0) await add.click();
        await page.getByRole('button', { name: 'In a file', exact: true }).click();
        await draftFields(page.locator('.instructions'), page);
      }
    }
    await assertHealthy(page, faults);
  });
}

for (const dialog of ['team', 'plan', 'login', 'rename'] as const) {
  test('synthetic long-value disclosed draft: ' + dialog, async ({ page }) => {
    const faults = await open(page, dialog === 'team' ? 'teams' : dialog === 'plan' ? 'models' : 'models/' + STACK.oauthHead);
    if (dialog === 'team') {
      await page.getByRole('button', { name: 'New team', exact: true }).first().click();
      await page.getByRole('dialog').locator('details > summary').click();
    } else if (dialog === 'plan') {
      await page.getByRole('button', { name: 'Add a command', exact: true }).click();
      await page.getByRole('dialog').getByRole('button', { name: /^API key\s/i }).click();
    } else if (dialog === 'login') {
      await page.getByRole('button', { name: 'Add account', exact: true }).first().click();
    } else {
      await page.getByRole('button', { name: 'Rename', exact: true }).first().click();
    }
    await draftFields(page.getByRole('dialog'), page);
    await page.keyboard.press('Escape');
    await assertHealthy(page, faults);
  });
}

test('synthetic long-value Settings census includes global and every declared head override', async ({ page }) => {
  const heads = await read<HeadsPayload>(page, '/api/heads');
  const original = await read<ConfigPayload>(page, '/api/config');
  const extra = 'synthetic-overrides-only';
  const overrides = { ...original.layers.perHead, [extra]: { foldMarkerText: LONG.token } };
  const scopes = headOptions(overrides, heads.heads.map(head => head.key));
  const fields = new Map(Object.entries(original.effective)
    .filter(([key, value]) => typeof value !== 'boolean' && typeof value !== 'number' && ['text', 'locked', 'head-only'].includes(controlOf(value, KNOB_META[key], null).kind))
    .map(([key]) => [key, knobValue(key)]));
  await page.route(url => url.pathname === '/api/config', async route => {
    const response = await route.fetch();
    const body = await response.json() as ConfigPayload;
    const head = new URL(route.request().url()).searchParams.get('head');
    body.layers.perHead = overrides;
    for (const [key, value] of Object.entries(body.effective)) {
      if (!['text', 'locked', 'head-only'].includes(controlOf(value, KNOB_META[key], head).kind) || typeof value === 'boolean' || typeof value === 'number') continue;
      const synthetic = knobValue(key);
      body.effective[key] = synthetic;
      fields.set(key, synthetic);
    }
    await route.fulfill({ response, json: body });
  });
  await page.route(url => url.pathname === '/api/topology', async route => {
    const response = await route.fetch();
    const body = await response.json() as { topology: { heads: Record<string, { overrides?: Record<string, string> }> } };
    for (const head of Object.values(body.topology.heads)) head.overrides = {
      ...head.overrides,
      ...Object.fromEntries([...fields].map(([key, value]) => [key, value])),
    };
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'settings/advanced');
  await page.getByRole('button', { name: 'Open the full list', exact: true }).click();
  const failures: string[] = [];
  for (const scope of scopes.map(scope => scope === 'global' ? null : scope)) {
    if (scope !== null) {
      await page.getByRole('button', { name: 'Which command', exact: true }).click();
      const label = heads.heads.find(head => head.key === scope)?.label ?? scope;
      await page.getByRole('menuitemradio', { name: label, exact: true }).click();
      await expect(page.getByRole('button', { name: 'Which command', exact: true })).toContainText(label);
    }
    for (const width of WIDTHS) {
      await page.setViewportSize({ width, height: 1024 });
      await page.evaluate(() => document.fonts.ready);
      for (const [key] of fields) {
        const label = (KNOB_LABELS as Record<string, string>)[key] ?? key;
        const row = page.locator('.row').filter({ has: page.getByRole('heading', { name: label, exact: true }) });
        if (await row.count() === 0) {
          expect(CURATED_KNOBS.has(key), key + ': an absent Advanced field must have a source-declared curated disposition').toBe(true);
          expect(key, 'the only curated free-text value is swept as Storage folders').toBe('statuslineGitRoots');
          test.info().annotations.push({ type: 'disposition', description: key + ': Storage folder basename wraps; the full root is retained in its native title' });
          continue;
        }
        const input = row.getByRole('textbox');
        try {
          if (await input.count() > 0) {
            await expect(input.first()).toHaveValue(fields.get(key) ?? '');
            await controlReachable(input.first(), ['codexAuthPath', 'grokAuthPath', 'chatgptApiBase', 'xaiApiBase'].includes(key) ? undefined : NATIVE_INPUT);
          } else {
            await expect(row.locator('.ctl')).toContainText('synthetic-long-');
            await textReachable(row.locator('.ctl'));
          }
        } catch {
          failures.push(`${scope ?? 'global'} / ${label} / ${width}px`);
        }
      }
      try { await pageContained(page); } catch { failures.push(`${scope ?? 'global'} / page overflow / ${width}px`); }
    }
  }
  expect(fields.has('codexAuthPath') && fields.has('grokAuthPath'), 'source census must include both parent-red login fields').toBe(true);
  expect(failures, 'synthetic long-value Settings failures').toEqual([]);
  await assertHealthy(page, faults);
  await page.unrouteAll({ behavior: 'wait' });
});

test('synthetic long-value text controls preserve native single-line values and save boundaries', async ({ page }) => {
  let current = LONG.token;
  const writes: Record<string, unknown>[] = [];
  await page.route(url => url.pathname === '/api/config', async route => {
    if (route.request().method() === 'PATCH') {
      const patch = route.request().postDataJSON() as Record<string, unknown>;
      writes.push(patch);
      if (typeof patch.foldMarkerText === 'string') current = patch.foldMarkerText;
      return route.fulfill({ json: { applied: patch, rejected: {}, restart_required: [], targets: [], persisted: '/synthetic/config.json' } });
    }
    const response = await route.fetch();
    const body = await response.json() as ConfigPayload;
    body.effective.foldMarkerText = current;
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'settings/advanced');
  await page.getByRole('button', { name: 'Open the full list', exact: true }).click();
  const field = page.getByRole('textbox', { name: 'Fold marker', exact: true });
  await expect(field).toHaveValue(LONG.token);
  for (const width of WIDTHS) {
    await page.setViewportSize({ width, height: 1024 });
    for (const value of Object.values(LONG)) {
      await field.fill(value);
      await expect(field).toHaveValue(value);
      await controlReachable(field, NATIVE_INPUT);
      await pageContained(page);
    }
  }
  const pasted = 'C:\\Synthetic\\login files\\literal-%0A-' + LONG.token.slice(0, 10) + '\r\n' + LONG.token.slice(10);
  await page.evaluate(() => {
    const reference = document.createElement('input');
    reference.setAttribute('aria-label', 'Synthetic native paste reference');
    document.body.append(reference);
  });
  const reference = page.getByRole('textbox', { name: 'Synthetic native paste reference', exact: true });
  await reference.fill(pasted);
  const native = await reference.inputValue();
  await reference.evaluate(element => element.remove());
  await field.fill(pasted);
  await expect(field).toHaveValue(native);
  expect(writes, 'editing alone must not save').toEqual([]);
  await field.press('Enter');
  await expect.poll(() => writes).toEqual([{ foldMarkerText: native }]);
  await expect(field).toBeEnabled();
  await expect(field).toHaveValue(native);
  await assertHealthy(page, faults);
  await page.unrouteAll({ behavior: 'wait' });
});

test('synthetic long-value optional model offers and Playground replies remain reachable', async ({ page }) => {
  await page.route(url => url.pathname === '/api/add-model', route => route.fulfill({ json: {
    path: LONG.path, heads: [{ head: STACK.oauthHead, provider: 'openrouter', models: [{ id: LONG.model, label: LONG.token, context_window: 100000, slots: [] }] }],
  } }));
  const faults = await open(page, `models/${STACK.oauthHead}?tab=models`);
  await page.getByRole('button', { name: 'Add models', exact: true }).click();
  for (const width of WIDTHS) {
    await page.setViewportSize({ width, height: 1024 });
    expect(await renderedValues(page, page.getByRole('dialog'))).toBeGreaterThan(0);
  }
  await page.keyboard.press('Escape');
  await page.route(url => url.pathname === '/api/models/upstream', route => route.fulfill({ json: {
    path: LONG.path, providers: [{ key: new URL(route.request().url()).searchParams.get('provider'), dialect: 'openai', url: LONG.url, roster: 'published', agrees: false,
      rows: [{ id: LONG.model, verdict: 'unserved', declared_window: 100000, upstream_window: null, note: LONG.token }],
    }],
  } }));
  await page.getByRole('button', { name: 'Compare with the provider', exact: true }).click();
  await expect(page.locator('.models').filter({ hasText: LONG.model })).toContainText(LONG.model);
  for (const width of WIDTHS) {
    await page.setViewportSize({ width, height: 1024 });
    await renderedValues(page);
  }
  await page.route(url => url.pathname === '/api/playground', route => route.fulfill({ json: {
    request: { url: LONG.url, method: 'POST', headers: { 'x-synthetic': LONG.token }, body: { model: LONG.model, input: LONG.token } },
    response: { status: 200, body: { choices: [{ message: { content: LONG.token } }], usage: { prompt_tokens: 1, completion_tokens: 1 } } },
  } }));
  await page.goto(page.url().split('#')[0] + '#/playground');
  await page.locator('.pg-ask textarea').fill(LONG.token);
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(page.locator('.pg-text').first()).toContainText(LONG.token);
  await openDisclosures(page);
  for (const width of WIDTHS) {
    await page.setViewportSize({ width, height: 1024 });
    expect(await renderedValues(page)).toBeGreaterThan(0);
  }
  await assertHealthy(page, faults);
});

test('synthetic long-value checker rejects nested clips, suppressed tracks and unreachable native ends', async ({ page }) => {
  const short = 'https://synthetic.invalid/example';
  await page.setContent(`<style>
    * { box-sizing: border-box; }
    .outer { width:100px; overflow:clip; }
    .scroll { width:220px; overflow-x:auto; white-space:nowrap; }
    .hidden-track::-webkit-scrollbar { height:0; }
    input { width:300px; font:14px monospace; padding:2px; }
    #spaced { letter-spacing:5px; }
  </style><div class="outer"><div id="nested" class="scroll">${LONG.token}</div></div>
  <div id="hidden-track" class="scroll hidden-track">${LONG.token}</div>
  <div class="outer"><input aria-label="ancestor clip" value="${short}"></div>
  <input id="spaced" aria-label="letter spacing" value="${short}">
  <input aria-label="limited scroll" value="${LONG.token}">
  <input aria-label="native reachable" value="${LONG.token}">`);
  await expect(textReachable(page.locator('#nested'))).rejects.toThrow();
  await expect(textReachable(page.locator('#hidden-track'))).rejects.toThrow();
  const clipped = page.getByRole('textbox', { name: 'ancestor clip', exact: true });
  await expect(controlReachable(clipped)).rejects.toThrow();
  await expect(controlReachable(clipped, NATIVE_INPUT)).rejects.toThrow();
  await expect(controlReachable(page.getByRole('textbox', { name: 'letter spacing', exact: true }))).rejects.toThrow();
  const limited = page.getByRole('textbox', { name: 'limited scroll', exact: true });
  await limited.evaluate(element => element.addEventListener('scroll', () => { if (element.scrollLeft > 1) element.scrollLeft = 1; }));
  await expect(controlReachable(limited, NATIVE_INPUT)).rejects.toThrow();
  await controlReachable(page.getByRole('textbox', { name: 'native reachable', exact: true }), NATIVE_INPUT);
});

test('synthetic long-value geometry rejects invisible clipping and admits wrapping or visible scrolling', async ({ page }) => {
  await page.setContent(`<style>
    * { box-sizing: border-box; }
    .field { width: 220px; padding: 8px; font: 14px monospace; }
    .wrap { overflow-wrap: anywhere; }
    .clip { overflow: hidden; white-space: nowrap; }
    .scroll { overflow-x: auto; white-space: nowrap; scrollbar-gutter: stable; }
    textarea { resize: vertical; scrollbar-gutter: stable; }
  </style><input aria-label="clipped" class="field" value="${LONG.token}">
  <textarea aria-label="wrapped" class="field" rows="20">${LONG.path}</textarea>
  <textarea aria-label="scrolling" class="field" rows="2">${LONG.path}</textarea>
  <div id="wrap" class="field wrap">${LONG.model}</div>
  <div id="clip" class="field clip">${LONG.model}</div>
  <div id="scroll" class="field scroll">${LONG.model}</div>`);
  await expect(controlReachable(page.getByRole('textbox', { name: 'clipped', exact: true }))).rejects.toThrow();
  await controlReachable(page.getByRole('textbox', { name: 'wrapped', exact: true }));
  await expect(controlReachable(page.getByRole('textbox', { name: 'scrolling', exact: true }))).rejects.toThrow();
  await controlReachable(page.getByRole('textbox', { name: 'scrolling', exact: true }), NATIVE_INPUT);
  await textReachable(page.locator('#wrap'));
  await expect(textReachable(page.locator('#clip'))).rejects.toThrow();
  await textReachable(page.locator('#scroll'));
  await pageContained(page);
  await page.setContent('<main></main>');
  await expect(sectionValues(page, 'tools')).rejects.toThrow();
});
