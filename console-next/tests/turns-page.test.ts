// The Turns pages' arithmetic: outcomes, plan rows, quiet turns, recorded phases, unrecorded tails, and what was kept.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { RunningCard, TurnRowView, TurnsPage } from '../src/pages/turns/TurnsPage';
import { describe, expect, test } from 'vitest';
import {
  askAndAnswer, failedCount, lineOf, linesOf, outcomeOf, pageLede, planRows, localStepsOf, runningOf, servedLocally, spokenFailure, stagesOf, turnLede, turnsLede, WINDOW_MS,
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
  test('a restart cut belongs to Failed and never claims the client stopped it', () => {
    expect(outcomeOf('error:restarted')).toMatchObject({ word: 'Restarted by splice', tone: 'stuck', failed: true });
    expect(failedCount(summary({ outcomes: { 'error:restarted': 1, client_abort: 1, 'error:stopped': 1 } }))).toBe(1);
  });
  test('the unattributed tag is not a failure in a head count', () => {
    expect(failedCount(summary())).toBe(1);
  });
  test('counts use the same stopped classification as request rows, without hiding unknown failures', () => {
    const outcomes = { ok: 8, '?': 2, client_abort: 3, 'error:cancelled': 4, 'error:stopped': 5, 'error:rate-limited': 6, 'error:new-ending': 7 };
    expect(failedCount(summary({ outcomes }))).toBe(17);
    expect(outcomeOf('error:cancelled')).toMatchObject({ word: 'Ended by splice', tone: 'stuck', failed: true });
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
    expect(turnLede(local)).toBe('This step sent no request to the model. Splice answered from the existing script, so its input and output token counts are zero.');
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
  test('gate stop ids stay exact and keep card identity through reordered snapshots', () => {
    const a = { ...live(1000), turnId: 'synthetic-live-a' };
    const b = { ...live(1000), turnId: 'synthetic-live-b' };
    const forward = runningOf([a, b], h => h, none);
    const reversed = runningOf([b, a], h => h, none);
    expect(forward.map(line => line.turnId)).toEqual(['synthetic-live-a', 'synthetic-live-b']);
    expect(forward.map(line => line.key)).toEqual(reversed.map(line => line.key).reverse());
    expect(runningOf([live(1000)], h => h, none)[0]).not.toHaveProperty('turnId');
  });
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
  for (const transport of ['websocket', 'sse'] as const) {
    test(`a large delay before the ${transport} send is unmeasured, not model or queue time`, () => {
      const transportMarks = transport === 'websocket'
        ? { arrival_to_ws_send_accepted_ms: 20_000, ws_send_accepted_to_first_fragment_ms: 50 }
        : { arrival_to_upstream_write_ms: 20_000, upstream_write_to_first_byte_ms: 50 };
      const delayed = row({
        ...transportMarks, admit_wait_ms: 1, lease_wait_ms: 0, prep_ms: 12,
        recv: 1, parse: 6, build: 15, gate: 1, first_frame: 30, first_byte: 20_050,
        first_delta: 21_000, stream_end: 25_000, finish: 25_005, total: 25_010, attempts: 1,
      });
      const timing = stagesOf(delayed);
      const { stages } = timing;
      expect(stages.find(stage => stage.key === 'provider')?.ms).toBe(50);
      expect(stages.find(stage => stage.key === 'queue')?.ms).toBe(1);
      expect(stages.find(stage => stage.key === 'prepare')?.ms).toBe(12);
      expect(stages.find(stage => stage.key === 'wait')?.ms).toBeGreaterThan(19_000);
      expect(timing.additive).toBe(true);
      expect(turnLede(delayed, timing)).not.toContain('the model thinking');
    });
  }
  test('an earlier first byte and marks past total cannot claim most time was one measured stage', () => {
    const transportMarks = { arrival_to_ws_send_accepted_ms: 30_000, ws_send_accepted_to_first_fragment_ms: 50 };
    const later = row({
      ...transportMarks, admit_wait_ms: 1, lease_wait_ms: 0, prep_ms: 12, attempts: 2,
      recv: 1, parse: 6, build: 15, gate: 1, first_byte: 100, first_delta: 200,
      stream_end: 42_000, finish: 40_000, total: 40_005,
    });
    expect(turnLede(later, stagesOf(later))).not.toContain('Most of it');
  });
  test('a WebSocket size refusal before SSE fallback is not one wire attempt', () => {
    const fallback = row({
      head: 'synthetic', ts: 1, model: 'synthetic', attempts: 1, ws_refused_too_large: 1,
      prep_ms: 12, admit_wait_ms: 1, lease_wait_ms: 0,
      arrival_to_upstream_write_ms: 20000, upstream_write_to_first_byte_ms: 50,
      first_byte: 20050, first_frame: 30, first_delta: 21000,
      stream_end: 25000, finish: 25005, total: 25010,
    });
    const timing = stagesOf(fallback);
    expect(timing.additive).toBe(false);
    expect(turnLede(fallback, timing)).not.toContain('Most of it');
    expect(stagesOf({ ...fallback, ws_refused_too_large: 0 }).additive).toBe(true);
  });
  test('structural frames must fit completion bounds without following the first byte', () => {
    const completed = row({
      head: 'synthetic', ts: 1, model: 'synthetic', attempts: 1,
      prep_ms: 20, admit_wait_ms: 30, lease_wait_ms: 0,
      arrival_to_upstream_write_ms: 100, upstream_write_to_first_byte_ms: 8000,
      first_byte: 8100, first_delta: 8200, stream_end: 9900,
      finish: 9905, first_frame: 9990, total: 10000,
    });
    for (const first_frame of [9990, 9903, 10001]) {
      const conflicting = { ...completed, first_frame };
      const timing = stagesOf(conflicting);
      expect(timing.additive).toBe(false);
      expect(turnLede(conflicting, timing)).not.toContain('Most of it');
    }
    expect(stagesOf({ ...completed, first_frame: 50 }).additive).toBe(true);
    expect(stagesOf({ ...completed, first_frame: 9900 }).additive).toBe(true);
  });
  const noTiming = { stages: [], additive: false };
  const marks = {
    recv: 1, parse: 3, build: 20, gate: 2100, headers: 2300, first_byte: 5500,
    first_delta: 5500, stream_end: 14000, finish: 14000, total: 14200, attempts: 1,
    prep_ms: 20, admit_wait_ms: 2080, lease_wait_ms: 0,
    arrival_to_upstream_write_ms: 2300, upstream_write_to_first_byte_ms: 3200,
  };
  test('measured non-overlapping spans account for the total without attributing the remainder', () => {
    const timing = stagesOf(row(marks));
    expect(timing.additive).toBe(true);
    expect(timing.stages).toEqual([
      { key: 'prepare', ms: 20 }, { key: 'queue', ms: 2080 }, { key: 'lease', ms: 0 },
      { key: 'provider', ms: 3200 }, { key: 'stream', ms: 8500 }, { key: 'wait', ms: 400 },
    ]);
    expect(timing.stages.reduce((n, s) => n + s.ms, 0)).toBe(14200);
  });
  test('a turn with no marks has no stages, and the lede does not invent one', () => {
    expect(stagesOf(row())).toEqual(noTiming);
    expect(turnLede(row(), noTiming)).toBe('Done. It carries no timing.');
  });
  test('legacy marks never become measured preparation, admission or provider spans', () => {
    const legacy = row({ recv: 1, build: 20, gate: 2100, first_byte: 5500, stream_end: 14000, total: 14200 });
    expect(stagesOf(legacy)).toEqual({ stages: [{ key: 'wait', ms: 14200 }], additive: false });
    expect(turnLede(legacy, stagesOf(legacy))).toBe('Took 14.2 s.');
  });
  for (const patch of [
    { attempts: 2 }, { first_byte: 100 }, { first_byte: 6000 }, { first_delta: 100 }, { stream_end: 15000 },
    { finish: 13000 }, { prep_ms: 3000 }, { recv: -1 }, { gate: Infinity },
    { first_delta: NaN }, { arrival_to_ws_send_accepted_ms: 2300, ws_send_accepted_to_first_fragment_ms: 3200 },
    { arrival_to_upstream_write_ms: null }, { upstream_write_to_first_byte_ms: null },
  ]) {
    test('incomplete or conflicting measurements never form an additive bar: ' + JSON.stringify(patch), () => {
      const incomplete = row({ ...marks, ...patch });
      const timing = stagesOf(incomplete);
      expect(timing.additive).toBe(false);
      expect(turnLede(incomplete, timing)).not.toContain('Most of it');
    });
  }
  test('unknown transport observations and missing local counters are not reported as zero', () => {
    const unknown = row({ total: 1000, arrival_to_ws_send_accepted_ms: null, ws_send_accepted_to_first_fragment_ms: null });
    expect(stagesOf(unknown).stages).toEqual([{ key: 'wait', ms: 1000 }]);
    const { prep_ms, ...missing } = marks;
    expect(prep_ms).toBe(20);
    expect(stagesOf(row(missing)).additive).toBe(false);
    expect(stagesOf(row(missing)).stages.some(stage => stage.key === 'prepare')).toBe(false);
  });
  test('overlapping spans or a send past total cannot define an unmeasured remainder', () => {
    for (const patch of [{ arrival_to_upstream_write_ms: 10 }, { arrival_to_upstream_write_ms: 15000 }]) {
      const timing = stagesOf(row({ ...marks, ...patch }));
      expect(timing.additive).toBe(false);
      expect(timing.stages.some(stage => stage.key === 'wait')).toBe(false);
    }
  });
  test('missing totals are never fabricated by summing partial measurements', () => {
    const partial = row({ prep_ms: 2000, admit_wait_ms: 3000 });
    expect(stagesOf(partial)).toEqual({ stages: [{ key: 'prepare', ms: 2000 }, { key: 'queue', ms: 3000 }], additive: false });
    expect(turnLede(partial, stagesOf(partial))).toBe('Done. No total time was reported.');
  });
  test('a largest stage that is not a majority is not called most of the request', () => {
    const balanced = row({
      total: 6000, prep_ms: 1000, admit_wait_ms: 1000, lease_wait_ms: 1000, attempts: 1,
      arrival_to_ws_send_accepted_ms: 3000, ws_send_accepted_to_first_fragment_ms: 1000,
      first_byte: 4000, first_delta: 4000, stream_end: 6000,
    });
    const timing = stagesOf(balanced);
    expect(timing.additive).toBe(true);
    expect(turnLede(balanced, timing)).toBe('Took 6.0 s.');
  });
  test('an empty answer names thinking only when its own row reports positive reasoning tokens', () => {
    const empty = row({ outcome: 'empty_message', out_tokens: 417, reasoning_tokens: 417, total: 20 });
    expect(turnLede(empty)).toContain('The model reported thinking tokens.');
    expect(turnLede(empty)).toContain('Took 20 ms.');
    for (const reasoning_tokens of [undefined, null, 0]) {
      expect(turnLede(row({ outcome: 'empty_message', out_tokens: 417, ...(reasoning_tokens === undefined ? {} : { reasoning_tokens }) }), noTiming)).not.toContain('reported thinking');
    }
    expect(turnLede(row({ outcome: 'ok', reasoning_tokens: 417 }), noTiming)).not.toContain('reported thinking');
    expect(turnLede(row({ outcome: 'empty_model', reasoning_tokens: 417 }), noTiming)).not.toContain('reported thinking');
  });
  test('a clean empty answer explains what reached the session before unchanged timing', () => {
    const sentence = 'The session received an empty answer from the model, which ended its reply with no text and no tool call.';
    const timed = row({ ...marks, outcome: 'empty_message' });
    expect(turnLede(timed, stagesOf(timed))).toBe(sentence + ' Took 14.2 s. Most of it, 8.5 s, was the answer arriving.');
    expect(turnLede(row({ outcome: 'empty_message' }), noTiming)).toBe(sentence + ' Empty answer. It carries no timing.');
    expect(turnLede(row({ outcome: 'empty_message', total: 20 }), noTiming)).toBe(sentence + ' Took 20 ms.');
    expect(outcomeOf('empty_message')).toEqual({ word: 'Empty answer', tone: 'work', failed: false });
    expect(failedCount(summary({ outcomes: { empty_message: 3, ok: 1 } }))).toBe(0);
    expect(failedCount(summary({ outcomes: { empty_message: 3, 'error:upstream-failed': 1 } }))).toBe(1);
    for (const out_tokens of [undefined, 0, 4000]) {
      expect(turnLede(row({ ...timed, ...(out_tokens === undefined ? {} : { out_tokens }), in_tokens: 80 }), stagesOf(timed))).toBe(sentence + ' Took 14.2 s. Most of it, 8.5 s, was the answer arriving.');
    }
    expect(turnLede(row({ outcome: 'ok', ...marks }), stagesOf(row(marks)))).toBe('Took 14.2 s. Most of it, 8.5 s, was the answer arriving.');
    expect(turnLede(row({ outcome: 'empty_model', total: 20 }), noTiming)).toBe('Empty answer after 20 ms.');
    expect(turnLede(row({ outcome: 'empty_message', local_step: 1 }), noTiming)).toBe(T.servedLocallyLede);
  });

  test('the lede names the longest stage; a failure says how long it ran', () => {
    expect(turnLede(row(marks), stagesOf(row(marks)))).toContain('Most of it, 8.5 s, was the answer arriving.');
    expect(turnLede(row({ ...marks, outcome: 'error:upstream-failed' }), stagesOf(row(marks)))).toBe('Provider failed after 14.2 s.');
  });
  test('a watchdog ending shows only measured wait and leaves the tail unattributed', () => {
    const timedOut = row({
      outcome: 'error:cancelled', total: 915_000, recv: 1, build: 20, headers: 1000, first_delta: 12_000,
      attempts: 1, prep_ms: 20, admit_wait_ms: 0, lease_wait_ms: 0,
      arrival_to_upstream_write_ms: 1000, upstream_write_to_first_byte_ms: 11000,
    });
    const timing = stagesOf(timedOut);
    expect(timing.additive).toBe(false);
    expect(timing.stages.find(stage => stage.key === 'wait')?.ms).toBe(903_980);
    expect(timing.stages.reduce((sum, stage) => sum + stage.ms, 0)).toBe(915_000);
    expect(timing.stages.find(stage => stage.key === 'provider')?.ms).toBe(11000);
    expect(timing.stages.some(stage => stage.key === 'stream')).toBe(false);
    const delivered = stagesOf(row({ ...timedOut, first_frame: 500, stream_end: 13_000, total: 15_000 }));
    expect(delivered.stages.find(stage => stage.key === 'provider')?.ms).toBe(11000);
    expect(delivered.stages.find(stage => stage.key === 'stream')?.ms).toBe(1000);
    const local = stagesOf(row({ local_step: 1, first_frame: 1, first_delta: 5, stream_end: 10, total: 10 }));
    expect(local.stages.some(stage => stage.key === 'provider')).toBe(false);
    expect(local.additive).toBe(false);
    expect(turnLede(timedOut, timing)).toBe('Ended by splice after 15m 15s.');
    expect(stagesOf(row({ ...timedOut, total: 1000 })).stages.some(stage => stage.key === 'wait')).toBe(false);
  });
  test('only known splice failures become plain explanations without rewriting provider words', () => {
    const reason = '[SPLICE-OVERLOADED] splice progress timeout expired after 900000ms without upstream progress; retry';
    expect(spokenFailure(reason)).toBe('splice gave up after 15m 0s without progress from the provider. Retry the request.');
    expect(spokenFailure('splice progress timeout expired after 0ms without upstream progress; retry'))
      .toBe('splice progress timeout expired after 0ms without upstream progress; retry');
    expect(spokenFailure('splice restarted while this request was running; retry the request'))
      .toBe('Splice restarted while this request was running. Retry the request.');
    expect(spokenFailure('Splice restarted while this request was running. Retry the request.'))
      .toBe('Splice restarted while this request was running. Retry the request.');
    expect(spokenFailure('synthetic provider refused the request')).toBe('synthetic provider refused the request');
    expect(spokenFailure('synthetic provider: splice restarted while this request was running; retry the request'))
      .toBe('synthetic provider: splice restarted while this request was running; retry the request');
  });
  test('the finished line and detail both read the proven runtime port', () => {
    const refused = row({ outcome: 'error:conn-reset', refused_runtime_port: 8123, total: 20 });
    expect(lineOf(refused, (head) => head, none, () => null).outcome.word)
      .toBe("Couldn't reach its runtime on :8123");
    expect(turnLede(refused, noTiming)).toBe("Couldn't reach its runtime on :8123 after 20 ms.");
  });

  test('same-millisecond request rows retain separate identities across polls and window expiry', () => {
    const first = row({ outcome: 'error:restarted', response_message_id: 'synthetic-first-response', turn: 'synthetic-first-trace' });
    const second = row({ outcome: 'error:restarted', response_message_id: 'synthetic-second-response', turn: 'synthetic-second-trace' });
    const newer = row({ ts: first.ts + 1000, response_message_id: 'synthetic-newer-response' });
    const keys = new Map<string, string>();
    for (const snapshot of [[first, second], [first, second], [second, first, newer], [second, newer], [newer], []]) {
      const lines = linesOf(snapshot, head => head, none, () => null);
      expect(new Set(lines.map(line => line.key)).size).toBe(snapshot.length);
      snapshot.forEach((item, index) => {
        const id = item.response_message_id ?? '';
        const key = lines[index]?.key ?? '';
        if (keys.has(id)) expect(key).toBe(keys.get(id));
        else keys.set(id, key);
      });
    }
  });
  test.each([false, true])('id-less rows remain distinct and stable through reordered polls, empty ids %s', empty => {
    const ids = empty ? { response_message_id: '', turn: '' } : {};
    const first = row({ ...ids, outcome: 'error:restarted', model: 'Synthetic first', total: 111 });
    const second = row({ ...ids, outcome: 'error:restarted', model: 'Synthetic second', total: 222 });
    const newer = row({ ...ids, ts: first.ts + 1000, model: 'Synthetic newer', total: 333 });
    const keys = new Map<string | null, string>();
    for (const snapshot of [[first, second], [first, second], [second, first, newer], [second, newer], [newer], []]) {
      const lines = linesOf(snapshot, head => head, none, () => null);
      expect(new Set(lines.map(line => line.key)).size).toBe(snapshot.length);
      snapshot.forEach((item, index) => {
        const key = lines[index]?.key ?? '';
        if (keys.has(item.model)) expect(key).toBe(keys.get(item.model));
        else keys.set(item.model, key);
      });
      expect(lines.map(line => line.model)).toEqual(snapshot.map(item => item.model));
    }
  });
  test('wire property order cannot rename a legacy row and identical records stay separately rendered', () => {
    const first = row({ model: 'Synthetic legacy', total: 111, response_message_id: '', turn: '' });
    const reordered = Object.fromEntries(Object.entries(first).reverse()) as TurnRow;
    const lines = linesOf([first, reordered], head => head, none, () => null);
    expect(lines).toHaveLength(2);
    expect(new Set(lines.map(line => line.key)).size).toBe(2);
    expect(lineOf(first, head => head, none, () => null).key).toBe(lineOf(reordered, head => head, none, () => null).key);
  });
  test('captured legacy rows use their trace identity and command namespaces remain separate', () => {
    const key = (item: TurnRow) => lineOf(item, head => head, none, () => null).key;
    expect(key(row({ turn: 'synthetic-trace-one' }))).not.toBe(key(row({ turn: 'synthetic-trace-two' })));
    expect(key(row({ response_message_id: 'synthetic-response' }))).not.toBe(key(row({ head: 'other-command', response_message_id: 'synthetic-response' })));
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
  test.each(['ok', 'error:restarted', 'error:cancelled', 'failure:api_error'])('compaction is a request kind, not a completion claim: %s', outcome => {
    const line = lineOf(row({ compact: true, outcome }), h => h, none, () => null);
    expect(line.tags).toEqual(['Compaction']);
    expect(line.outcome).toEqual(outcomeOf(outcome));
    expect(html({ compact: true, outcome })).toContain('<span class="tag">Compaction</span>');
    expect(html({ compact: true, outcome })).not.toContain('Compacted');
  });

  test('a row with a session links to that session\'s requests', () => {
    expect(html({ session: '1a2b3c4d' })).toContain('<a class="same" href="/requests?session=1a2b3c4d" data-discover="true">Same session</a>');
    expect(html({})).not.toContain('Same session');
  });
  test('the chips say a compaction, a cache hit, a retry and a switch of account, each only when the row carries it', () => {
    const tags = (over: Partial<TurnRow>) => lineOf(row(over), (h) => h, none, () => null).tags;
    expect(tags({})).toEqual([]);
    expect(tags({ compact: true, cached_tokens: 120, retries: 2, account: 'backup', cache_cold: true })).toEqual(['Compaction', 'Cache hit', 'Retried', 'Switched account']);
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
  test('the windows are an hour, a day and a week', () => {
    expect(WINDOW_MS['7d'] / WINDOW_MS['24h']).toBe(7);
  });
});
