// WALLS for the first viewport and the phone (row M3-03).
//
// THE ONE THE ROW NAMES: the layout sheets declare no fixed-pixel grid column. A column measured in
// px holds at the width it was authored for and wraps a hundred pixels narrower, which is the exact
// failure the responsive gate exists to catch -- and it is invisible in a capture taken at the
// width it was authored for. Tracks are fr, minmax(0, 1fr), percentages or rem; a ch width on a
// strip FIELD is not a column and is the world's own unit for a printed field (CONTRACTS.md
// section 2), so the scan is of the property that lays columns out, over every sheet on disk.
//
// The rest pin the phone's three moves, each of which is one declaration that a later row could
// drop while every page still renders: the rail becomes a bottom strip, the rule stacks, and an
// opened detail column covers the screen. They are asserted on the CSS text because a jsdom-less
// node test has no layout -- what is measured in a browser is in the row's note, and what is
// pinned here is the declaration that produced it.
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { revealBy } from '../src/widgets/rail';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const sheet = (rel: string): string => readFileSync(path.join(HERE, '..', rel), 'utf8');

/** The phone block of a sheet: everything inside `@media (max-width: 720px)`, braces matched. */
function phone(css: string): string {
  const out: string[] = [];
  let at = css.indexOf('@media (max-width: 720px)');
  while (at !== -1) {
    const open = css.indexOf('{', at);
    let depth = 0;
    let end = open;
    for (; end < css.length; end += 1) {
      if (css[end] === '{') depth += 1;
      if (css[end] === '}') { depth -= 1; if (depth === 0) break; }
    }
    out.push(css.slice(open + 1, end));
    at = css.indexOf('@media (max-width: 720px)', end);
  }
  return out.join('\n');
}

/** Every stylesheet under src, found on disk rather than listed here: a sheet added by a later row
 *  has to be scanned without an edit to this file, and a hand-kept list cannot fail for the one it
 *  omits. */
function allSheets(): string[] {
  const walk = (dir: string): string[] => readdirSync(path.join(HERE, '..', dir), { withFileTypes: true })
    .flatMap((entry) => (entry.isDirectory()
      ? walk(`${dir}/${entry.name}`)
      : entry.name.endsWith('.css') ? [`${dir}/${entry.name}`] : []));
  return walk('src');
}

/** Every `grid-template-columns` value in a sheet, comments stripped. */
function columnTracks(css: string): string[] {
  const bare = css.replace(/\/\*[\s\S]*?\*\//g, '');
  return [...bare.matchAll(/grid-template-columns:\s*([^;}]+)/g)].map((m) => m[1].trim());
}

/** A track list that pins a column to pixels. `0` is not a pixel width, and neither is a minmax
 *  FLOOR -- `minmax(360px, 40%)` is a column that grows; `40%` is what it resolves to. */
function fixedPx(tracks: string): boolean {
  return /(^|[\s(,])\d+(\.\d+)?px/.test(tracks.replace(/minmax\(\s*\d+(\.\d+)?px\s*,/g, 'minmax(0,'));
}

describe('the layout sheets lay out in fractions, not pixels', () => {
  // The row names app.css and ui.css. EVERY sheet is scanned instead, for the reason the row gives
  // for scanning at all: a px column wraps a hundred pixels narrower, and it does that wherever it
  // is declared -- ui.css holds no grid column at all today (0 of them), so a scan of the two named
  // sheets would be a wall over an empty set on the very file it names first.
  const sheets = allSheets();

  test('no grid column in any sheet is a fixed pixel width', () => {
    for (const rel of sheets) {
      for (const tracks of columnTracks(sheet(rel))) {
        expect(`${rel}: ${tracks}`).toBe(fixedPx(tracks) ? `${rel}: <a fixed px column>` : `${rel}: ${tracks}`);
      }
    }
  });

  test('the scan can fail: a px column is caught, a ch field and a minmax floor are not', () => {
    expect(fixedPx('minmax(0, 1fr) 320px')).toBe(true);
    expect(fixedPx('240px 1fr')).toBe(true);
    expect(fixedPx('minmax(0, 1fr) minmax(0, 24rem)')).toBe(false);
    expect(fixedPx('minmax(360px, 40%)')).toBe(false);
    expect(fixedPx('max-content minmax(0, 1fr)')).toBe(false);
    expect(fixedPx('minmax(0, 1fr) 0')).toBe(false);
  });

  test('the scan really read the sheets, the two the row names included', () => {
    expect(sheets).toContain('src/app/app.css');
    expect(sheets).toContain('src/shared/ui/ui.css');
    expect(sheets.length).toBeGreaterThan(20);
    for (const rel of sheets) expect(sheet(rel).length).toBeGreaterThan(0);
    // and the property it looks for is present somewhere, or the scan is over an empty set
    expect(sheets.flatMap((rel) => columnTracks(sheet(rel))).length).toBeGreaterThan(10);
  });
});

describe('the phone', () => {
  const app = phone(sheet('src/app/app.css'));
  const rail = phone(sheet('src/widgets/rail/rail.css'));
  const rule = phone(sheet('src/widgets/rule/rule.css'));
  const ui = phone(sheet('src/shared/ui/ui.css'));

  test('the shell stacks the rail under the page', () => {
    // one column for the page, and the rail is the row after it
    expect(app).toMatch(/\.myx-console-body\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)/);
    expect(app).toMatch(/\.myx-console-body\s*>\s*\.myx-rail\s*\{\s*order:\s*2/);
  });

  test('the rail lies down: its tabs are a row that scrolls sideways', () => {
    expect(rail).toMatch(/\.myx-rail-tabs\s*\{[^}]*flex-direction:\s*row/);
    expect(rail).toMatch(/\.myx-rail\s*\{[^}]*overflow-x:\s*auto/);
    // the tabs lie in one line that is as wide as its plates, not the column's width
    expect(rail).toMatch(/\.myx-rail-tabs\s*\{[^}]*width:\s*max-content/);
  });

  test('the rule stacks the clocks over health', () => {
    const areas = rule.match(/grid-template-areas:([\s\S]*?);/);
    expect(areas).not.toBeNull();
    const rows = (areas?.[1] ?? '').match(/'[^']*'/g) ?? [];
    expect(rows.map((row) => row.replace(/'/g, '').trim().split(/\s+/)))
      .toEqual([['mark', 'clocks'], ['mark', 'health'], ['window', 'window'], ['tail', 'tail']]);
  });

  test('an opened detail column covers the screen', () => {
    expect(ui).toMatch(/\.myx-swell:not\(\[aria-hidden='true'\]\)\s*\{[^}]*position:\s*fixed/);
    expect(ui).toMatch(/\.myx-swell:not\(\[aria-hidden='true'\]\)\s*\{[^}]*inset:\s*0/);
    // and it can be closed without the strip that opened it, which it is covering
    expect(ui).toMatch(/\.myx-swell-close\s*\{[^}]*display:\s*inline-flex/);
  });

  test('the walls can fail: the phone blocks are real text, not an empty match', () => {
    expect(phone('.myx-x { color: red; }')).toBe('');
    for (const block of [app, rail, rule, ui]) expect(block.length).toBeGreaterThan(40);
  });
});

// S12, THE 390 WALKTHROUGH. Five things the phone got wrong, each measured in a browser at 390x844
// before and after (the row's note carries the numbers). What is pinned here is the declaration or
// the arithmetic that produced each fix, so a later row cannot drop one while every page renders.
/** The declarations of the rule whose selector is exactly `selector`, at a line start. Comments go
 *  first: a note that names the old value would otherwise read as the value. */
function body(sheetText: string, selector: string): string {
  const css = sheetText.replace(/\/\*[\s\S]*?\*\//g, '');
  const at = css.search(new RegExp(`^${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{`, 'm'));
  return at < 0 ? '' : css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
}

describe('the phone at 390', () => {
  test('an empty wraps its sentence inside the rack instead of running under its edge', () => {
    expect(body(sheet('src/shared/ui/ui.css'), '.myx-empt')).toMatch(/max-width:\s*100%/);
  });

  test("team compose's fields shrink to the form", () => {
    const css = sheet('src/features/team-compose/team-compose.css');
    expect(body(css, '.myx-compose .myx-input')).toMatch(/min-width:\s*0/);
    expect(body(css, '.myx-compose .myx-input')).toMatch(/max-width:\s*100%/);
    expect(body(css, '.myx-compose .myx-input-box')).toMatch(/max-width:\s*100%/);
  });

  test("projects' cells never shrink below their ch: the strip is at least the bay, not exactly it", () => {
    const css = sheet('src/pages/projects/projects.css');
    expect(body(css, '.myx-px-bays .myx-strip')).toMatch(/min-width:\s*100%/);
    expect(body(css, '.myx-px-bays .myx-strip')).not.toMatch(/(^|[;\s])width:/);
    // the page rule that brought flex-shrink back over ui.css's `flex: 0 0 auto`
    expect(body(css, '.myx-px-bays .myx-sfield')).toBe('');
  });

  test('the board footer scrolls with its board rather than sitting over the page', () => {
    expect(body(sheet('src/widgets/team-board/board.css'), '.myx-board-footer')).not.toMatch(/position:\s*fixed/);
  });

  test('the rail brings an out-of-view tab to the middle and leaves a visible one alone', () => {
    const box = { start: 0, size: 390 };
    expect(revealBy(box, { start: 100, size: 80 })).toBe(0);
    // doctor, measured at 390 before the fix: x 1016..1089 in a 0..390 rail
    expect(revealBy(box, { start: 1016, size: 73 })).toBeCloseTo(1052.5 - 195);
    // a tab behind the start comes back the other way
    expect(revealBy(box, { start: -120, size: 60 })).toBe(-90 - 195);
    // a tab cut by the edge is not in view
    expect(revealBy(box, { start: 350, size: 80 })).toBe(390 - 195);
  });

  test('the walls can fail: a missing rule reads as empty, not as a pass', () => {
    expect(body('.myx-x { color: red; }', '.myx-empt')).toBe('');
    expect(body(sheet('src/shared/ui/ui.css'), '.myx-empt')).toMatch(/background/);
  });
});
