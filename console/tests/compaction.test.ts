// The compaction page on the kit: outcomes by state, the week before the undated counted rows,
// rare failures kept visible, the rules with their lengths, and every table naming its columns once.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { mergeInstructions } from '../src/entities/compact-stats/model/instructions';
import type { InstructionsWire } from '../src/entities/compact-stats';
import { CompactionBoard, RulesSection } from '../src/pages/compaction';
import { fixtureCompact } from '../src/pages/compaction/fixtures/compaction';
import {
  failedOf, headRows, MARK, medianOf, outcomeCounts, outcomeParts, outcomeText, seriesOf, shareText, stateOf, TONE,
} from '../src/pages/compaction/model';
import { H, S } from '../src/pages/compaction/strings';

const h = createElement;
const render = (element: Parameters<typeof renderToStaticMarkup>[0]): string => renderToStaticMarkup(element);

/** The column names of one labelled table, and its body rows. */
function table(markup: string, label: string): { names: string[]; rows: string[] } {
  const match = new RegExp(`<table[^>]*aria-label="${label}"[^>]*>([\\s\\S]*?)</table>`).exec(markup);
  const [head, body] = (match?.[1] ?? '').split('</thead>');
  return {
    names: [...(head ?? '').matchAll(/<th scope="col"[^>]*>([^<]*)</g)].map((m) => m[1] ?? ''),
    rows: (body ?? '').split('<tr').slice(1).filter((row) => !row.includes('myx-dt-group')),
  };
}

describe('an outcome earns one state', () => {
  test('a summary is ok, a failure is fail, and an outcome the console has not met is never ok', () => {
    expect(stateOf('model_text')).toBe('ok');
    expect(stateOf('model_summary')).toBe('ok');
    expect(stateOf('empty_model')).toBe('fail');
    expect(stateOf('stream_error')).toBe('fail');
    expect(stateOf('upstream_error')).toBe('fail');
    expect(stateOf('truncated')).toBe('warn');
    expect(stateOf('something_new')).toBe('warn');
  });

  test('the badge tone and the chart mark come from the same state, so they cannot disagree', () => {
    for (const outcome of ['model_text', 'truncated', 'stream_error', 'something_new']) {
      const state = stateOf(outcome);
      expect(TONE[state]).toBe({ ok: 'ok', warn: 'warn', fail: 'danger' }[state]);
      expect(MARK[state]).toBe(TONE[state]);
    }
  });

  test('an outcome the daemon is known to write reads in words, and a new one in its own spelling', () => {
    // The live feed on 2026-09-24 carried exactly these six names across 3,786 compactions.
    expect(['model_text', 'model_thinking', 'tooled_no_text', 'empty_model', 'stream_error', 'upstream_error'].map(outcomeText))
      .toEqual(['Summary written', 'From reasoning', 'Tool call', 'Empty reply', 'Stream failed', 'Provider error']);
    expect(outcomeText('something_new')).toBe('something new');
  });
});

describe('the counts the page leads with', () => {
  const sep21 = new Date(2026, 8, 21, 12).getTime();
  const stats = {
    ...fixtureCompact.stats,
    total: 3787,
    by_outcome: { model_text: 2467, empty_model: 666, stream_error: 567, upstream_error: 82 },
    by_outcome_7d: { model_text: 40, stream_error: 2 },
    heads: {
      claudex: { total: 3000, by_outcome: { model_text: 2000 }, by_outcome_7d: { model_text: 30, stream_error: 2 }, first_ts: sep21 + 86_400_000 },
      bonsai: { total: 787, by_outcome: { model_text: 10 }, by_outcome_7d: { model_text: 10 }, first_ts: sep21 },
      fresh: { total: 0, by_outcome: {} },
    },
  };

  test('with seven-day counts the week leads, and the counted rows keep their own tile', () => {
    expect(outcomeCounts(stats)).toEqual({ counts: { model_text: 40, stream_error: 2 }, total: 42, week: true });
    const markup = render(h(CompactionBoard, { payload: { stats } }));
    expect(markup).toContain(`>${S.week}<`);
    expect(markup).toContain('>42<');
    expect(markup, 'the failed share is of the week, not of every counted row').toContain('>4.8%<');
    expect(markup).toContain(`>${S.counted}<`);
    expect(markup).toContain('>3,787<');
  });

  test('without seven-day counts the first tile is the counted rows, and there is no week', () => {
    expect(outcomeCounts(fixtureCompact.stats)).toEqual({ counts: fixtureCompact.stats.by_outcome, total: fixtureCompact.stats.total, week: false });
    const markup = render(h(CompactionBoard, { payload: fixtureCompact }));
    expect(markup).toContain(`>${S.counted}<`);
    expect(markup).not.toContain(`>${S.week}<`);
  });

  test('a head\'s split is its week when dated, and a head with no rows is not a row', () => {
    expect(headRows(stats).map((row) => [row.head, row.total, row.failed])).toEqual([['claudex', 32, 2], ['bonsai', 10, 0]]);
    expect(failedOf(stats.by_outcome)).toBe(1315);
  });

  test('an outcome\'s share keeps a rare failure visible', () => {
    // Live 2026-09-24: 666 empty replies, 567 broken streams and 3 reasoning summaries of 3,786.
    expect(shareText(666, 3786)).toBe('18%');
    expect(shareText(567, 3786)).toBe('15%');
    expect(shareText(3, 3786)).toBe('<0.1%');
    expect(shareText(20, 3786)).toBe('0.5%');
    expect(shareText(1, 0)).toBe('–');
  });

  test('the split bar draws each outcome largest first, coloured by its state', () => {
    expect(outcomeParts({ empty_model: 3, model_text: 9 }).map((part) => [part.key, part.mark])).toEqual([['model_text', 'ok'], ['empty_model', 'danger']]);
  });
});

describe('the tail as series', () => {
  const at = (minute: number) => new Date(2026, 8, 24, 12, minute).getTime();
  const tail = [
    { head: 'a', ts: at(30), outcome: 'model_text', chars: 300, ms: 3000 },
    { head: 'a', ts: at(10), outcome: 'stream_error', ms: 9000 },
    { head: 'a', ts: at(20), outcome: 'model_text', chars: 100, ms: 1000 },
  ];

  test('a sparkline reads oldest to newest, and a row with no value is a gap, never a zero', () => {
    expect(seriesOf(tail, 'ms')).toEqual([9000, 1000, 3000]);
    expect(seriesOf(tail, 'chars')).toEqual([null, 100, 300]);
  });

  test('the middle value counts only rows that reported the field', () => {
    expect(medianOf(tail, 'ms')).toBe(3000);
    expect(medianOf(tail, 'chars')).toBe(200);
    expect(medianOf([], 'ms')).toBeNull();
  });
});

describe('the board', () => {
  test('the outcomes and recent tables name their columns once, in the head row, never per cell', () => {
    const markup = render(h(CompactionBoard, { payload: fixtureCompact }));
    const outcomes = table(markup, S.outcomes);
    expect(outcomes.names).toEqual([S.outcome, S.share, S.count]);
    expect(outcomes.rows).toHaveLength(Object.keys(fixtureCompact.stats.by_outcome).length);
    const recent = table(markup, S.recent);
    expect(recent.names).toEqual([S.when, S.head, S.outcome, S.summary, S.took]);
    expect(recent.rows).toHaveLength(fixtureCompact.stats.tail.length);
    // one cell per column in every row, so no row carries a name of its own beside its value
    for (const row of outcomes.rows) expect([...row.matchAll(/<td/g)].length).toBe(outcomes.names.length);
    for (const row of recent.rows) expect([...row.matchAll(/<td/g)].length).toBe(recent.names.length);
  });

  test('every outcome prints in words, never in the daemon\'s spelling', () => {
    const markup = render(h(CompactionBoard, { payload: fixtureCompact }));
    for (const outcome of Object.keys(fixtureCompact.stats.by_outcome)) {
      expect(markup).toContain(`>${outcomeText(outcome)}<`);
      expect(markup, `the daemon's spelling ${outcome} is not what a reader sees`).not.toContain(`>${outcome}<`);
    }
  });

  test('a failed compaction takes the danger tint, and its time and length are bars', () => {
    const markup = render(h(CompactionBoard, { payload: fixtureCompact }));
    const failures = fixtureCompact.stats.tail.filter((row) => stateOf(row.outcome ?? '') === 'fail').length;
    expect((markup.match(/myx-dt-tone-danger/g) ?? []).length).toBe(failures);
    expect(markup).toContain('role="meter"');
    expect(markup).toContain('class="myx-spark');
  });

  test('which model compacts is one sentence behind an info mark, not prose on the page', () => {
    const markup = render(h(CompactionBoard, { payload: fixtureCompact }));
    expect(markup).toContain(`aria-label="${S.aboutModel}"`);
    expect(markup).toContain(H.model.replace("'", '&#x27;'));
    expect(H.model.split(/\s+/).length).toBeLessThanOrEqual(12);
  });

  test('no compaction at all is one line, not a page of empty tiles', () => {
    const markup = render(h(CompactionBoard, { payload: { stats: { total: 0, by_outcome: {}, tail: [] } } }));
    expect(markup).toContain(`>${S.none}<`);
    expect(markup).not.toContain('myx-stat');
    expect(markup.split(`>${S.none}<`)).toHaveLength(2);
  });

  test('a fixture-fed board carries the capture marker with the fixture name', () => {
    expect(render(h(CompactionBoard, { payload: fixtureCompact, sample: 'compaction' }))).toContain('data-sample="compaction"');
    expect(render(h(CompactionBoard, { payload: fixtureCompact }))).not.toContain('data-sample');
  });
});

describe('the rules', () => {
  const CODEX: InstructionsWire = {
    scopes: [
      { scope: 'project', source: 'project:/tmp/e2e-repo', chars: 36 },
      { scope: 'model', source: 'model:e2e-model', chars: 31 },
      { scope: 'global', source: 'global', chars: 41 },
    ],
  };
  const SOLO: InstructionsWire = {
    scopes: [
      { scope: 'project', source: 'project:/tmp/e2e-repo', chars: 36 },
      { scope: 'global', source: 'global', chars: 41 },
    ],
  };

  test('one row per rule, with its source, its length drawn, and every head it applies to', () => {
    const rules = mergeInstructions([{ head: 'e2e-codex', wire: CODEX }, { head: 'e2e-codex-solo', wire: SOLO }]);
    const markup = render(h(RulesSection, { instructions: { rules, unread: [] } }));
    const rows = table(markup, S.rules).rows;
    expect(rows).toHaveLength(3);
    const model = rows.find((row) => row.includes('model:e2e-model')) ?? '';
    expect(model).toContain('>31<');
    expect(model).toContain('role="meter"');
    expect(model).toContain('e2e-codex');
    expect(model).not.toContain('e2e-codex-solo');
  });

  test('an empty rule is the client default and an unreadable file is unavailable, both as badges', () => {
    const rules = [
      { scope: 'global' as const, source: 'global', chars: 0, heads: ['a'] },
      { scope: 'model' as const, source: 'model:m', chars: null, heads: ['a'] },
    ];
    const markup = render(h(RulesSection, { instructions: { rules, unread: [] } }));
    expect(markup).toContain(`>${S.clientDefault}<`);
    expect(markup).toContain(`>${S.unavailable}<`);
  });

  test('no rule is one line, and its help names where a rule goes', () => {
    const markup = render(h(RulesSection, { instructions: { rules: [], unread: [] } }));
    expect(markup).toContain(`>${S.noRules}<`);
    expect(markup).toContain('[compaction]');
  });

  test('a head that could not be asked is named with its reason', () => {
    const markup = render(h(RulesSection, { instructions: { rules: [], unread: [{ head: 'e2e-openrouter', reason: 'HTTP 503' }] } }));
    expect(markup).toContain('e2e-openrouter: HTTP 503');
  });
});
