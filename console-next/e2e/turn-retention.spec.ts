// NEW: V4-444 — retained turn bytes, failure identity, transcript privacy and capture writes.
import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import type { PerfTurnsWire, TurnRowWire } from '../src/types/perf';
import { env, open, read } from './support';
import { driveOneTurn, saveTranscript, STACK, TURN_PROMPT } from './stack';

async function newest(page: Page, head: string): Promise<number> {
  const body = await read<PerfTurnsWire>(page, '/api/perf/turns?head=' + head);
  const rows = body.heads.flatMap((entry) => entry.rows ?? []);
  const at = Math.max(...rows.map((row) => row.ts));
  expect(Number.isFinite(at), 'a synthetic turn must be recorded').toBe(true);
  return at;
}

async function openTurn(page: Page, head: string, at: number): Promise<void> {
  const href = '#/turns/' + head + '/' + at;
  // A turn's link is named for its session, not necessarily for its plan.
  const link = page.locator('a[href="' + href + '"]');
  await expect(link).toBeVisible({ timeout: 15_000 });
  await link.click();
  await expect.poll(() => new URL(page.url()).hash).toBe(href);
}

test('a recorded turn opens its own page with the exact received request and answer', async ({ page }) => {
  const faults = await open(page, 'turns');
  await driveOneTurn(Number(env('CONSOLE_E2E_SOLO_PORT')), env('CONSOLE_E2E_KEY'), undefined, STACK.soloModel);
  await openTurn(page, STACK.soloHead, await newest(page, STACK.soloHead));
  await page.getByRole('link', { name: 'Request and answer', exact: true }).click();
  for (const summary of await page.locator('main details summary').all()) await summary.click();
  await expect(page.getByRole('main')).toContainText(TURN_PROMPT);
  await expect(page.getByRole('main')).toContainText('console e2e answer');
  await expect(page.getByRole('heading', { name: 'API cost, estimated', exact: true })).toBeVisible();
  expect(faults.pageErrors).toEqual([]);
  expect(faults.failedReads).toEqual([]);
});

test('a late trace for the previous turn cannot replace the current whole failure sentence on any tab', async ({ page }) => {
  const at = Date.now();
  const rows: TurnRowWire[] = [0, 1].map((offset) => ({
    ts: at - offset, model: STACK.soloModel, outcome: 'error:conn-reset', compact: false,
    session: null, account: null, cache_cold: null, turn: 'synthetic-failure-' + offset,
    session_id: null, response_message_id: null, recv: 1, first_byte: 10, finish: 20, total: 20,
  }));
  await page.route('**/api/perf/turns?*', (route) => {
    const query = new URL(route.request().url()).searchParams;
    if (query.get('head') !== STACK.soloHead) return route.fallback();
    return route.fulfill({ json: {
      since: at - 86_400_000, n: 200,
      heads: [{ key: STACK.soloHead, label: STACK.soloHead, count: 2, returned: 2, truncated: false, oldest_held_ts: at - 1, rows }],
    } });
  });
  const reads: string[] = [];
  const settled: string[] = [];
  let release: (() => void) | undefined;
  await page.route('**/api/heads/*/trace?turn=*', async (route) => {
    const id = new URL(route.request().url()).searchParams.get('turn');
    if (id === null) throw new Error('missing synthetic trace id');
    reads.push(id);
    if (id === 'synthetic-failure-0') await new Promise<void>((resolve) => { release = resolve; });
    await route.fulfill({ json: {
      head: STACK.soloHead,
      turn: { id, ts: at, session: null, model: STACK.soloModel, compact: false, open: false,
        outcome: 'error:conn-reset', failure_sentence: 'The connection for ' + id + ' closed mid-request; retry with the same session.',
        rounds: 1, attempts: 1, total_ms: 20 },
      records: [],
    } });
    settled.push(id);
  });
  try {
    await open(page, 'turns');
    await openTurn(page, STACK.soloHead, at);
    await expect.poll(() => reads).toContain('synthetic-failure-0');
    await page.getByRole('link', { name: 'Turns', exact: true }).last().click();
    await openTurn(page, STACK.soloHead, at - 1);
    const sentence = 'The connection for synthetic-failure-1 closed mid-request; retry with the same session.';
    await expect(page.locator('main .failure-sentence')).toHaveText(sentence);
    release?.();
    await expect.poll(() => settled).toContain('synthetic-failure-0');
    for (const tab of ['Request and answer', 'Sent to the plan', 'Conversation']) {
      await page.getByRole('link', { name: tab, exact: true }).click();
      await expect(page.locator('main .failure-sentence')).toHaveText(sentence);
      await expect(page.getByRole('main')).not.toContainText('The connection for synthetic-failure-0');
    }
    expect(reads).toEqual(['synthetic-failure-0', 'synthetic-failure-1']);
  } finally {
    release?.();
    await page.unrouteAll({ behavior: 'wait' });
  }
});

test('turn transcript privacy clears an already open second profile and persists across reload', async ({ page }) => {
  const faults = await open(page, 'turns');
  const responseId = await driveOneTurn(Number(env('CONSOLE_E2E_OAUTH_PORT')), env('CONSOLE_E2E_KEY'), STACK.sender.id);
  saveTranscript(env('CONSOLE_E2E_TRANSCRIPT_ROOT'), STACK.sender.id, responseId, TURN_PROMPT, 'console e2e answer');
  const at = await newest(page, STACK.oauthHead);
  await openTurn(page, STACK.oauthHead, at);
  await expect(page.getByRole('main')).toContainText(TURN_PROMPT);
  await expect(page.getByRole('main')).toContainText('console e2e answer');
  const browser = page.context().browser();
  if (browser === null) throw new Error('no browser for independent privacy profile');
  const context = await browser.newContext();
  try {
    const other = await context.newPage();
    const otherFaults = await open(other, 'turns/' + STACK.oauthHead + '/' + at);
    await expect(other.getByRole('main')).toContainText(TURN_PROMPT);
    await page.getByRole('navigation', { name: 'Pages', exact: true }).getByRole('link', { name: 'Settings', exact: true }).click();
    await page.getByRole('link', { name: 'Storage', exact: true }).click();
    const toggle = page.getByRole('switch', { name: 'Transcript view', exact: true });
    await expect(toggle).toHaveAttribute('aria-checked', 'true');
    const patch = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/config' && response.request().method() === 'PATCH');
    await toggle.click();
    expect((await (await patch).json() as { applied: { transcriptView: boolean } }).applied.transcriptView).toBe(false);
    await expect(toggle).toHaveAttribute('aria-checked', 'false');
    await expect(other.getByRole('main')).not.toContainText(TURN_PROMPT, { timeout: 20_000 });
    await expect(other.getByRole('main')).not.toContainText('console e2e answer');
    const off = await read<{ state: string }>(page, '/api/heads/' + STACK.oauthHead + '/conversation?session=' + STACK.sender.id + '&message=' + responseId);
    expect(off.state).toBe('off');
    await other.reload();
    await expect(other.getByRole('main')).not.toContainText(TURN_PROMPT);
    await page.reload();
    await expect(toggle).toHaveAttribute('aria-checked', 'false');
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-checked', 'true');
    await expect(other.getByRole('main')).toContainText(TURN_PROMPT, { timeout: 20_000 });
    expect(otherFaults.pageErrors).toEqual([]);
  } finally {
    await context.close();
    const response = await page.request.patch(env('CONSOLE_E2E_BASE') + '/api/config', {
      headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY') }, data: { transcriptView: true },
    });
    expect(response.ok()).toBe(true);
  }
  expect(faults.pageErrors).toEqual([]);
});

test('capture writes reread running state, keep new bodies absent until restart and reverse topology staleness', async ({ page }) => {
  const faults = await open(page, 'turns');
  const config = env('CONSOLE_E2E_CONFIG');
  const original = readFileSync(config, 'utf8');
  const path = '/api/heads/' + STACK.oauthHead + '/capture';
  const calls: { method: string; body: string | null }[] = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === path) calls.push({ method: request.method(), body: request.postData() });
  });
  const wroteThenRead = (enabled: boolean) => {
    const at = calls.findIndex((call) => call.method === 'PUT' && call.body !== null && (JSON.parse(call.body) as { enabled: boolean }).enabled === enabled);
    return at >= 0 && calls.slice(at + 1).some((call) => call.method === 'GET');
  };
  const stale = async () => (await read<{ topologyStale: boolean }>(page, '/health')).topologyStale;
  expect(await stale()).toBe(false);
  try {
    await driveOneTurn(Number(env('CONSOLE_E2E_OAUTH_PORT')), env('CONSOLE_E2E_KEY'));
    await openTurn(page, STACK.oauthHead, await newest(page, STACK.oauthHead));
    await page.getByRole('link', { name: 'Request and answer', exact: true }).click();
    const toggle = page.getByRole('switch', { name: 'Body capture', exact: true });
    await expect(toggle).toHaveAttribute('aria-checked', 'false');
    await expect(page.getByRole('main')).toContainText('Request capture is off');
    await toggle.click();
    await expect.poll(() => wroteThenRead(true)).toBe(true);
    await expect(toggle).toHaveAttribute('aria-checked', 'true');
    await expect(page.getByRole('main')).toContainText('Restart to apply');
    await expect(page.getByRole('main')).not.toContainText('Recording bodies');
    expect(readFileSync(config, 'utf8')).toMatch(new RegExp('\\[heads\\.' + STACK.oauthHead + '\\.overrides\\][^[]*trace = "true"'));
    await expect.poll(stale).toBe(true);
    await page.getByRole('link', { name: 'Turns', exact: true }).last().click();
    await driveOneTurn(Number(env('CONSOLE_E2E_OAUTH_PORT')), env('CONSOLE_E2E_KEY'));
    await openTurn(page, STACK.oauthHead, await newest(page, STACK.oauthHead));
    await page.getByRole('link', { name: 'Request and answer', exact: true }).click();
    await expect(page.getByRole('main')).toContainText('Restart to apply');
    await expect(page.getByRole('main')).not.toContainText(TURN_PROMPT);
    await expect(page.getByRole('main')).not.toContainText('Recording bodies');
    await page.reload();
    await expect(toggle).toHaveAttribute('aria-checked', 'true');
    await expect(page.getByRole('main')).toContainText('Restart to apply');
    await toggle.click();
    await expect.poll(() => wroteThenRead(false)).toBe(true);
    await expect(toggle).toHaveAttribute('aria-checked', 'false');
    await expect(page.getByRole('main')).not.toContainText('Restart to apply');
    expect(readFileSync(config, 'utf8'), 'true then false must preserve the whole isolated config').toBe(original);
    await expect.poll(stale).toBe(false);
    expect(faults.pageErrors).toEqual([]);
  } finally {
    const response = await page.request.put(env('CONSOLE_E2E_BASE') + path, {
      headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY') }, data: { enabled: false },
    });
    expect(response.ok()).toBe(true);
    await expect.poll(stale).toBe(false);
  }
});
