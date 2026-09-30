// NEW: V4-444 — retained display floors and whole values on the replacement card/list surfaces.
import { expect, test, type Locator, type Page } from '@playwright/test';
import type { PerfTurnsWire } from '../src/types/perf';
import type { HeadsPayload } from '../src/types/core';
import { FIRST_READ_MS, open } from './support';
import { STACK } from './stack';

const FRAMES = [
  { width: 3840, height: 2060, used: 0.8, body: 20 },
  { width: 1600, height: 1000, used: 0.7, body: 17 },
] as const;

async function drawing(page: Page) {
  return page.getByRole('main').evaluate((root) => {
    const drawn: DOMRect[] = [];
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
      const parent = node.parentElement;
      if ((node.textContent ?? '').trim() === '' || parent === null) continue;
      const style = getComputedStyle(parent);
      if (style.visibility === 'hidden' || Number(style.opacity) === 0 || parent.closest('.sr') !== null) continue;
      const range = document.createRange();
      range.selectNodeContents(node);
      drawn.push(...[...range.getClientRects()].filter((rect) => rect.width > 1 && rect.height > 1));
    }
    for (const element of root.querySelectorAll('svg, img, canvas')) {
      const rect = element.getBoundingClientRect();
      if (rect.width > 1 && rect.height > 1) drawn.push(rect);
    }
    return {
      left: Math.max(0, Math.min(...drawn.map((rect) => rect.left))),
      right: Math.min(window.innerWidth, Math.max(...drawn.map((rect) => rect.right))),
      body: parseFloat(getComputedStyle(document.body).fontSize),
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

for (const frame of FRAMES) {
  for (const route of ['sessions', 'usage', 'turns']) {
    test(route + ' at ' + frame.width + ' keeps the retained drawn-width and reading floors', async ({ page }) => {
      await page.setViewportSize({ width: frame.width, height: frame.height });
      const faults = await open(page, route);
      await expect(page.getByRole('main').getByRole('listitem').first()).toBeVisible();
      await page.evaluate(() => document.fonts.ready);
      const measured = await drawing(page);
      expect.soft((measured.right - measured.left) / frame.width, 'actual glyphs and graphics use the retained screen share').toBeGreaterThanOrEqual(frame.used);
      expect.soft(measured.body, 'body text keeps the retained pixel floor').toBeGreaterThanOrEqual(frame.body);
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
  const faults = await open(page, 'fleet');
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
  const faults = await open(page, 'turns');
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
