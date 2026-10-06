// NEW: V4-444 — runtime/quota/refused-account signals over the actual replacement cards and plan pages.
import { expect, test, type Page } from '@playwright/test';
import type { AuthPayload, ControlStatusPayload, HeadsPayload, UsagePayload } from '../src/types/core';
import type { KeysPayload } from '../src/types/login';
import type { AccountsWire } from '../src/types/accounts';
import { STACK } from './stack';
import { assertHealthy, env, open, read } from './support';

/** Every card's words on one line each, so a failed count says which head stood in which state. */
async function cardStates(page: Page): Promise<string> {
  const cards = await page.locator('li.card').all();
  return (await Promise.all(cards.map(async (card) => (await card.innerText()).replace(/\s+/g, ' ').trim().slice(0, 160)))).join('\n');
}

test('command metadata wraps without leading separator dots', async ({ page }, testInfo) => {
  const heads = await read<HeadsPayload>(page, '/api/heads');
  const head = heads.heads.find(row => row.key === STACK.oauthHead);
  if (head === undefined) throw new Error('isolated stack needs a synthetic subscription command');
  head.label = 'Synthetic metadata command';
  head.last_provider_answer = { accepted: true, status: 200, observed_at_epoch_ms: Date.now() };
  await page.route(url => url.pathname === '/api/heads', route => route.fulfill({ json: heads }));
  await page.route(url => url.pathname === '/api/accounts', async route => {
    const response = await route.fetch();
    const body = await response.json() as AccountsWire;
    const account = body.accounts.find(row => row.heads.includes(STACK.oauthHead));
    if (account === undefined) throw new Error('isolated stack needs a synthetic account');
    account.identity_verified = true;
    account.account = { uuid: 'synthetic-metadata-identity', email: 'long-synthetic-metadata@example.invalid' };
    body.accounts = body.accounts.filter(row => row === account || !row.heads.includes(STACK.oauthHead));
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'models');
  const card = page.locator('li.card').filter({ has: page.getByRole('link', { name: head.label, exact: true }) });
  const meta = card.locator('.quiet-meta').first();
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 1024 });
    await expect(meta).toContainText('long-synthetic-metadata@example.invalid');
    const lines = await meta.evaluate(area => {
      let previousTop: number | null = null;
      let rows = 0;
      let leadingDots = 0;
      for (const span of area.querySelectorAll(':scope > span')) {
        const box = span.getBoundingClientRect();
        if (box.width === 0) continue;
        if (previousTop === null || Math.abs(box.top - previousTop) > 2) {
          rows++;
          if (getComputedStyle(span, '::before').content.includes('·')) leadingDots++;
        }
        previousTop = box.top;
      }
      const actualLines: { top: number; glyphs: { left: number; text: string }[] }[] = [];
      const walker = document.createTreeWalker(area, NodeFilter.SHOW_TEXT);
      let node: Node | null;
      while ((node = walker.nextNode()) !== null) {
        const value = node.textContent ?? '';
        for (let index = 0; index < value.length; index++) {
          const range = document.createRange();
          range.setStart(node, index);
          range.setEnd(node, index + 1);
          const box = range.getBoundingClientRect();
          if (box.width === 0 || value[index]?.trim() === '') continue;
          let line = actualLines.find(candidate => Math.abs(candidate.top - box.top) < 2);
          if (line === undefined) {
            line = { top: box.top, glyphs: [] };
            actualLines.push(line);
          }
          line.glyphs.push({ left: box.left, text: value[index] ?? '' });
        }
      }
      leadingDots += actualLines.filter(line => line.glyphs.sort((left, right) => left.left - right.left).map(glyph => glyph.text).join('').startsWith('·')).length;
      return { rows: Math.max(rows, actualLines.length), leadingDots };
    });
    if (width === 393) expect(lines.rows).toBeGreaterThan(1);
    expect(lines.leadingDots).toBe(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await card.screenshot({ path: testInfo.outputPath('command-metadata-' + width + '.png') });
  }
  await assertHealthy(page, faults);
});

test('unknown readiness uses theme amber while accepted and refused commands keep green and red', async ({ page }, testInfo) => {
  const heads = await read<HeadsPayload>(page, '/api/heads');
  const unknown = heads.heads.find(head => head.key === STACK.soloHead);
  const accepted = heads.heads.find(head => head.key === STACK.oauthHead);
  const refused = heads.heads.find(head => head.key === STACK.keyHead);
  if (unknown === undefined || accepted === undefined || refused === undefined) throw new Error('isolated stack needs all three synthetic commands');
  unknown.label = 'Synthetic unknown command';
  unknown.last_provider_answer = null;
  accepted.label = 'Synthetic accepted command';
  accepted.last_provider_answer = { status: 200, accepted: true, observed_at_epoch_ms: Date.now() };
  refused.label = 'Synthetic refused command';
  refused.last_provider_answer = { status: 403, accepted: false, observed_at_epoch_ms: Date.now() };
  await page.route(url => url.pathname === '/api/heads', route => route.fulfill({ json: heads }));
  await page.route(url => url.pathname === '/api/auth', async route => {
    const response = await route.fetch();
    const body = await response.json() as AuthPayload;
    const credential = body[STACK.keyHead];
    if (credential === undefined) throw new Error('isolated stack needs key-head authentication');
    credential.present = true;
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'models');
  const card = (name: string) => page.locator('li.card').filter({ has: page.getByRole('link', { name, exact: true }) });
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 1024 });
    for (const theme of ['Day', 'Night']) {
      await page.getByRole('button', { name: theme, exact: true }).click();
      for (const [name, word, day, night] of [
        [unknown.label, 'Not checked yet', 'rgb(125, 84, 25)', 'rgb(231, 192, 105)'],
        [accepted.label, 'Ready', 'rgb(44, 101, 85)', 'rgb(97, 190, 163)'],
        [refused.label, 'Access refused', 'rgb(165, 59, 57)', 'rgb(238, 122, 112)'],
      ] as const) {
        const state = card(name).locator('.state').filter({ hasText: new RegExp('^' + word + '$') });
        await expect(state).toHaveCount(1);
        await expect(state.locator('i')).toHaveCSS('background-color', theme === 'Day' ? day : night);
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await card(unknown.label).screenshot({ path: testInfo.outputPath('readiness-attention-' + width + '-' + theme.toLowerCase() + '.png') });
    }
  }
  await assertHealthy(page, faults);
});

test('a ready command shows its real session launcher and copies it without starting or restarting the daemon', async ({ page }, testInfo) => {
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  const heads = await read<HeadsPayload>(page, '/api/heads');
  const ready = heads.heads.find(head => head.key === STACK.soloHead);
  if (ready === undefined) throw new Error('isolated stack has no solo command');
  ready.label = 'claude-ready-example';
  ready.last_provider_answer = { status: 200, observed_at_epoch_ms: Date.now() - 60_000, accepted: true };
  await page.route('**/api/heads', route => route.fulfill({ json: heads }));
  let lifecyclePosts = 0;
  page.on('request', request => {
    if (request.method() === 'POST' && /\/api\/heads\/[^/]+\/(start|restart)$/.test(new URL(request.url()).pathname)) lifecyclePosts += 1;
  });
  const faults = await open(page, 'models');
  const card = page.locator('li.card').filter({ has: page.getByRole('link', { name: ready.label, exact: true }) });
  await expect(card.getByText('Ready', { exact: true })).toBeVisible();
  await expect(card).toContainText('Start a session in your terminal:');
  await expect(card.locator('.acts code')).toHaveText(ready.label);
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 1024 });
    for (const theme of ['Day', 'Night']) {
      await page.getByRole('button', { name: theme, exact: true }).click();
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
      await expect(card.getByRole('button', { name: 'Copy session command', exact: true })).toBeVisible();
      await card.screenshot({ path: testInfo.outputPath('ready-session-command-' + width + '-' + theme.toLowerCase() + '.png') });
    }
  }
  await card.getByRole('button', { name: 'Copy session command', exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(ready.label);
  await expect(card.getByRole('button', { name: 'Copied', exact: true })).toBeVisible();
  await card.getByRole('link', { name: ready.label, exact: true }).click();
  await expect(page.locator('header.top')).toContainText('Start a session in your terminal:');
  await page.getByRole('button', { name: 'Copy session command', exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(ready.label);
  await page.evaluate(() => {
    navigator.clipboard.writeText = () => Promise.reject(new Error('Synthetic clipboard denied'));
  });
  await expect(page.getByRole('button', { name: 'Copy session command', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Copy session command', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Synthetic clipboard denied');
  await expect(page.locator('header.top code')).toHaveText(ready.label);
  expect(lifecyclePosts).toBe(0);
  await assertHealthy(page, faults);
});

test('Models and its detail use the last real provider answer instead of daemon liveness or a missing gauge', async ({ page }, testInfo) => {
  const heads = await read<HeadsPayload>(page, '/api/heads');
  const provider = heads.heads.find(head => head.key === STACK.soloHead);
  if (provider === undefined) throw new Error('isolated stack has no solo provider command');
  const observed = Date.now() - 3_600_000;
  provider.label = 'claude-provider-observation';
  provider.last_provider_answer = { status: 403, observed_at_epoch_ms: observed, accepted: false };
  await page.route('**/api/heads', route => route.fulfill({ json: heads }));
  const faults = await open(page, 'models');
  const card = () => page.locator('li.card').filter({ has: page.getByRole('link', { name: provider.label, exact: true }) });
  await expect(card().getByText('Access refused', { exact: true })).toBeVisible();
  const details = () => card().locator('details').filter({ has: page.getByText('Connection details', { exact: true }) });
  await expect(details()).not.toHaveAttribute('open');
  await expect(details().locator('code')).toHaveText('HTTP 403');
  await expect(details().locator('code')).not.toBeVisible();
  await expect(card()).toContainText('1h ago');
  await expect(card()).not.toContainText('No reading yet');
  await expect(card().getByText('Ready', { exact: true })).toHaveCount(0);
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 1024 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const order = await page.locator('li.card .card-link').allTextContents();
    await details().locator('summary').focus();
    await details().locator('summary').press('Enter');
    await expect(details().locator('code')).toBeVisible();
    expect(await page.locator('li.card .card-link').allTextContents()).toEqual(order);
    await details().locator('summary').click();
    await expect(details().locator('code')).not.toBeVisible();
    await card().screenshot({ path: testInfo.outputPath('provider-refused-' + width + '.png') });
  }
  await card().getByRole('link', { name: provider.label, exact: true }).click();
  await expect(page.locator('header.top')).toContainText('Access refused');
  const detailDisclosure = page.getByRole('main').locator('details').filter({ has: page.getByText('Connection details', { exact: true }) });
  await expect(detailDisclosure.locator('code')).toHaveText('HTTP 403');
  await expect(detailDisclosure.locator('code')).not.toBeVisible();
  await detailDisclosure.locator('summary').click();
  await expect(detailDisclosure.locator('code')).toBeVisible();
  await detailDisclosure.locator('summary').click();
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 1024 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.getByRole('main').screenshot({ path: testInfo.outputPath('provider-detail-' + width + '.png') });
  }
  await expect(page.getByRole('main')).not.toContainText('No reading yet');
  provider.last_provider_answer = { status: 429, observed_at_epoch_ms: observed, accepted: false };
  await open(page, 'models');
  await page.reload();
  await expect(card().getByText('Rate limited', { exact: true })).toBeVisible();
  await expect(details().locator('code')).toHaveText('HTTP 429');
  await expect(details().locator('code')).not.toBeVisible();
  await details().locator('summary').click();
  await expect(details().locator('code')).toBeVisible();
  await expect(card()).toContainText('1h ago');
  provider.last_provider_answer = null;
  await page.reload();
  await expect(card().getByText('Not checked yet', { exact: true })).toBeVisible();
  await expect(card().getByText('Ready', { exact: true })).toHaveCount(0);
  await expect(details()).toHaveCount(0);
  provider.last_provider_answer = { status: 200, observed_at_epoch_ms: Date.now(), accepted: true };
  await page.reload();
  await expect(card().getByText('Ready', { exact: true })).toBeVisible();
  await expect(details().locator('code')).toHaveText('HTTP 200');
  await expect(details().locator('code')).not.toBeVisible();
  await details().locator('summary').click();
  await expect(details().locator('code')).toBeVisible();
  provider.last_provider_answer = { status: null, observed_at_epoch_ms: Date.now(), accepted: true };
  await page.reload();
  await expect(card().getByText('Ready', { exact: true })).toBeVisible();
  await expect(card()).toContainText('Last request accepted');
  await expect(details()).toHaveCount(0);
  await expect(card()).not.toContainText('HTTP 200');
  await assertHealthy(page, faults);
});

test('a key-auth head with a stored key reads as local when the daemon names its family', async ({ page }) => {
  await page.route((url) => url.pathname === '/api/status', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as ControlStatusPayload;
    const local = body.registry.find((row) => row.key === STACK.keyHead);
    if (local === undefined) throw new Error('isolated stack has no API-key head');
    local.family = 'local';
    await route.fulfill({ response, json: body });
  });
  await page.route((url) => url.pathname === '/api/keys', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as KeysPayload;
    body.keys.push({ name: 'E2E_LOCAL_KEY', stored: true, heads: [{ head: STACK.keyHead, source: 'store' }] });
    await route.fulfill({ response, json: body });
  });
  await open(page, 'models');
  const card = page.locator('li.card').filter({ has: page.getByRole('link', { name: STACK.keyHead, exact: true }) });
  await expect(card.locator('.quiet-meta').first()).toContainText('this computer');
  await expect(card.locator('.quiet-meta').first()).not.toContainText('api key');
  await card.getByRole('link', { name: STACK.keyHead, exact: true }).click();
  await expect(page.locator('header.top .quiet-meta')).toContainText('this computer');
  await expect(page.locator('header.top .quiet-meta')).not.toContainText('api key');
  await expect(page.getByRole('main')).not.toContainText('Pays per token; no window');
  await page.unrouteAll({ behavior: 'ignoreErrors' });
});

for (const authKind of ['bearer', 'local']) {
  test(`a remote family stays remote on Fleet and detail with auth kind ${authKind}`, async ({ page }) => {
    await page.route((url) => url.pathname === '/api/heads', async (route) => {
      const response = await route.fetch();
      const body = await response.json() as HeadsPayload;
      const head = body.heads.find((row) => row.key === STACK.keyHead);
      if (head === undefined) throw new Error('isolated stack has no key head');
      head.authKind = authKind;
      await route.fulfill({ response, json: body });
    });
    await page.route((url) => url.pathname === '/api/status', async (route) => {
      const response = await route.fetch();
      const body = await response.json() as ControlStatusPayload;
      const head = body.registry.find((row) => row.key === STACK.keyHead);
      if (head === undefined) throw new Error('isolated stack has no key head');
      head.family = 'openrouter';
      await route.fulfill({ response, json: body });
    });
    const faults = await open(page, 'models');
    const card = page.locator('li.card').filter({ has: page.getByRole('link', { name: STACK.keyHead, exact: true }) });
    await expect(card.locator('.quiet-meta').first()).toContainText('api key');
    await expect(card.locator('.quiet-meta').first()).not.toContainText('this computer');
    await expect(card.getByText('Key missing', { exact: true })).toBeVisible();
    await expect(card.getByRole('button', { name: 'Copy the key command', exact: true })).toBeVisible();
    await expect(card).not.toContainText('until you sign in');
    await card.getByRole('link', { name: STACK.keyHead, exact: true }).click();
    await expect(page.locator('header.top .quiet-meta')).toContainText('api key');
    await expect(page.locator('header.top .quiet-meta')).not.toContainText('this computer');
    await expect(page.getByRole('main')).toContainText('Pays per token; no window');
    await expect(page.getByRole('button', { name: 'Store key', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Copy the key command', exact: true })).toBeVisible();
    await assertHealthy(page, faults);
    await page.unrouteAll({ behavior: 'ignoreErrors' });
  });
}

test('a silent runtime is off on its card and detail while unmarked plans remain ready', async ({ page }) => {
  await page.route((url) => url.pathname === '/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    const marked = body.heads.find((head) => head.key === STACK.oauthHead);
    if (marked === undefined) throw new Error('isolated stack has no OAuth head');
    expect(marked.running).toBe(true);
    marked.runtimeNotAnswering = ':8099';
    await route.fulfill({ response, json: body });
  });
  await open(page, 'models');
  const card = page.locator('li.card').filter({ has: page.getByRole('link', { name: STACK.oauthHead, exact: true }) });
  await expect(card.getByText('Model not responding', { exact: true })).toBeVisible();
  await expect(card).toContainText('The model on this computer is not responding.');
  const details = card.locator('details').filter({ has: page.getByText('Connection details', { exact: true }) });
  await expect(details.locator('code')).toContainText(':8099');
  await expect(details.locator('code')).not.toBeVisible();
  await details.locator('summary').click();
  await expect(details.locator('code')).toBeVisible();
  await expect(page.locator('li.card').filter({ hasNot: page.getByRole('link', { name: STACK.oauthHead, exact: true }) }).getByText('Model not responding', { exact: true })).toHaveCount(0);
  await expect(page.locator('main .lede')).toContainText('one not available');
  await card.getByRole('link', { name: STACK.oauthHead, exact: true }).click();
  await expect(page.getByRole('main')).toContainText('Model not responding');
  const detailDisclosure = page.getByRole('main').locator('details').filter({ has: page.getByText('Connection details', { exact: true }) });
  await expect(detailDisclosure.locator('code')).toContainText(':8099');
  await expect(detailDisclosure.locator('code')).not.toBeVisible();
  await detailDisclosure.locator('summary').click();
  await expect(detailDisclosure.locator('code')).toBeVisible();
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.getByRole('button', { name: 'Copy start command', exact: true }).click();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe('rig up ' + STACK.oauthHead);
});

test('critical and warning readings differ on Models and detail without inventing a refused turn', async ({ page }, testInfo) => {
  await page.route('**/api/status', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as ControlStatusPayload;
    const head = body.registry.find((row) => row.key === STACK.oauthHead);
    if (head === undefined) throw new Error('isolated stack has no OAuth registry head');
    head.family = 'openai';
    await route.fulfill({ response, json: body });
  });
  const reset = Math.floor(Date.now() / 1000) + 3600;
  let pct = 100;
  await page.route((url) => url.pathname === '/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    const marked = body.heads.find((head) => head.key === STACK.oauthHead);
    if (marked === undefined) throw new Error('isolated stack has no OAuth head');
    delete marked.quotaResetAtEpochSeconds;
    marked.last_provider_answer = { status: 200, observed_at_epoch_ms: Date.now() - 60_000, accepted: true };
    await route.fulfill({ response, json: body });
  });
  await page.route((url) => url.pathname === '/api/usage' || url.pathname === '/api/usage/probe', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as UsagePayload;
    const marked = body.heads.find((head) => head.key === STACK.oauthHead);
    if (marked === undefined || marked.usage === null) throw new Error('isolated stack has no OAuth usage');
    marked.usage.quota = { five_hour: { used_pct: pct, resets_at: reset, observed_at: Math.floor(Date.now() / 1000) } };
    await route.fulfill({ response, json: body });
  });
  // No switch target: usage remains the source for the head with no account row.
  await page.route((url) => url.pathname === '/api/accounts', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as AccountsWire;
    body.accounts = body.accounts.filter((account) => !account.heads.includes(STACK.oauthHead));
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'models');
  const card = page.locator('li.card').filter({ has: page.getByRole('link', { name: STACK.oauthHead, exact: true }) });
  await expect(card.getByText('At reported limit', { exact: true })).toBeVisible();
  await expect(card.getByText('Ready', { exact: true })).toHaveCount(0);
  await expect(card).not.toContainText('Out of quota');
  await expect(card).toContainText('Last request accepted');
  await expect(card.locator('.track')).not.toHaveClass(/full/);
  await expect(card.locator('.track i')).toHaveCSS('width', await card.locator('.track').evaluate((node) => getComputedStyle(node).width));
  const local = await page.evaluate((seconds) => new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'short',
  }).format(new Date(seconds * 1000)), reset);
  await expect(card.locator('.gl small')).toHaveText('at its reported limit · 100%, resets ' + local);
  await expect(page.locator('main .lede')).toContainText('one with high quota use');
  await card.getByRole('link', { name: STACK.oauthHead, exact: true }).click();
  await expect(page.locator('header.top')).toContainText('At reported limit');
  await expect(page.getByRole('main')).not.toContainText('Out of quota');
  pct = 98;
  await open(page, 'models');
  await page.reload();
  await expect(card.getByText('Quota nearly used', { exact: true })).toBeVisible();
  await expect(card.locator('.gl small')).toHaveText('almost at its reported limit · 98%, resets ' + local);
  await expect(card.locator('.track')).not.toHaveClass(/full/);
  await card.getByRole('link', { name: STACK.oauthHead, exact: true }).click();
  await expect(page.locator('header.top')).toContainText('Quota nearly used');
  pct = 85;
  await open(page, 'models');
  await page.reload();
  await expect(card.getByText('Ready', { exact: true })).toBeVisible();
  await expect(card.locator('.gl small')).toHaveText('near its limit · 85%, resets ' + local);
  await expect(page.locator('main .lede')).not.toContainText('with high quota use');
  pct = 100;
  await page.reload();
  await expect(card.getByText('At reported limit', { exact: true })).toBeVisible();
  await expect(card.locator('.gl small')).toHaveText('at its reported limit · 100%, resets ' + local);
  expect((await card.locator('.gl').innerText()).match(/100%/g)).toHaveLength(1);
  for (const width of [1536, 393]) {
    await page.setViewportSize({ width, height: 1024 });
    for (const theme of ['Day', 'Night']) {
      await page.getByRole('button', { name: theme, exact: true }).click();
      const colours = await card.locator('.track i').evaluate((node) => {
        const style = getComputedStyle(node);
        const probe = document.createElement('i');
        node.append(probe);
        probe.style.color = 'var(--gpt)';
        const command = getComputedStyle(probe).color;
        probe.style.color = 'var(--stuck)';
        const refusal = getComputedStyle(probe).color;
        probe.remove();
        return { fill: style.backgroundColor, image: style.backgroundImage, command, refusal };
      });
      expect(colours.fill).toBe(colours.command);
      expect(colours.fill).not.toBe(colours.refusal);
      expect(colours.image).toBe('none');
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await card.screenshot({ path: testInfo.outputPath('critical-reading-' + width + '-' + theme.toLowerCase() + '.png') });
    }
  }
  await page.getByRole('link', { name: 'Usage', exact: true }).click();
  await expect(page.locator('main .lede')).toContainText(STACK.oauthHead + ' is at 100% of its 5-hour limit.');
  const plan = page.locator('li.uplan').filter({ hasText: STACK.oauthHead }).first();
  await expect(plan.locator('.track')).not.toHaveClass(/full/);
  // The removed global observations feed has no nav badge; serving and quota facts stay on Models and Usage.
  await assertHealthy(page, faults);
  await page.unrouteAll({ behavior: 'wait' });
});

test('quota refusal moves one card out of ready and keeps its local reset identical on the plan page', async ({ page }) => {
  const reset = Math.floor(Date.now() / 1000) + 3 * 86_400;
  let marking = false;
  await page.route((url) => url.pathname === '/api/heads', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as HeadsPayload;
    const marked = body.heads.find((head) => head.key === STACK.oauthHead);
    if (marked === undefined) throw new Error('isolated stack has no OAuth head');
    expect(marked.running).toBe(true);
    marked.last_provider_answer = { status: marking ? 429 : 200, observed_at_epoch_ms: Date.now(), accepted: !marking };
    if (marking) marked.quotaResetAtEpochSeconds = reset;
    await route.fulfill({ response, json: body });
  });
  await open(page, 'models');
  await expect(page.locator('li.card').getByText('Ready', { exact: true }).first()).toBeVisible();
  marking = true;
  await page.reload();
  const card = page.locator('li.card').filter({ has: page.getByRole('link', { name: STACK.oauthHead, exact: true }) });
  const state = card.getByText(/^Out of quota until /);
  await expect(state).toBeVisible();
  const sentence = await state.innerText();
  const local = await page.evaluate((seconds) => new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'short',
  }).format(new Date(seconds * 1000)), reset);
  expect(sentence).toBe('Out of quota until ' + local);
  // The marked card alone must leave Ready and be the only one out of quota. No count of the other cards is taken: the
  // stack's heads are still moving between states while the page first loads, which made a before/after count flake.
  try {
    await expect(card.getByText('Ready', { exact: true })).toHaveCount(0);
    await expect(page.locator('li.card').getByText(/^Out of quota/)).toHaveCount(1);
  } catch (error) {
    throw new Error(`the quota refusal must mark only ${STACK.oauthHead}; every card:\n${await cardStates(page)}`, { cause: error });
  }
  await expect(page.locator('main .lede')).toContainText('one out of quota');
  await expect(card.locator('.track')).toHaveClass(/full/);
  const refusal = await card.locator('.track i').evaluate((node) => {
    const probe = document.createElement('i');
    node.append(probe);
    probe.style.color = 'var(--stuck)';
    const colour = getComputedStyle(probe).color;
    probe.remove();
    return { colour, image: getComputedStyle(node).backgroundImage };
  });
  expect(refusal.image).toContain(refusal.colour);
  await card.getByRole('link', { name: STACK.oauthHead, exact: true }).click();
  await expect(page.getByRole('main').getByText(sentence, { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Usage', exact: true }).click();
  await expect(page.locator('main .lede')).toContainText(STACK.oauthHead + ' is out of quota.');
  const plan = page.locator('li.uplan').filter({ hasText: STACK.oauthHead }).first();
  await expect(plan.locator('.track')).toHaveClass(/full/);
  const usageRefusal = await plan.locator('.track i').evaluate((node) => {
    const probe = document.createElement('i');
    node.append(probe);
    probe.style.color = 'var(--stuck)';
    const colour = getComputedStyle(probe).color;
    probe.remove();
    return { colour, image: getComputedStyle(node).backgroundImage };
  });
  expect(usageRefusal.image).toContain(usageRefusal.colour);
  await page.unrouteAll({ behavior: 'wait' });
});

test('refused credentials keep the daemon sentence and allow neither switching nor renewal', async ({ page }) => {
  const refusal = "'" + STACK.poolLabel + "' is a symbolic link, and splice does not load a linked credential; remove the link and sign in again, or sign in under a different label";
  await page.route('**/api/accounts', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as AccountsWire;
    const linked = body.accounts.find((account) => account.label === STACK.poolLabel);
    if (linked === undefined) throw new Error('isolated stack has no pooled account');
    linked.credential_present = false;
    linked.refusal = refusal;
    await route.fulfill({ response, json: body });
  });
  await open(page, 'accounts');
  const need = page.locator('li.account-card').filter({ hasText: refusal });
  await expect(need).toBeVisible();
  await expect(need).toContainText(STACK.poolLabel);
  await expect(need.getByText('Credential refused', { exact: true })).toBeVisible();
  await expect(need.getByRole('button', { name: 'Sign in again', exact: true })).toBeDisabled();
  await expect(need).not.toContainText('Its login is gone');
  await page.goto(env('CONSOLE_E2E_BASE') + '/#/models/' + STACK.oauthHead);
  const account = page.locator('li.account').filter({ has: page.getByText(STACK.poolLabel, { exact: true }) });
  await expect(account.getByText('refused', { exact: true })).toBeVisible();
  await expect(account).toContainText(refusal);
  await expect(account.getByText('Signed out', { exact: true })).toHaveCount(0);
  await expect(account.getByRole('button', { name: 'Switch to this one', exact: true })).toHaveCount(0);
  await expect(account.getByRole('button', { name: 'Sign in again', exact: true })).toHaveCount(0);
  await expect(account.getByRole('button', { name: 'Rename', exact: true })).toBeVisible();
  await expect(account.getByRole('button', { name: 'Remove', exact: true })).toBeVisible();
  const serving = page.locator('li.account').filter({ hasNot: page.getByText(STACK.poolLabel, { exact: true }) }).first();
  await expect(serving).toBeVisible();
  await expect(serving.getByText('refused', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Refresh sign-in', exact: true })).toBeVisible();
  await page.unrouteAll({ behavior: 'wait' });
});

test('the real account pool keeps provider windows, its exact next target and serving actions separate from a single login', async ({ page }) => {
  const faults = await open(page, 'models/' + STACK.oauthHead);
  const wire = await read<AccountsWire>(page, '/api/accounts');
  const pool = wire.accounts.filter((account) => account.heads.includes(STACK.oauthHead));
  expect(pool).toHaveLength(2);
  const accounts = page.locator('li.account');
  await expect(accounts).toHaveCount(pool.length);
  for (const account of pool) {
    const row = accounts.filter({ has: page.locator('b').getByText(account.label ?? '', { exact: true }) });
    await expect(row).toBeVisible();
    if (account.five_hour_used_percent !== null) {
      if (account.five_hour_window_seconds === null) throw new Error('reported short usage has no window');
      await expect(row).toContainText(account.five_hour_window_seconds / 3600 + 'h ' + account.five_hour_used_percent + '%');
    }
    if (account.seven_day_used_percent !== null) {
      if (account.seven_day_window_seconds === null) throw new Error('reported long usage has no window');
      await expect(row).toContainText(account.seven_day_window_seconds / 86400 + 'd ' + account.seven_day_used_percent + '%');
    }
    await expect(row.getByText('Next', { exact: true })).toHaveCount(account.next_target === true ? 1 : 0);
    if (account.next_target === true && account.primary) await expect(row).not.toContainText('Next because it is the primary account.');
    if (account.label === STACK.poolLabel) {
      await expect(row.getByRole('button', { name: 'Switch to this one', exact: true })).toBeVisible();
      await expect(row.getByRole('button', { name: 'Rename', exact: true })).toBeVisible();
      await expect(row.getByRole('button', { name: 'Remove', exact: true })).toBeVisible();
    }
  }
  const main = page.getByRole('main');
  await expect(main).toContainText('No order is saved.');
  await expect(main).toContainText('available pinned account');
  await expect(main).toContainText('Each session keeps its account');
  await expect(main).toContainText('weekly reset comes soonest');
  await expect(main).not.toContainText('most weekly room');
  await expect(page.getByRole('button', { name: 'Refresh sign-in', exact: true })).toBeVisible();
  await expect(accounts.filter({ hasText: STACK.soloHead })).toHaveCount(0);
  await page.goto(env('CONSOLE_E2E_BASE') + '/#/models/' + STACK.keyHead);
  await expect(page.locator('li.account')).toHaveCount(0);
  await expect(page.getByRole('main')).toContainText('This command has no account pool: it uses one login or a key.');
  expect(faults.pageErrors).toEqual([]);
});

test('Models reads a saved account order instead of guessing a default from the Next flag', async ({ page }) => {
  await page.route('**/api/auth/' + STACK.oauthHead + '/order', route => route.fulfill({ json: {
    head: STACK.oauthHead, order: [STACK.poolLabel, STACK.oauthHead], effective_order: [STACK.poolLabel, STACK.oauthHead],
  } }));
  await open(page, 'models/' + STACK.oauthHead);
  const main = page.getByRole('main');
  await expect(main).toContainText('You set this order.');
  await expect(main).toContainText('after any available manual pin');
  await expect(main).toContainText('saved accounts in order');
  await expect(main).toContainText('primary account');
  await expect(main).toContainText('session’s previous account');
  await expect(main).toContainText('weekly reset comes soonest');
  await expect(main).toContainText('the one whose reset is nearest');
  await expect(main).not.toContainText('No order is saved.');
  await expect(main).not.toContainText('most weekly room');
});

test('Models keeps account priority reading while its order route is pending', async ({ page }) => {
  let release!: () => void;
  const released = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/auth/' + STACK.oauthHead + '/order', async route => {
    await released;
    await route.fulfill({ json: { head: STACK.oauthHead, order: [], effective_order: [STACK.oauthHead, STACK.poolLabel] } });
  });
  try {
    await open(page, 'models/' + STACK.oauthHead);
    const main = page.getByRole('main');
    await expect(main).toContainText('Reading failover order');
    await expect(main).not.toContainText('No order is saved.');
    await expect(main).not.toContainText('most weekly room');
    release();
    await expect(main).toContainText('No order is saved.');
    await expect(main).toContainText('the one whose reset is nearest');
  } finally {
    release();
  }
});

test('Models cannot invent a default account policy when the order source is unavailable', async ({ page }) => {
  await page.route('**/api/auth/' + STACK.oauthHead + '/order', route => route.fulfill({ status: 409, json: { error: 'Synthetic order source unavailable' } }));
  await open(page, 'models/' + STACK.oauthHead);
  const main = page.getByRole('main');
  await expect(main).toContainText('Account ordering is unavailable for this command.');
  await expect(main).not.toContainText('No order is saved.');
  await expect(main).not.toContainText('You set this order.');
  await expect(main).not.toContainText('most weekly room');
  await expect(main).not.toContainText('HTTP 409');
});

test('an excluded account prints its whole provider reason and cannot be switched to while a serving peer can', async ({ page }) => {
  const reason = 'Synthetic subscription is excluded until its provider accepts this login again.';
  await page.route('**/api/accounts', async (route) => {
    const response = await route.fetch();
    const body = await response.json() as AccountsWire;
    const account = body.accounts.find((row) => row.label === STACK.poolLabel);
    if (account === undefined) throw new Error('isolated stack has no pooled account');
    account.available = false;
    account.auth_excluded_until_epoch_millis = Date.now() + 3600_000;
    account.auth_exclusion_reason = reason;
    await route.fulfill({ response, json: body });
  });
  const faults = await open(page, 'models/' + STACK.oauthHead);
  const excluded = page.locator('li.account').filter({ has: page.getByText(STACK.poolLabel, { exact: true }) });
  await expect(excluded.getByText('excluded', { exact: true })).toBeVisible();
  await expect(excluded).toContainText(reason);
  await expect(excluded.getByRole('button', { name: 'Switch to this one', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Refresh sign-in', exact: true })).toBeVisible();
  expect(faults.pageErrors).toEqual([]);
  await page.unrouteAll({ behavior: 'wait' });
});
