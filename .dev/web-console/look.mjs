#!/usr/bin/env node
// look — ONE command a look-bearing row runs before calling itself done: freeze the address, run
// the rendered detector on it, run the look-gate, and report the two AREA-WEIGHTED distributions
// the operator's two complaints ("the font size is too small", "there is no proper spacing") can
// actually be answered with.
//
// Usage: node .dev/web-console/look.mjs '<url>' [--out DIR] [--width W] [--height H] [--json]
//
// M1-76 DISPOSITION — the two ways a check can be decorative, answered for this file. CLEAN BOTH.
//   SHAPE ONE, does every FAIL reach the exit code? YES, and the shape is the reason: findings are
//     PUSHED to `blocks`, and `process.exit(blocks.length === 0 ? 0 : 1)` is the only exit. Every
//     catch in the file pushes — the snapshot catch (:527), the look-gate non-zero (:580), the
//     theme/room mismatch (:619), the measure catch (:622) — so there is no path that prints a
//     block and returns 0. The one worth naming because it is the easiest to get wrong: the
//     dom-pass failure does NOT die where it is caught. `domError` is recorded and then reaches
//     `blocks` at :645 as `no rule ran on the page`, so a run in which NEITHER engine produced a
//     rule is blocked rather than reported as a page with no findings. That is the distinction
//     between "clean" and "did not run", made at the exact point most files lose it.
//   SHAPE TWO, if every page threw, what would it print? IT WOULD BLOCK, LOUDLY — and this is the
//     inversion that matters. Because failures are pushes and not prints, an all-throwing run makes
//     `blocks` its LONGEST, not its shortest; the denominator fills as things go wrong instead of
//     emptying. Compare capture.mjs's --sweep before this row, where an unreadable directory emptied
//     the frame list and the summary read as a clean sweep. Same language, opposite polarity.
//
// WHY THE SNAPSHOT AND NOT THE URL: the console is behind the management-key gate (see
// snapshot.mjs).
//
// TWO PASSES, BECAUSE ONE ENGINE CANNOT SEE EVERYTHING.
//   dom    - detect.mjs reading the snapshot AS A FILE. A file target reaches the static-HTML
//            engine, which parses the markup AND resolves the cascade, so it emits rules a source
//            DIRECTORY cannot: a regex pass never sees a computed inset. This is what found the
//            board's flush cells (review B20) mechanically.
//   layout - the detector's own rendered rule set, run on OUR page over CDP. Those rules live in
//            one page-side bundle (detect-antipatterns-browser.js) that detect-url.mjs injects with
//            page.evaluate and then calls as window.impeccableDetect(...); nothing in it wants a
//            puppeteer handle, so the only thing puppeteer provides is evaluate - which this
//            campaign's CDP client does equally well. This is the only route to the rules that need
//            real geometry (clipped-overflow-container, text-occlusion, occlusion, contrast by
//            rect), and it is why no puppeteer install is needed. The bundle is READ FROM THE SKILL
//            at run time and never copied into this repo: a vendored rule set goes stale silently.
//
// THE OLD URL ROUTE IS A TRAP, AND IT IS STILL ASSERTED. Passing the snapshot as a file:// URL
// routes to the browser engine, which needs puppeteer; without it detect.mjs writes "Error:
// puppeteer is required for URL scanning" to stderr and EXITS 0 with `[]` on stdout, so a verify
// line chaining it reports a GREEN with nothing rendered. look.mjs never takes that route, and it
// blocks when neither of its two passes produced a finding.
//
// THE DISTRIBUTIONS. A count of CSS declarations has no weight in it: a strip's internal 4px field
// padding is one declaration and a few hundred pixels, the rack's 34px gap is one declaration and
// most of what the eye reads as rhythm, so counting them equally measures the stylesheet instead of
// the page (measured: the comp-faithful hero scored WORSE than nine of the pages it sets the
// standard for). These two are weighted by what the page actually shows: text by the rendered area
// of its own line boxes, gaps by the area between sibling boxes (the gap's size times the length of
// the edge it runs along).
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { resolve } from 'node:path';
import process from 'node:process';
import { show, withChrome } from './lib/cdp.mjs';
import { decodePng } from './lib/png.mjs';
import { LOOK_DIR, nameFor, snapshot } from './snapshot.mjs';
import { themeLanded, themeValues } from './theme.mjs';

const ARGS = process.argv.slice(2);
const flag = (name, dflt) => {
  const i = ARGS.indexOf(`--${name}`);
  return i === -1 ? dflt : ARGS[i + 1];
};
const has = (name) => ARGS.includes(`--${name}`);

const SKILL = process.env.IMPECCABLE_SKILL ?? '/home/user/.claude/skills/impeccable';
const DETECT = process.env.IMPECCABLE_DETECT ?? `${SKILL}/scripts/detect.mjs`;
const GATE = resolve(import.meta.dirname ?? '.', 'look-gate.mjs');

/** Every text node's own line boxes, and every gap between laid-out sibling boxes: two censuses. */
const MEASURE = `(() => {
  const area = new Map();
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    if (node.textContent.trim() === '') continue;
    const el = node.parentElement;
    if (el === null) continue;
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) continue;
    const range = document.createRange();
    range.selectNodeContents(node);
    let px = 0;
    for (const rect of range.getClientRects()) px += rect.width * rect.height;
    if (px === 0) continue;
    const size = parseFloat(style.fontSize);
    area.set(size, (area.get(size) ?? 0) + px);
  }
  const boxy = new Set(['block', 'flex', 'grid', 'list-item', 'table', 'table-row', 'flow-root']);
  const gaps = new Map();
  for (const parent of document.querySelectorAll('*')) {
    const kids = [...parent.children].filter((kid) => {
      const style = getComputedStyle(kid);
      if (style.display === 'none' || style.visibility === 'hidden') return false;
      if (style.position === 'absolute' || style.position === 'fixed') return false;
      return boxy.has(style.display);
    });
    if (kids.length < 2) continue;
    const rects = kids.map((kid) => kid.getBoundingClientRect());
    for (let i = 0; i + 1 < rects.length; i++) {
      const a = rects[i]; const b = rects[i + 1];
      const overV = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
      const overH = Math.min(a.right, b.right) - Math.max(a.left, b.left);
      const across = b.left - a.right;
      const down = b.top - a.bottom;
      let size = null; let length = 0;
      if (overV > 2 && across >= 0) { size = across; length = overV; }
      else if (overH > 2 && down >= 0) { size = down; length = overH; }
      if (size === null || length <= 0) continue;
      const rung = Math.round(size);
      gaps.set(rung, (gaps.get(rung) ?? 0) + rung * length);
    }
  }
  const series = (map) => [...map.entries()].sort((x, y) => x[0] - y[0]);
  return { type: series(area), gap: series(gaps) };
})()`;

/** What the page RESOLVED, not what it was asked for: the room colour and the theme attribute.
 *  Same shape theme.mjs's own probe returns, because themeLanded() reads it. */
const ROOM_PROBE = `(() => JSON.stringify({
  attr: document.documentElement.getAttribute('data-theme'),
  room: getComputedStyle(document.documentElement).getPropertyValue('--room').trim(),
}))()`;

/** Share of a series' total that sits at or below a threshold. */
function below(series, threshold) {
  const total = series.reduce((sum, [, px]) => sum + px, 0);
  if (total === 0) return { pct: 0, total: 0 };
  const hit = series.filter(([size]) => size <= threshold).reduce((sum, [, px]) => sum + px, 0);
  return { pct: (hit / total) * 100, total };
}

function table(series, unit, limit) {
  const total = series.reduce((sum, [, px]) => sum + px, 0);
  if (total === 0) return '    (nothing measured)';
  return series
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([size, px]) => {
      // A distance of hundreds of pixels between two siblings is not a spacing rung, it is a
      // layout void (a space-between row, an empty rack). It is left in the denominator - dropping
      // it silently would be a denominator chosen to flatter the answer - and labelled, so whoever
      // reads the number knows which of the two they are looking at.
      const note = size > 200 ? '   <- layout void, not a rung' : '';
      return `    ${String(size).padStart(5)}${unit}  ${((px / total) * 100).toFixed(1).padStart(5)}%${note}`;
    })
    .join('\n');
}

/** The comp's own numbers, for the same two questions, from its measured regions: type sizes from
 *  the scaffold's cap heights, gaps from the pixel scans of M1-12. The comp's side is a census of
 *  measured sizes, not an area-weighted sum - its text masks are the next step, and saying which
 *  half is weighted keeps the comparison honest rather than flattering. */
function compReference() {
  const scaffold = 'webui/.impeccable/build/scaffold/layout.css';
  const sizes = new Map();
  try {
    for (const line of readFileSync(scaffold, 'utf8').split('\n')) {
      const size = line.match(/font:\s*(\d+(?:\.\d+)?)px/);
      if (size) sizes.set(Number(size[1]), (sizes.get(Number(size[1])) ?? 0) + 1);
    }
  } catch { /* the comp scaffold is gitignored; the reference is then simply absent */ }
  const named = [...sizes.entries()].sort((a, b) => a[0] - b[0]);
  return {
    type: named.length === 0 ? null : named.map(([px, n]) => `${px}px x${n}`).join(', '),
    gap: 'between-strip 34px, strip pitch 96px, rack slot rule 31-32px, cell padding 8px (M1-12 scans)',
  };
}

// ---------------------------------------------------------------------------------------------
// THE COMP'S OWN TYPE, BY AREA (M1-22 Half One).
//
// Why this had to exist: the build side of this comparison is area-weighted and the comp side was a
// census of seven named regions, so the row was comparing a weighted number to an unweighted one —
// the defect this campaign has made repeatedly. The comp is a raster, so its type is measured the
// way a reader sees it: ink, grouped into lines, weighted by how many pixels each size actually
// covers.
//
// THREE RULES, each one a correction to a mask that was already tried and was wrong:
//
//   GROUND PER BAND, NOT PER REGION. The first mask took one ground per declared region, so a box
//   holding two grounds (a strip's paper over the room behind it) counted the paper as ink and the
//   measurement collapsed — every text line measured as tall as its strip. A band here is a run of
//   rows with a stable palette, and its ground is the two most common colours IN THAT BAND, because
//   a band through a rack legitimately holds both the paper and the room. Ink is what differs from
//   both, which is text and not the boundary between two grounds.
//
//   LINES, NOT REGIONS. Ink rows are grouped into runs and each run is one line of text with its
//   own ink height and its own ink area. A region is a box somebody declared; a line is what the
//   eye reads, and the reviewer's blind pass measured lines too.
//
//   COVERAGE BESIDE EVERY NUMBER. Ink is a fraction of the frame, and a line of type over an empty
//   room is a different fact from the same line over a rack. Every band prints its own coverage so
//   a reader can see how much of that band is ink at all, and a band that is 0.2% ink cannot be
//   mistaken for a body of text.
const COMP_TOLERANCE = 42;   // channel distance that separates ink from either ground of a band
const COMP_MIN_RUN = 6;      // ink pixels in a row before that row counts as carrying a line

/** The modal colours of a row, sampled across it. */
function rowPalette(png, y, step = 2) {
  const { width, channels, pixels } = png;
  const counts = new Map();
  for (let x = 0; x < width; x += step) {
    const at = (y * width + x) * channels;
    const key = `${pixels[at]},${pixels[at + 1]},${pixels[at + 2]}`;
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

/**
 * The comp's text, as lines with a height and an area, and the area-weighted distribution of those
 * heights. `bands` is published so the denominator is visible rather than implied.
 */
export function compType(png) {
  const { width, height, channels, pixels } = png;
  const near = (a, b) => Math.abs(a[0] - b[0]) <= 6 && Math.abs(a[1] - b[1]) <= 6 && Math.abs(a[2] - b[2]) <= 6;
  const rgbAt = (x, y) => { const i = (y * width + x) * channels; return [pixels[i], pixels[i + 1], pixels[i + 2]]; };
  const far = (c, ground) => Math.max(
    Math.abs(c[0] - ground[0]), Math.abs(c[1] - ground[1]), Math.abs(c[2] - ground[2])) > COMP_TOLERANCE;

  // 1. The GROUNDS: the comp's flat colours, measured over the whole frame.
  //
  //    Not per row and not per region. A band taken per row re-segments itself across a line of
  //    text - the rows carrying ink have a different palette from the rows between letters, so one
  //    line becomes three bands and every line reports as a few 3px slivers. (Measured: that is
  //    exactly what the previous attempt produced, 25 percent of its ink at a 3px "height".) The
  //    comp is a printed world with a handful of flat tones, so the grounds are a property of the
  //    IMAGE and are measured once: every colour covering at least one percent of the frame, plus
  //    its immediate neighbours, is a ground; ink is whatever is far from all of them.
  const palette = new Map();
  for (let y = 0; y < height; y += 3) {
    for (let x = 0; x < width; x += 3) {
      const i = (y * width + x) * channels;
      const key = `${pixels[i]},${pixels[i + 1]},${pixels[i + 2]}`;
      palette.set(key, (palette.get(key) ?? 0) + 1);
    }
  }
  const sampled = [...palette.values()].reduce((sum, n) => sum + n, 0);
  // CLUSTERED, because a flat tone in a raster is not one colour: every edge carries an
  // anti-aliased ramp of a dozen near-tones, and taking colours at a threshold would make the ramp
  // a ground and the tone itself ink. Sorting by coverage and folding each colour into the first
  // cluster it is close to leaves the frame's actual flat grounds, and the ramp is absorbed with the
  // tone it belongs to.
  const clusters = [];
  for (const [key, n] of [...palette.entries()].sort((a, b) => b[1] - a[1])) {
    const colour = key.split(',').map(Number);
    const home = clusters.find((c) => near(c.colour, colour));
    if (home === undefined) clusters.push({ colour, n });
    else home.n += n;
  }
  const grounds = clusters.filter((c) => c.n / sampled >= 0.01).map((c) => c.colour);
  const bands = [{ start: 0, end: height - 1, palette: grounds }];
  const groundShare = clusters.filter((c) => c.n / sampled >= 0.01).reduce((sum, c) => sum + c.n, 0) / sampled;
  // A MASK THAT CANNOT FAIL MUST SAY SO (law 23). If the grounds it derived cover essentially the
  // whole frame, nothing can be ink and every table below is empty for a reason the reader cannot
  // see - which is how a broken instrument reads as a clean result.
  const blind = groundShare > 0.98;

  // 2. Ink rows per band, against BOTH of the band's grounds.
  const lines = [];
  const report = [];
  for (const band of bands) {
    const grounds = band.palette;
    if (grounds.length === 0) continue;
    if (grounds.length > 5) continue;
    const inkRows = [];
    const firstX = new Map();   // the first and last ink x of a row, for the line's own extent
    const lastX = new Map();
    const maxStrokes = new Map();
    for (let y = band.start; y <= band.end; y += 1) {
      let ink = 0;
      let strokes = 0;      // contiguous x segments of ink in this row
      let inStroke = false;
      let lo = -1; let hi = -1;
      for (let x = 0; x < width; x += 1) {
        const c = rgbAt(x, y);
        const isInk = grounds.every((ground) => far(c, ground));
        if (isInk) {
          ink += 1; if (!inStroke) strokes += 1;
          if (lo === -1) lo = x;
          hi = x;
        }
        inStroke = isInk;
      }
      firstX.set(y, lo); lastX.set(y, hi);
      // TYPE IS MANY STROKES. A border, a plate edge or a ground boundary is one contiguous run of
      // ink across the row; a line of text is a row of letterforms, so it breaks into several. The
      // first mask had no such test and reported 3px and 4px "type" wherever a rule was drawn - the
      // same class of error as the withdrawn ladder, which was measured off junk rows.
      maxStrokes.set(y, strokes);
      inkRows.push(ink >= COMP_MIN_RUN && strokes >= 3 ? ink : 0);
    }
    const bandPixels = (band.end - band.start + 1) * width;
    const bandInk = inkRows.reduce((sum, n) => sum + n, 0);
    // 3. Lines: runs of rows that carry ink, a one-row gap tolerated inside a run.
    let run = null;
    const bandLines = [];
    for (let i = 0; i < inkRows.length; i += 1) {
      const carries = inkRows[i] >= COMP_MIN_RUN;
      if (carries) {
        if (run === null) run = { start: i, end: i, area: 0 };
        run.end = i;
        run.area += inkRows[i];
      } else if (run !== null && i + 1 < inkRows.length && inkRows[i + 1] >= COMP_MIN_RUN) {
        continue; // a single blank row inside a line (a descender gap) does not end it
      } else if (run !== null) {
        bandLines.push(run);
        run = null;
      }
    }
    if (run !== null) bandLines.push(run);
    for (const line of bandLines) {
      const px = line.end - line.start + 1;
      if (px < 3) continue; // a 1-2px rule is a border, not type
      // TYPE IS WIDE. A line of text spans many columns and is short; what otherwise passes every
      // test is a ROUNDED PLATE CORNER - four or five short ink segments, three rows tall - and the
      // empty rack's slot rules. The first mask reported 24 of those as "3px type" and they were 24
      // percent of its ink, which is how a measurement gets withdrawn.
      let lo = width; let hi = -1;
      for (let y = band.start + line.start; y <= band.start + line.end; y += 1) {
        const a = firstX.get(y); const b = lastX.get(y);
        if (a === undefined || a === -1) continue;
        if (a < lo) lo = a;
        if (b > hi) hi = b;
      }
      const extend = hi - lo + 1;
      if (extend < 24 || extend < 4 * px) continue;
      // AND TYPE HAS LETTERS. A rail plate carries two small square DOTS at its ends; a row through
      // a dot is two or three short segments, three rows tall, spread across the plate's whole
      // width - every other test passes it. A line of text breaks into a segment per letter, so the
      // count of segments in its busiest row is what separates the two.
      let busiest = 0;
      for (let y = band.start + line.start; y <= band.start + line.end; y += 1) {
        busiest = Math.max(busiest, maxStrokes.get(y) ?? 0);
      }
      if (busiest < 6) continue;
      lines.push({ top: band.start + line.start, height: px, area: line.area, width: extend, strokes: busiest });
    }
    report.push({
      start: band.start, end: band.end, grounds: grounds.map((c) => c.join(',')),
      coverage: bandPixels === 0 ? 0 : (bandInk / bandPixels) * 100, lines: bandLines.length,
    });
  }

  // 4. The distribution the build's side is compared against: share of INK AREA by line height.
  const byHeight = new Map();
  for (const line of lines) byHeight.set(line.height, (byHeight.get(line.height) ?? 0) + line.area);
  // Empty is a did-not-run too: a frame this full of type that yields zero lines has a mask
  // problem, not an absence of text.
  const zeroLines = lines.length === 0;
  return { bands: report, lines, byHeight, blind: blind || zeroLines, groundShare,
    groundCount: grounds.length, zeroLines, totalInk: lines.reduce((sum, l) => sum + l.area, 0) };
}

// ---------------------------------------------------------------------------------------------

// ---------------------------------------------------------------------------------------------
// THE LADDER CHECK (M1-22 Half Three), replacing look-gate's ladder-steps.
//
// WHY THE OLD ONE COULD NOT FAIL FOR THE REAL DEFECT: it counted the DECLARATION steps a stylesheet
// announces (0 of 5 reach 1.25x) and said nothing about what the frame shows. Measured on this tree:
// by declaration count --text-1 looked like the most-used rung in the world at 66 occurrences, and
// by AREA it is 6.8 percent while one rung carries 88.9. A count and the eye disagreed and the
// instrument with weight in it agreed with the eye. This one measures the frame.
//
// WHAT IT ASSERTS, both of them derived from the reviewer's own blind pass (N-2) rather than typed:
//   FLOOR      every text role renders a cap height of at least FLOOR of the frame. N-2 measured 9px
//              of ink at 3840 on knob names and column headers - 1.6mm on a 32-inch panel - and the
//              claim here is the RATIO, not the pixel: a per-frame rule is the only kind that can
//              be true at two sizes, and this row exists because type was in px while layout was vw.
//   HIERARCHY  the roles must not invert: wordmark >= page title >= value >= tab label >= field
//              label. N-2's finding was a nav tab label LARGER than the wordmark, and a title 2.3x
//              the smallest text. A ladder whose top is 2.3x its bottom is not a ladder.
//
// THE INK HEIGHT IS MEASURED, NOT ASSUMED: a canvas 2D context is asked for the actual bounding box
// ascent of a capital H in the element's own computed font, which is the same quantity the blind
// pass measured off the raster. No cap-height ratio is invented anywhere.
const LADDER_ROLES = [
  ['wordmark', '.myx-rule-wordmark'],
  ['page title', 'h1, h2'],
  ['strip value', '.myx-sfield-text'],
  ['tab label', '.myx-rail-tab .myx-edge-label'],
  ['field label', '.myx-sfield-label'],
  ['bay label', '.myx-bay-label'],
];
// THE FLOOR, and why it is this number rather than a round one. Two measurements bracket it:
// design-reviewer's blind pass at 3840 measured the smallest text in the world at 9px of ink on a
// 2160 frame - 0.417 percent, about 1.6mm of cap on a 32-inch panel - and called it the defect; the
// same role measures 20px today, 0.926 percent, after M1-26 moved the ladder into rem. The floor is
// set BETWEEN them, so the frame that was called unreadable fails and the frame that ships passes,
// and a regression of more than about 8 percent toward the old defect goes red. A floor set at
// today's own value would be a ratchet that freezes whatever happens to be there; a floor set at
// the reviewer's value would pass the frame they rejected.
const LADDER_FLOOR = 0.0085;
/** Two roles closer than this are the same rung, not an inversion: the ladder is measured off
 *  rendered glyph boxes and a rounding step of half a pixel is not a design decision. */
const LADDER_INVERSION_SLACK = 0.5;

const LADDER_PROBE = `(() => {
  const ctx = document.createElement('canvas').getContext('2d');
  const cap = (el) => {
    const cs = getComputedStyle(el);
    ctx.font = cs.font || cs.fontWeight + ' ' + cs.fontSize + ' ' + cs.fontFamily;
    const m = ctx.measureText('H');
    return Number.isFinite(m.actualBoundingBoxAscent) ? m.actualBoundingBoxAscent : null;
  };
  const out = [];
  for (const [name, sel] of ${JSON.stringify(LADDER_ROLES)}) {
    const els = [...document.querySelectorAll(sel)].filter((el) => el.textContent.trim() !== '');
    const caps = els.map(cap).filter((c) => c !== null);
    out.push({ name, count: els.length, min: caps.length === 0 ? null : Math.min(...caps) });
  }
  return JSON.stringify({ frame: window.innerHeight, roles: out });
})()`;

/** The layout a mutated ladder produces: every role on one rung. Used by the selftest. */
const LADDER_FLATTEN = '*, *::before, *::after { font-size: 12px !important; }';

async function ladderReport(url, width, height, theme, mutate) {
  let report = null;
  // `theme` WAS A PARAMETER THIS FUNCTION NEVER USED (M1-60). It was accepted, threaded through
  // from --theme, and dropped on the floor: the session seeded the key and not the room, so every
  // ladder report was measured in the default room whatever the caller asked for, and printed the
  // requested theme in its headline. A number labelled with a room it was not measured in.
  await withChrome(themeValues(theme), async (send) => {
    await show(send, url, width, height, 4000);
    if (mutate !== null) {
      await send('Runtime.evaluate', { expression: `(() => { const s = document.createElement('style'); s.textContent = ${JSON.stringify(mutate)}; document.head.append(s); })()` });
    }
    report = JSON.parse(await send('Runtime.evaluate', { expression: LADDER_PROBE, returnByValue: true }).then((r) => r.result.value));
  });
  return report;
}

/** The verdict: which roles are under the floor, and where the ladder inverts. */
function ladderVerdict(report) {
  const floor = LADDER_FLOOR * report.frame;
  const under = report.roles.filter((role) => role.min !== null && role.min < floor);
  const seen = report.roles.filter((role) => role.min !== null);
  const inversions = [];
  for (let i = 0; i + 1 < seen.length; i += 1) {
    if (seen[i + 1].min > seen[i].min + LADDER_INVERSION_SLACK) inversions.push(`${seen[i + 1].name} (${seen[i + 1].min}px) > ${seen[i].name} (${seen[i].min}px)`);
  }
  return { floor, under, inversions, measured: seen };
}

// --ladder: the check, with its own mutation proof available in the same run.
if (has('ladder')) {
  const target = ARGS.find((a) => a.startsWith('http'));
  if (target === undefined) {
    console.error('usage: node .dev/web-console/look.mjs --ladder <url> [--width W] [--height H] [--theme dark|light] [--mutate]');
    process.exit(2);
  }
  const width = Number(flag('width', '3840'));
  const height = Number(flag('height', '2160'));
  const theme = flag('theme', 'dark');
  const mutated = has('mutate') ? LADDER_FLATTEN : null;
  const report = await ladderReport(target, width, height, theme, mutated);
  const verdict = ladderVerdict(report);
  const mode = mutated === null ? 'as rendered' : 'MUTATED (every role forced onto one rung)';
  console.log(`ladder — ${target} at ${width}x${height} ${theme}, ${mode}\n`);
  for (const role of verdict.measured) {
    const share = role.min / report.frame;
    console.log(`  ${role.name.padEnd(12)} ${String(role.min).padStart(5)}px ink  ${(share * 100).toFixed(3)}% of the frame  n=${role.count}`);
  }
  console.log(`\n  floor      ${verdict.floor.toFixed(1)}px (${(LADDER_FLOOR * 100).toFixed(3)}% of the frame)`);
  for (const role of verdict.under) console.log(`  BELOW FLOOR  ${role.name}: ${role.min}px`);
  for (const line of verdict.inversions) console.log(`  INVERTED     ${line}`);
  const failed = verdict.under.length > 0 || verdict.inversions.length > 0;
  console.log(`\n  LADDER: ${failed ? 'FAIL' : 'PASS'}${mutated === null ? '' : ' (mutation: a PASS here would mean the check cannot see a flattened ladder)'}`);
  process.exit(failed ? 1 : 0);
}

const COMP_ARG = flag('comp', null);
if (COMP_ARG !== null) {
  const png = decodePng(readFileSync(COMP_ARG));
  const measured = compType(png);
  if (has('json')) {
    console.log(JSON.stringify({ comp: COMP_ARG, frame: `${png.width}x${png.height}`,
      bands: measured.bands, lines: measured.lines,
      byHeight: [...measured.byHeight.entries()].sort((a, b) => a[0] - b[0]) }, null, 2));
  } else {
    console.log(`comp type by ink area — ${COMP_ARG}  ${png.width}x${png.height}\n`);
    if (measured.blind) {
      console.error(`DID NOT RUN — ${measured.zeroLines
        ? 'the mask found no line of type in a frame full of it'
        : `the grounds derived from this frame cover ${(measured.groundShare * 100).toFixed(1)}% of it (${measured.groundCount} ground(s)), so nothing can be ink`}. The mask needs a round it has not had; anything below is NOT a distribution and must not be quoted.`);
    }
    console.log('  band            grounds                 coverage   lines');
    for (const band of measured.bands) {
      console.log(`  y${String(band.start).padStart(4)}..${String(band.end).padEnd(4)}  ${band.grounds.join(' / ').padEnd(22)} ${band.coverage.toFixed(2).padStart(6)}%   ${String(band.lines).padStart(3)}`);
    }
    console.log('\n  the heaviest lines (top, height, ink pixels)');
    for (const line of [...measured.lines].sort((a, b) => b.area - a.area).slice(0, 24)) {
      console.log(`    y${String(line.top).padStart(4)}  ${String(line.height).padStart(3)}px  ${String(line.area).padStart(6)} px`);
    }
    console.log('\n  ink height   share of ink area');
    const total = [...measured.byHeight.values()].reduce((sum, px) => sum + px, 0);
    for (const [height, px] of [...measured.byHeight.entries()].sort((a, b) => a[0] - b[0])) {
      console.log(`  ${String(height).padStart(6)}px   ${((px / total) * 100).toFixed(2).padStart(6)}%   ${'#'.repeat(Math.round((px / total) * 60))}`);
    }
    console.log(`\n  ${measured.lines.length} line(s), ${total} ink pixel(s), frame ${png.width * png.height}`);
  }
  process.exit(0);
}


const URL_ARG = ARGS.find((a) => !a.startsWith('--'));
if (URL_ARG === undefined || has('help')) {
  console.log("usage: node .dev/web-console/look.mjs '<url>' [--out DIR] [--width W] [--height H] [--theme dark|light] [--json]");
  console.log("       node .dev/web-console/look.mjs --comp <png> [--json]   (the comp's type by ink area)");
  console.log('  freezes the address, runs the rendered detector and the look-gate on it, and');
  console.log('  reports the two area-weighted distributions (type area, sibling-gap area).');
  process.exit(URL_ARG === undefined && !has('help') ? 2 : 0);
}

const outDir = flag('out', LOOK_DIR);
const width = Number(flag('width', '1536'));
const height = Number(flag('height', '1024'));
// The theme is part of the snapshot's IDENTITY, not a setting applied to it, so it is in the
// filename. Two themes writing one path would have the second silently overwrite the first and
// the run would report two passes over one room — the shape of defect this campaign keeps
// finding, arriving this time as a filename collision.
const theme = flag('theme', 'dark');
if (theme !== 'dark' && theme !== 'light') {
  console.error(`REFUSED: --theme ${theme} is not dark or light. An unknown theme renders the default and reports on a room nobody asked for.`);
  process.exit(2);
}
const out = resolve(outDir, nameFor(URL_ARG).replace(/\.html$/, `-${theme}.html`));
const blocks = [];

// 1 — the snapshot
let bytes = 0;
try {
  ({ bytes } = await snapshot(URL_ARG, out, width, height, theme));
  if (!existsSync(out) || statSync(out).size < 1000) throw new Error('the snapshot is empty');
} catch (error) {
  blocks.push(['snapshot', error.message]);
}

// 2a — the DOM pass: the detector CLI reading the SNAPSHOT AS A FILE. A file target reaches the
// static-HTML engine, which parses the markup AND resolves the cascade (computed padding, colors,
// font sizes), so it emits rules a source-DIRECTORY scan structurally cannot: a regex pass never
// sees a computed inset. Passing the file as a URL instead routes to the browser engine, which
// needs puppeteer - and which, in this build, prints its failure to stderr and EXITS 0, so a verify
// line chaining it reports a green with nothing rendered.
let domFindings = [];
let domError = null;
if (blocks.length === 0) {
  const run = spawnSync('node', [DETECT, '--json', out], { encoding: 'utf8', maxBuffer: 1 << 28 });
  const stderr = run.stderr ?? '';
  try {
    domFindings = JSON.parse(run.stdout);
    if (!Array.isArray(domFindings)) throw new Error('no findings array');
  } catch (error) {
    domFindings = [];
    domError = /puppeteer is required/i.test(stderr)
      ? `the detector reached its browser engine and stopped there: ${stderr.trim().split('\n')[0]}`
      : `the detector printed no JSON (${error.message})${stderr.trim() === '' ? '' : `: ${stderr.trim().split('\n')[0]}`}`;
  }
}

// 2b — the LAYOUT pass, on our own page. The detector's rendered rule set lives in one page-side
// bundle (detect-antipatterns-browser.js) that detect-url.mjs injects with page.evaluate and then
// calls as window.impeccableDetect(...). Nothing in it wants a puppeteer handle: the rules run
// inside the page against the real DOM, so the only thing puppeteer provides at that point is
// evaluate - which this campaign's own CDP client does equally well. The bundle is READ FROM THE
// SKILL at run time and never copied into this repo, so a rule that changes upstream changes here.
const BUNDLE = process.env.IMPECCABLE_BROWSER_BUNDLE ?? `${SKILL}/scripts/detector/detect-antipatterns-browser.js`;

async function layoutFindings(send) {
  if (!existsSync(BUNDLE)) throw new Error(`the rule bundle is not at ${BUNDLE}`);
  const source = readFileSync(BUNDLE, 'utf8');
  await send('Runtime.evaluate', {
    expression: 'window.__IMPECCABLE_CONFIG__ = Object.assign(window.__IMPECCABLE_CONFIG__ || {}, { autoScan: false });',
  });
  await send('Runtime.evaluate', { expression: source });
  const out2 = await send('Runtime.evaluate', {
    expression: "JSON.stringify(typeof window.impeccableDetect === 'function' ? window.impeccableDetect({ decorate: false, serialize: true }) : null)",
    returnByValue: true,
  });
  const raw = out2?.result?.value;
  if (typeof raw !== 'string') throw new Error('the bundle did not expose window.impeccableDetect');
  const groups = JSON.parse(raw);
  if (groups === null) throw new Error('the bundle exposed no scan function');
  return groups.flatMap((group) => (group.findings ?? []).map((f) => ({ id: f.type, detail: f.detail, severity: f.severity })));
}

// 3 — the look-gate (its own static + capture checks, blocking set included)
const gate = spawnSync('node', [GATE, '--captures', 'webui/.impeccable/review/sections'], { encoding: 'utf8' });
if (gate.status !== 0) blocks.push(['look-gate', (gate.stdout ?? '').trim().split('\n').filter((l) => /FAIL/.test(l)).join(' · ') || 'blocking check failed']);

// 3b/4 — ONE browser session does both: the layout rules on the live page, and the two
// area-weighted censuses. Same Chrome the snapshot used, same seeded values, no second engine.
//
// "SAME SEEDED KEY" WAS TRUE AND WAS THE BUG (M1-60). This session seeded the management key and
// NOT the theme, while the snapshot twenty lines up seeded both — so on `--theme light` the
// snapshot was light and the layout findings and BOTH area-weighted censuses were measured in the
// dark room, under a headline that says light. The gate runs exactly that cell: LOOKS carries
// ['3840x2160', 'light'], so every light-room layout finding and every light coverage number this
// leg has ever produced was read off the dark room. themeValues() seeds both, which is the whole
// reason it returns the key alongside the theme rather than the theme alone.
let measured = null;
let layout = [];
let layoutError = null;
let room = null;
try {
  const both = await withChrome(themeValues(theme), async (send) => {
    await show(send, URL_ARG, width, height);
    // AND IT PROVES THE ROOM IT MEASURED, rather than printing the one it asked for (M1-60). Seeding
    // the theme is not the same claim as rendering it: this session seeded no theme at all until
    // today and still printed `light` in its headline, and nothing anywhere could have told the
    // difference. themeLanded compares the RESOLVED --room against the room the theme declares —
    // the attribute says what was requested, the room says what resolved — so a pass that renders
    // the wrong room now blocks instead of reporting numbers under the wrong label. This is the LOOK
    // law applied to the instrument itself: a claim about how something looks needs a measured DOM.
    const probe = JSON.parse((await send('Runtime.evaluate', { expression: ROOM_PROBE, returnByValue: true })).result.value);
    const landed = themeLanded(theme, probe);
    let rules = null;
    let failure = null;
    try { rules = await layoutFindings(send); } catch (error) { failure = error.message; }
    const result = await send('Runtime.evaluate', { expression: MEASURE, returnByValue: true });
    return { rules, failure, measured: result?.result?.value ?? null, landed };
  });
  layout = both.rules ?? [];
  layoutError = both.failure;
  measured = both.measured;
  room = both.landed;
  if (!room.ok) {
    blocks.push(['theme', `the measured page is not the room this report names: ${room.why}`]);
  }
} catch (error) {
  blocks.push(['measure', error.message]);
}

const ref = compReference();
if (has('json')) {
  console.log(JSON.stringify({ url: URL_ARG, snapshot: out, bytes, dom: domFindings, layout, measured, room, comp: ref, blocks }, null, 2));
} else {
  // frame and theme in the headline: this is run more than once per gate now, and two reports that
  // do not say which room and which size they read are two reports nobody can tell apart.
  // THE MEASURED ROOM, not the requested one. Two reports that do not say which room they read are
  // two reports nobody can tell apart — and one that names a room it did not render is worse.
  const roomSaid = room === null ? 'room UNMEASURED' : (room.ok ? `room ${room.seen.join(',')} ✓` : `ROOM MISMATCH — ${room.why}`);
  console.log(`look — ${URL_ARG}  ${width}x${height} ${theme}  (${roomSaid})\n`);
  console.log(`  snapshot   ${out} (${bytes} bytes)`);
  console.log(`  dom        ${domError === null ? `${domFindings.length} findings (static-HTML engine: markup + resolved cascade)` : `DID NOT RUN - ${domError}`}`);
  for (const finding of domFindings.slice(0, 8)) {
    console.log(`               ${finding.name ?? finding.antipattern ?? '?'}  ${(finding.description ?? finding.snippet ?? '').slice(0, 80)}`);
  }
  console.log(`  layout     ${layoutError === null ? `${layout.length} findings (the detector's own rule bundle, on our page over CDP)` : `DID NOT RUN - ${layoutError}`}`);
  for (const finding of layout.slice(0, 8)) {
    console.log(`               ${finding.id ?? '?'}  ${(finding.detail ?? '').slice(0, 88)}`);
  }
  if (domFindings.length === 0 && layout.length === 0) {
    blocks.push(['detect', `no rule ran on the page: ${domError ?? ''} ${layoutError ?? ''}`.trim()]);
  }
  console.log(`  look-gate  ${blocks.some(([who]) => who === 'look-gate') ? 'FAIL (blocking)' : 'pass'}`);
  if (measured !== null) {
    const type = below(measured.type, 12);
    const gap = below(measured.gap, 8);
    console.log('\n  TYPE by rendered text area (live boxes)');
    console.log(table(measured.type, 'px', 8));
    console.log(`    ${type.pct.toFixed(0)}% of the text area is set at 12px or smaller`);
    console.log(`  comp reference (measured regions): ${ref.type ?? 'scaffold not present'}`);
    console.log('\n  GAP by area between sibling boxes (live boxes)');
    console.log(table(measured.gap, 'px', 8));
    console.log(`    ${gap.pct.toFixed(0)}% of the gap area is 8px or smaller`);
    console.log(`  comp reference (pixel scans): ${ref.gap}`);
  }
  for (const [who, detail] of blocks) console.log(`\n  BLOCKED  ${who}: ${detail}`);
  console.log(`\n  LOOK: ${blocks.length === 0 ? 'PASS' : `BLOCKED (${blocks.length})`}`);
}
process.exit(blocks.length === 0 ? 0 : 1);
