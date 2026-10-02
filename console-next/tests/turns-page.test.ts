// The Turns pages' arithmetic: outcomes, plan rows, the quiet-turn explanation, a turn's four stages, and what was kept.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { RunningCard, TurnRowView } from '../src/pages/turns/TurnsPage';
import { describe, expect, test } from 'vitest';
import {
  askAndAnswer, failedCount, filterLines, lineOf, outcomeOf, planRows, localStepsOf, liveTurnFor, runningOf, servedLocally, stagesOf, turnLede, turnsLede, wireFor, WINDOW_MS,
} from '../src/lib/turns-page';
import type { RunningLine } from '../src/lib/turns-page';
import { colourFromRegistry } from '../src/lib/model';
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
  test('the lede counts turns, failures and the typical first word', () => {
    expect(turnsLede(planRows([summary()], none), '1h')).toBe('10 turns in the last hour. One failed, and the typical first word came back in 1.4 s.');
    expect(turnsLede([], '24h')).toBe('No model has answered a turn in the last 24 hours.');
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
    expect(html).toContain('No word from the model for 2 min; splice is keeping the turn open.');
    expect(html).toContain('Stop the turn');
    expect(html).not.toMatch(/limit/i);
  });
  test('even a long-quiet provider is Working, not a failure or attention card', () => {
    const line = runningOf([live(7 * 60_000)], (h) => h, none)[0] as RunningLine;
    const html = renderToStaticMarkup(createElement(RunningCard, { turn: line, act: createElement('button', null, 'Stop the turn') }));
    expect(html).toContain('Working');
    expect(html).not.toContain('Stuck');
    expect(html).not.toContain('win attn');
    expect(html).toContain('No word from the model for 7 min; splice is keeping the turn open.');
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
  test('a list line carries no cost when the row is not priced', () => {
    const line = lineOf(row({ cost_usd: null }), (h) => h, none, () => null);
    expect(line.cost).toBe('–');
    expect(line.title).toBe('claude');
  });
  test.each([null, '', '   '])('an absent model %j leaves only the command, with no separator or placeholder', (model) => {
    const line = lineOf(row({ model }), () => 'claude-splice', none, () => null);
    const html = renderToStaticMarkup(createElement(MemoryRouter, null, createElement(TurnRowView, { line })));
    expect(html).toContain('<span>claude-splice</span>');
    expect(html).not.toContain('claude-splice ·');
    expect(filterLines([line], 'all', 'no-such-model')).toEqual([]);
    expect(filterLines([line], 'all', 'splice')).toEqual([line]);
  });
  test('a measured model remains after the command and its separator', () => {
    const line = lineOf(row(), () => 'claude-splice', none, () => null);
    const html = renderToStaticMarkup(createElement(MemoryRouter, null, createElement(TurnRowView, { line })));
    expect(html).toContain('claude-splice · opus-5.5');
  });
  test('the filters keep failures and compactions, and a query reads title, plan and model', () => {
    const lines = [
      lineOf(row({ ts: 1 }), (h) => h, none, () => 'Tidy the changelog'),
      lineOf(row({ ts: 2, outcome: 'error:plan-limit' }), (h) => h, none, () => 'Explain the auth flow'),
      lineOf(row({ ts: 3, compact: true }), (h) => h, none, () => 'Write the tests'),
    ];
    expect(filterLines(lines, 'failed', '').map((l) => l.ts)).toEqual([2]);
    expect(filterLines(lines, 'compacted', '').map((l) => l.ts)).toEqual([3]);
    expect(filterLines(lines, 'all', 'auth').map((l) => l.ts)).toEqual([2]);
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
