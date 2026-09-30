// The Turns pages' arithmetic: outcomes, plan rows, the stuck rule, a turn's four stages, and what was kept.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { RunningCard } from '../src/pages/turns/TurnsPage';
import { describe, expect, test } from 'vitest';
import {
  askAndAnswer, failedCount, filterLines, lineOf, outcomeOf, planRows, localStepsOf, liveTurnFor, runningOf, servedLocally, stagesOf, turnLede, turnsLede, wireFor, WINDOW_MS,
} from '../src/lib/turns-page';
import type { RunningLine } from '../src/lib/turns-page';
import type { PerfSummaryHead, TurnRow } from '../src/types/perf';

const none = () => 'none' as const;
const row = (over: Partial<TurnRow> = {}): TurnRow => ({ head: 'claude', ts: 1_000_000, model: 'opus-5.5', outcome: 'ok', compact: false, ...over });
const summary = (over: Partial<PerfSummaryHead> = {}): PerfSummaryHead => ({
  key: 'claude', label: 'Claude', window: '1h', count: 10, empty: false, coverage_known: true, clamped: false, covers_ms: 3_600_000,
  time_before_first_byte_ms: { count: 10, p50: 1400, p95: 3900, max: 5000 }, outcomes: { ok: 8, '?': 1, 'error:upstream-failed': 1 }, cache_hit_ratio: 0.86, ...over,
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
    expect(turnLede(local, [])).toBe('The plan was not asked. Splice answered this step itself, from a script the model had already written.');
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
    expect(turnsLede([], '24h')).toBe('No plan has answered a turn in the last 24 hours.');
  });
});

describe('running turns', () => {
  const live = (idleMs: number) => ({ head: 'kimi', label: 'Migrate', compact: false, phase: 'streaming', ageMs: 900_000, idleMs, streamIdleMs: 60_000 });
  test('stuck only past five minutes of silence, and the stuck one comes first', () => {
    const lines = runningOf([live(4 * 60_000), { ...live(6 * 60_000), label: 'Old' }], (h) => h, none);
    expect(lines.map((l) => [l.label, l.stuck])).toEqual([['Old', true], ['Migrate', false]]);
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
    expect(turnLede(row({ ...marks, outcome: 'error:upstream-failed' }), stagesOf(row(marks)))).toBe('Plan failed after 14.2 s.');
  });
  test('a list line carries no cost when the row is not priced', () => {
    const line = lineOf(row({ cost_usd: null }), (h) => h, none, () => null);
    expect(line.cost).toBe('–');
    expect(line.title).toBe('claude');
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
