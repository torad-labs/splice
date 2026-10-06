// NEW: V4-444 — the Requests filters run on the daemon and live in the address, so a link opens the same rows. A filter
// run in the browser over the newest slice listed no failure while the header counted 480 (the persona walk of 36218a37c).
import { expect, test, type Page } from '@playwright/test';
import { appendFileSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { assertHealthy, env, open } from './support';
import { driveOneTurn, STACK } from './stack';

/** The query of every turns read the page sends from now on, across reloads. */
function turnsAsks(page: Page): URLSearchParams[] {
  const asks: URLSearchParams[] = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname === '/api/perf/turns') asks.push(url.searchParams);
  });
  return asks;
}

const hash = (page: Page): string => decodeURIComponent(new URL(page.url()).hash);

for (const identity of ['recorded', 'missing', 'empty'] as const) {
  test(identity === 'recorded' ? 'same-millisecond restarted requests never multiply while polling or ageing out' : `id-less restarted requests never multiply or merge across polls: ${identity}`, async ({ page }) => {
    await page.clock.install();
    const at = Date.now();
    const rows = ['first', 'second', 'newer'].map((name, index) => ({
      ts: at + (index === 2 ? 1000 : 0), model: 'Synthetic ' + name, outcome: 'error:restarted', compact: false,
      session: null, account: null, cache_cold: null, turn: identity === 'empty' ? '' : null, session_id: null,
      response_message_id: identity === 'recorded' ? 'synthetic-response-' + name : identity === 'empty' ? '' : null, total: 20,
    }));
    let current = rows.slice(0, 2);
    let reads = 0;
    await page.route(url => url.pathname === '/api/heads', async route => {
      const response = await route.fetch();
      const body = await response.json();
      body.heads = body.heads.filter((head: { key: string }) => head.key === STACK.soloHead);
      await route.fulfill({ response, json: body });
    });
    await page.route(url => url.pathname === '/api/perf/turns', route => {
      const query = new URL(route.request().url()).searchParams;
      reads++;
      return route.fulfill({ json: { since: Number(query.get('since')), n: 200, heads: [{
        key: STACK.soloHead, label: STACK.soloHead, count: current.length, rows: current,
      }] } });
    });
    const faults = await open(page, 'requests');
    const shown = page.locator('.turn');
    await expect(shown).toHaveCount(2);
    for (const snapshot of [rows.slice(0, 2), rows.slice(0, 2), [rows[1], rows[0], rows[2]], rows.slice(1), rows.slice(2), []]) {
      current = snapshot.filter((item): item is typeof rows[number] => item !== undefined);
      const before = reads;
      await page.clock.fastForward(6000);
      await expect.poll(() => reads).toBeGreaterThan(before);
      await expect(shown).toHaveCount(current.length);
      for (const item of current) await expect(shown.filter({ hasText: item.model })).toHaveCount(1);
    }
    await assertHealthy(page, faults);
  });
}

test('Failed asks the daemon for the failed requests, and the address opens the same view after a reload', async ({ page }) => {
  const asks = turnsAsks(page);
  const faults = await open(page, 'requests');
  const show = page.getByRole('group', { name: 'Show', exact: true });
  await show.getByRole('button', { name: 'Failed', exact: true }).click();
  await expect.poll(() => hash(page)).toBe('#/requests?status=failed');
  await expect.poll(() => asks.some((ask) => ask.get('outcome') === 'failed' && ask.get('local') === '0')).toBe(true);
  await page.reload();
  await expect(show.getByRole('button', { name: 'Failed', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByRole('main')).not.toContainText('Reading the requests.');
  await assertHealthy(page, faults);
});

test('stops stay out of failure counts and have a separate reloadable view with the whole recorded reason', async ({ page }) => {
  const at = Date.now();
  const outcomes = ['ok', '?', 'client_abort', 'error:cancelled', 'error:stopped', 'error:rate-limited'];
  const rows = outcomes.map((outcome, index) => ({
    ts: at - index, model: STACK.soloModel, outcome, compact: false,
    session: null, account: null, cache_cold: null, turn: outcome === 'error:rate-limited' ? 'synthetic-limit' : null,
    session_id: null, response_message_id: null, total: 20,
  }));
  await page.route('**/api/perf/summary?*', route => route.fulfill({ json: { window: '1h', heads: [{
    key: STACK.soloHead, label: STACK.soloHead, window: '1h', count: rows.length, empty: false,
    coverage_known: true, clamped: false, covers_ms: 3_600_000,
    outcomes: Object.fromEntries(outcomes.map(outcome => [outcome, 1])),
  }] } }));
  const asks = turnsAsks(page);
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const query = new URL(route.request().url()).searchParams;
    const key = query.get('head') ?? '';
    const filtered = key !== STACK.soloHead ? [] : query.get('outcome') === 'stopped'
      ? [rows[2], rows[4]] : query.get('outcome') === 'failed' ? [rows[3], rows[5]] : rows;
    const since = Number(query.get('since'));
    const until = query.has('until') ? Number(query.get('until')) : Infinity;
    const matching = filtered.filter((row): row is typeof rows[number] => row !== undefined && row.ts >= since && row.ts < until);
    return route.fulfill({ json: { since: Number(query.get('since')), n: 200,
      heads: [{ key, label: key, count: matching.length, rows: matching }],
    } });
  });
  const sentence = 'rate limit reached; retry after the named reset, with the same session.';
  await page.route('**/api/heads/*/trace?turn=*', route => route.fulfill({ json: {
    head: STACK.soloHead,
    turn: { id: 'synthetic-limit', ts: at - 5, session: null, model: STACK.soloModel, compact: false,
      open: false, outcome: 'error:rate-limited', failure_sentence: sentence, rounds: 1, attempts: 1, total_ms: 20 },
    records: [],
  } }));
  const faults = await open(page, 'requests');
  await expect(page.locator('.page-head .lede')).toContainText('Two failed');
  await expect(page.locator('.plan .bad')).toHaveText('2');
  await expect(page.locator('.turn')).toHaveCount(6);
  await expect(page.locator('.turn.failed')).toHaveCount(2);
  const show = page.getByRole('group', { name: 'Show', exact: true });
  await show.getByRole('button', { name: 'Stopped', exact: true }).click();
  await expect.poll(() => hash(page)).toBe('#/requests?status=stopped');
  await expect.poll(() => asks.some(ask => ask.get('outcome') === 'stopped' && ask.get('local') === '0')).toBe(true);
  await expect(page.locator('.turn')).toHaveCount(2);
  await expect(page.locator('.turn.failed')).toHaveCount(0);
  await expect(page.locator('.turn .state')).toHaveText(['Stopped', 'Stopped']);
  await page.reload();
  await expect(show.getByRole('button', { name: 'Stopped', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.turn')).toHaveCount(2);
  await show.getByRole('button', { name: 'Failed', exact: true }).click();
  await expect(page.locator('.turn')).toHaveCount(2);
  await expect(page.locator('.turn .state')).toHaveText(['Ended by splice', 'Rate limited']);
  await page.locator('.turn').filter({ hasText: 'Rate limited' }).locator('h3 a').click();
  await expect(page.locator('.failure-sentence')).toHaveText('Rate limit reached; retry after the named reset, with the same session.');
  await expect(page.locator('.page-head .failure-sentence')).toHaveText('Rate limit reached; retry after the named reset, with the same session.');
  const failure = await page.locator('.failure-sentence').boundingBox();
  const timing = await page.getByRole('heading', { name: 'Where the time went', exact: true }).boundingBox();
  expect(failure).not.toBeNull();
  expect(timing).not.toBeNull();
  expect((failure?.y ?? Infinity) + (failure?.height ?? 0)).toBeLessThan(timing?.y ?? 0);
  await assertHealthy(page, faults);
});

test('an empty answer stays clean in Requests and explains the delivered ending before timing', async ({ page }) => {
  const at = Date.now();
  const row = { ts: at, model: STACK.soloModel, outcome: 'empty_message', compact: false,
    upstream_req_bytes: 512, in_tokens: 80, out_tokens: 4000, total: 14200, recv: 1, parse: 3, build: 20, gate: 2100, headers: 2300, first_byte: 5500, first_delta: 5500, stream_end: 13900, finish: 14000,
    prep_ms: 20, admit_wait_ms: 2080, lease_wait_ms: 0, attempts: 1,
    arrival_to_upstream_write_ms: 2300, upstream_write_to_first_byte_ms: 3200,
    session: null, account: null, cache_cold: null, turn: null, session_id: null, response_message_id: null };
  await page.route('**/api/perf/summary?*', route => route.fulfill({ json: { window: '1h', heads: [{
    key: STACK.soloHead, label: STACK.soloHead, count: 1, empty: false, outcomes: { empty_message: 1 },
  }] } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const query = new URL(route.request().url()).searchParams;
    const key = query.get('head') ?? '';
    const rows = key === STACK.soloHead && query.get('outcome') !== 'failed' ? [row] : [];
    return route.fulfill({ json: { since: at - 1000, n: 200, heads: [{ key, label: key, count: rows.length, rows }] } });
  });
  const faults = await open(page, 'requests');
  await expect(page.locator('.turn .state')).toHaveText('Empty answer');
  await expect(page.locator('.turn.failed')).toHaveCount(0);
  await page.locator('.turn h3 a').click();
  await expect(page.locator('.page-head .lede')).toHaveText('The session received an empty answer from the model, which ended its reply with no text and no tool call. Took 14.2 s. Most of it, 8.4 s, was the answer arriving.');
  await expect(page.locator('.failure-sentence')).toHaveCount(0);
  await expect(page.locator('.page-head .lede')).not.toContainText('token');
  await expect(page.locator('.page-head .lede')).not.toContainText('reasoning');
  await assertHealthy(page, faults);
});

test('an empty answer explains reported thinking and uses the same plan-cost sentence as Usage', async ({ page }) => {
  const at = Date.now();
  const row = { ts: at, model: STACK.soloModel, outcome: 'empty_message', compact: false,
    upstream_req_bytes: 512, in_tokens: 80, out_tokens: 417, reasoning_tokens: 417,
    cost_usd: null, cost_reason: 'plan', total: 20,
    session: null, account: null, cache_cold: null, turn: null, session_id: null, response_message_id: null };
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const key = new URL(route.request().url()).searchParams.get('head') ?? '';
    return route.fulfill({ json: { since: at, n: 1, heads: [{ key, label: key, count: 1, rows: [row] }] } });
  });
  const faults = await open(page, 'requests/' + STACK.soloHead + '/' + at);
  await expect(page.locator('.page-head .lede')).toContainText('The model reported thinking tokens.');
  await expect(page.locator('.page-head .lede')).toContainText('Took 20 ms.');
  await expect(page.getByRole('heading', { name: 'Written out', exact: true }).locator('..')).toContainText('417 of the written tokens were reported as thinking.');
  await expect(page.getByRole('heading', { name: 'Written out', exact: true }).locator('..').locator('.n')).toHaveText('417');
  await expect(page.getByRole('heading', { name: 'API cost, estimated', exact: true }).locator('..')).toContainText('1 request is covered by a plan, so it has no price.');
  await expect(page.getByRole('main')).not.toContainText('This request was not priced.');
  await expect(page.locator('.failure-sentence')).toHaveCount(0);
  await assertHealthy(page, faults);
});

for (const ending of [
  { outcome: 'error:restarted', word: 'Restarted by splice' },
  { outcome: 'ok', word: 'Done' },
]) {
  test('a compaction request tag never invents completion: ' + ending.outcome, async ({ page }, testInfo) => {
    const at = Date.now();
    const row = { ts: at, model: STACK.soloModel, outcome: ending.outcome, compact: true, total: 20,
      session: null, account: null, cache_cold: null, turn: null, session_id: null, response_message_id: null };
    await page.route('**/api/perf/summary?*', route => route.fulfill({ json: { window: '1h', heads: [{
      key: STACK.soloHead, label: STACK.soloHead, count: 1, empty: false, outcomes: { [ending.outcome]: 1 },
    }] } }));
    await page.route(url => url.pathname === '/api/perf/turns', route => {
      const key = new URL(route.request().url()).searchParams.get('head') ?? '';
      return route.fulfill({ json: { since: at - 1000, n: 200, heads: [{
        key, label: key, count: key === STACK.soloHead ? 1 : 0, rows: key === STACK.soloHead ? [row] : [],
      }] } });
    });
    for (const width of [1536, 393]) {
      await page.setViewportSize({ width, height: 1024 });
      const faults = await open(page, 'requests');
      await page.getByRole('button', { name: 'Compaction', exact: true }).click();
      await expect(page).toHaveURL(/status=compacted/);
      await expect(page.getByRole('button', { name: 'Compacted', exact: true })).toHaveCount(0);
      await expect(page.locator('.turn')).toHaveCount(1);
      await expect(page.locator('.turn .tag')).toHaveText('Compaction');
      await expect(page.locator('.turn .state')).toHaveText(ending.word);
      await page.locator('.turn h3 a').click();
      await expect(page.locator('.facts .tag')).toHaveText('Compaction');
      await expect(page.locator('.facts .state')).toHaveText(ending.word);
      await expect(page.getByRole('main')).not.toContainText('Compacted');
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      if (ending.outcome === 'error:restarted') await page.locator('header.hero').screenshot({ path: testInfo.outputPath('cut-compaction-' + width + '.png') });
      await assertHealthy(page, faults);
    }
  });
}

test('a restart cut keeps its owner in Failed and never falls back to an operator stop', async ({ page }) => {
  const at = Date.now();
  const row = {
    ts: at, model: STACK.soloModel, outcome: 'error:restarted', compact: false, total: 20,
    session: null, account: null, cache_cold: null, turn: null, session_id: null, response_message_id: null,
  };
  await page.route('**/api/perf/summary?*', route => route.fulfill({ json: { window: '1h', heads: [{
    key: STACK.soloHead, label: STACK.soloHead, count: 1, empty: false, outcomes: { 'error:restarted': 1 },
  }] } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const key = new URL(route.request().url()).searchParams.get('head') ?? '';
    return route.fulfill({ json: { since: at - 1000, n: 200, heads: [{
      key, label: key, count: key === STACK.soloHead ? 1 : 0, rows: key === STACK.soloHead ? [row] : [],
    }] } });
  });
  const faults = await open(page, 'requests?status=failed');
  await expect(page.locator('.turn.failed')).toHaveCount(1);
  await expect(page.locator('.turn .state')).toHaveText('Restarted by splice');
  await page.locator('.turn h3 a').click();
  await expect(page.locator('.failure-sentence')).toHaveText('This request ended: Restarted by splice. No detailed failure reason was kept.');
  await expect(page.getByRole('main')).not.toContainText('No detailed stop reason was kept.');
  await assertHealthy(page, faults);
});

test('a progress timeout explains provider silence and draws the missing wait without changing the kept answer', async ({ page }) => {
  const at = Date.now();
  const reason = '[SPLICE-OVERLOADED] splice progress timeout expired after 900000ms without upstream progress; retry';
  const answer = JSON.stringify({ type: 'error', error: { type: 'overloaded_error', message: reason } });
  const row = {
    ts: at, model: STACK.soloModel, outcome: 'error:cancelled', compact: false, total: 915_000,
    recv: 1, build: 20, headers: 1000, first_delta: 12_000,
    prep_ms: 20, admit_wait_ms: 0, lease_wait_ms: 0, attempts: 1,
    arrival_to_upstream_write_ms: 1000, upstream_write_to_first_byte_ms: 11_000,
    session: null, account: null, cache_cold: null, turn: 'synthetic-watchdog', session_id: null, response_message_id: null,
  };
  await page.route('**/api/perf/summary?*', route => route.fulfill({ json: { window: '1h', heads: [{
    key: STACK.soloHead, label: STACK.soloHead, count: 1, empty: false, outcomes: { 'error:cancelled': 1 },
  }] } }));
  await page.route(url => url.pathname === '/api/perf/turns', route => {
    const key = new URL(route.request().url()).searchParams.get('head') ?? '';
    return route.fulfill({ json: { since: at - 1_000_000, n: 200, heads: [{
      key, label: key, count: key === STACK.soloHead ? 1 : 0, rows: key === STACK.soloHead ? [row] : [],
    }] } });
  });
  await page.route('**/api/heads/*/trace?turn=*', route => route.fulfill({ json: {
    head: STACK.soloHead,
    turn: { id: 'synthetic-watchdog', ts: at, session: null, model: STACK.soloModel, compact: false,
      open: false, outcome: 'error:cancelled', failure_sentence: reason, rounds: 1, attempts: 1, total_ms: 915_000 },
    records: [{ kind: 'turn', ts: at, answer: { status: 200, body: answer } }],
  } }));
  const faults = await open(page, 'requests?status=failed');
  await expect(page.locator('.turn.failed')).toHaveCount(1);
  await expect(page.locator('.turn .state')).toHaveText('Ended by splice');
  await page.locator('.turn h3 a').click();
  await expect(page.locator('.failure-sentence')).toHaveText('Splice gave up after 15m 0s without progress from the provider. Retry the request.');
  await expect(page.locator('.legend > div').filter({ hasText: 'Unmeasured time' }).locator('.v')).toHaveText('15m 3s');
  await expect(page.locator('.legend > div').filter({ hasText: 'Waiting for response' }).locator('.v')).toHaveText('11.0 s');
  await expect(page.locator('.legend')).not.toContainText('Streaming');
  await expect(page.locator('.legend')).not.toContainText('Model thinking');
  await expect(page.getByRole('region', { name: 'Where the time went', exact: true })).toContainText('do not form a complete breakdown');
  await expect(page.locator('.water')).toHaveCount(0);
  await page.setViewportSize({ width: 1536, height: 1000 });
  await page.screenshot({ path: 'captures/console-walk-oct3/watchdog-wait-1536.png', fullPage: true });
  await page.setViewportSize({ width: 393, height: 850 });
  await page.screenshot({ path: 'captures/console-walk-oct3/watchdog-wait-393.png', fullPage: true });
  await page.getByRole('link', { name: 'Request and answer', exact: true }).click();
  await expect(page.locator('.attempts pre')).toHaveText(answer);
  await assertHealthy(page, faults);
});

for (const [origin, sentence] of [
  ['splice', "splice could not complete this session's code-mode step; start a new session, and if it repeats read the daemon log"],
  ['provider', 'the provider rejected the request as invalid, so resending it unchanged fails the same way; change the request before retrying'],
] as const) {
  test(`an invalid request shows the recorded ${origin} origin rather than guessing from its type`, async ({ page }) => {
    const at = Date.now();
    const row = { ts: at, model: STACK.soloModel, outcome: 'failure:invalid_request_error', compact: false,
      total: 20, session: null, account: null, cache_cold: null, turn: 'synthetic-origin',
      session_id: null, response_message_id: null };
    await page.route('**/api/perf/summary?*', route => route.fulfill({ json: { window: '1h', heads: [{
      key: STACK.soloHead, label: STACK.soloHead, count: 1, empty: false, outcomes: { [row.outcome]: 1 },
    }] } }));
    await page.route(url => url.pathname === '/api/perf/turns', route => {
      const key = new URL(route.request().url()).searchParams.get('head') ?? '';
      return route.fulfill({ json: { since: at - 1000, n: 200, heads: [{
        key, label: key, count: key === STACK.soloHead ? 1 : 0, rows: key === STACK.soloHead ? [row] : [],
      }] } });
    });
    await page.route('**/api/heads/*/trace?turn=*', route => route.fulfill({ json: {
      head: STACK.soloHead, turn: { id: row.turn, ts: at, session: null, model: STACK.soloModel,
        compact: false, open: false, outcome: row.outcome, failure_sentence: sentence,
        rounds: 1, attempts: 1, total_ms: 20 }, records: [],
    } }));
    const faults = await open(page, 'requests?status=failed');
    await page.locator('.turn h3 a').click();
    await expect(page.locator('.failure-sentence')).toHaveText(sentence.charAt(0).toUpperCase() + sentence.slice(1));
    if (origin === 'splice') {
      await expect(page.locator('.failure-sentence')).not.toContainText('change the request');
      await expect(page.locator('.failure-sentence')).not.toContainText('provider rejected');
    }
    await assertHealthy(page, faults);
  });
}

for (const outcome of ['failure:api_error', 'failure:invalid_request_error']) {
  test(`a provider policy refusal stays a refusal in its list badge and timed detail for ${outcome}`, async ({ page }) => {
    const at = Date.now();
    const sentence = 'OpenAI refused the request under its cybersecurity check. This request was flagged. Try rephrasing.';
    const row = { ts: at, model: STACK.soloModel, outcome, cause: 'CONTENT_FILTERED', compact: false,
      total: 422_000, session: null, account: null, cache_cold: null, turn: 'synthetic-policy',
      session_id: null, response_message_id: null };
    await page.route('**/api/perf/summary?*', route => route.fulfill({ json: { window: '1h', heads: [{
      key: STACK.soloHead, label: STACK.soloHead, count: 1, empty: false, outcomes: { [outcome]: 1 },
    }] } }));
    await page.route(url => url.pathname === '/api/perf/turns', route => {
      const key = new URL(route.request().url()).searchParams.get('head') ?? '';
      return route.fulfill({ json: { since: at - 500_000, n: 200, heads: [{
        key, label: key, count: key === STACK.soloHead ? 1 : 0, rows: key === STACK.soloHead ? [row] : [],
      }] } });
    });
    await page.route('**/api/heads/*/trace?turn=*', route => route.fulfill({ json: {
      head: STACK.soloHead, turn: { id: row.turn, ts: at, session: null, model: STACK.soloModel,
        compact: false, open: false, outcome, cause: row.cause, failure_sentence: sentence, rounds: 1, attempts: 1, total_ms: 422_000 },
      records: [],
    } }));
    const faults = await open(page, 'requests?status=failed');
    await expect(page.locator('.turn .state')).toHaveText('Request refused');
    await page.locator('.turn h3 a').click();
    await expect(page.locator('.page-head .lede')).toHaveText('Request refused after 7m 2s.');
    await expect(page.locator('.page-head .state')).toHaveText('Request refused');
    await expect(page.locator('.failure-sentence')).toHaveText(sentence);
    await expect(page.getByRole('main')).not.toContainText('Provider failed');
    await expect(page.getByRole('main')).not.toContainText('failed on its side');
    await expect(page.getByRole('main')).not.toContainText('retry in a moment');
    await assertHealthy(page, faults);
  });
}

for (const retained of [true, false]) {
  test(`an old retained policy row corrects the real API and page with provider text ${retained ? 'kept' : 'unavailable'}`, async ({ page, request }) => {
    const at = Date.now();
    const id = retained ? 'synthetic-old-refusal-kept' : 'synthetic-old-refusal-absent';
    const state = join(dirname(dirname(dirname(env('CONSOLE_E2E_CONFIG')))), '.splice/state');
    const traceDir = join(state, 'trace');
    mkdirSync(traceDir, { recursive: true });
    const file = join(traceDir, `${STACK.soloHead}-${new Date(at).toISOString().slice(0, 10)}.jsonl`);
    const stale = retained ? 'the provider failed on its side; retry in a moment' : 'a differently worded obsolete server failure';
    const provider = JSON.stringify({ type: 'response.failed', response: { error: {
      code: 'cyber_policy', message: 'this synthetic request was flagged. Try rephrasing.',
    } } });
    const records = [
      { kind: 'attempt', turn: id, ts: at + 20, durationMs: 40, model: STACK.soloModel, attempt: 1, transport: 'ws',
        response: { text: retained ? provider : { unavailable: true } } },
      { kind: 'turn', turn: id, ts: at + 30, model: STACK.soloModel, outcome: 'failure:api_error',
        failure_sentence: stale, rounds: 1, attempts: 1 },
    ];
    appendFileSync(file, records.map(record => JSON.stringify(record) + '\n').join(''));
    appendFileSync(join(state, `${STACK.soloHead}-perf.jsonl`), JSON.stringify({
      ts: at, model: STACK.soloModel, outcome: 'failure:api_error', cause: 'CONTENT_FILTERED',
      compact: false, turn: id, total: 20,
    }) + '\n');
    const neighborId = id + '-overlap-outage';
    const neighborSentence = 'the synthetic provider is temporarily unavailable; retry later';
    appendFileSync(file, JSON.stringify({ ...records[1], turn: neighborId, failure_sentence: neighborSentence }) + '\n');
    appendFileSync(join(state, `${STACK.soloHead}-perf.jsonl`), JSON.stringify({
      ts: at + 1, model: STACK.soloModel, outcome: 'failure:api_error', cause: 'UPSTREAM_STATUS_5XX',
      compact: false, turn: neighborId, total: 20,
    }) + '\n');
    const before = readFileSync(file, 'utf8');
    const neighborApi = await request.get(env('CONSOLE_E2E_BASE') + `/api/heads/${STACK.soloHead}/trace?turn=${neighborId}`, {
      headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY') },
    });
    expect(neighborApi.ok()).toBe(true);
    const neighborBody = await neighborApi.json() as { turn: { cause: string; failure_sentence: string } };
    expect(neighborBody.turn.failure_sentence).toBe(neighborSentence);
    const api = await request.get(env('CONSOLE_E2E_BASE') + `/api/heads/${STACK.soloHead}/trace?turn=${id}`, {
      headers: { Authorization: 'Bearer ' + env('CONSOLE_E2E_KEY') },
    });
    expect(api.ok()).toBe(true);
    const body = await api.json() as { turn: { cause: string; failure_sentence: string }; records: typeof records };
    const sentence = retained
      ? 'OpenAI refused the request under its cybersecurity check. This synthetic request was flagged. Try rephrasing.'
      : 'the provider stopped the answer under its content check; ask for a different task';
    expect(body.turn.failure_sentence).toBe(sentence);
    expect(body.turn.cause).toBe('CONTENT_FILTERED');
    expect(neighborBody.turn.cause).toBe('UPSTREAM_STATUS_5XX');
    expect(body.records.at(-1)?.failure_sentence).toBe(stale);
    expect(readFileSync(file, 'utf8')).toBe(before);
    const faults = await open(page, `requests?head=${STACK.soloHead}&status=failed`);
    const old = page.locator('.turn').filter({ has: page.locator(`a[href$="/${STACK.soloHead}/${at}"]`) });
    await expect(old.locator('.state')).toHaveText('Request refused');
    await old.locator('h3 a').click();
    await expect(page.locator('.page-head .state')).toHaveText('Request refused');
    await expect(page.locator('.failure-sentence')).toHaveText(sentence.charAt(0).toUpperCase() + sentence.slice(1));
    await expect(page.locator('.failure-sentence')).not.toContainText(stale);
    await page.goto(env('CONSOLE_E2E_BASE') + `/#/requests?head=${STACK.soloHead}&status=failed`);
    const neighbor = page.locator('.turn').filter({ has: page.locator(`a[href$="/${STACK.soloHead}/${at + 1}"]`) });
    await expect(neighbor.locator('.state')).toHaveText('Provider failed');
    await neighbor.locator('h3 a').click();
    await expect(page.locator('.failure-sentence')).toHaveText('The synthetic provider is temporarily unavailable; retry later');
    await assertHealthy(page, faults);
  });
}

test('the headline reads the pooled request timing while command bars keep their own percentiles', async ({ page }) => {
  const commands = [
    { key: 'busy', count: 100, first: 100 },
    { key: 'sparse', count: 1, first: 10_000 },
    { key: 'slower', count: 1, first: 20_000 },
  ];
  await page.route('**/api/perf/summary?*', route => route.fulfill({ json: {
    window: '1h',
    time_before_first_byte_ms: { count: 102, p50: 100, p95: 100, max: 20_000 },
    heads: commands.map(command => ({
      key: command.key, label: command.key, window: '1h', count: command.count, empty: false,
      coverage_known: true, clamped: false, covers_ms: 3_600_000, outcomes: { ok: command.count },
      time_before_first_byte_ms: { count: command.count, p50: command.first, p95: command.first, max: command.first },
    })),
  } }));
  const faults = await open(page, 'requests');
  await expect(page.locator('.page-head .lede')).toHaveText('102 requests in the last hour. None failed, and the typical first word came back in 100 ms.');
  await expect(page.locator('.plan-first span')).toHaveText([
    '100 ms typical · 100 ms slowest', '20.0 s typical · 20.0 s slowest', '10.0 s typical · 10.0 s slowest',
  ]);
  await assertHealthy(page, faults);
});

test('a request\'s model opens the requests on that model, and its chip returns to them all', async ({ page }) => {
  const asks = turnsAsks(page);
  const faults = await open(page, 'requests');
  await driveOneTurn(Number(env('CONSOLE_E2E_SOLO_PORT')), env('CONSOLE_E2E_KEY'), undefined, STACK.soloModel);
  const row = page.locator('.turn').filter({ has: page.getByRole('link', { name: STACK.soloModel, exact: true }) }).first();
  await expect(row).toBeVisible({ timeout: 15_000 });
  await row.getByRole('link', { name: STACK.soloModel, exact: true }).click();
  await expect.poll(() => hash(page)).toBe('#/requests?model=' + STACK.soloModel);
  await expect.poll(() => asks.some((ask) => ask.get('model') === STACK.soloModel)).toBe(true);
  await expect(page.locator('.turn').first()).toBeVisible();
  for (const model of await page.locator('.turn .sub').allInnerTexts()) expect(model).toContain(STACK.soloModel);
  await page.getByRole('list', { name: 'Showing only', exact: true }).getByRole('link').click();
  await expect.poll(() => hash(page)).toBe('#/requests');
  await assertHealthy(page, faults);
});

test('Usage\'s link with since, until and a command reads that span on that command alone, and says how many it holds', async ({ page }) => {
  await driveOneTurn(Number(env('CONSOLE_E2E_SOLO_PORT')), env('CONSOLE_E2E_KEY'), undefined, STACK.soloModel);
  const until = Date.now() + 120_000;
  const since = until - 3_720_000;
  const asks = turnsAsks(page);
  const faults = await open(page, `requests?since=${since}&until=${until}&head=${STACK.soloHead}`);
  await expect(page.locator('.page-head .lede')).toHaveText(/^[\d,]+ requests? from /, { timeout: 15_000 });
  expect(asks.length).toBeGreaterThan(0);
  for (const ask of asks) expect([ask.get('head'), ask.get('since'), ask.get('until')]).toEqual([STACK.soloHead, String(since), String(until)]);
  await assertHealthy(page, faults);
});

test('a link the page cannot read says which part, and reads no requests', async ({ page }) => {
  const asks = turnsAsks(page);
  await open(page, 'requests?day=2026-02-31');
  await expect(page.getByRole('main').getByRole('alert')).toHaveText('This link\'s day, "2026-02-31", is not one this page can read.');
  expect(asks).toEqual([]);
});
