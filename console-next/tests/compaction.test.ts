// The compaction block's reads: which outcomes are failures, which counts lead, and the rules in words.
import { describe, expect, test } from 'vitest';
import { compactLede, failedOf, medianOf, outcomeCounts, outcomeRows, outcomeText, recentOf, ruleText, shareText, stateOf } from '../src/lib/compaction';
import type { CompactPayload, CompactRow } from '../src/types/compaction';

const row = (over: Partial<CompactRow> = {}): CompactRow => ({ head: 'claude-splice', ts: 1, outcome: 'model_text', ms: 10_000, ...over });

describe('outcomes', () => {
  test('a written summary is ok, nothing coming back is a failure, and an unknown outcome is a warning never a success', () => {
    expect(stateOf('model_text')).toBe('ok');
    expect(stateOf('model_thinking')).toBe('ok');
    expect(stateOf('model_text_weak')).toBe('warn');
    expect(stateOf('empty_model')).toBe('fail');
    expect(stateOf('something_new')).toBe('warn');
  });
  test('a known outcome has a word and an unknown one prints as the daemon spells it', () => {
    expect(outcomeText('stream_error')).toBe('Stream failed');
    expect(outcomeText('something_new')).toBe('something new');
  });
  test('the seven-day counts lead when the daemon sends them, else the counted rows', () => {
    const base: CompactPayload['stats'] = { total: 100, by_outcome: { model_text: 100 }, tail: [] };
    expect(outcomeCounts(base)).toEqual({ counts: { model_text: 100 }, total: 100, week: false });
    expect(outcomeCounts({ ...base, by_outcome_7d: { model_text: 3, empty_model: 1 } })).toEqual({ counts: { model_text: 3, empty_model: 1 }, total: 4, week: true });
  });
  test('failures are counted, rows sorted largest first, and a share needs a total', () => {
    const counts = { model_text: 3, empty_model: 1, model_text_weak: 2 };
    expect(failedOf(counts)).toBe(1);
    expect(outcomeRows(counts).map((r) => r.outcome)).toEqual(['model_text', 'model_text_weak', 'empty_model']);
    expect(shareText(1, 0)).toBe('–');
    expect(shareText(1, 4)).toBe('25%');
  });
  test('the lede says none failed, or how many and what share', () => {
    expect(compactLede(0, 0, true)).toBe('No compaction has run yet.');
    expect(compactLede(4, 0, true)).toBe('4 compactions in the last 7 days. None failed.');
    expect(compactLede(4, 1, false)).toBe('4 compactions. 1 failed (25%).');
  });
});

describe('the tail', () => {
  test('the median skips rows that reported nothing, and is null when none did', () => {
    expect(medianOf([row({ ms: 10 }), row({ ms: 30 }), row({ ms: 20 }), { head: 'h', ts: 2 }], 'ms')).toBe(20);
    expect(medianOf([{ head: 'h', ts: 2 }], 'ms')).toBeNull();
  });
  test('the newest come first and no more than asked', () => {
    expect(recentOf([row({ ts: 1 }), row({ ts: 3 }), row({ ts: 2 })], 2).map((r) => r.ts)).toEqual([3, 2]);
  });
});

describe('the rules', () => {
  test('a scope reads as words and the project or model it names is pulled out of the source', () => {
    expect(ruleText({ scope: 'global', source: 'global' })).toEqual({ scope: 'Everywhere', names: null });
    expect(ruleText({ scope: 'model', source: 'model:opus-5.5' })).toEqual({ scope: 'For a model', names: 'opus-5.5' });
    expect(ruleText({ scope: 'project', source: 'project:/home/a/tally file:/home/a/tally/rules.md' })).toEqual({ scope: 'For a project', names: '/home/a/tally' });
  });
});
