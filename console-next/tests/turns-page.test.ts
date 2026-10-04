// The Turns pages' arithmetic: outcomes, plan rows, the quiet-turn explanation, a turn's four stages, and what was kept.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { RunningCard, TurnRowView, TurnsPage } from '../src/pages/turns/TurnsPage';
import { describe, expect, test } from 'vitest';
import {
  askAndAnswer, failedCount, lineOf, outcomeOf, pageLede, planRows, localStepsOf, liveTurnFor, runningOf, servedLocally, stagesOf, turnLede, turnsLede, wireFor, WINDOW_MS,
} from '../src/lib/turns-page';
import type { RunningLine } from '../src/lib/turns-page';
import { colourFromRegistry } from '../src/lib/model';
import { viewOf } from '../src/lib/requests-view';
import { T } from '../src/lib/words-turns';
import type { PerfSummaryHead, TurnRow } from '../src/types/perf';

const none = () => 'none' as const;
const row = (over: Partial<TurnRow> = {}): TurnRow => ({ head: 'claude', ts: 1_000_000, model: 'opus-5.5', outcome: 'ok', compact: false, ...over });
const summary = (over: Partial<PerfSummaryHead> = {}): PerfSummaryHead => ({
  key: 'claude', label: 'Claude', window: '1h', count: 10, empty: false, coverage_known: true, clamped: false, covers_ms: 3_600_000,
  time_before_first_byte_ms: { count: 10, p50: 1400, p95: 3900, max: 5000 }, outcomes: { ok: 8, '?': 1, 'error:upstream-failed': 1 }, cache_hit_ratio: 0.86, ...over,
});

test('the status-registry family chooses provider colour, never the configured auth kind', () => {
  const registry = [
    { key: 'local-key', label: 'Local', authKind: 'api-key', family: 'local' },
    { key: 'remote-bearer', label: 'Remote', authKind: 'bearer', family: 'openrouter' },
    { key: 'claude-key', label: 'Claude', authKind: 'api-key', family: 'anthropic' },
  ];
  const colours = colourFromRegistry({ registry });
  expect(registry.map((head) => colours(head.key))).toEqual(['local', 'router', 'claude']);
  expect(colours('unknown')).toBe('none');
  expect(colourFromRegistry(undefined)('local-key')).toBe('none');
  expect(colourFromRegistry({ registry: [{ key: 'unmapped', label: 'Unmapped', authKind: 'api-key', family: null }] })('unmapped')).toBe('none');
  const reordered = colourFromRegistry({ registry: [...registry].reverse() });
  expect(registry.map((head) => reordered(head.key))).toEqual(['local', 'router', 'claude']);
});

describe('outcomes', () => {
  test('ok is done, a stop is quiet, a refusal is a failure, an unknown tag is still a failure', () => {
    expect(outcomeOf('ok')).toEqual({ word: 'Done', tone: 'work', failed: false });
    expect(outcomeOf('client_abort')).toEqual({ word: 'Stopped', tone: 'idle', failed: false });
    expect(outcomeOf('error:plan-limit')).toMatchObject({ word: 'Out of quota', failed: true });
    expect(outcomeOf('failure:overloaded')).toMatchObject({ word: 'Failed', failed: true });
    expect(outcomeOf('?')).toMatchObject({ word: 'Unknown', failed: false });
  });
  test('the unattributed tag is not a failure in a head count', () => {
    expect(failedCount(summary())).toBe(1);
  });
  test('counts use the same stopped classification as request rows, without hiding unknown failures', () => {
    const outcomes = { ok: 8, '?': 2, client_abort: 3, 'error:cancelled': 4, 'error:stopped': 5, 'error:rate-limited': 6, 'error:new-ending': 7 };
    expect(failedCount(summary({ outcomes }))).toBe(17);
    expect(outcomeOf('error:cancelled')).toMatchObject({ word: 'Cancelled', tone: 'stuck', failed: true });
    for (const tag of ['client_abort', 'error:stopped']) {
      expect(outcomeOf(tag)).toMatchObject({ tone: 'idle', failed: false });
      expect(failedCount(summary({ outcomes: { [tag]: 1 } }))).toBe(0);
    }
    expect(turnsLede(planRows([summary({ count: 2, outcomes: { client_abort: 1, 'error:stopped': 1 } })], none), '1h')).toContain('None failed');
  });
});

describe('a record splice answered itself', () => {
  const local = row({ local_step: 1, in_tokens: 0, out_tokens: 0, total: 53 });
  test('only the daemon\'s own mark makes a local answer; the shape of the row does not', () => {
    expect(servedLocally(local)).toBe(true);
    expect(servedLocally(row())).toBe(false);
    expect(servedLocally(row({ local_step: 0 }))).toBe(false);
    expect(servedLocally(row({ first_byte: 900, in_tokens: 1200, out_tokens: 80 }))).toBe(false);
    expect(servedLocally(row({ in_tokens: 0, out_tokens: 0 }))).toBe(false);
    expect(servedLocally(row({ outcome: 'error:admission', in_tokens: 0, out_tokens: 0 }))).toBe(false);
  });
  test('the steps left out are the summary\'s count across plans, and none from a daemon without the field', () => {
    expect(localStepsOf([summary({ local_steps: 4 }), summary({ local_steps: 3 }), summary()])).toBe(7);
    expect(localStepsOf([summary()])).toBe(0);
    expect(localStepsOf([])).toBe(0);
  });
  test('its page says the plan was not asked', () => {
    expect(turnLede(local, [])).toBe('The model was not asked. splice answered this step itself, from a script the model had already written.');
  });
});

describe('plan rows', () => {
  test('an empty window lists no plan, and the rest sort by turns', () => {
    const rows = planRows([summary({ key: 'a', label: 'A', count: 3 }), summary({ key: 'b', label: 'B', count: 9 }), summary({ key: 'c', empty: true, count: 0 })], none);
    expect(rows.map((r) => r.key)).toEqual(['b', 'a']);
  });
  test('a head with no first-word stats carries null, never 0', () => {
    const { time_before_first_byte_ms: full, ...bare } = summary();
    expect(full).toBeDefined();
    expect(planRows([bare], none)[0]).toMatchObject({ firstP50: null, firstP95: null });
  });
  test('the lede counts requests, failures and the typical first word', () => {
    expect(turnsLede(planRows([summary()], none), '1h', summary().time_before_first_byte_ms)).toBe('10 requests in the last hour. One failed, and the typical first word came back in 1.4 s.');
    expect(turnsLede([], '24h')).toBe('No model has answered a request in the last 24 hours.');
  });
  test('the fleet headline uses the pooled request percentile, not equally weighted command medians', () => {
    const heads = [
      summary({ key: 'busy', count: 100, time_before_first_byte_ms: { count: 100, p50: 100, p95: 100, max: 100 }, outcomes: { ok: 100 } }),
      summary({ key: 'sparse', count: 1, time_before_first_byte_ms: { count: 1, p50: 10_000, p95: 10_000, max: 10_000 }, outcomes: { ok: 1 } }),
      summary({ key: 'slower', count: 1, time_before_first_byte_ms: { count: 1, p50: 20_000, p95: 20_000, max: 20_000 }, outcomes: { ok: 1 } }),
    ];
    const pooled = { count: 102, p50: 100, p95: 100, max: 20_000 };
    expect(turnsLede(planRows(heads, none), '1h', pooled)).toBe('102 requests in the last hour. None failed, and the typical first word came back in 100 ms.');
    expect(pageLede(viewOf(new URLSearchParams()), planRows(heads, none), 200, pooled)).toContain('100 ms.');
  });
  test('without pooled readings an older daemon keeps request counts but invents no fleet percentile', () => {
    expect(turnsLede(planRows([summary()], none), '1h')).toBe('10 requests in the last hour. One failed.');
    expect(turnsLede(planRows([summary()], none), '1h', { count: 10, p50: 0, p95: 1, max: 1 })).toContain('0 ms.');
  });
  test('every count reads with thousands separators', () => {
    const busy = summary({ count: 2362, outcomes: { ok: 1882, 'error:rate-limited': 480 } });
    expect(turnsLede(planRows([busy], none), '7d', busy.time_before_first_byte_ms)).toBe('2,362 requests in the last 7 days. 480 failed, and the typical first word came back in 1.4 s.');
    expect(T.shownOf(200, 2362)).toBe('Showing the newest 200 of 2,362. Narrow the window or filter to see the rest.');
    expect(T.matching(2362)).toBe('2,362 requests match.');
  });
  test('while a read is in flight the lede says so, never that the window is empty', () => {
    expect(pageLede(viewOf(new URLSearchParams('window=7d')), null, undefined)).toBe('Reading the requests.');
    expect(pageLede(viewOf(new URLSearchParams('since=1000&until=2000')), null, undefined)).toBe('Reading the requests.');
    expect(pageLede(viewOf(new URLSearchParams('since=1000&until=2000')), null, 0)).toMatch(/^No requests from /);
  });
});

describe('running turns', () => {
  const live = (idleMs: number) => ({ head: 'kimi', label: 'Migrate', compact: false, phase: 'streaming', ageMs: 900_000, idleMs });
  test('long-quiet turns come first for inspection, without claiming they failed', () => {
    const lines = runningOf([live(4 * 60_000), { ...live(6 * 60_000), label: 'Old' }], (h) => h, none);
    expect(lines.map((l) => [l.label, l.longQuiet])).toEqual([['Old', true], ['Migrate', false]]);
  });
  test('a live turn is titled by its session, never by the code the gate labels it with', () => {
    const coded = { ...live(1000), label: '544af4b6 gpt-6-sol' };
    const named = runningOf([coded], (h) => h, none, (prefix) => (prefix === '544af4b6' ? 'claude-builder' : null))[0];
    expect([named?.title, named?.model]).toEqual(['claude-builder', 'gpt-6-sol']);
    const unknown = runningOf([coded], (h) => h, none)[0];
    expect([unknown?.title, unknown?.model]).toEqual(['gpt-6-sol', null]);
    expect(runningOf([live(1000)], (h) => h, none)[0]?.title).toBe('Migrate');
  });
  test('a running card finds the daemon turn a stop must name, by the gate label and its age', () => {
    const turn = (id: string, session: string | null, model: string, age_ms: number, stopped = false) => ({ id, session, model, compact: false, age_ms, stopped });
    const turns = [turn('a', '544af4b6-0000', 'gpt-6-sol', 900_000), turn('b', '544af4b6-0000', 'gpt-6-sol', 4_000), turn('c', '99999999-0000', 'gpt-6-sol', 900_000), turn('d', '544af4b6-0000', 'gpt-6-sol', 900_000, true)];
    expect(liveTurnFor({ label: '544af4b6 gpt-6-sol', ageMs: 5_000 }, turns)?.id).toBe('b');
    expect(liveTurnFor({ label: '544af4b6 gpt-6-sol', ageMs: 899_000 }, turns)?.id).toBe('a');
    expect(liveTurnFor({ label: '544af4b6 other-model', ageMs: 5_000 }, turns)).toBeNull();
    expect(liveTurnFor({ label: 'Migrate', ageMs: 5_000 }, turns)).toBeNull();
  });
  test('a running card draws the act it is given beside what the turn is doing', () => {
    const line = { ...runningOf([live(1000)], (h) => h, none)[0] } as RunningLine;
    const html = renderToStaticMarkup(createElement(RunningCard, { turn: line, act: createElement('button', null, 'Stop the turn') }));
    expect(html).toContain('Stop the turn');
    expect(renderToStaticMarkup(createElement(RunningCard, { turn: line }))).not.toContain('Stop the turn');
  });
  test('a turn quiet for two minutes says so as a sentence a person understands, with Stop beside it', () => {
    const line = runningOf([live(2 * 60_000)], (h) => h, none)[0] as RunningLine;
    const html = renderToStaticMarkup(createElement(RunningCard, { turn: line, act: createElement('button', null, 'Stop the turn') }));
    expect(html).toContain('No word from the model for 2 min; splice is keeping the request open.');
    expect(html).toContain('Stop the turn');
    expect(html).not.toMatch(/limit/i);
  });
  test('even a long-quiet provider is Working, not a failure or attention card', () => {
    const line = runningOf([live(7 * 60_000)], (h) => h, none)[0] as RunningLine;
    const html = renderToStaticMarkup(createElement(RunningCard, { turn: line, act: createElement('button', null, 'Stop the turn') }));
    expect(html).toContain('Working');
    expect(html).not.toContain('Stuck');
    expect(html).not.toContain('win attn');
    expect(html).toContain('No word from the model for 7 min; splice is keeping the request open.');
    expect(html).toContain('Stop the turn');
  });
  test('a turn that has just spoken names no silence', () => {
    expect(runningOf([live(2000)], (h) => h, none)[0]?.quiet).toBeNull();
  });
});

describe('a turn', () => {
  const marks = { recv: 1, parse: 3, build: 20, gate: 2100, headers: 2300, first_byte: 5500, stream_end: 13900, finish: 14000, total: 14200 };
  test('its marks fold into four stages in the reader\'s order', () => {
    const stages = stagesOf(row(marks));
    expect(stages.map((s) => s.key)).toEqual(['prepare', 'queue', 'provider', 'stream']);
    expect(stages.reduce((n, s) => n + s.ms, 0)).toBe(14000);
  });
  test('a turn with no marks has no stages, and the lede does not invent one', () => {
    expect(stagesOf(row())).toEqual([]);
    expect(turnLede(row(), [])).toBe('Done. It carries no timing.');
  });
  test('the lede names the longest stage; a failure says how long it ran', () => {
    expect(turnLede(row(marks), stagesOf(row(marks)))).toContain('Most of it, 8.5 s, was the answer arriving.');
    expect(turnLede(row({ ...marks, outcome: 'error:upstream-failed' }), stagesOf(row(marks)))).toBe('Provider failed after 14.2 s.');
  });
  test('the finished line and detail both read the proven runtime port', () => {
    const refused = row({ outcome: 'error:conn-reset', refused_runtime_port: 8123, total: 20 });
    expect(lineOf(refused, (head) => head, none, () => null).outcome.word)
      .toBe("Couldn't reach its runtime on :8123");
    expect(turnLede(refused, [])).toBe("Couldn't reach its runtime on :8123 after 20 ms.");
  });

  test('a list line carries no cost when the row is not priced', () => {
    const line = lineOf(row({ cost_usd: null }), (h) => h, none, () => null);
    expect(line.cost).toBe('–');
    expect(line.title).toBe('claude');
  });
  const narrow = (selector: string, value: string) => `/requests?${new URLSearchParams({ [selector]: value }).toString()}`;
  const html = (over: Partial<TurnRow>, plan = 'claude-splice') =>
    renderToStaticMarkup(createElement(MemoryRouter, null, createElement(TurnRowView, { line: lineOf(row(over), () => plan, none, () => null), narrow })));
  test('the rendered cost column distinguishes unpriced turns from a measured API-rate estimate', () => {
    expect(html({ cost_usd: null })).toContain('<span class="cost">Not priced</span>');
    expect(html({ cost_usd: null })).not.toContain('<span class="cost">–</span>');
    expect(html({ cost_usd: 1.25 })).toContain('<span class="cost">$1.25</span>');
    expect(html({ cost_usd: 1.25 })).not.toContain('Not priced');
  });
  test.each([null, '', '   '])('an absent model %j leaves only the command, with no separator or placeholder', (model) => {
    expect(html({ model })).toContain('<a href="/requests?head=claude" data-discover="true">claude-splice</a>');
    expect(html({ model })).not.toContain('claude-splice</a> · ');
  });
  test('the command, the model and the account each open the requests narrowed to them', () => {
    const shown = html({ account: 'work@x.io' });
    const link = (href: string, text: string) => `<a href="${href}" data-discover="true">${text}</a>`;
    expect(shown).toContain(`${link('/requests?head=claude', 'claude-splice')} · ${link('/requests?model=opus-5.5', 'opus-5.5')} · ${link('/requests?account=work%40x.io', 'work@x.io')}`);
  });
  test('a row with a session links to that session\'s requests', () => {
    expect(html({ session: '1a2b3c4d' })).toContain('<a class="same" href="/requests?session=1a2b3c4d" data-discover="true">Same session</a>');
    expect(html({})).not.toContain('Same session');
  });
  test('the chips say a compaction, a cache hit, a retry and a switch of account, each only when the row carries it', () => {
    const tags = (over: Partial<TurnRow>) => lineOf(row(over), (h) => h, none, () => null).tags;
    expect(tags({})).toEqual([]);
    expect(tags({ compact: true, cached_tokens: 120, retries: 2, account: 'backup', cache_cold: true })).toEqual(['Compacted', 'Cache hit', 'Retried', 'Switched account']);
    expect(tags({ cached_tokens: 0, retries: 0, account: 'work', cache_cold: false })).toEqual([]);
    expect(html({ retries: 1 })).toContain('<span class="tag">Retried</span>');
  });
});

describe('while the reads are in flight', () => {
  /** The page as it first draws, with only the reads `seed` answered: nothing else has come back yet. */
  const page = (address: string, seed: (client: QueryClient) => void = () => undefined) => {
    const client = new QueryClient();
    seed(client);
    return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(MemoryRouter, { initialEntries: [address] }, createElement(TurnsPage))));
  };
  const answered = (client: QueryClient) => client.setQueryData(['perf-summary', '7d'], { heads: [summary({ window: '7d', count: 2362, outcomes: { ok: 1882, 'error:rate-limited': 480 } })] });

  test('nothing answered yet is a page reading, with no empty window', () => {
    const shown = page('/requests?window=7d');
    expect(shown).toContain('Reading the requests.');
    expect(shown).not.toContain('No model has answered');
    expect(shown).not.toContain('No finished request matches.');
  });
  test('a summary that came back before the rows leaves the list reading, and its Failed count links to the failures', () => {
    const shown = page('/requests?window=7d', answered);
    expect(shown).toContain('2,362 requests in the last 7 days. 480 failed');
    expect(shown).toContain('Reading the requests.');
    expect(shown).not.toContain('No finished request matches.');
    expect(shown).toContain('<a class="n bad" aria-label="480 failed requests on Claude" href="/requests?window=7d&amp;status=failed&amp;head=claude"');
  });
  test('a link the page cannot read says which part, and shows no list', () => {
    const shown = page('/requests?status=broken');
    expect(shown).toContain('This link&#x27;s status, &quot;broken&quot;, is not one this page can read.');
    expect(shown).not.toContain('Reading the requests.');
  });
});

describe('what was kept', () => {
  test('the exchange is the last ask and everything after it', () => {
    const got = askAndAnswer([{ role: 'user' }, { role: 'assistant' }, { role: 'user' }, { role: 'tool' }, { role: 'assistant' }]);
    expect(got.earlier).toBe(2);
    expect(got.reply.map((m) => m.role)).toEqual(['tool', 'assistant']);
  });
  test('a conversation with no ask keeps every message', () => {
    expect(askAndAnswer([{ role: 'assistant' }]).ask).toBeNull();
  });
  test('the wire keeps bodies sent inside the turn\'s span, from the same session', () => {
    const r = row({ ts: 100_000, total: 10_000, session: 'abc' });
    const got = wireFor([{ ts: 95_000, session: 'abc' }, { ts: 50_000, session: 'abc' }, { ts: 96_000, session: 'zzz' }, { ts: 97_000 }], r);
    expect(got.map((w) => w.ts)).toEqual([95_000, 97_000]);
  });
  test('the windows are an hour, a day and a week', () => {
    expect(WINDOW_MS['7d'] / WINDOW_MS['24h']).toBe(7);
  });
});
