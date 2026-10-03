// NEW: V4-444 — retained display floors and whole values on the replacement card/list surfaces.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { appendFileSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { PerfTurnsWire } from '../src/types/perf';
import type { HeadsPayload } from '../src/types/core';
import { UNKNOWN_HEAD } from '../src/types/sessions';
import type { SessionRow } from '../src/types/sessions';
import { FIRST_READ_MS, env, open, routePath } from './support';
import { STACK } from './stack';

test('wide Finished rows keep each name beside its measurements', async ({ page }) => {
  await page.setViewportSize({ width: 3840, height: 2060 });
  const faults = await open(page, 'requests');
  const rows = page.locator('li.turn');
  await expect(rows.first()).toBeVisible({ timeout: FIRST_READ_MS });
  const distance = await rows.first().evaluate((row) => {
    const name = row.querySelector('h3 a')?.getBoundingClientRect();
    const state = row.querySelector('.state')?.getBoundingClientRect();
    if (name === undefined || state === undefined) throw new Error('turn row lost its name or outcome');
    return state.left - name.right;
  });
  expect(distance, 'a row does not require looking across most of the screen').toBeLessThan(900);
  expect(faults.pageErrors).toEqual([]);
});

test('system messages have a system speaker rather than the command or Assistant', async ({ page }) => {
  await page.route('**/api/sessions/' + STACK.sender.id + '/transcript?*', (route) => route.fulfill({ json: {
    session_id: STACK.sender.id, path: '/synthetic/transcript.jsonl', earlier: null, messages: [
      { index: 0, role: 'user', text: 'Synthetic user message.' },
      { index: 1, role: 'system', text: 'Synthetic metadata message.' },
      { index: 2, role: 'assistant', text: 'Synthetic model answer.' },
    ],
  } }));
  const faults = await open(page, 'sessions/' + STACK.sender.id);
  const metadata = page.locator('.msg').filter({ hasText: 'Synthetic metadata message.' });
  await expect(metadata.locator('.who')).toContainText('Claude Code note');
  await expect(metadata.locator('.who')).not.toContainText('Assistant');
  await expect(metadata.locator('.who')).not.toContainText(STACK.oauthHead);
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

test('an original user isMeta image annotation is normalized by the daemon and never labelled Assistant', async ({ page }) => {
  const file = join(env('CONSOLE_E2E_TRANSCRIPT_ROOT'), 'projects', 'console-e2e', STACK.sender.id + '.jsonl');
  const original = readFileSync(file, 'utf8');
  const text = '[Image: original 1440x2466, synthetic metadata annotation]';
  try {
    appendFileSync(file, JSON.stringify({ type: 'user', isMeta: true, message: { role: 'user', content: text } }) + '\n');
    const faults = await open(page, 'sessions/' + STACK.sender.id);
    const metadata = page.locator('.msg').filter({ hasText: text });
    await expect(metadata.locator('.who')).toContainText('Claude Code note');
    await expect(metadata.locator('.who')).not.toContainText('Assistant');
    expect(faults.pageErrors).toEqual([]);
  } finally {
    writeFileSync(file, original);
  }
});

for (const width of [1440, 3840]) {
  test('turn detail at ' + width + ' separates its sections and attaches the session action', async ({ page }) => {
    await page.setViewportSize({ width, height: width === 3840 ? 2060 : 1000 });
    await page.route('**/api/perf/turns?*', async (route) => {
      const response = await route.fetch();
      const body = await response.json() as PerfTurnsWire;
      for (const row of body.heads.flatMap((head) => head.rows ?? [])) {
        row.session_id = STACK.sender.id;
        row.session = STACK.sender.id.slice(0, 8);
      }
      await route.fulfill({ response, json: body });
    });
    await open(page, 'accounts');
    const faults = await open(page, await routePath(page, 'requests/:head/:ts'));
    const link = page.getByRole('link', { name: 'Open the session', exact: true });
    await expect(link).toBeVisible({ timeout: FIRST_READ_MS });
    const placement = await page.getByRole('main').evaluate((root) => {
      const title = root.querySelector('h1')?.getBoundingClientRect();
      const action = root.querySelector('.hero a')?.getBoundingClientRect();
      const stages = root.querySelector('.turn-section.wide')?.getBoundingClientRect();
      const moved = root.querySelector('[aria-labelledby="turn-moved"]')?.getBoundingClientRect();
      if (title === undefined || action === undefined || stages === undefined || moved === undefined) {
        throw new Error('turn detail lost its title, action or section');
      }
      return { attachment: Math.abs(action.left - title.left), separation: moved.top - stages.bottom };
    });
    expect(placement.attachment, 'the action belongs with the session title').toBeLessThan(48);
    expect(placement.separation, 'separate objects have room between them').toBeGreaterThanOrEqual(width === 1440 ? 56 : 32);
    expect(faults.pageErrors).toEqual([]);
  });
}

for (const board of ['models', 'sessions']) {
  test(board + ' cards reorder by their bodies without rendering grips', async ({ page }) => {
    if (board === 'sessions') {
      await page.route('**/api/sessions', async (route) => {
        const response = await route.fetch();
        const body = await response.json() as { sessions: SessionRow[] };
        for (const row of body.sessions) {
          row.status = 'idle';
          row.head = UNKNOWN_HEAD;
          row.availability = 'live';
        }
        await route.fulfill({ response, json: body });
      });
    }
    const faults = await open(page, board);
    const cards = page.locator('li.card');
    await expect(cards.nth(1)).toBeVisible({ timeout: FIRST_READ_MS });
    const names = cards.locator('h3');
    const before = await names.allTextContents();
    const start = await cards.first().locator('.quiet-meta').first().boundingBox();
    const finish = await cards.nth(1).locator('.quiet-meta').first().boundingBox();
    if (start === null || finish === null) throw new Error('card bodies have no layout');
    await page.mouse.move(start.x + start.width / 2, start.y + start.height / 2);
    await page.mouse.down();
    await page.mouse.move(finish.x + finish.width / 2, finish.y + finish.height / 2, { steps: 12 });
    await page.mouse.up();
    await expect.poll(() => names.allTextContents()).not.toEqual(before);
    const after = await names.allTextContents();
    expect([...after].sort()).toEqual([...before].sort());
    await page.reload();
    await expect.poll(() => names.allTextContents()).toEqual(after);
    await expect(page.locator('.grip')).toHaveCount(0);
    expect(faults.pageErrors).toEqual([]);
  });
}

const FRAMES = [
  { width: 3840, height: 2060, used: 0.8, body: 20, caption: 16 },
  { width: 1600, height: 1000, used: 0.7, body: 17, caption: 12 },
] as const;

async function drawing(page: Page) {
  return page.getByRole('main').evaluate((root) => {
    const drawn: DOMRect[] = [];
    const smallest: number[] = [];
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
      const parent = node.parentElement;
      if ((node.textContent ?? '').trim() === '' || parent === null) continue;
      const style = getComputedStyle(parent);
      if (style.visibility === 'hidden' || Number(style.opacity) === 0 || parent.closest('.sr, .tools') !== null) continue;
      const range = document.createRange();
      range.selectNodeContents(node);
      smallest.push(parseFloat(style.fontSize));
      drawn.push(...[...range.getClientRects()].filter((rect) => rect.width > 1 && rect.height > 1));
    }
    for (const element of root.querySelectorAll('svg, img, canvas, [role="img"]')) {
      const rect = element.getBoundingClientRect();
      if (rect.width > 1 && rect.height > 1) drawn.push(rect);
    }
    return {
      left: Math.max(0, Math.min(...drawn.map((rect) => rect.left))),
      right: Math.min(window.innerWidth, Math.max(...drawn.map((rect) => rect.right))),
      body: parseFloat(getComputedStyle(document.body).fontSize),
      smallest: Math.min(...smallest),
      overflow: document.documentElement.scrollWidth - window.innerWidth,
    };
  });
}

async function clipped(locator: Locator): Promise<string[]> {
  return locator.evaluateAll((elements) => elements.flatMap((element) => {
    const range = document.createRange();
    range.selectNodeContents(element);
    const text = range.getBoundingClientRect();
    let parent: Element | null = element;
    while (parent !== null) {
      const style = getComputedStyle(parent);
      const box = parent.getBoundingClientRect();
      if ((style.overflowX !== 'visible' && (text.left < box.left - 1 || text.right > box.right + 1)) ||
          (style.overflowY !== 'visible' && (text.top < box.top - 1 || text.bottom > box.bottom + 1))) {
        return [(element.textContent ?? '').trim()];
      }
      parent = parent.parentElement;
    }
    return text.right > window.innerWidth + 1 ? [(element.textContent ?? '').trim()] : [];
  }));
}

// Every page, not three: the operator's frame is 3840 wide and a page that draws in a third of it fails him as much as one that overflows.
const LISTS = ['sessions', 'usage', 'requests'];
const PAGES = ['accounts', 'sessions', 'sessions/:id', 'teams/:id', 'models', 'models/:head', 'requests', 'requests/:head/:ts', 'usage', 'projects/:id', 'settings'];

for (const frame of FRAMES) {
  for (const route of frame.width === 3840 ? PAGES : LISTS) {
    test(route + ' at ' + frame.width + ' keeps the retained drawn-width and reading floors', async ({ page }) => {
      await page.setViewportSize({ width: frame.width, height: frame.height });
      await open(page, 'accounts');
      const faults = await open(page, await routePath(page, route));
      await expect(page.getByRole('main').getByRole('heading').first()).toBeVisible({ timeout: FIRST_READ_MS });
      // The page polls, so the network is never idle: it is ready when its text stops changing.
      let length = -1;
      for (let read = 0; read < 12; read += 1) {
        const now = (await page.getByRole('main').innerText()).length;
        if (now === length && now > 150) break;
        length = now;
        await page.waitForTimeout(800);
      }
      await page.evaluate(() => document.fonts.ready);
      // Content that arrives late (a transcript) may widen the drawing; a layout that is wrong stays under the floor for the whole wait.
      await expect.soft.poll(async () => { const now = await drawing(page); return (now.right - now.left) / frame.width; },
        { message: 'actual glyphs and graphics use the retained screen share', timeout: 10_000 }).toBeGreaterThanOrEqual(frame.used);
      const measured = await drawing(page);
      expect.soft(measured.body, 'body text keeps the retained pixel floor').toBeGreaterThanOrEqual(frame.body);
      expect.soft(measured.smallest, 'no text is below the caption floor').toBeGreaterThanOrEqual(frame.caption);
      expect.soft(measured.overflow, 'the document does not require horizontal scrolling').toBeLessThanOrEqual(1);
      expect(faults.pageErrors).toEqual([]);
    });
  }
}

test('Fleet keeps a long command and its complete state visible at desktop width', async ({ page }) => {
  const command = 'synthetic-secondary-provider-plan';
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.route('**/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    const head = body.heads.find((row) => row.key === STACK.oauthHead);
    if (head === undefined) throw new Error('synthetic OAuth head missing');
    head.label = command;
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'models');
  const title = page.getByRole('link', { name: command, exact: true });
  await expect(title).toBeVisible();
  const card = page.locator('li.card').filter({ has: title });
  expect(await clipped(title)).toEqual([]);
  expect(await clipped(card.locator('.state-word, .quiet-meta span, .gl span, .gl b'))).toEqual([]);
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

test('Turns keeps a long model, large token counts and complete refusal outcome visible at desktop width', async ({ page }) => {
  const model = 'synthetic-long-model-with-a-declared-context-window[1m]';
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.route((url) => url.pathname === '/api/perf/turns', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as PerfTurnsWire;
    for (const row of body.heads.flatMap((head) => head.rows ?? [])) {
      row.model = model;
      row.outcome = 'error:rate-limited';
      row.in_tokens = 2_000_000;
      row.cached_tokens = 1_999_999;
      row.out_tokens = 123_456;
    }
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'requests');
  const row = page.locator('li.turn').filter({ hasText: model }).first();
  await expect(row).toBeVisible({ timeout: FIRST_READ_MS });
  await expect(row).toContainText('2.00M in');
  await expect(row).toContainText('123k out');
  await expect(row.getByText('Rate limited', { exact: true })).toBeVisible();
  expect(await clipped(row.locator('.sub span, .state, .tok'))).toEqual([]);
  await row.getByRole('link').click();
  await expect(page.getByRole('main')).toContainText(model);
  expect(await clipped(page.getByText(model, { exact: true }).first())).toEqual([]);
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

test('Settings uses plain product words and unique accessible region names on every section', async ({ page }) => {
  // Settings mounts every section together; this single page read covers them all.
  const faults = await open(page, 'settings/health');
  const main = page.getByRole('main');
  await expect(main.locator('.row').first()).toBeVisible();
  await main.evaluate((root) => {
    root.querySelectorAll<HTMLElement>('pre, textarea, code, input, .path').forEach((element) => { element.style.display = 'none'; });
  });
  const words = (await main.innerText()).split('\n').map((line) => line.trim()).filter(Boolean);
  expect.soft(words.filter((line) => /\b(topology|daemon)\b/i.test(line)), 'visible wording').toEqual([]);
  const names = await main.locator('section[aria-label], [role="region"]').evaluateAll((regions) => regions.map((region) => region.getAttribute('aria-label') ?? ''));
  expect.soft(names.filter((name, at) => name !== '' && names.indexOf(name) !== at), 'unique regions').toEqual([]);
  expect(faults.pageErrors).toEqual([]);
});
