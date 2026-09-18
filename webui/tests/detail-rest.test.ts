// NO DETAIL COLUMN HOLDS WIDTH AT REST (M1-112, from M1-109's measurement and M1-102's remedy).
//
// THE DEFECT: a detail column occupying a grid track before anything is opened, carrying an empty
// card or nothing at all. Measured at 1536 dark as the largest region holding no paper and only a
// thin scatter of mid-tone -- space crossed by the rack's own rails and printed with nothing --
// against the comp's own figure of 11.2%: models 21.5%, accounts 20.3%, turns 17.7%, compaction
// 17.2%. The comp settles it rather than anyone's preference: team-board-a's right third is a
// POPULATED BAY, not a placeholder, so a resting detail column departs from the comp.
//
// WHY THIS IS A SOURCE WALL AND NOT A RENDER TEST, stated plainly because the row asked for
// "absent at rest and present when open" and this cannot assert the second half by rendering:
// vitest runs here with `environment: 'node'` and the repo installs no jsdom or happy-dom, so
// there is no DOM to query. The page components read stores, and a static render sees a store's
// INITIAL state -- which is the rest state and only ever the rest state, so `renderToStaticMarkup`
// can show a column absent and can never drive it open. Rather than assert half the claim and
// imply the whole, this reads the SOURCE for the structure that produces both states, the way
// hairline.test.ts reads stylesheets for a defect no rendered instrument could see.
//
// IT ASSERTS THE PROPERTY, NOT ONE IMPLEMENTATION OF IT. The tree solves this two ways and both
// are correct, so a wall demanding one would fail three pages that do not have the defect:
//
//   UNMOUNTED  the aside is not rendered at rest      -- fleet (M1-102), models (this row)
//   COLLAPSED  the aside renders, but the body's      -- turns, sessions, projects
//              second track is `0` at rest and the
//              open class transitions it open
//
// The COLLAPSED idiom is the older and the majority one, and it answers M1-102's own honest
// residual: opening a head still pays the column's width, and a track transitioning from 0 pays
// it as a movement rather than a jump. Which idiom the console should keep is a design decision
// above this row; it is reported, not resolved here.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(here, '..', 'src');
const ROOTS = ['pages', 'widgets'];

export type Rest = 'unmounted' | 'collapsed' | 'resting' | 'absent';

/**
 * THE DENOMINATOR, ENUMERATED FROM THE SOURCE.
 *
 * It matches on the SHAPE -- `<aside className="myx-*-detail">` -- and not on the directory name,
 * because the class prefix is not the directory: turns spells its column `myx-tn-detail`, sessions
 * `myx-sx-detail`, doctor `myx-doc-detail`, projects `myx-px-detail`. A first cut of this file
 * derived the marker from the directory name and silently skipped all four, including the page
 * M1-109 measured at 17.7%. A denominator that comes from a naming assumption is the same defect
 * as a denominator that comes from the list being checked.
 *
 * `<aside>` is the discriminator on purpose: it is the element that takes the second grid track.
 * usage carries `<div className="myx-usage-detail">`, which is in-flow content INSIDE the main
 * column -- the chart block for the selected head, already behind an Empty -- and not a track. It
 * is correctly not collected here, and it is named in NOT_A_COLUMN so the exclusion is visible.
 */
export function detailColumns(): { unit: string; dir: string; cls: string }[] {
  const found: { unit: string; dir: string; cls: string }[] = [];
  for (const root of ROOTS) {
    const base = path.join(SRC, root);
    for (const unit of readdirSync(base)) {
      const dir = path.join(base, unit);
      if (!statSync(dir).isDirectory()) continue;
      let tsx: string;
      try { tsx = readFileSync(path.join(dir, 'index.tsx'), 'utf8'); } catch { continue; }
      const m = /<aside className="(myx-[a-z-]+-detail)"/.exec(tsx);
      if (m !== null) found.push({ unit, dir, cls: m[1] });
    }
  }
  return found;
}

/** Every `.css` in the unit's own directory, as one blob. */
function sheetOf(dir: string): string {
  return readdirSync(dir).filter((f) => f.endsWith('.css'))
    .map((f) => readFileSync(path.join(dir, f), 'utf8')).join('\n');
}

/**
 * Does this detail column occupy width before anything is opened?
 *
 * UNMOUNTED: the aside sits behind a `? null : (` gate, so nothing renders at rest.
 * COLLAPSED: the aside always renders, but its body grid declares a second track of literal `0`
 *            and a separate `-open` rule widens it, so the resting track is zero-width.
 * RESTING:   neither -- the track is declared at its full width unconditionally. The defect.
 */
export function restState(tsx: string, css: string, cls: string): Rest {
  const at = tsx.indexOf(`<aside className="${cls}"`);
  if (at < 0) return 'absent';
  // the gate, if there is one, is the nearest preceding `? null : (` -- and it has to be NEAR:
  // a ternary four hundred characters back belongs to something else on the page.
  if (/\?\s*null\s*:\s*\(/.test(tsx.slice(Math.max(0, at - 400), at))) return 'unmounted';
  // the collapse idiom: a resting second track of literal 0, PLUS an open rule that widens it.
  // Half the idiom is not the idiom -- a track that collapses and never widens is a dead column.
  const collapsed = /grid-template-columns:\s*minmax\(0,\s*1fr\)\s+0\s*;/.test(css)
    && /-open\s*\{[^}]*grid-template-columns:/.test(css);
  return collapsed ? 'collapsed' : 'resting';
}

// -- THE DISPOSITIONS --------------------------------------------------------------------------
// ABSENCE IS NOT A DISPOSITION. Every aside `detailColumns()` finds is named in exactly one list
// below with a reason that is a sentence, not a label; a unit in none of them fails BY NAME.
// Measured 2026-09-18 at 1536 dark; the dead-region figures are M1-109's, against the comp's 11.2%.

/** Must not hold width at rest. The wall proper. */
export const NO_RESTING_COLUMN: Record<string, string> = {
  fleet: 'COLLAPSES at rest since M1-116 -- its track is 0 and the -open class widens it, with '
    + 'the gutter moving with the track. It read "M1-102 unmounted it" until M1-122: the mechanism '
    + 'changed and the disposition did not, so the file documented a repair the code no longer '
    + 'used. Dead region 13.5%.',
  models: 'COLLAPSES at rest since M1-116, the same mechanism as fleet -- it read "M1-112 unmounted '
    + 'it" until M1-122 for the same reason. Dead region 21.5%, the second worst in the set.',
  // M1-117 moved this unit here from HELD, and M1-122 moved the registration INTO this literal
  // rather than assigning it after the object was built. A collect-time mutation is fragile by
  // inspection -- it guards only if the assignment happens to precede the Object.keys() that
  // reads it -- and a guarded list that can silently lose a member is the failure this file
  // exists to prevent. Entry order in a literal cannot be reordered by accident.
  'compact-feed': 'src/widgets/compact-feed/compact-feed.css: .myx-cfeed second track is 0 at '
    + 'rest and the -open class widens it, with column-gap moving with the track so the collapse '
    + 'leaves no gutter of its own (M1-117, mirroring sessions and M1-119).',
  turns: 'already collapses its track to 0 at rest and transitions it open. Dead region 17.7%.',
  sessions: 'already collapses its track to 0 at rest and transitions it open. Dead region 10.2%.',
  projects: 'already collapses its track to 0 at rest and transitions it open. Its 43.7% dead '
    + 'region is the empty slot rails below four repo strips -- a different defect with a '
    + 'different remedy, and not this column.',
  accounts: 'COLLAPSES at rest since M2-24, the same mechanism as the other six -- second track 0, '
    + 'the -open class carrying both the wide track and its gutter, the transition covering both, '
    + 'and a reduced-motion arm. Held under M1-107 until that row landed; it was the last resting '
    + 'column in the console. Dead region 20.3%, third worst. Its rest state is TWO conditions '
    + 'rather than one, so the gate is a named `closed` read by both aria-hidden and the content '
    + 'rather than a compound expression written out twice and left to drift.',
};

/** Carries real content at rest, so removing the column would delete content, not reclaim space. */
export const POPULATED: Record<string, string> = {
  doctor: 'its column carries the version strip, the claude-code version, the attention count, '
    + 'three empties naming pending V4 rows and the fix list at rest; only the opened-check '
    + 'section is conditional, and it already is. Dead region 10.6%, below the comp\'s own 11.2%.',
  mcp: 'its column carries HostLimits at rest -- four knob cards of real content -- after the '
    + 'opened-server branch. Dead region 7.8%, the lowest of the nine.',
};

/** Carries the defect, but the file belongs to another live row or to no row at all. */
export const HELD: Record<string, string> = {
  // EMPTY, AND THAT IS A RESULT RATHER THAN A GAP: every detail column in the console is now
  // either collapsed or populated. accounts was the last entry and M2-24 moved it out. The list
  // stays because the next held unit needs somewhere to be named, and because an empty
  // disposition list is only meaningful while the wall can still say which units it covers.
};

/** Named `-detail` but is not a second grid track, so the wall does not and should not see it. */
export const NOT_A_COLUMN: Record<string, string> = {
  usage: 'myx-usage-detail is a div of in-flow content inside the MAIN column -- the chart block '
    + 'for the selected head, already behind an `active === null ? <Empty/> :` gate. It is not an '
    + 'aside and takes no second track. Dead region 11.3%, at the comp\'s own figure.',
};

describe('no detail column holds width at rest', () => {
  const columns = detailColumns();

  test('the denominator comes from the source and is not empty', () => {
    // A wall whose enumeration returns nothing passes every assertion below it (law 34: if every
    // page threw, what would this print?). `turns` is named because it is the one the first cut's
    // directory-derived marker silently missed.
    expect(columns.length).toBeGreaterThanOrEqual(9);
    expect(columns.map((c) => c.unit)).toContain('turns');
  });

  test.each(Object.keys(NO_RESTING_COLUMN))('%s takes no track at rest', (unit) => {
    const col = columns.find((c) => c.unit === unit);
    // THROW, not skip: a guarded page that no longer carries an aside means the class was renamed
    // and this guard stopped guarding without anyone being told. That is the silent failure the
    // directory-derived marker already caused once in this file.
    if (col === undefined) throw new Error(`${unit} no longer carries a detail aside`);
    const tsx = readFileSync(path.join(col.dir, 'index.tsx'), 'utf8');
    expect(restState(tsx, sheetOf(col.dir), col.cls)).not.toBe('resting');
  });

  test('every detail column in the tree is dispositioned exactly once', () => {
    const lists = [NO_RESTING_COLUMN, POPULATED, HELD];
    const undispositioned: string[] = [];
    const twice: string[] = [];
    for (const { unit } of columns) {
      const n = lists.filter((l) => Object.prototype.hasOwnProperty.call(l, unit)).length;
      if (n === 0) undispositioned.push(unit);
      if (n > 1) twice.push(unit);
    }
    expect(undispositioned).toEqual([]);
    expect(twice).toEqual([]);
  });

  test('no disposition is a blank or a placeholder wearing a label', () => {
    const thin: string[] = [];
    for (const list of [NO_RESTING_COLUMN, POPULATED, HELD, NOT_A_COLUMN]) {
      for (const [unit, why] of Object.entries(list)) if (why.trim().length < 40) thin.push(unit);
    }
    expect(thin).toEqual([]);
  });

  // -- THE WALL MUST BE ABLE TO FAIL -------------------------------------------------------------
  // Each of these is a shape that actually shipped in this tree, or its fix.

  test('fires on an aside rendered into a fixed track (the shape M1-112 removed)', () => {
    const tsx = `<div className="myx-models-body">
          <aside className="myx-models-detail" aria-label={S.catalog}>
            {opened === null ? <Empty text="none open" /> : <ModelDetail model={opened.model} />}
          </aside>`;
    const css = '.myx-models-body { grid-template-columns: minmax(0, 1fr) minmax(0, 24rem); }';
    expect(restState(tsx, css, 'myx-models-detail')).toBe('resting');
  });

  test('does not fire on the unmounted shape', () => {
    const tsx = `{opened === null ? null : (
          <aside className="myx-models-detail" aria-label={S.catalog}>
            <ModelDetail model={opened.model} />
          </aside>
        )}`;
    expect(restState(tsx, '', 'myx-models-detail')).toBe('unmounted');
  });

  test('does not fire on the collapsed shape', () => {
    const tsx = '<aside className="myx-tn-detail" aria-label={S.detail}>';
    const css = `.myx-tn-board { grid-template-columns: minmax(0, 1fr) 0; }
      .myx-tn-board-open { grid-template-columns: minmax(0, 1fr) minmax(360px, 40%); }`;
    expect(restState(tsx, css, 'myx-tn-detail')).toBe('collapsed');
  });

  test('a track that collapses and never widens is still resting', () => {
    const tsx = '<aside className="myx-tn-detail">';
    const css = '.myx-tn-board { grid-template-columns: minmax(0, 1fr) 0; }';
    expect(restState(tsx, css, 'myx-tn-detail')).toBe('resting');
  });

  test('a unit with no detail aside is neither guarded nor a violation', () => {
    expect(restState('<div className="myx-logs-body" />', '', 'myx-logs-detail')).toBe('absent');
  });
});
