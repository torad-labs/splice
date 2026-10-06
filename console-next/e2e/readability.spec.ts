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

test('Models puts command ordering before its catalog and supports click and keyboard Move', async ({ page }) => {
  await page.route(url => url.pathname === '/api/heads', async route => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    await route.fulfill({ response, json: { ...body, heads: body.heads.slice(0, 2).map((head, index) => ({ ...head, label: index === 0 ? 'Synthetic first command' : 'Synthetic second command' })) } });
  });
  const faults = await open(page, 'models');
  const commands = page.getByRole('region', { name: 'Commands', exact: true });
  const names = commands.locator('li.card h3');
  await expect(names).toHaveText(['Synthetic first command', 'Synthetic second command']);
  expect(await commands.evaluate(node => {
    const catalog = document.querySelector('[aria-labelledby="every-model"]');
    return catalog !== null && (node.compareDocumentPosition(catalog) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
  })).toBe(true);
  await expect(commands.getByRole('button', { name: 'Move Synthetic first command earlier', exact: true })).toBeDisabled();
  await commands.getByRole('button', { name: 'Move Synthetic first command later', exact: true }).click();
  await expect(names).toHaveText(['Synthetic second command', 'Synthetic first command']);
  await commands.getByRole('button', { name: 'Move Synthetic first command earlier', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(names).toHaveText(['Synthetic first command', 'Synthetic second command']);
  await page.reload();
  await expect(names).toHaveText(['Synthetic first command', 'Synthetic second command']);
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 980 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const titleBounds = await names.first().boundingBox();
    if (titleBounds === null) throw new Error('a command card lost its title');
    expect(titleBounds.width).toBeGreaterThanOrEqual(160);
    await commands.screenshot({ path: test.info().outputPath('model-order-' + width + '.png') });
  }
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

test('the retired needs-you bookmark opens Accounts', async ({ page }) => {
  const faults = await open(page, 'accounts');
  await page.goto(env('CONSOLE_E2E_BASE') + '/#/needs-you');
  await expect.poll(() => new URL(page.url()).hash).toBe('#/accounts');
  await expect(page.getByRole('heading', { name: 'Accounts', exact: true })).toBeVisible();
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});

test('Sessions preserves its initial order through new roster reads before anyone drags', async ({ page }) => {
  let reverse = false;
  let newcomer = false;
  let reads = 0;
  await page.route(url => url.pathname === '/api/sessions', async route => {
    const response = await route.fetch();
    const body = await response.json() as { sessions: SessionRow[] };
    const base = body.sessions.find(row => row.session_id === STACK.sender.id);
    if (base === undefined) throw new Error('synthetic sender is missing');
    reads += 1;
    const rows = ['first', 'second'].map(name => ({ ...base, session_id: 'synthetic-order-' + name,
      name: 'Synthetic ' + name, status: 'busy', head: UNKNOWN_HEAD, availability: 'live', team: null }));
    if (reverse) rows.reverse();
    if (newcomer) rows.unshift({ ...base, session_id: 'synthetic-order-new', name: 'Synthetic new', status: 'busy', head: UNKNOWN_HEAD, availability: 'live', team: null });
    await route.fulfill({ response, json: { ...body, sessions: rows } });
  });
  const faults = await open(page, 'sessions');
  const names = page.locator('li.card h3');
  await expect(names).toHaveText(['Synthetic first', 'Synthetic second']);
  const firstRead = reads;
  reverse = true;
  await expect.poll(() => reads, { timeout: 20_000 }).toBeGreaterThan(firstRead);
  await expect(names).toHaveText(['Synthetic first', 'Synthetic second']);
  const secondRead = reads;
  newcomer = true;
  await expect.poll(() => reads, { timeout: 20_000 }).toBeGreaterThan(secondRead);
  await expect(names).toHaveText(['Synthetic first', 'Synthetic second', 'Synthetic new']);
  await page.reload();
  await expect(names).toHaveText(['Synthetic first', 'Synthetic second', 'Synthetic new']);
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

test('Sessions offers named grip and Move controls without turning card bodies into buttons', async ({ page }) => {
  await page.route(url => url.pathname === '/api/sessions', async route => {
    const response = await route.fetch();
    const body = await response.json() as { sessions: SessionRow[] };
    const base = body.sessions.find(row => row.session_id === STACK.sender.id);
    if (base === undefined) throw new Error('synthetic sender is missing');
    await route.fulfill({ response, json: { ...body, sessions: ['first', 'second'].map(name => ({
      ...base, session_id: 'synthetic-move-' + name, name: 'Synthetic ' + name,
      status: 'idle', head: UNKNOWN_HEAD, availability: 'live', team: null,
    })) } });
  });
  const faults = await open(page, 'sessions');
  const cards = page.locator('li.card');
  const names = cards.locator('h3');
  await expect(names).toHaveText(['Synthetic first', 'Synthetic second']);
  await expect(page.getByRole('button', { name: 'Drag Synthetic first to reorder', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Move Synthetic first earlier', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Move Synthetic first later', exact: true }).click();
  await expect(names).toHaveText(['Synthetic second', 'Synthetic first']);
  await page.getByRole('button', { name: 'Move Synthetic first earlier', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(names).toHaveText(['Synthetic first', 'Synthetic second']);
  await expect(cards.first()).not.toHaveAttribute('tabindex', '0');
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 980 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: test.info().outputPath('session-order-' + width + '.png'), fullPage: true });
  }
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});

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

test('the Sessions toolbar fits at 393px in both themes and keeps grouping, search and New team usable', async ({ page }) => {
  await page.setViewportSize({ width: 393, height: 980 });
  const faults = await open(page, 'sessions');
  const grouping = page.getByRole('group', { name: 'Group by', exact: true });
  const search = page.getByRole('searchbox', { name: 'Find a session', exact: true });
  await expect(grouping).toBeVisible({ timeout: FIRST_READ_MS });
  for (const theme of ['Day', 'Night']) {
    await page.getByRole('group', { name: 'Theme', exact: true }).getByRole('button', { name: theme, exact: true }).click();
    for (const by of ['State', 'Repo', 'Command', 'Team']) {
      await grouping.getByRole('button', { name: by, exact: true }).click();
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(1);
      const controls = [grouping, page.locator('.page-head .search')];
      if (by === 'Team') controls.push(page.getByRole('button', { name: 'New team', exact: true }));
      for (const control of controls) {
        const bounds = await control.boundingBox();
        if (bounds === null) throw new Error('a Sessions toolbar control is not rendered');
        expect(bounds.x).toBeGreaterThanOrEqual(0);
        expect(bounds.x + bounds.width).toBeLessThanOrEqual(394);
      }
    }
    await page.keyboard.press('Control+k');
    await expect(search).toBeFocused();
    await search.fill(STACK.sender.name);
    await expect(page.getByRole('link', { name: STACK.sender.name, exact: true })).toBeVisible();
    await search.fill('');
    await page.screenshot({ path: test.info().outputPath(`sessions-toolbar-393-${theme.toLowerCase()}.png`), fullPage: true });
    await page.getByRole('button', { name: 'New team', exact: true }).click();
    await expect(page.getByRole('dialog', { name: 'New team', exact: true })).toBeVisible();
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel', exact: true }).click();
  }
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
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

test('a wrapped peer delivery renders its named Markdown body without transport metadata', async ({ page }) => {
  for (const timing of [' while you were working', '']) {
    const delivery = '<system-reminder>Another Claude session sent a message' + timing + ':\n' +
      '<cross-session-message from="uds:/synthetic/console.sock" from-name="synthetic-console" from-mode="bypass">' +
      '**Synthetic peer review**\n\n- Preserve this boundary.\n\n```typescript\nconst ready = true;\n```' +
      '</cross-session-message>\n\nThis came from another Claude session. Synthetic delivery metadata.</system-reminder>';
    await page.route('**/api/sessions/' + STACK.sender.id + '/transcript?*', route => route.fulfill({ json: {
      session_id: STACK.sender.id, path: '/synthetic/transcript.jsonl', earlier: null, messages: [
        { index: 0, role: 'system', text: delivery },
        { index: 1, role: 'system', text: 'Synthetic ordinary client note.' },
      ],
    } }));
    const faults = await open(page, 'sessions/' + STACK.sender.id);
    // The same session address can retain the previous prefix's query cache until the next poll.
    await page.reload();
    const peer = page.locator('.msg.peer').filter({ hasText: 'Synthetic peer review' });
    await expect(peer.locator('.stamp')).toContainText('Message from synthetic-console');
    await expect(peer.locator('strong')).toHaveText('Synthetic peer review');
    await expect(peer.getByRole('listitem')).toHaveText('Preserve this boundary.');
    await expect(peer.locator('pre.code')).toContainText('const ready = true;');
    for (const transport of ['uds:/synthetic', 'cross-session-message', 'from-mode', 'Synthetic delivery metadata', 'Another Claude session sent']) {
      await expect(page.getByRole('main')).not.toContainText(transport);
    }
    await expect(page.locator('.msg').filter({ hasText: 'Synthetic ordinary client note.' }).locator('.who')).toContainText('Claude Code note');
    expect(faults.pageErrors).toEqual([]);
    expect(faults.failedReads).toEqual([]);
    await page.unrouteAll({ behavior: 'wait' });
  }
});

test('session messages keep long speakers, peer stamps and code inside the conversation at narrow widths', async ({ page }) => {
  const command = 'Synthetic command with a deliberately long readable name for the session speaker';
  const peerName = 'synthetic-peer-' + 'long'.repeat(20);
  const code = 'const syntheticBoundary = "' + 'wide'.repeat(40) + '";';
  const table = '| Synthetic column | Other column |\n| --- | --- |\n| ' + 'table'.repeat(40) + ' | Recorded value |';
  await page.route(url => url.pathname === '/api/sessions', async route => {
    const response = await route.fetch();
    const body = await response.json() as { sessions: SessionRow[] };
    await route.fulfill({ response, json: { ...body, sessions: body.sessions.map(row => row.session_id === STACK.sender.id ? { ...row, head: STACK.oauthHead } : row) } });
  });
  await page.route(url => url.pathname === '/api/heads', async route => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    await route.fulfill({ response, json: { ...body, heads: body.heads.map(head => head.key === STACK.oauthHead ? { ...head, label: command } : head) } });
  });
  await page.route('**/api/sessions/' + STACK.sender.id + '/transcript?*', route => route.fulfill({ json: {
    session_id: STACK.sender.id, path: '/synthetic/transcript.jsonl', earlier: null, messages: [
      { index: 0, role: 'user', ts: 1_700_000_000_000, text: 'Synthetic message.\n\n```typescript\n' + code + '\n```\n\n' + table },
      { index: 1, role: 'assistant', ts: 1_700_000_001_000, text: 'Synthetic complete answer.' },
      { index: 2, role: 'system', text: '<cross-session-message from="uds:/synthetic/peer.sock" from-name="' + peerName + '">Synthetic peer message.</cross-session-message>' },
    ],
  } }));
  const faults = await open(page, 'sessions/' + STACK.sender.id);
  await expect(page.locator('.msg .who .m')).toHaveText(command);
  await expect(page.locator('.msg.peer .stamp').filter({ hasText: peerName })).toContainText(peerName);
  await expect(page.locator('.msg pre.code')).toHaveText(code);
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 980 });
    const overflow = await page.evaluate(() => ({
      viewport: innerWidth,
      document: document.documentElement.scrollWidth,
      outside: [...document.querySelectorAll('.top, .top > *, .facts, .acts, .cols, .sheet, .bar, .seg, .convo, .msg, .who, .stamp, .rail, .seat, .composer')].map(node => {
        const bounds = node.getBoundingClientRect();
        return { class: node.className, left: bounds.left, right: bounds.right, width: bounds.width };
      }).filter(bounds => bounds.left < 0 || bounds.right > innerWidth),
    }));
    expect(overflow).toEqual({ viewport: width, document: width, outside: [] });
    for (const message of await page.locator('.convo > .msg').all()) {
      const bounds = await message.boundingBox();
      expect(bounds).not.toBeNull();
      expect((bounds?.x ?? Infinity) + (bounds?.width ?? Infinity)).toBeLessThanOrEqual(width);
      for (const label of await message.locator('.who, .who > span, .stamp').all()) {
        const labelBounds = await label.boundingBox();
        expect(labelBounds).not.toBeNull();
        expect(labelBounds?.x ?? -Infinity).toBeGreaterThanOrEqual(bounds?.x ?? Infinity);
        expect((labelBounds?.x ?? Infinity) + (labelBounds?.width ?? Infinity)).toBeLessThanOrEqual((bounds?.x ?? 0) + (bounds?.width ?? 0));
      }
    }
    await page.locator('.cols').screenshot({ path: test.info().outputPath('session-message-width-' + width + '.png') });
  }
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
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
  test(board + (board === 'sessions' ? ' cards reorder by their handles and keep the order' : ' cards reorder by their bodies without rendering grips'), async ({ page }) => {
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
    const activator = board === 'sessions' ? '.drag-handle' : '.quiet-meta';
    const start = await cards.first().locator(activator).first().boundingBox();
    const finish = await cards.nth(1).locator(activator).first().boundingBox();
    if (start === null || finish === null) throw new Error('card drag controls have no layout');
    await page.mouse.move(start.x + start.width / 2, start.y + start.height / 2);
    await page.mouse.down();
    await page.mouse.move(finish.x + finish.width / 2, finish.y + finish.height / 2, { steps: 12 });
    await page.mouse.up();
    await expect.poll(() => names.allTextContents()).not.toEqual(before);
    const after = await names.allTextContents();
    expect([...after].sort()).toEqual([...before].sort());
    await page.reload();
    await expect.poll(() => names.allTextContents()).toEqual(after);
    if (board === 'sessions') await expect(cards.locator('.drag-handle')).toHaveCount(after.length);
    else await expect(page.locator('.grip')).toHaveCount(0);
    expect(faults.pageErrors).toEqual([]);
    await page.unrouteAll({ behavior: 'wait' });
  });
}

const FRAMES = [
  { width: 3840, height: 2060, used: 0.8, body: 20, caption: 16 },
  { width: 1600, height: 1000, used: 0.7, body: 17, caption: 12 },
] as const;

// Focused Settings retains a 77.5rem form canvas, not the former all-sections board.
const SETTINGS_CANVAS_REM = 77.5;

async function drawing(page: Page, area: Locator = page.getByRole('main')) {
  return area.evaluate((root) => {
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
      const rects = [...range.getClientRects()].filter((rect) => rect.width > 1 && rect.height > 1);
      if (rects.length === 0) continue;
      smallest.push(parseFloat(style.fontSize));
      drawn.push(...rects);
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

// Every board retains its screen share. Focused Settings retains its independently pinned form canvas.
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
      const focused = route === 'settings';
      const area = focused ? page.locator('.settings-page .split') : page.getByRole('main');
      const drawingWidth = focused
        ? SETTINGS_CANVAS_REM * await page.evaluate(() => parseFloat(getComputedStyle(document.documentElement).fontSize))
        : frame.width;
      if (focused) {
        const box = await area.boundingBox();
        expect.soft(box?.width ?? 0, 'the focused form keeps its declared canvas').toBeGreaterThanOrEqual(drawingWidth - 1);
        expect.soft(box?.width ?? 0, 'the focused form does not become the former board').toBeLessThanOrEqual(drawingWidth + 1);
        await expect(page.locator('.settings-sheet:visible')).toHaveCount(1);
      }
      // Late content may widen a board. Empty boxes never count as drawn glyphs or graphics.
      await expect.soft.poll(async () => { const now = await drawing(page, area); return (now.right - now.left) / drawingWidth; },
        { message: 'actual glyphs and graphics use the retained work area', timeout: 10_000 }).toBeGreaterThanOrEqual(frame.used);
      const measured = await drawing(page, area);
      expect.soft(measured.body, 'body text keeps the retained pixel floor').toBeGreaterThanOrEqual(frame.body);
      expect.soft(measured.smallest, 'no text is below the caption floor').toBeGreaterThanOrEqual(frame.caption);
      expect.soft(measured.overflow, 'the document does not require horizontal scrolling').toBeLessThanOrEqual(1);
      if (focused) {
        const prior = await area.evaluate((element, rem) => {
          const style = (element as HTMLElement).style;
          const previous = style.maxWidth;
          style.maxWidth = rem + 'rem';
          return previous;
        }, SETTINGS_CANVAS_REM / 2);
        try {
          const shrunk = await drawing(page, area);
          expect((shrunk.right - shrunk.left) / drawingWidth, 'a half-width form fails the same drawing floor').toBeLessThan(frame.used);
        } finally {
          await area.evaluate((element, previous) => { (element as HTMLElement).style.maxWidth = previous; }, prior);
        }
      }
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
  expect(await clipped(row.locator('.sub span, .sub a, .state, .tok'))).toEqual([]);
  // The row's title opens the request; its command, model and account narrow the list.
  await row.getByRole('heading').getByRole('link').click();
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
