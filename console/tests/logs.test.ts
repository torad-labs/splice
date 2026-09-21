// WALLS for the log tail. The load-bearing one is `a row prints each fact once`.
//
// A tail row was reading `01:14:02 | claude-deepseek | - | [2026-09-18 01:14:02] [claude-deepseek]
// turn compact=false ...`: the timestamp twice and the head twice, on every line, in a column that
// measured 3714px of ink against a 1536px cell. The duplication is not a tidiness question -- the
// text cell is the one column whose whole job is to be read, and 277px of every line was spent
// reprinting what the two cells to its left already said (M2-30, measured rack-wide at 1536 dark).
//
// The second wall is what makes the first safe: `strip only what the row prints beside it`. The
// head bracket is removed because `headOf` returns it verbatim, and the timestamp bracket because
// its time is the time cell and its DATE is something the page has already decided not to print --
// `timeOf`'s own comment says the console prints the time and never the date. A line that carries
// neither bracket is untouched, so a continuation line keeps every character the daemon wrote.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with React.createElement and
// asserted against renderToStaticMarkup's string.
import * as React from 'react';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { headOf, levelOf, timeOf } from '../src/entities/logs';
import { LogColumns, LogLine, edgeOfLevel, messageOf } from '../src/widgets/log-tail';

const LINE = '[2026-09-18 01:14:02] [claude-deepseek] turn compact=false model=deepseek-flash ok';

describe('a tail row prints each fact once', () => {
  test('the text cell drops the timestamp and head its own row already prints', () => {
    expect(messageOf(LINE)).toBe('turn compact=false model=deepseek-flash ok');
  });

  test('what it drops is exactly what the other cells print', () => {
    // the guarantee stated as an identity rather than as a string literal: whatever the parsers
    // pull out for the time and head cells must be absent from what the text cell shows.
    expect(timeOf(LINE)).toBe('01:14:02');
    expect(headOf(LINE)).toBe('claude-deepseek');
    expect(messageOf(LINE)).not.toContain('claude-deepseek');
    expect(messageOf(LINE)).not.toContain('01:14:02');
  });

  test('the rendered row PRINTS the head once, not twice', () => {
    // counted in the visible text only: the accessible name deliberately keeps the whole line
    // (see the test below), so counting raw markup would count the fix as the defect.
    const out = renderToStaticMarkup(React.createElement(LogLine, { line: LINE }));
    const visible = out.replace(/\saria-label="[^"]*"/g, '');
    expect(visible.split('claude-deepseek').length - 1).toBe(1);
    expect(visible.split('01:14:02').length - 1).toBe(1);
  });

  test('the whole line survives on the row for a screen reader', () => {
    // the cell drops what is printed beside it; the accessible name keeps the daemon's own line
    const out = renderToStaticMarkup(React.createElement(LogLine, { line: LINE }));
    expect(out).toContain('[2026-09-18 01:14:02] [claude-deepseek]');
  });
});

// THE RACK PRINTS ITS COLUMN NAMES ONCE (M3-04). Every tail row printed `time head level text` above its
// own four values, so a 40-line tail spent half its height on forty copies of one header. The labels
// now live on one label-only strip above the scroll, and a row prints values only.
describe('the rack prints its column names once', () => {
  const labels = (html: string) => [...html.matchAll(/class="myx-sfield-label">([^<]*)</g)].map((m) => m[1]);

  test('a row prints no column name', () => {
    expect(labels(renderToStaticMarkup(React.createElement(LogLine, { line: LINE })))).toEqual([]);
  });

  test('the column strip prints all four, in the row order', () => {
    expect(labels(renderToStaticMarkup(React.createElement(LogColumns)))).toEqual(['time', 'head', 'level', 'text']);
  });
});

// A ROW CUT BY THE TOP OF THE TAIL KEEPS ITS FACTS (M3-04). Following scrolls the tail to its end, so
// the top row is usually cut through and its time, head and level -- on its first line -- were above
// the fold. They stick while any of the row shows. Sticky resolves in LAYOUT space, so the rows must
// be placed by `top`: a translated row still sits at 0 there, and every value was pushed to its
// cell's floor (measured: 53px down a 90px row that was fully in view). Both halves are read here.
describe('a row cut by the top of the tail keeps its facts', () => {
  const webui = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const css = readFileSync(path.join(webui, 'src/widgets/log-tail/log-tail.css'), 'utf8');
  const tsx = readFileSync(path.join(webui, 'src/widgets/log-tail/index.tsx'), 'utf8');

  test('the fact cells stick and the text cell does not', () => {
    expect(css).toMatch(/^\.myx-lt-row \.myx-sfield:not\(:last-child\) \.myx-sfield-value \{ position: sticky; top: 0; \}$/m);
  });

  test('the rows are placed by top, never by a transform sticky cannot see', () => {
    expect(tsx).toMatch(/style=\{\{ top: item\.start \}\}/);
    expect(tsx).not.toMatch(/translateY\(\$\{item\.start\}/);
  });
});

describe('it strips only what the row prints beside it', () => {
  test('a line with a timestamp and no head bracket keeps everything after the timestamp', () => {
    const line = '[2026-09-18 01:14:02] daemon started, no tag on this one';
    expect(headOf(line)).toBeNull();
    expect(messageOf(line)).toBe('daemon started, no tag on this one');
  });

  test('a continuation line carrying no brackets is untouched', () => {
    const line = '    at Module._load (node:internal/modules/cjs/loader:1285:25)';
    expect(messageOf(line)).toBe(line);
  });

  test('a bracket belonging to the MESSAGE is kept', () => {
    // only the row's own two brackets come off; a third is the daemon's and is the line's content
    const line = '[2026-09-18 01:14:02] [claude-grok] [retry] backoff=64000ms';
    expect(messageOf(line)).toBe('[retry] backoff=64000ms');
  });

  test('a line that is only a timestamp leaves an empty message rather than throwing', () => {
    expect(messageOf('[2026-09-18 01:14:02]')).toBe('');
  });
});

describe('the edge is the daemon severity', () => {
  test.each([
    ['error', 'red'], ['fatal', 'red'], ['warn', 'amber'], ['info', 'grey'], [null, 'grey'],
  ] as const)('%s reads %s', (level, edge) => {
    expect(edgeOfLevel(level)).toBe(edge);
  });

  test('stripping the prefix never changes the level the row reads', () => {
    // levelOf runs on the WHOLE line, so a severity word inside the prefix could not be lost --
    // asserted rather than assumed, because the cell and the edge now read different strings.
    const line = '[2026-09-18 01:14:05] [claude-deepseek] turn ERROR conn-reset latency=2827ms';
    expect(levelOf(line)).toBe('error');
    expect(edgeOfLevel(levelOf(line))).toBe('red');
  });
});
