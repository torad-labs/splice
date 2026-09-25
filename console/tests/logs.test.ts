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
import { cacheHitOf, LogColumns, LogLine, messageOf, partsOf, perfOfLine, rowsOf, scaleOf, toneOfLevel, totalOf, whenOf } from '../src/widgets/log-tail';

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

// THE STREAM PRINTS ITS COLUMN NAMES ONCE (M3-04). Every tail row printed `time head level text` above
// its own four values, so a 40-line tail spent half its height on forty copies of one header. The
// names live on one row above the scroll, and a line prints values only.
describe('the stream prints its column names once', () => {
  const cells = (html: string) => [...html.matchAll(/class="myx-lt-(?:time|head|text)">([^<]*)</g)].map((m) => m[1]);

  test('a line prints no column name', () => {
    const row = renderToStaticMarkup(React.createElement(LogLine, { line: LINE }));
    expect(row).not.toContain('myx-lt-cols');
    expect(cells(row)).not.toContain('Time');
    expect(cells(row)).not.toContain('Message');
  });

  test('the column row prints its names in the line order, with no level column', () => {
    // The level is the dot's word; a column beside it printed it twice, and `-` on unmarked lines.
    expect(cells(renderToStaticMarkup(React.createElement(LogColumns)))).toEqual(['Time', 'Head', 'Message']);
  });

  test('a head\'s own log drops the head column from the names and from every row', () => {
    expect(cells(renderToStaticMarkup(React.createElement(LogColumns, { tagged: false })))).toEqual(['Time', 'Message']);
    const row = renderToStaticMarkup(React.createElement(LogLine, { line: LINE, tagged: false }));
    expect(row.replace(/\saria-label="[^"]*"/g, '')).not.toContain('claude-deepseek');
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

  test('the time cell sticks, shorter than its row, and the text cell does not', () => {
    expect(css).toMatch(/^\.myx-lt-row \.myx-lt-time \{ position: sticky; top: 0; align-self: start; \}$/m);
    expect(css).not.toMatch(/\.myx-lt-text[^{]*\{[^}]*position: sticky/);
  });

  test('scrolled, the rows fade under the column names instead of being sliced by them', () => {
    expect(css).toMatch(/^\.myx-lt-scrolled \{ mask-image: linear-gradient\(to bottom, transparent, /m);
    expect(tsx).toMatch(/virtualizer\.scrollOffset \?\? 0\) > 0 && 'myx-lt-scrolled'/);
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

describe('the tone is the daemon severity', () => {
  test.each([
    ['error', 'danger'], ['fatal', 'danger'], ['warn', 'warn'], ['info', 'neutral'], [null, null],
  ] as const)('%s reads %s', (level, tone) => {
    expect(toneOfLevel(level)).toBe(tone);
  });

  test('stripping the prefix never changes the level the row reads', () => {
    // levelOf runs on the WHOLE line, so a severity word inside the prefix could not be lost --
    // asserted rather than assumed, because the cell and the tone now read different strings.
    const line = '[2026-09-18 01:14:05] [claude-deepseek] turn ERROR conn-reset latency=2827ms';
    expect(levelOf(line)).toBe('error');
    expect(toneOfLevel(levelOf(line))).toBe('danger');
  });
});

// A LINE FROM ANOTHER DAY SAYS WHICH (console review, 2026-09-24). daemon.log rotates by size, so a
// tail spans days, and the time alone read 21:48:51 above 01:16:00 with no sign a midnight fell
// between them. The daemon dates every line for exactly this (DaemonBoundary.kt); the row now
// prints the day for any line not from the reader's today.
describe('a row says the day when it is not today', () => {
  const noon = new Date(2026, 8, 24, 12, 0, 0);

  test('today prints the time alone', () => {
    expect(whenOf('[2026-09-24 01:16:00] [claudex] ok', noon)).toBe('01:16:00');
  });

  test('another day prints the day before the time', () => {
    expect(whenOf('[2026-09-22 21:48:51] [claudex] ok', noon)).toBe('sep 22 21:48:51');
    expect(whenOf('[2025-12-31 23:59:59] [claudex] ok', noon)).toBe('dec 31 23:59:59');
  });

  test('a line with no timestamp prints no time', () => {
    expect(whenOf('    at Module._load (node:internal/modules/cjs/loader:1285:25)', noon)).toBe('');
  });

  test('an unmarked line carries no mark and no word, and a marked one prints its level', () => {
    const words = (html: string) => [...html.matchAll(/class="myx-badge-word">([^<]*)</g)].map((m) => m[1]);
    expect(words(renderToStaticMarkup(React.createElement(LogLine, { line: LINE })))).toEqual([]);
    const marked = '[2026-09-18 01:14:05] [claude-deepseek] turn ERROR conn-reset';
    const out = renderToStaticMarkup(React.createElement(LogLine, { line: marked }));
    expect(words(out)).toEqual(['error']);
    expect(out).toContain('myx-lt-danger');
  });
});

// A PERF LINE READS AS FIELDS (DESIGN.md section 7): its key=value pairs split into a quiet key and a
// full value, and the prose between them kept as written, so nothing the daemon wrote is lost.
describe('a message splits into its key=value pairs', () => {
  test('pairs and prose come back in order, and rejoin to the message', () => {
    const message = 'turn compact=false model=deepseek-flash ok';
    const parts = partsOf(message);
    expect(parts).toEqual([
      { text: 'turn ' },
      { key: 'compact', text: 'false' },
      { text: ' ' },
      { key: 'model', text: 'deepseek-flash' },
      { text: ' ok' },
    ]);
    expect(parts.map((part) => (part.key === undefined ? part.text : `${part.key}=${part.text}`)).join('')).toBe(message);
  });

  test('a message with no pair is one piece of prose', () => {
    expect(partsOf('daemon started')).toEqual([{ text: 'daemon started' }]);
  });
});

// A PERF LINE IS DRAWN, NOT PRINTED (the 2026-09-25 "show, then say" ruling: "a perf line becomes a
// per-turn waterfall row with a cache-hit bar and token bars, the raw line one click away"). The line
// below is the demo daemon's own, copied from its log on 2026-09-25, marks in the order it printed
// them: gate is stamped at admission, before parse and build, so the bar must follow the clock.
describe('a perf line is drawn as its turn', () => {
  const PERF = '[2026-09-25 02:20:02] [bonsai-2-27b] perf outcome=ok compact=false model=bonsai-2-27b session=e5b7a0c4 '
    + 'recv=2 parse=11 build=18 gate=1 headers=459 first_byte=470 first_frame=461 first_delta=473 stream_end=859 finish=866 total=869 '
    + '| inflight=1 in_tokens=72448 out_tokens=370 cached_tokens=65203 cache_write_tokens=0 usage_ms=1';
  const perf = perfOfLine(PERF);

  test('the line reads as its facts, and any other line reads as none', () => {
    expect(perf).not.toBeNull();
    expect(perf?.model).toBe('bonsai-2-27b');
    expect(perf?.outcome).toBe('ok');
    expect(perf?.marks).toMatchObject({ gate: 1, recv: 2, total: 869 });
    expect(perf?.inTokens).toBe(72448);
    expect(perfOfLine(LINE)).toBeNull();
    // `perf` inside a message is not a perf line: the daemon starts the message with it
    expect(perfOfLine('[2026-09-25 02:20:02] [claudex] turn note=perf outcome=ok')).toBeNull();
  });

  test('the cache hit is cached over in, since in_tokens holds the cached part', () => {
    if (perf === null) throw new Error('the demo line must parse');
    expect(cacheHitOf(perf)).toBeCloseTo(65203 / 72448);
    expect(cacheHitOf({ ...perf, inTokens: 0 })).toBeNull();
    expect(cacheHitOf({ ...perf, cachedTokens: null })).toBeNull();
  });

  test('the length is the closing tally, else the last mark stamped', () => {
    if (perf === null) throw new Error('the demo line must parse');
    expect(totalOf(perf)).toBe(869);
    const open = { ...perf.marks };
    delete open.total;
    expect(totalOf({ ...perf, marks: open })).toBe(866);
  });

  test('every row shares the tail\'s scale, so a slow turn is a long bar', () => {
    if (perf === null) throw new Error('the demo line must parse');
    const slow = { ...perf, marks: { ...perf.marks, total: 3476 }, inTokens: 144896, outTokens: 90 };
    expect(scaleOf([perf, slow])).toEqual({ ms: 3476, inTokens: 144896, outTokens: 370 });
    expect(scaleOf([])).toEqual({ ms: 0, inTokens: 0, outTokens: 0 });
    const html = renderToStaticMarkup(React.createElement(LogLine, { line: PERF, scale: scaleOf([perf, slow]) }));
    // the waterfall's last segment ends at 866 of 3476 ms, a quarter of the width
    const ends = [...html.matchAll(/left:([\d.]+)%;width:([\d.]+)%/g)].map((m) => Number(m[1]) + Number(m[2]));
    expect(Math.max(...ends)).toBeCloseTo((866 / 3476) * 100, 1);
    expect(html).toContain('aria-valuenow="50"'); // 72k of the tail's 145k in
  });

  test('the row draws the waterfall, the cache hit and the tokens, and prints no pair', () => {
    const html = renderToStaticMarkup(React.createElement(LogLine, { line: PERF }));
    expect(html).toContain('class="myx-wf"');
    expect(html).toContain('aria-label="Cache hit 90%"');
    expect(html).toContain('aria-label="Tokens in 72k"');
    expect(html).toContain('aria-label="Tokens out 370"');
    expect(html).toContain('869ms');
    expect(html, 'closed, the raw pairs stay one click away').not.toContain('myx-lt-key');
    expect(html).toContain('aria-expanded="false"');
  });

  test('open, the daemon\'s own line shows under the drawing, every pair kept', () => {
    const html = renderToStaticMarkup(React.createElement(LogLine, { line: PERF, open: true }));
    expect(html).toContain('aria-expanded="true"');
    const keys = [...html.matchAll(/class="myx-lt-key">([^<]*)=</g)].map((m) => m[1]);
    expect(keys).toEqual(partsOf(messageOf(PERF)).flatMap((part) => (part.key === undefined ? [] : [part.key])));
  });

  test('a counter the line did not carry is the absence mark over an empty bar, never a zero', () => {
    const bare = PERF.slice(0, PERF.indexOf(' |'));
    const html = renderToStaticMarkup(React.createElement(LogLine, { line: bare }));
    expect(perfOfLine(bare)?.inTokens).toBeNull();
    expect(html, 'the waterfall is the only reading left').not.toContain('role="meter"');
    expect(html).not.toContain('aria-valuenow');
    expect([...html.matchAll(/class="myx-meter myx-meter-neutral" aria-hidden="true"/g)]).toHaveLength(3);
    expect([...html.matchAll(/class="myx-meter-figure">–/g)]).toHaveLength(3);
  });

  test('an ok turn wears no outcome, a failed one wears it as a badge', () => {
    expect(renderToStaticMarkup(React.createElement(LogLine, { line: PERF }))).not.toContain('myx-badge');
    const failed = PERF.replace('outcome=ok', 'outcome=upstream_error').replace(/ stream_end=\d+ finish=\d+ total=\d+/, '');
    const html = renderToStaticMarkup(React.createElement(LogLine, { line: failed }));
    expect(html).toContain('myx-badge-danger');
    expect(html).toContain('>upstream_error<');
    // a failed turn stops where its last mark was stamped, never drawn as a finished one
    expect(perfOfLine(failed)?.marks.total).toBeUndefined();
  });
});

// A TURN PRINTS ITS NUMBERS ONCE (splice-lead on the 2026-09-25 second pass: "every turn still prints
// its raw turn and cache lines above the rendered row, so each number appears three times"). The
// daemon writes three lines per finished turn; the `turn` and `cache:` lines carry only numbers the
// perf row draws, so they fold under it, one click away. The group below is the demo daemon's own
// log from 04:53:34 on 2026-09-25: two turns finishing at once, their lines interleaved, so a fold
// by position ("the two lines before a perf line") hands the second turn's line to the first.
describe('a turn prints its numbers once', () => {
  const at = (time: string, message: string) => `[2026-09-25 ${time}] [claudex] ${message}`;
  const TURN_A = at('04:53:34', 'turn compact=false model=gpt-5.6-sol latency=10949ms ok out=2413 tool=false incomplete=false');
  const CACHE_A = at('04:53:34', 'cache: input=126236 cached=107211 hit=84% output=2413 model=gpt-5.6-sol');
  const TURN_B = at('04:53:34', 'turn compact=false model=gpt-5.6-sol latency=9449ms ok out=1521 tool=false incomplete=false');
  const PERF_A = at('04:53:34', 'perf outcome=ok compact=false model=gpt-5.6-sol session=d4c6f9b3 recv=0 parse=1 build=1 gate=0 '
    + 'headers=1389 first_byte=1389 first_frame=1389 first_delta=1389 stream_end=10949 finish=10949 total=10949 '
    + '| inflight=2 in_tokens=126236 out_tokens=2413 cached_tokens=107211 cache_write_tokens=0');
  const CACHE_B = at('04:53:34', 'cache: input=34432 cached=31861 hit=92% output=1521 model=gpt-5.6-sol');
  const PERF_B = at('04:53:34', 'perf outcome=ok compact=false model=gpt-5.6-sol session=d4c6f9b3 recv=1 parse=1 build=1 gate=0 '
    + 'headers=792 first_byte=9449 first_frame=792 first_delta=9449 stream_end=9449 finish=9450 total=9450 '
    + '| inflight=2 in_tokens=34432 out_tokens=1521 cached_tokens=31861 cache_write_tokens=0');
  const INTERLEAVED = [TURN_A, CACHE_A, TURN_B, PERF_A, CACHE_B, PERF_B];

  test('each perf row takes its own turn\'s lines, matched on their numbers, not their place', () => {
    expect(rowsOf(INTERLEAVED)).toEqual([
      { line: PERF_A, folded: [TURN_A, CACHE_A] },
      { line: PERF_B, folded: [TURN_B, CACHE_B] },
    ]);
  });

  test('a turn that crosses a second still folds: the second is not a key', () => {
    // 30 of 7,594 turns on the live log wrote their perf line in the next second
    const late = [TURN_B.replace('04:53:34', '04:53:33'), CACHE_B.replace('04:53:34', '04:53:33'), PERF_B];
    expect(rowsOf(late)).toEqual([{ line: PERF_B, folded: late.slice(0, 2) }]);
  });

  test('no line is lost: every line is a row or folded under one, once', () => {
    const lines = [at('04:53:30', 'account work -> spare: 5h window full'), ...INTERLEAVED];
    const rows = rowsOf(lines);
    expect(rows.flatMap((row) => [...row.folded, row.line]).sort()).toEqual([...lines].sort());
  });

  test('a line the row does not draw stays a line: another tag, an error, a failure, another turn\'s numbers', () => {
    const lines = [
      at('04:53:34', 'turn ERROR conn-reset compact=false latency=2827ms upstream closed'),
      at('04:53:34', 'turn compact=false model=gpt-5.6-sol latency=9449ms FAILURE type=api_error msg=out=1521 overloaded'),
      TURN_B.replace('[claudex]', '[bonsai]'),
      CACHE_B.replace('output=1521', 'output=1522'),
      PERF_B,
    ];
    expect(rowsOf(lines).map((row) => row.folded.length)).toEqual([0, 0, 0, 0, 0]);
  });

  test('a turn whose perf line has not landed yet shows its lines until it does', () => {
    expect(rowsOf([TURN_A, CACHE_A]).map((row) => row.line)).toEqual([TURN_A, CACHE_A]);
  });

  test('closed, the row prints none of the folded numbers; open, it shows the turn\'s three lines in order', () => {
    const closed = renderToStaticMarkup(React.createElement(LogLine, { line: PERF_A, folded: [TURN_A, CACHE_A] }));
    expect(closed).not.toContain('latency');
    expect(closed).not.toContain('hit=');
    const open = renderToStaticMarkup(React.createElement(LogLine, { line: PERF_A, folded: [TURN_A, CACHE_A], open: true }));
    const raw = [...open.matchAll(/class="myx-lt-raw-line">(.*?)<\/span><\/span>(?=<span class="myx-lt-raw-line">|<\/span>)/g)];
    expect(raw.map((m) => m[1].replace(/<[^>]+>/g, ''))).toEqual([TURN_A, CACHE_A, PERF_A].map(messageOf));
  });
});
