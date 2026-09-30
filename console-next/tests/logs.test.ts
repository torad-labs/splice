// Ported from the old console's entities-turns.test.ts (the log tail cursor and the log filter) and
// the filter-model block of turns.test.ts: the blocks that exercise only lib/logs with plain data.
// The old logs.test.ts renders the log-tail widget (messageOf, perfOfLine, rowsOf, LogLine, ...) and
// its store (setLogHead, setLogRead), none of which is in this layer, so it is not ported.
import { describe, expect, test } from 'vitest';
import { advance, applyFilter, headOf, headsPresent, levelOf, levelsPresent, NO_FILTER, shownAfter, tailOf, timeOf } from '../src/lib/logs';
import type { LogsPayload, LogTail } from '../src/types/logs';

function logs(lines: string[], over: Partial<LogsPayload> = {}): LogsPayload {
  return { key: over.key ?? 'claudex', path: over.path ?? '/home/user/.splice/logs/daemon.log', lines };
}

describe('log tail cursor', () => {
  const line = (n: number): string => `[2026-09-18 01:14:0${n % 10}] [claudex] turn ${n}`;

  test('the first read appends everything and is not a reset', () => {
    const first = advance(null, logs([line(1), line(2)]));
    expect(first.appended).toEqual([line(1), line(2)]);
    expect(first.reset).toBe(false);
    expect(first.tail).toEqual(tailOf(logs([line(1), line(2)])));
  });

  test('a growing tail appends only the new lines', () => {
    const prev: LogTail = tailOf(logs([line(1), line(2)]));
    const next = advance(prev, logs([line(1), line(2), line(3)]));
    expect(next.appended).toEqual([line(3)]);
    expect(next.reset).toBe(false);
  });

  test('a window that slid forwards still appends only what is new', () => {
    const prev: LogTail = tailOf(logs([line(1), line(2), line(3)]));
    const next = advance(prev, logs([line(2), line(3), line(4)]));
    expect(next.appended).toEqual([line(4)]);
    expect(next.reset).toBe(false);
  });

  test('an unchanged tail appends nothing', () => {
    const prev: LogTail = tailOf(logs([line(1), line(2)]));
    expect(advance(prev, logs([line(1), line(2)])).appended).toEqual([]);
  });

  test('a rotated log resets instead of claiming the whole window is new', () => {
    const prev: LogTail = tailOf(logs([line(1), line(2), line(3)]));
    const next = advance(prev, logs(['[2026-09-18 02:00:00] [claudex] daemon restarted']));
    expect(next.reset).toBe(true);
    expect(next.appended).toEqual(['[2026-09-18 02:00:00] [claudex] daemon restarted']);
  });

  test('switching head resets even when the lines happen to match', () => {
    const prev: LogTail = tailOf(logs([line(1)], { key: 'claudex' }));
    const next = advance(prev, logs([line(1)], { key: 'claude-grok' }));
    expect(next.reset).toBe(true);
    expect(next.tail.key).toBe('claude-grok');
  });

  test('an empty first read leaves nothing to reset from', () => {
    const prev: LogTail = tailOf(logs([]));
    expect(advance(prev, logs([])).reset).toBe(false);
    expect(advance(prev, logs([line(1)])).reset).toBe(false);
    expect(advance(prev, logs([line(1)])).appended).toEqual([line(1)]);
  });
});

describe('log filter', () => {
  const error = '[2026-09-18 00:27:10] [claudex] turn ERROR conn-reset compact=false latency=900031ms';
  const ok = '[2026-09-18 01:14:01] [claudex] cache: input=182346 cached=181248 hit=99% output=252';
  const subsystem = '[2026-09-18 00:13:52] [shadow-compact] compact=false has_marker=false tool_count=177';
  const lines = [error, ok, subsystem];

  test('reads the daemon tag and only an explicitly marked severity', () => {
    expect(headOf(error)).toBe('claudex');
    expect(headOf(subsystem)).toBe('shadow-compact');
    expect(headOf('no brackets here')).toBeNull();
    expect(levelOf(error)).toBe('error');
    expect(levelOf(ok)).toBeNull(); // unmarked is null, never a guessed level
    expect(levelOf('[2026-09-18 01:00:00] [claudex] retries=3 failed=true')).toBeNull();
  });

  test('filters by tag, by level and by substring', () => {
    expect(applyFilter(lines, { ...NO_FILTER, head: 'claudex' })).toEqual([error, ok]);
    expect(applyFilter(lines, { ...NO_FILTER, level: 'error' })).toEqual([error]);
    expect(applyFilter(lines, { ...NO_FILTER, substring: 'HIT=99%' })).toEqual([ok]);
    expect(applyFilter(lines, { ...NO_FILTER, head: 'claudex', level: 'error', substring: 'conn-reset' })).toEqual([error]);
    expect(applyFilter(lines, NO_FILTER)).toEqual(lines);
    expect(applyFilter(lines, { ...NO_FILTER, substring: '   ' })).toEqual(lines);
  });

  test('offers only the tags and levels the tail actually holds', () => {
    expect(headsPresent(lines)).toEqual(['claudex', 'shadow-compact']);
    expect(levelsPresent(lines)).toEqual(['error']);
    expect(levelsPresent([ok, subsystem])).toEqual([]);
  });
});

describe('the filter model reads tags, levels and substrings', () => {
  const line = '[2026-09-18 01:14:05] [claude-deepseek] turn ERROR conn-reset compact=false latency=2827ms';

  test('one marked line', () => {
    expect(headOf(line)).toBe('claude-deepseek');
    expect(timeOf(line)).toBe('01:14:05');
    expect(levelOf(line)).toBe('error');
    expect(applyFilter([line], { ...NO_FILTER, level: 'error' })).toEqual([line]);
    expect(applyFilter([line], { ...NO_FILTER, level: 'warn' })).toEqual([]);
    expect(applyFilter([line], { ...NO_FILTER, substring: 'CONN-RESET' })).toEqual([line]);
    expect(headsPresent([line])).toEqual(['claude-deepseek']);
    expect(levelsPresent([line])).toEqual(['error']);
    expect(levelsPresent(['[2026-09-18 01:14:01] [claudex] ok'])).toEqual([]);
  });
});

describe('what the view shows after a read', () => {
  const tail = { key: 'k', path: 'p', lines: [] };
  test('new lines follow what was shown; a restarted window replaces it', () => {
    expect(shownAfter(['a', 'b'], { tail, appended: ['c'], reset: false })).toEqual(['a', 'b', 'c']);
    expect(shownAfter(['a', 'b'], { tail, appended: ['x', 'y'], reset: true })).toEqual(['x', 'y']);
    expect(shownAfter([], { tail, appended: ['a'], reset: false })).toEqual(['a']);
  });
  test('the page keeps the newest lines only, past the cap', () => {
    expect(shownAfter(['a', 'b', 'c'], { tail, appended: ['d', 'e'], reset: false }, 4)).toEqual(['b', 'c', 'd', 'e']);
    expect(shownAfter([], { tail, appended: ['a', 'b', 'c'], reset: true }, 2)).toEqual(['b', 'c']);
  });
});
